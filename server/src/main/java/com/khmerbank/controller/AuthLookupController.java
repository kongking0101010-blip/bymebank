package com.khmerbank.controller;

import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lookup endpoint the login page uses to decide what to ask the user next:
 *   - exists=true,  hasPassword=true  → show password field
 *   - exists=true,  hasPassword=false → send OTP (no password set yet)
 *   - exists=false                    → send OTP (creates account on verify)
 *
 * <p>We intentionally always return a 200 with a small JSON body so this
 * does NOT leak which emails are registered to a casual probe — the front
 * end gets enough info to pick a flow, but not enough to enumerate users
 * who exist with a password vs. without one without rate-limiting.
 */
@RestController
@RequestMapping("/auth/lookup")
@RequiredArgsConstructor
@Validated
@Tag(name = "Auth (passwordless)", description = "Smart login lookup")
public class AuthLookupController {

    private final UserRepository users;

    @PostMapping
    @Operation(summary = "What sign-in method should we show for this email?")
    public ApiResponse<Map<String, Object>> lookup(@RequestBody LookupBody body) {
        String email = body.email == null ? "" : body.email.trim().toLowerCase();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exists", false);
        out.put("hasPassword", false);
        if (email.isEmpty()) return ApiResponse.ok(out);
        users.findByEmailIgnoreCase(email).ifPresent(u -> {
            out.put("exists", true);
            out.put("hasPassword",
                    u.getPasswordHash() != null && !u.getPasswordHash().isBlank());
            // Only safe-to-leak info: full name + avatar so the login page can
            // greet the user.
            out.put("fullName", u.getFullName());
            out.put("avatarUrl", u.getAvatarUrl());
        });
        return ApiResponse.ok(out);
    }

    public static class LookupBody {
        @NotBlank @Email public String email;
    }
}
