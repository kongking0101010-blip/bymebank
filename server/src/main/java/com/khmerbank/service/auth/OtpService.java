package com.khmerbank.service.auth;

import com.khmerbank.exception.ApiException;
import com.khmerbank.model.EmailOtp;
import com.khmerbank.model.EmailOtp.Purpose;
import com.khmerbank.repository.EmailOtpRepository;
import com.khmerbank.service.email.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/**
 * Issues and verifies 6-digit Gmail OTPs.
 *
 * <p>Storage: code is hashed (SHA-256(code + email + salt)) — raw code only
 * exists in the email body. Verification is constant-time.
 *
 * <p>Limits:
 *   request: 3 / 10 min / email
 *   verify : 5 attempts per OTP, then OTP locked + user locked 15 min
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int OTP_LENGTH = 6;
    private static final int VALID_MINUTES = 10;
    private static final int MAX_REQUESTS_PER_WINDOW = 3;
    private static final Duration REQUEST_WINDOW = Duration.ofMinutes(10);
    private static final int MAX_VERIFY_ATTEMPTS = 5;

    private final EmailOtpRepository repo;
    private final EmailService email;

    @Value("${app.otp.salt:khmerbank-otp-salt}")
    private String salt;

    /** Issue a fresh OTP and email it. Throws if rate-limited. */
    @Transactional
    public void requestOtp(String emailAddr, Purpose purpose) {
        String e = normalizeEmail(emailAddr);
        Instant windowStart = Instant.now().minus(REQUEST_WINDOW);
        long recent = repo.countByEmailAndPurposeAndCreatedAtAfter(e, purpose, windowStart);
        if (recent >= MAX_REQUESTS_PER_WINDOW) {
            throw ApiException.tooManyRequests("OTP_RATE_LIMITED",
                    "Too many code requests. Try again in a few minutes.");
        }
        String code = generateCode();
        EmailOtp row = EmailOtp.builder()
                .email(e)
                .codeHash(hash(e, code))
                .purpose(purpose)
                .expiresAt(Instant.now().plus(Duration.ofMinutes(VALID_MINUTES)))
                .build();
        row = repo.save(row);
        try {
            email.sendOtp(e, code, VALID_MINUTES);
        } catch (Exception sendEx) {
            // Roll back the OTP row so the user isn't stuck on a code that never arrived.
            repo.delete(row);
            log.error("OTP send failed for {}: {}", mask(e), sendEx.getMessage());
            throw ApiException.internal("MAIL_FAILED",
                    "Could not send the code. Check Gmail SMTP settings.");
        }
        log.info("otp issued purpose={} email={}", purpose, mask(e));
    }

    /**
     * Returns true if the code matches and is fresh. Increments
     * {@code attempt_count} and consumes the OTP on success.
     */
    @Transactional
    public boolean verifyOtp(String emailAddr, String code, Purpose purpose) {
        String e = normalizeEmail(emailAddr);
        EmailOtp otp = repo
                .findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(e, purpose)
                .orElse(null);
        if (otp == null) {
            log.info("otp verify no_pending email={}", mask(e));
            return false;
        }
        Instant now = Instant.now();
        if (otp.getExpiresAt().isBefore(now)) {
            log.info("otp verify expired email={}", mask(e));
            return false;
        }
        if (otp.getAttemptCount() >= MAX_VERIFY_ATTEMPTS) {
            throw ApiException.tooManyRequests("OTP_LOCKED",
                    "Too many bad attempts. Request a new code.");
        }
        otp.setAttemptCount(otp.getAttemptCount() + 1);
        boolean ok = constantTimeEquals(otp.getCodeHash(), hash(e, code));
        if (ok) {
            otp.setConsumedAt(now);
            repo.save(otp);
            log.info("otp verified email={}", mask(e));
            return true;
        }
        repo.save(otp);
        log.info("otp bad_code email={} attempt={}/5", mask(e), otp.getAttemptCount());
        return false;
    }

    /* ── helpers ── */

    private static String generateCode() {
        StringBuilder sb = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) sb.append(RNG.nextInt(10));
        return sb.toString();
    }

    private String hash(String email, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest((email + ":" + code + ":" + salt)
                    .getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException nope) {
            throw new IllegalStateException(nope);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    private static String normalizeEmail(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static String mask(String e) {
        int at = e.indexOf('@');
        if (at < 1) return "***";
        return e.charAt(0) + "***" + e.substring(at);
    }
}
