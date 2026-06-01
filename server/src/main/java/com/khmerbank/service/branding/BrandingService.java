package com.khmerbank.service.branding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.netty.channel.ChannelOption;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cache + fetcher for admin-uploaded branding logos.
 *
 * <p>The Telegram bot is the source of truth — we just keep a 5-minute
 * in-memory snapshot so the dashboard doesn't hammer the bridge on every
 * page load.
 *
 * <p>If the bridge is unreachable, the last good snapshot is returned
 * (stale-OK) — we never want logos to "break" the UI.
 */
@Service
@Slf4j
public class BrandingService {

    /** Slots we accept from the upstream. Anything else is dropped. */
    public static final Set<String> ALLOWED_SLOTS =
            Set.of("brand", "aba", "wing", "acleda", "bakong");

    /** TTL — refresh every 60 s. Matches the bridge cache so admin uploads
     *  appear in the dashboard within ~1 minute end-to-end. */
    private static final Duration TTL = Duration.ofSeconds(60);

    private final WebClient bridge;
    private final AtomicReference<Snapshot> cache = new AtomicReference<>(Snapshot.empty());

    public BrandingService(WebClient.Builder builder,
                           @Value("${bridge.url:${BRIDGE_URL:http://localhost:8090}}") String bridgeUrl,
                           @Value("${bridge.token:${BRIDGE_AUTH_TOKEN:}}") String bridgeToken) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(15));
        this.bridge = builder
                .baseUrl(normalizeUrl(bridgeUrl))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("X-Bridge-Token", bridgeToken)
                .build();
    }

    /**
     * Render injects {@code BRIDGE_URL} from the bridge service's
     * {@code hostport} property — e.g. {@code bymebank-bridge.onrender.com:443}
     * — which has NO scheme. WebClient needs an absolute URL with a scheme or
     * it silently fails every call (and we'd serve empty logos forever). So if
     * the value doesn't already start with http(s), prepend https://. A bare
     * ":443"/":80" port suffix is dropped since the scheme implies it.
     */
    static String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) return "http://localhost:8090";
        String url = raw.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = url.replaceFirst(":443$", "").replaceFirst(":80$", "");
            url = "https://" + url;
        }
        return url;
    }

    /** Public read used by the controller. Refreshes on demand if the
     *  snapshot is stale. */
    public Map<String, String> getLogos() {
        Snapshot s = cache.get();
        if (s.isFresh() && s.error == null) return s.logos;
        return refresh(false);
    }

    /** Force-refresh — used by the admin "Refresh from bot" button. */
    public Map<String, String> refresh() {
        return refresh(true);
    }

    /** Upload a logo into the bridge's local store. */
    public Map<String, String> upsert(String slot, String dataUrl) {
        if (!ALLOWED_SLOTS.contains(slot)) {
            throw new IllegalArgumentException("Unknown slot: " + slot);
        }
        if (dataUrl == null || !dataUrl.startsWith("data:image/")) {
            throw new IllegalArgumentException("dataUrl must be a data:image/* URL");
        }
        if (dataUrl.length() > 750_000) {
            throw new IllegalArgumentException("Logo too large (max ~500 KB)");
        }
        try {
            BridgeLogosResponse resp = bridge.post()
                    .uri("/bridge/logos")
                    .bodyValue(Map.of("slot", slot, "dataUrl", dataUrl))
                    .retrieve()
                    .bodyToMono(BridgeLogosResponse.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            Map<String, String> logos = sanitise(resp == null ? null : resp.getLogos());
            cache.set(new Snapshot(Instant.now(), logos, null));
            log.info("branding upserted slot={} new_slots={}", slot, logos.keySet());
            return logos;
        } catch (Exception e) {
            log.warn("branding upsert failed slot={}: {}", slot, e.getMessage());
            throw new RuntimeException("Could not save logo: " + e.getMessage(), e);
        }
    }

    /** Remove a logo from the bridge's local store. */
    public Map<String, String> delete(String slot) {
        if (!ALLOWED_SLOTS.contains(slot)) {
            throw new IllegalArgumentException("Unknown slot: " + slot);
        }
        try {
            BridgeLogosResponse resp = bridge.delete()
                    .uri("/bridge/logos/{slot}", slot)
                    .retrieve()
                    .bodyToMono(BridgeLogosResponse.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            Map<String, String> logos = sanitise(resp == null ? null : resp.getLogos());
            cache.set(new Snapshot(Instant.now(), logos, null));
            log.info("branding deleted slot={} remaining={}", slot, logos.keySet());
            return logos;
        } catch (Exception e) {
            log.warn("branding delete failed slot={}: {}", slot, e.getMessage());
            throw new RuntimeException("Could not delete logo: " + e.getMessage(), e);
        }
    }

    /** Diagnostic snapshot for the admin page. */
    public Map<String, Object> meta() {
        Snapshot s = cache.get();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fetchedAt",   s.fetchedAt == null ? null : s.fetchedAt.toString());
        m.put("ageSeconds",  s.fetchedAt == null
                ? null
                : Duration.between(s.fetchedAt, Instant.now()).toSeconds());
        m.put("ttlSeconds",  TTL.toSeconds());
        m.put("slots",       s.logos.keySet().stream().sorted().toList());
        m.put("error",       s.error);
        return m;
    }

    /** Fetch from the bridge and update the cache.
     *  Falls back to the last good snapshot on any error. */
    private synchronized Map<String, String> refresh(boolean force) {
        // Re-check inside the lock so concurrent refreshes don't double-fetch.
        Snapshot current = cache.get();
        if (!force && current.isFresh() && current.error == null) return current.logos;

        try {
            BridgeLogosResponse resp = bridge.get()
                    .uri(uri -> uri.path("/bridge/logos")
                            .queryParam("refresh", String.valueOf(force))
                            .build())
                    .retrieve()
                    .bodyToMono(BridgeLogosResponse.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();

            Map<String, String> logos = sanitise(resp == null ? null : resp.getLogos());
            cache.set(new Snapshot(Instant.now(), logos, null));
            log.info("branding refreshed slots={}", logos.keySet());
            return logos;
        } catch (Exception e) {
            // Keep serving the last good logos.
            cache.set(new Snapshot(Instant.now(), current.logos, e.getMessage()));
            log.warn("branding refresh failed (serving stale): {}", e.getMessage());
            return current.logos;
        }
    }

    /** Filter to allowed slots + ensure each value is a data: URL ≤ 500 KB. */
    private static Map<String, String> sanitise(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) return Collections.emptyMap();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String slot = e.getKey();
            String url  = e.getValue();
            if (!ALLOWED_SLOTS.contains(slot)) continue;
            if (url == null || !url.startsWith("data:image/")) continue;
            if (url.length() > 750_000)                       continue; // ~500KB base64
            out.put(slot, url);
        }
        return Collections.unmodifiableMap(out);
    }

    // ── shapes ──────────────────────────────────────────────────────────

    private record Snapshot(Instant fetchedAt, Map<String, String> logos, String error) {
        static Snapshot empty() { return new Snapshot(null, Collections.emptyMap(), null); }
        boolean isFresh() {
            return fetchedAt != null
                && Duration.between(fetchedAt, Instant.now()).compareTo(TTL) < 0;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BridgeLogosResponse {
        private boolean ok;
        private Map<String, String> logos;
        private String error;
    }
}
