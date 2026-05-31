package com.khmerbank.controller;

import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.exception.ApiException;
import com.khmerbank.service.notify.NotifyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Telegram-notify proxy endpoints.
 *
 * <p>The Render bot owns the chat-id ↔ sk_ key mapping on its own disk;
 * Spring just forwards two read-only queries the dashboard needs:
 *
 * <ul>
 *   <li>{@code GET /api/v1/notify/info} — bot username + deep link
 *       (cached 5 min via {@link NotifyService#info()})</li>
 *   <li>{@code GET /api/v1/notify/status-of?key=sk_xxx} — is this key
 *       linked to a Telegram chat? (never cached, dashboard polls)</li>
 * </ul>
 *
 * <p>Both endpoints are kept public (no auth required) because they
 * mirror the upstream bot's own public read-only contract — the dashboard
 * shows the panel as part of the post-payment Done step, before the user
 * is necessarily inside the authenticated app shell.
 */
@RestController
@RequestMapping("/api/v1/notify")
@RequiredArgsConstructor
@Tag(name = "Telegram notifications", description = "Discover the bot + check link status")
public class NotifyController {

    private final NotifyService notifyService;

    @GetMapping("/info")
    @Operation(summary = "Bot username + deep link, cached 5 min")
    public ApiResponse<Map<String, Object>> info() {
        return ApiResponse.ok(notifyService.info());
    }

    @GetMapping("/status-of")
    @Operation(summary = "Is the given sk_ key linked to a Telegram chat?")
    public ApiResponse<Map<String, Object>> statusOf(@RequestParam("key") String key) {
        if (key == null || key.isBlank()
                || !key.startsWith("sk_") || key.length() < 12) {
            throw ApiException.badRequest("BAD_KEY", "key must look like sk_<32 hex>");
        }
        return ApiResponse.ok(notifyService.statusOf(key));
    }

    /**
     * Manually fire a "payment received" Telegram DM. The Render bot
     * dedupes by {@code (key, md5)}, so calling this twice for the same
     * payment is safe — second call returns {@code sent:false / reason:
     * "Already notified..."}.
     *
     * <p>Auth model is self-proof: the request body MUST carry
     * {@code key=sk_xxx} and the same value as the {@code X-API-Key}
     * header upstream — we just forward the body, the bot enforces the
     * match. This endpoint stays public so the React dashboard can call
     * it as a sidecar from its own polling loop without an extra hop
     * through bearer auth.
     */
    @PostMapping("/paid")
    @Operation(summary = "Trigger Telegram DM for a confirmed payment "
            + "(forwards to upstream /tg/notify-paid). Idempotent — "
            + "dedup by (key, md5).")
    public ApiResponse<Map<String, Object>> notifyPaid(
            @RequestBody Map<String, Object> body) {
        if (body == null) {
            throw ApiException.badRequest("BAD_BODY", "body required");
        }
        String key = strOrNull(body.get("key"));
        String md5 = strOrNull(body.get("md5"));
        if (key == null || !key.startsWith("sk_") || key.length() < 12) {
            throw ApiException.badRequest("BAD_KEY", "key must look like sk_<32 hex>");
        }
        if (md5 == null || md5.length() < 8) {
            throw ApiException.badRequest("BAD_MD5", "md5 required");
        }
        java.math.BigDecimal amount = null;
        Object rawAmount = body.get("amount");
        if (rawAmount != null) {
            try { amount = new java.math.BigDecimal(String.valueOf(rawAmount)); }
            catch (NumberFormatException ignore) { /* fall through with null */ }
        }
        // Bakong context — accept several upstream-style aliases so the
        // dashboard's sidecar call works regardless of which casing it
        // reaches for.
        String hash       = strOrNull(body.get("hash"));
        if (hash == null) hash = strOrNull(body.get("bakong_hash"));
        String externalRef = strOrNull(body.get("external_ref"));
        if (externalRef == null) externalRef = strOrNull(body.get("externalRef"));
        if (externalRef == null) externalRef = strOrNull(body.get("tran_id"));
        String description = strOrNull(body.get("description"));
        if (description == null) description = strOrNull(body.get("remark"));
        if (description == null) description = strOrNull(body.get("note"));
        String to = strOrNull(body.get("to"));
        Long createdAtMs = null;
        Object rawCreated = body.get("created_at_ms");
        if (rawCreated == null) rawCreated = body.get("createdAtMs");
        if (rawCreated != null) {
            try { createdAtMs = Long.parseLong(String.valueOf(rawCreated)); }
            catch (NumberFormatException ignore) { /* leave null */ }
        }
        return ApiResponse.ok(notifyService.firePaid(new NotifyService.PaidNotification(
                key,
                md5,
                amount,
                strOrNull(body.get("currency")),
                strOrNull(body.get("from")),
                strOrNull(body.get("bank")),
                strOrNull(body.get("timestamp")),
                hash,
                externalRef,
                description,
                to,
                createdAtMs
        )));
    }

    private static String strOrNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
