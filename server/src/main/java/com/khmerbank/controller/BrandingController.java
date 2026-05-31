package com.khmerbank.controller;

import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.service.branding.BrandingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public branding endpoint — read-only.
 *
 * <p>Whitelisted in {@code SecurityConfig} under {@code /api/v1/public/**}.
 * Login + landing pages need it before the user has a JWT.
 */
@RestController
@RequestMapping("/api/v1/public/branding")
@RequiredArgsConstructor
@Tag(name = "Branding", description = "Admin-uploaded logos (read-only, public)")
public class BrandingController {

    private final BrandingService branding;

    @GetMapping
    @Operation(summary = "Get all admin-uploaded logos as a slot → data: URL map. "
            + "Pass ?refresh=true to bypass the 60-second in-memory cache "
            + "and pull straight from the upstream Telegram bot (used after "
            + "an admin updates a logo so the dashboard sees it within ~1s "
            + "instead of waiting up to 2 min for the cache to age out).")
    public ApiResponse<Map<String, String>> get(
            @org.springframework.web.bind.annotation.RequestParam(
                    value = "refresh", required = false, defaultValue = "false")
            boolean refresh) {
        return ApiResponse.ok(refresh ? branding.refresh() : branding.getLogos());
    }
}
