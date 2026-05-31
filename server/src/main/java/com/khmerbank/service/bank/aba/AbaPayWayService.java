package com.khmerbank.service.bank.aba;

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
 * ABA PayWay integration. Uses HMAC-SHA-512 signing.
 * Endpoint: /api/payment-gateway/v1/payments/check-transaction
 */
@Service
@Slf4j
public class AbaPayWayService implements BankIntegration {

    private final WebClient client;
    private final String merchantId;
    private final String apiKey;

    public AbaPayWayService(WebClient.Builder builder,
                            @Value("${app.bank.aba.base-url}") String baseUrl,
                            @Value("${app.bank.aba.merchant-id:}") String merchantId,
                            @Value("${app.bank.aba.api-key:}") String apiKey) {
        this.client = builder.baseUrl(baseUrl).build();
        this.merchantId = merchantId;
        this.apiKey = apiKey;
    }

    @Override
    public BankType bankType() { return BankType.ABA; }

    @Override
    public PaymentStatus checkStatus(QrCode qr) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("ABA API key not configured");
            return qr.getStatus();
        }
        try {
            String reqTime = String.valueOf(System.currentTimeMillis() / 1000);
            String hashInput = reqTime + merchantId + qr.getTransactionId();
            String hash = HashUtil.hmacSha256Hex(apiKey, hashInput);

            Map<?, ?> resp = client.post()
                    .uri("/api/payment-gateway/v1/payments/check-transaction")
                    .bodyValue(Map.of(
                            "req_time", reqTime,
                            "merchant_id", merchantId,
                            "tran_id", qr.getTransactionId(),
                            "hash", hash))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(8))
                    .onErrorResume(e -> {
                        log.warn("ABA check failed: {}", e.getMessage());
                        return Mono.empty();
                    })
                    .block();

            if (resp == null) return qr.getStatus();
            Object code = resp.get("status");
            if (code != null && code.toString().contains("APPROVED")) {
                return PaymentStatus.PAID;
            }
            return qr.getStatus();
        } catch (Exception e) {
            throw ApiException.internal("ABA_CHECK_FAILED", e.getMessage());
        }
    }

    @Override
    public void verifyWebhook(String signature, String rawBody) {
        if (apiKey == null || apiKey.isBlank()) return;
        String expected = HashUtil.hmacSha256Hex(apiKey, rawBody);
        if (signature == null || !expected.equalsIgnoreCase(signature)) {
            throw ApiException.unauthorized("INVALID_SIGNATURE", "Invalid ABA signature");
        }
    }
}
