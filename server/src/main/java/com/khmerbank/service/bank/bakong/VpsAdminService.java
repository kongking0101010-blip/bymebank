package com.khmerbank.service.bank.bakong;

import com.khmerbank.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Talks to the VPS admin endpoints to mint / list / revoke sk_ keys.
 *
 * <p><b>SECURITY:</b> the ADMIN_KEY here is master credentials. It is loaded
 * from {@code app.bank.bakong.vps-admin-key} which should come from an env
 * variable (never committed to git). It is never exposed to API responses
 * or logs.
 */
@Service
@ConditionalOnProperty(name = "app.bank.bakong.vps-admin-key")
@Slf4j
public class VpsAdminService {

    private final WebClient client;
    private final String adminKey;

    public VpsAdminService(WebClient.Builder builder,
                           @Value("${app.bank.bakong.vps-base-url:https://apicheckpayment.onrender.com}") String baseUrl,
                           @Value("${app.bank.bakong.vps-admin-key}") String adminKey) {
        this.client = builder.baseUrl(baseUrl).build();
        this.adminKey = adminKey;
    }

    public Map<String, Object> mintKey(String label) {
        try {
            return client.post()
                    .uri("/admin/keys/generate")
                    .header("X-Admin-Key", adminKey)
                    .bodyValue(Map.of("label", label == null ? "khmerbank" : label))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(15))
                    .map(m -> (Map<String, Object>) m)
                    .block();
        } catch (Exception e) {
            log.error("VPS mintKey failed: {}", e.getMessage());
            throw ApiException.internal("VPS_MINT_FAILED", "Could not mint key on VPS");
        }
    }

    public List<Map<String, Object>> listKeys() {
        try {
            Map<?, ?> r = client.get()
                    .uri("/admin/keys")
                    .header("X-Admin-Key", adminKey)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            if (r == null || !(r.get("keys") instanceof List<?> list)) return List.of();
            return list.stream()
                    .map(o -> (Map<String, Object>) o)
                    .toList();
        } catch (Exception e) {
            log.error("VPS listKeys failed: {}", e.getMessage());
            throw ApiException.internal("VPS_LIST_FAILED", "Could not list VPS keys");
        }
    }

    public void revokeKey(String key) {
        try {
            client.post()
                    .uri("/admin/keys/revoke")
                    .header("X-Admin-Key", adminKey)
                    .bodyValue(Map.of("key", key))
                    .retrieve()
                    .toBodilessEntity()
                    .timeout(Duration.ofSeconds(15))
                    .block();
        } catch (Exception e) {
            log.error("VPS revokeKey failed: {}", e.getMessage());
            throw ApiException.internal("VPS_REVOKE_FAILED", "Could not revoke VPS key");
        }
    }
}
