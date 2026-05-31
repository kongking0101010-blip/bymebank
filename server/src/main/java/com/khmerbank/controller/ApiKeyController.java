package com.khmerbank.controller;

import com.khmerbank.dto.request.CreateApiKeyRequest;
import com.khmerbank.dto.response.ApiKeyResponse;
import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.security.AuthenticatedUser;
import com.khmerbank.service.apikey.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import com.khmerbank.model.User;

@RestController
@RequestMapping("/api/v1/api-keys")
@RequiredArgsConstructor
@Tag(name = "API Keys", description = "Create and manage your API keys")
public class ApiKeyController {

    private final ApiKeyService apiKeyService;

    @PostMapping
    @Operation(summary = "Create a new API key (returned only once)")
    public ApiResponse<ApiKeyResponse> create(@Valid @RequestBody CreateApiKeyRequest req,
                                              @AuthenticationPrincipal User user) {
        return ApiResponse.ok(apiKeyService.createKey(user, req), "Save this key now");
    }

    @GetMapping
    @Operation(summary = "List your API keys")
    public ApiResponse<List<ApiKeyResponse>> list(@AuthenticationPrincipal User user) {
        return ApiResponse.ok(apiKeyService.listKeys(user));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revoke an API key")
    public ApiResponse<Void> revoke(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        apiKeyService.revokeKey(user, id);
        return ApiResponse.ok(null, "Key revoked");
    }
}
