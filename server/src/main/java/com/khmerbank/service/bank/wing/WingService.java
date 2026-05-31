package com.khmerbank.service.bank.wing;

import com.khmerbank.exception.ApiException;
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
 * Wing Money integration via Wing Online Acquiring API.
 */
@Service
@Slf4j
public class WingService implements BankIntegration {

    private final WebClient client;
    private final String partnerId;
    private final String partnerSecret;

    public WingService(WebClient.Builder builder,
                       @Value("${app.bank.wing.base-url}") String baseUrl,
                       @Value("${app.bank.wing.partner-id:}") String partnerId,
                       @Value("${app.bank.wing.partner-secret:}") String partnerSecret) {
        this.client = builder.baseUrl(baseUrl).build();
        this.partnerId = partnerId;
        this.partnerSecret = partnerSecret;
    }

    @Override
    public BankType bankType() { return BankType.WING; }

    @Override
    public PaymentStatus checkStatus(QrCode qr) {
        if (partnerSecret == null || partnerSecret.isBlank()) {
            log.debug("Wing partner secret not configured");
            return qr.getStatus();
        }
        try {
            String ts = String.valueOf(System.currentTimeMillis() / 1000);
            String sig = HashUtil.hmacSha256Hex(partnerSecret, partnerId + qr.getTransactionId() + ts);

            Map<?, ?> resp = client.get()
                    .uri(uri -> uri.path("/api/v1/payments/{id}/status")
                            .build(qr.getTransactionId()))
                    .header("X-Partner-Id", partnerId)
                    .header("X-Timestamp", ts)
                    .header("X-Signature", sig)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(8))
                    .onErrorResume(e -> {
                        log.warn("Wing check failed: {}", e.getMessage());
                        return Mono.empty();
                    })
                    .block();

            if (resp == null) return qr.getStatus();
            Object status = resp.get("status");
            if ("PAID".equalsIgnoreCase(String.valueOf(status))
                    || "SUCCESS".equalsIgnoreCase(String.valueOf(status))) {
                return PaymentStatus.PAID;
            }
            return qr.getStatus();
        } catch (Exception e) {
            throw ApiException.internal("WING_CHECK_FAILED", e.getMessage());
        }
    }

    @Override
    public void verifyWebhook(String signature, String rawBody) {
        if (partnerSecret == null || partnerSecret.isBlank()) return;
        String expected = HashUtil.hmacSha256Hex(partnerSecret, rawBody);
        if (signature == null || !expected.equalsIgnoreCase(signature)) {
            throw ApiException.unauthorized("INVALID_SIGNATURE", "Invalid Wing signature");
        }
    }
}
