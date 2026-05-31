package com.khmerbank.controller;

import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.exception.ApiException;
import com.khmerbank.service.branding.BrandingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only branding endpoints.
 *
 * <p>Gated by {@code /api/v1/admin/**} → {@code hasRole("ADMIN")} in
 * {@link com.khmerbank.config.SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/admin/branding")
@RequiredArgsConstructor
@Tag(name = "Admin · Branding", description = "Manage admin-uploaded logos")
public class AdminBrandingController {

    private final BrandingService branding;

    /** Returns the current snapshot + cache metadata (age, error, slots). */
    @GetMapping
    @Operation(summary = "Read current branding snapshot + cache meta")
    public ApiResponse<Map<String, Object>> get() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("logos", branding.getLogos());
        out.put("meta",  branding.meta());
        return ApiResponse.ok(out);
    }

    /** Bypass the local cache + the bridge cache. */
    @PostMapping("/refresh")
    @Operation(summary = "Force a refetch from the Telegram bot (clears cache)")
    public ApiResponse<Map<String, Object>> refresh() {
        Map<String, String> logos = branding.refresh();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("logos", logos);
        out.put("meta",  branding.meta());
        return ApiResponse.ok(out);
    }

    /** Upload a logo as a data: URL into the bridge's local store. */
    @PutMapping("/{slot}")
    @Operation(summary = "Upload a logo for a slot (brand|aba|wing|acleda|bakong)")
    public ApiResponse<Map<String, Object>> upsert(
            @PathVariable String slot,
            @RequestBody UpsertBody body) {
        if (body == null || body.getDataUrl() == null) {
            throw ApiException.badRequest("DATA_URL_REQUIRED", "dataUrl is required");
        }
        try {
            Map<String, String> logos = branding.upsert(slot, body.getDataUrl());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("logos", logos);
            out.put("meta",  branding.meta());
            return ApiResponse.ok(out);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_LOGO", e.getMessage());
        }
    }

    /** Delete a logo by slot. */
    @DeleteMapping("/{slot}")
    @Operation(summary = "Delete the logo for a slot")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String slot) {
        try {
            Map<String, String> logos = branding.delete(slot);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("logos", logos);
            out.put("meta",  branding.meta());
            return ApiResponse.ok(out);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_SLOT", e.getMessage());
        }
    }

    @Data
    public static class UpsertBody {
        private String dataUrl;
    }
}
