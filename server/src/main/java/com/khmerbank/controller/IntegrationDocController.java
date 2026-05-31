package com.khmerbank.controller;

import com.khmerbank.exception.ApiException;
import com.khmerbank.model.ApiKey;
import com.khmerbank.model.User;
import com.khmerbank.repository.ApiKeyRepository;
import com.khmerbank.security.HashUtil;
import com.khmerbank.service.docs.IntegrationDocService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;

/**
 * Generates and serves the per-user PDF integration guide.
 *
 * <ul>
 *   <li>{@code GET /api/v1/me/integration-guide} – PDF for the current user, using
 *       the most recently created API key.</li>
 *   <li>{@code GET /api/v1/me/integration-guide?apiKey=kb_…} – PDF that embeds
 *       a specific key (used right after creation when we don't yet know which
 *       one is "newest").</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/me/integration-guide")
@RequiredArgsConstructor
@Tag(name = "Integration Guide", description = "Per-user PDF integration guide")
public class IntegrationDocController {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final IntegrationDocService docService;
    private final ApiKeyRepository apiKeyRepository;
    private final com.khmerbank.repository.UserApiKeyRepository userApiKeyRepository;

    @GetMapping
    @Operation(summary = "Download the integration PDF for this user")
    public ResponseEntity<ByteArrayResource> download(
            @AuthenticationPrincipal User user,
            @RequestParam(value = "apiKey", required = false) String apiKey) {

        // Three cases for what the FE might pass as ?apiKey=...:
        //   1. Nothing — fall back to the user's primary upstream sk_
        //      (mirrored on the User row at issue time).
        //   2. A local kb_xxx key — verify it via apiKeyRepository.
        //   3. An upstream sk_xxx key — verify it via userApiKeyRepository.
        // Cases 2 and 3 always check ownership before generating the PDF.
        if (apiKey != null && !apiKey.isBlank()) {
            if (apiKey.startsWith("sk_")) {
                com.khmerbank.model.UserApiKey ua = userApiKeyRepository
                        .findByApiKey(apiKey)
                        .orElseThrow(() -> ApiException.notFound("KEY_NOT_FOUND",
                                "API key not recognised"));
                if (!ua.getUser().getId().equals(user.getId())) {
                    throw ApiException.forbidden("ACCESS_DENIED", "Not your key");
                }
            } else {
                ApiKey key = apiKeyRepository.findByKeyHash(HashUtil.sha256(apiKey))
                        .orElseThrow(() -> ApiException.notFound("KEY_NOT_FOUND",
                                "API key not recognised"));
                if (!key.getUser().getId().equals(user.getId())) {
                    throw ApiException.forbidden("ACCESS_DENIED", "Not your key");
                }
            }
        } else if (user.getUpstreamApiKey() != null && !user.getUpstreamApiKey().isBlank()) {
            // Fall back to the upstream sk_ key if we have one
            apiKey = user.getUpstreamApiKey();
        } else {
            apiKey = "(no API key yet — buy one in the dashboard)";
        }

        byte[] pdf = docService.generate(user.getFullName(), user.getEmail(), apiKey);

        String filename = "khmerbank-integration-"
                + java.time.LocalDate.now().format(DT) + ".pdf";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(
                org.springframework.http.ContentDisposition.attachment()
                        .filename(filename).build());

        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(pdf.length)
                .body(new ByteArrayResource(pdf));
    }
}
