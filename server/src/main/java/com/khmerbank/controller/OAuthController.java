package com.khmerbank.controller;

import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.service.auth.OAuthSignInService;
import com.khmerbank.service.auth.OAuthSignInService.AuthBundle;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Passwordless authentication: Google one-tap and Gmail OTP.
 * Both flows end with the same JWT envelope used by the rest of the API.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Validated
@Tag(name = "Auth (passwordless)", description = "Google / Gmail-OTP sign-in")
public class OAuthController {

    private final OAuthSignInService service;

    @PostMapping("/google")
    @Operation(summary = "Sign in with a Google ID token")
    public ApiResponse<Map<String, Object>> google(@RequestBody GoogleSignInBody body) {
        AuthBundle b = service.signInWithGoogle(body.idToken);
        return ApiResponse.ok(envelope(b));
    }

    @PostMapping("/email/request")
    @Operation(summary = "Send a 6-digit code to the email")
    public ApiResponse<Map<String, Object>> request(@RequestBody EmailRequestBody body) {
        service.requestEmailOtp(body.email);
        return ApiResponse.ok(Map.of("ok", true));
    }

    @PostMapping("/email/check")
    @Operation(summary = "Check if an email has a password set (used for adaptive login)")
    public ApiResponse<Map<String, Object>> checkEmail(@RequestBody EmailRequestBody body) {
        return ApiResponse.ok(service.checkEmail(body.email));
    }

    @PostMapping("/email/verify")
    @Operation(summary = "Verify the OTP and sign in (creates account if needed)")
    public ApiResponse<Map<String, Object>> verify(@RequestBody EmailVerifyBody body) {
        AuthBundle b = service.verifyEmailOtp(body.email, body.code);
        return ApiResponse.ok(envelope(b));
    }

    @GetMapping("/lookup")
    @Operation(summary = "Tell the client whether the account exists and has a password set, "
            + "so the login UI can show the right next step.")
    public ApiResponse<Map<String, Object>> lookup(@RequestParam("email") String email) {
        return ApiResponse.ok(service.lookup(email));
    }

    private static Map<String, Object> envelope(AuthBundle b) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accessToken",  b.accessToken());
        out.put("refreshToken", b.refreshToken());
        out.put("tokenType",    "Bearer");
        out.put("expiresIn",    b.expiresIn());
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("id",        b.user().getId());
        u.put("email",     b.user().getEmail());
        u.put("fullName",  b.user().getFullName());
        u.put("role",      b.user().getRole().name());
        u.put("status",    b.user().getStatus());
        u.put("avatarUrl", b.user().getAvatarUrl());
        out.put("user", u);
        return out;
    }

    public static class GoogleSignInBody {
        @NotBlank public String idToken;
    }
    public static class EmailRequestBody {
        @NotBlank @Email public String email;
    }
    public static class EmailVerifyBody {
        @NotBlank @Email public String email;
        @NotBlank @Pattern(regexp = "\\d{6}", message = "Code must be 6 digits")
        public String code;
    }
}
