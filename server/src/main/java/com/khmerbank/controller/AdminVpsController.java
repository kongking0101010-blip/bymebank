package com.khmerbank.controller;

import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.service.bank.bakong.VpsAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin endpoints to manage sk_ keys on the upstream VPS.
 * Requires ROLE_ADMIN (mapped from User.role = ADMIN).
 */
@RestController
@RequestMapping("/api/v1/admin/vps")
@RequiredArgsConstructor
@Tag(name = "Admin · VPS", description = "Mint / list / revoke sk_ keys on the upstream Bakong VPS")
public class AdminVpsController {

    private final VpsAdminService vpsAdminService;

    @PostMapping("/keys")
    @Operation(summary = "Mint a new sk_ key on the VPS")
    public ApiResponse<Map<String, Object>> mint(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(vpsAdminService.mintKey(body.getOrDefault("label", "khmerbank")));
    }

    @GetMapping("/keys")
    @Operation(summary = "List sk_ keys stored on the VPS")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(vpsAdminService.listKeys());
    }

    @DeleteMapping("/keys")
    @Operation(summary = "Revoke a specific sk_ key on the VPS")
    public ApiResponse<Void> revoke(@RequestParam("key") String key) {
        vpsAdminService.revokeKey(key);
        return ApiResponse.ok(null, "Revoked");
    }
}
