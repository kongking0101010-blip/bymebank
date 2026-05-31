package com.khmerbank.service.notify;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thin proxy to the upstream payment bot's Telegram-notify endpoints.
 *
 * <p>The bot owns the chat-id ↔ sk_ key linkage on its own disk; we just
 * forward two read-only queries:
 *
 * <ul>
 *   <li>{@link #info()} — discovery: bot username + deep link.
 *       Cached in-process for 5 minutes via a tiny atomic snapshot so a
 *       refresh button doesn't hammer Render. We <i>don't</i> use
 *       {@code @Cacheable} because the project's CacheManager is a Redis
 *       client and Redis isn't a hard dependency for local dev.</li>
 *   <li>{@link #statusOf(String)} — per-key linkage state.
 *       Never cached: a status flip happens the moment the user pastes
 *       their key into Telegram, and the dashboard polls this every few
 *       seconds while the panel is mounted.</li>
 * </ul>
 *
 * <p>Goes <b>direct</b> to the upstream Render gateway, not through the
 * bridge. These endpoints are public read-only and don't need the
 * bridge's BRIDGE_AUTH_TOKEN, so threading them through bot-bridge would
 * just add latency.
 */
@Service
@Slf4j
public class NotifyService {

    private static final Duration INFO_TTL = Duration.ofMinutes(5);

    private final WebClient client;
    private final AtomicReference<CachedInfo> infoCache = new AtomicReference<>();

    /**
     * Background pool for fire-and-forget Telegram notifications.
     *
     * <p>The dashboard's payment polling MUST return to the browser the
     * moment we see PAID — making the user wait on a Render cold-start
     * just to send a Telegram DM is unacceptable. Daemon threads so they
     * don't block JVM shutdown.
     */
    private final java.util.concurrent.ExecutorService notifyPool =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "tg-notify-paid");
                t.setDaemon(true);
                return t;
            });

    public NotifyService(
            @Value("${khmerbank.upstream.base-url:${UPSTREAM_BASE_URL:https://apicheckpayment.onrender.com}}")
            String upstreamBaseUrl) {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(30));
        this.client = WebClient.builder()
                .baseUrl(upstreamBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(http))
                .build();
        log.info("NotifyService → {}", upstreamBaseUrl);
    }

    /** Discovery — bot username + deep link. 5-min in-process cache. */
    public Map<String, Object> info() {
        CachedInfo snap = infoCache.get();
        if (snap != null && snap.fresh()) return snap.body();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = client.get()
                    .uri("/tg/notify-info")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            Map<String, Object> body = r == null ? fallbackInfo() : r;
            infoCache.set(new CachedInfo(body, Instant.now()));
            return body;
        } catch (Exception e) {
            log.warn("notify-info upstream failed, returning fallback: {}", e.getMessage());
            // Return any prior cached body if we have one — better than the
            // hardcoded fallback in case the user previously hit a working
            // upstream and we just lost the network briefly.
            if (snap != null) return snap.body();
            return fallbackInfo();
        }
    }

    /** Per-key linkage state. NEVER cached — the dashboard polls. */
    public Map<String, Object> statusOf(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return notLinked(null);
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = client.get()
                    .uri(uri -> uri.path("/tg/notify-status-of")
                            .queryParam("key", apiKey).build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            return r == null ? notLinked(null) : r;
        } catch (Exception e) {
            log.warn("notify-status-of upstream failed: {}", e.getMessage());
            return notLinked("upstream_unreachable");
        }
    }

    /**
     * Synchronously POST a "payment received" trigger to the upstream bot.
     * Used by {@link #firePaidAsync(PaidNotification)} and the {@code POST
     * /api/v1/notify/paid} controller forwarder.
     *
     * <p>Returns the upstream JSON body verbatim so callers can surface
     * {@code sent:false / reason:"..."} when the user picks "Tell me why
     * the DM didn't land". On any transport error returns a synthetic
     * {@code ok:false / sent:false / error:"..."} body — never throws.
     */
    public Map<String, Object> firePaid(PaidNotification n) {
        if (n == null || n.key() == null || n.key().isBlank()
                || n.md5() == null || n.md5().isBlank()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ok", false);
            err.put("sent", false);
            err.put("error", "BAD_INPUT");
            return err;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key",       n.key());
        body.put("md5",       n.md5());
        body.put("amount",    n.amount() != null ? n.amount() : java.math.BigDecimal.ZERO);
        body.put("currency",  n.currency() != null ? n.currency() : "USD");
        body.put("from",      n.from()      != null ? n.from()      : "");
        body.put("bank",      n.bank()      != null ? n.bank()      : "");
        body.put("timestamp", n.timestamp() != null ? n.timestamp() : "");
        // ── Real Bakong context — only on the wire when present so the
        // bot's "short hash" line matches api-bakong.nbc.gov.kh's
        // expected input instead of md5[:8] which doesn't. Optional
        // upstream — sending null/blank just omits the line in the DM.
        if (n.hash() != null && !n.hash().isBlank()) {
            body.put("hash", n.hash());
        }
        if (n.externalRef() != null && !n.externalRef().isBlank()) {
            // Spec accepts external_ref / externalRef / tran_id — pick the
            // canonical one. Upstream maps the others as aliases.
            body.put("external_ref", n.externalRef());
        }
        if (n.description() != null && !n.description().isBlank()) {
            body.put("description", n.description());
        }
        if (n.to() != null && !n.to().isBlank()) {
            body.put("to", n.to());
        }
        if (n.createdAtMs() != null) {
            body.put("created_at_ms", n.createdAtMs());
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = client.post()
                    .uri("/tg/notify-paid")
                    .header("X-API-Key", n.key())
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    // Even on 4xx we want the body — the bot returns
                    // structured errors there (e.g. 403 X-API-Key mismatch).
                    .onStatus(s -> s.is4xxClientError() || s.is5xxServerError(),
                            resp -> resp.bodyToMono(String.class).flatMap(b -> {
                                Map<String, Object> err = new LinkedHashMap<>();
                                err.put("ok", false);
                                err.put("sent", false);
                                err.put("status", resp.statusCode().value());
                                err.put("body", b);
                                return reactor.core.publisher.Mono.error(
                                        new UpstreamPayload(err));
                            }))
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            log.info("notify-paid sk={} md5={} sent={} reason={}",
                    mask(n.key()), shortMd5(n.md5()),
                    r == null ? null : r.get("sent"),
                    r == null ? null : r.get("reason"));
            return r == null ? syntheticErr("empty_body") : r;
        } catch (UpstreamPayload up) {
            log.warn("notify-paid sk={} md5={} upstream-rejected body={}",
                    mask(n.key()), shortMd5(n.md5()), up.body);
            return up.body;
        } catch (Exception e) {
            log.warn("notify-paid sk={} md5={} transport-failed: {}",
                    mask(n.key()), shortMd5(n.md5()), e.getMessage());
            return syntheticErr(e.getMessage());
        }
    }

    /**
     * Fire-and-forget variant. Used by the dashboard's payment-status
     * polling so the UI can flip to PAID instantly without waiting on
     * a Telegram round-trip (Render cold-starts can be ~10-15s).
     */
    public void firePaidAsync(PaidNotification n) {
        try {
            notifyPool.execute(() -> firePaid(n));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            log.warn("notify-paid pool rejected sk={} md5={}",
                    mask(n.key()), shortMd5(n.md5()));
        }
    }

    /** Immutable payload for the notify trigger. */
    public record PaidNotification(
            String key,
            String md5,
            java.math.BigDecimal amount,
            String currency,
            String from,
            String bank,
            String timestamp,
            /** Full Bakong transaction hash from /api/check_payment. The
             *  bot derives the short hash (first 8 chars) so it matches
             *  what api-bakong.nbc.gov.kh accepts. */
            String hash,
            /** Bank transaction ID (e.g. "100FT37845835941"). */
            String externalRef,
            /** Remark / note attached to the payment. */
            String description,
            /** Receiver account id ("To (You)" line in the DM). */
            String to,
            /** Exact tx time in epoch millis. */
            Long createdAtMs
    ) {
        /** Backwards-compatible 7-field constructor for callers that
         *  don't have the Bakong context handy. */
        public PaidNotification(String key, String md5,
                                java.math.BigDecimal amount, String currency,
                                String from, String bank, String timestamp) {
            this(key, md5, amount, currency, from, bank, timestamp,
                 null, null, null, null, null);
        }

        /** 10-arg constructor for callers that have hash + externalRef +
         *  description but no receiver / created_at_ms. */
        public PaidNotification(String key, String md5,
                                java.math.BigDecimal amount, String currency,
                                String from, String bank, String timestamp,
                                String hash, String externalRef, String description) {
            this(key, md5, amount, currency, from, bank, timestamp,
                 hash, externalRef, description, null, null);
        }
    }

    /** Internal sentinel for upstream 4xx/5xx so we can return the body. */
    private static final class UpstreamPayload extends RuntimeException {
        final Map<String, Object> body;
        UpstreamPayload(Map<String, Object> body) { this.body = body; }
    }

    private static Map<String, Object> syntheticErr(String reason) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("ok", false);
        err.put("sent", false);
        err.put("error", reason == null ? "transport_failed" : reason);
        return err;
    }

    private static String mask(String s) {
        if (s == null || s.length() < 14) return String.valueOf(s);
        return s.substring(0, 14) + "…";
    }

    private static String shortMd5(String s) {
        if (s == null) return "—";
        return s.length() > 12 ? s.substring(0, 12) : s;
    }

    private static Map<String, Object> notLinked(String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("linked", false);
        out.put("chat_id", null);
        if (error != null) out.put("error", error);
        return out;
    }

    /** Static fallback when upstream is briefly unreachable AND no prior cache. */
    private static Map<String, Object> fallbackInfo() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bot_username", "byme_bank_bot");
        out.put("bot_link",     "https://t.me/byme_bank_bot");
        out.put("deep_link",    "https://t.me/byme_bank_bot?start=connect");
        out.put("instructions", java.util.List.of(
                "1. Open the bot link.",
                "2. Send /start.",
                "3. Paste your sk_ key.",
                "4. We DM you instantly when a customer pays."));
        return out;
    }

    /** Tiny TTL holder. */
    private record CachedInfo(Map<String, Object> body, Instant fetchedAt) {
        boolean fresh() {
            return Duration.between(fetchedAt, Instant.now()).compareTo(INFO_TTL) < 0;
        }
    }
}
