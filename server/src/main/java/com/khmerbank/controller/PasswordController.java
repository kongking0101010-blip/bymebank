package com.khmerbank.controller;

import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.exception.ApiException;
import com.khmerbank.model.User;
import com.khmerbank.repository.UserRepository;
import com.khmerbank.service.audit.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Set or change password while logged in. The first call sets the password
 * (for OTP/Google-only users); subsequent calls require the current one.
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@Tag(name = "Me — Password")
public class PasswordController {

    private final UserRepository userRepo;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    @PostMapping("/password")
    @Operation(summary = "Set or change password (no current password needed for first set)")
    @Transactional
    public ApiResponse<Map<String, Object>> setPassword(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody SetPasswordBody body) {

        boolean alreadyHasPassword =
                user.getPasswordHash() != null && !user.getPasswordHash().isBlank();

        // The OTP flow signs the user in via /auth/email/verify, then immediately
        // calls this endpoint. That email-verified session is proof enough — we
        // skip the current-password check when the OTP flag is set.
        boolean otpReset = body.viaOtp != null && body.viaOtp;

        if (alreadyHasPassword && !otpReset) {
            if (body.currentPassword == null || body.currentPassword.isBlank()) {
                throw ApiException.badRequest("CURRENT_REQUIRED",
                        "Current password is required to change it.");
            }
            if (!encoder.matches(body.currentPassword, user.getPasswordHash())) {
                throw ApiException.unauthorized("BAD_CURRENT_PASSWORD",
                        "Current password is incorrect.");
            }
        }
        user.setPasswordHash(encoder.encode(body.newPassword));
        userRepo.save(user);
        audit.log(user, alreadyHasPassword ? "PASSWORD_CHANGED" : "PASSWORD_SET",
                "user", user.getId().toString());
        return ApiResponse.ok(Map.of("ok", true),
                alreadyHasPassword ? "Password updated" : "Password set");
    }

    public static class SetPasswordBody {
        public String currentPassword;
        @Size(min = 8, max = 100, message = "Password must be 8–100 characters")
        public String newPassword;
        /** True when called right after /auth/email/verify — skips the current-password check. */
        public Boolean viaOtp;
    }
}
