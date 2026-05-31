package com.khmerbank.service.bank.bakong;

import com.khmerbank.model.QrCode;
import com.khmerbank.model.enums.BankType;
import com.khmerbank.model.enums.PaymentStatus;
import com.khmerbank.security.HashUtil;
import com.khmerbank.service.bank.BankIntegration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Bakong KHQR integration.
 *
 * <p>Bakong exposes a /v1/check_transaction_by_md5 endpoint that
 * returns whether a payment with the given QR (MD5-hashed) has been received.
 *
 * <p>This local service is the fallback. When a VPS proxy is configured
 * (via {@code app.bank.bakong.vps-base-url}), Spring deactivates this bean
 * via {@link org.springframework.context.annotation.Conditional} and uses
 * {@link VpsBakongService} instead.
 */
@Service
@org.springframework.context.annotation.Conditional(BakongService.NoVpsCondition.class)
@Slf4j
public class BakongService implements BankIntegration {

    static class NoVpsCondition implements org.springframework.context.annotation.Condition {
        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext ctx,
                               org.springframework.core.type.AnnotatedTypeMetadata md) {
            String v = ctx.getEnvironment().getProperty("app.bank.bakong.vps-base-url");
            return v == null || v.isBlank();
        }
    }

    private final WebClient client;
    private final String token;

    public BakongService(WebClient.Builder builder,
                         @Value("${app.bank.bakong.base-url}") String baseUrl,
                         @Value("${app.bank.bakong.bakong-token:}") String token) {
        this.client = builder.baseUrl(baseUrl).build();
        this.token = token;
    }

    @Override
    public BankType bankType() { return BankType.BAKONG; }

    @Override
    public PaymentStatus checkStatus(QrCode qr) {
        if (token == null || token.isBlank()) {
            log.debug("Bakong token not configured, returning PENDING");
            return qr.getStatus();
        }
        try {
            String md5 = HashUtil.sha256(qr.getQrPayload()).substring(0, 32); // simplified placeholder
            Map<?, ?> resp = client.post()
                    .uri("/v1/check_transaction_by_md5")
                    .header("Authorization", "Bearer " + token)
                    .bodyValue(Map.of("md5", md5))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(8))
                    .onErrorResume(e -> {
                        log.warn("Bakong check failed: {}", e.getMessage());
                        return Mono.empty();
                    })
                    .block();

            if (resp == null) return qr.getStatus();
            Object data = resp.get("data");
            if (data instanceof Map<?, ?> d && "SUCCESS".equalsIgnoreCase(String.valueOf(d.get("status")))) {
                return PaymentStatus.PAID;
            }
            return qr.getStatus();
        } catch (Exception e) {
            log.warn("Bakong check error", e);
            return qr.getStatus();
        }
    }

    @Override
    public void verifyWebhook(String signature, String rawBody) {
        // Bakong does not push webhooks publicly yet — relies on polling.
        // If a custom relay is used, implement HMAC verification here.
    }
}
