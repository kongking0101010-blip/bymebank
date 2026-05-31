package com.khmerbank.controller;

import com.khmerbank.dto.request.LoginRequest;
import com.khmerbank.dto.request.RegisterRequest;
import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.dto.response.AuthResponse;
import com.khmerbank.service.auth.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Account registration and login")
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @Operation(summary = "Register a new developer account")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.ok(authService.register(req), "Account created");
    }

    @PostMapping("/login")
    @Operation(summary = "Log in with email + password")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req), "Logged in");
    }
}
