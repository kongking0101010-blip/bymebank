package com.khmerbank.service.auth;

import com.khmerbank.exception.ApiException;
import com.khmerbank.model.EmailOtp.Purpose;
import com.khmerbank.model.User;
import com.khmerbank.model.enums.Role;
import com.khmerbank.repository.UserRepository;
import com.khmerbank.security.JwtService;
import com.khmerbank.service.audit.AuditService;
import com.khmerbank.service.auth.GoogleTokenVerifier.Verified;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Sign-in flows that don't depend on a stored password — Google OAuth and
 * Gmail OTP. Both end with a JWT issued via {@link JwtService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthSignInService {

    private final UserRepository userRepository;
    private final OtpService otpService;
    private final GoogleTokenVerifier googleVerifier;
    private final JwtService jwt;
    private final AuditService audit;

    @Value("${app.admin.initial-email:}")
    private String initialAdminEmail;

    /** Google one-tap sign-in. */
    @Transactional
    public AuthBundle signInWithGoogle(String idToken) {
        Verified v = googleVerifier.verify(idToken);
        if (!v.emailVerified()) {
            throw ApiException.unauthorized("EMAIL_NOT_VERIFIED",
                    "Google reports this email as unverified.");
        }
        User user = userRepository.findByGoogleSub(v.sub())
                .or(() -> userRepository.findByEmailIgnoreCase(v.email()))
                .map(u -> {
                    if (u.getGoogleSub() == null) u.setGoogleSub(v.sub());
                    if (u.getAvatarUrl() == null) u.setAvatarUrl(v.avatarUrl());
                    if (u.getFullName() == null && v.name() != null) u.setFullName(v.name());
                    u.setEmailVerified(true);
                    return u;
                })
                .orElseGet(() -> User.builder()
                        .email(v.email())
                        .fullName(v.name() == null ? v.email() : v.name())
                        .googleSub(v.sub())
                        .avatarUrl(v.avatarUrl())
                        .role(initialAdminFor(v.email()))
                        .emailVerified(true)
                        .status("ACTIVE")
                        .build());
        user.setLastLoginAt(Instant.now());
        user = userRepository.save(user);
        rejectIfInactive(user);
        audit.log(user, AuditService.GOOGLE_SIGNIN, "user", user.getId().toString());
        return bundle(user);
    }

    /** Step 1 of email-OTP flow. Always 200, even if user doesn't exist
     *  (don't leak which emails are registered). */
    public void requestEmailOtp(String emailRaw) {
        String e = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        if (e.isBlank()) {
            throw ApiException.badRequest("BAD_EMAIL", "email required");
        }
        otpService.requestOtp(e, Purpose.LOGIN);
        audit.log(null, AuditService.OTP_REQUESTED, "email", mask(e), Map.of("email", e));
    }

    /**
     * Lightweight pre-login probe used by the dashboard to decide whether
     * to show "enter password" or "send a code". Always 200 — never reveals
     * the exact account state to a 3rd party scraper.
     */
    public Map<String, Object> lookup(String emailRaw) {
        String e = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("email", e);
        if (e.isBlank() || !e.contains("@")) {
            out.put("exists",      false);
            out.put("hasPassword", false);
            return out;
        }
        boolean exists      = false;
        boolean hasPassword = false;
        var u = userRepository.findByEmailIgnoreCase(e).orElse(null);
        if (u != null) {
            exists      = true;
            hasPassword = u.getPasswordHash() != null && !u.getPasswordHash().isBlank();
        }
        out.put("exists",      exists);
        out.put("hasPassword", hasPassword);
        return out;
    }

    /**
     * Adaptive-login probe. Returns enough info for the UI to decide whether
     * to show the password field or jump to the OTP step. Does NOT reveal
     * whether the email exists — for unknown emails we still report
     * {@code hasPassword=false} so attackers can't enumerate accounts.
     */
    public Map<String, Object> checkEmail(String emailRaw) {
        String e = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        if (e.isBlank()) {
            throw ApiException.badRequest("BAD_EMAIL", "email required");
        }
        boolean hasPassword = userRepository.findByEmailIgnoreCase(e)
                .map(u -> u.getPasswordHash() != null && !u.getPasswordHash().isBlank())
                .orElse(false);
        return Map.of("hasPassword", hasPassword);
    }

    /** Step 2 of email-OTP flow. Issues JWT on success. */
    @Transactional
    public AuthBundle verifyEmailOtp(String emailRaw, String code) {
        String e = emailRaw == null ? "" : emailRaw.trim().toLowerCase();
        if (e.isBlank() || code == null || code.length() != 6) {
            throw ApiException.badRequest("BAD_INPUT", "Email + 6-digit code required.");
        }
        boolean ok = otpService.verifyOtp(e, code, Purpose.LOGIN);
        if (!ok) {
            audit.log(null, AuditService.OTP_BAD_CODE, "email", mask(e));
            throw ApiException.unauthorized("OTP_INVALID",
                    "Invalid or expired code.");
        }
        User user = userRepository.findByEmailIgnoreCase(e)
                .map(u -> { u.setEmailVerified(true); return u; })
                .orElseGet(() -> User.builder()
                        .email(e)
                        .fullName(e.substring(0, e.indexOf('@')))
                        .role(initialAdminFor(e))
                        .emailVerified(true)
                        .status("ACTIVE")
                        .build());
        user.setLastLoginAt(Instant.now());
        user = userRepository.save(user);
        rejectIfInactive(user);
        audit.log(user, AuditService.OTP_VERIFIED, "user", user.getId().toString());
        return bundle(user);
    }

    /* ── helpers ── */

    private Role initialAdminFor(String email) {
        if (initialAdminEmail != null && !initialAdminEmail.isBlank()
                && initialAdminEmail.equalsIgnoreCase(email)) {
            log.info("Promoting {} to ADMIN via INITIAL_ADMIN_EMAIL", mask(email));
            return Role.ADMIN;
        }
        return Role.USER;
    }

    private void rejectIfInactive(User user) {
        if ("SUSPENDED".equals(user.getStatus()) || "DELETED".equals(user.getStatus())) {
            throw ApiException.forbidden("ACCOUNT_SUSPENDED",
                    "Account is " + user.getStatus().toLowerCase() + ".");
        }
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(Instant.now())) {
            throw ApiException.forbidden("ACCOUNT_LOCKED",
                    "Account locked until " + user.getLockedUntil());
        }
    }

    private AuthBundle bundle(User user) {
        return new AuthBundle(
                jwt.generateAccessToken(user),
                jwt.generateRefreshToken(user),
                jwt.getAccessExpirySeconds(),
                user);
    }

    private static String mask(String e) {
        if (e == null) return "***";
        int at = e.indexOf('@');
        if (at < 1) return "***";
        return e.charAt(0) + "***" + e.substring(at);
    }

    public record AuthBundle(String accessToken, String refreshToken,
                             long expiresIn, User user) {}
}
