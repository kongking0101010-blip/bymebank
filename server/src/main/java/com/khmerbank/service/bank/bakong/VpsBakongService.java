package com.khmerbank.service.bank.bakong;

import com.khmerbank.model.QrCode;
import com.khmerbank.model.enums.BankType;
import com.khmerbank.model.enums.PaymentStatus;
import com.khmerbank.service.bank.BankIntegration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/**
 * Bakong-via-public-gateway integration.
 *
 * <p>Talks to the public KhmerBank Payment gateway (default
 * {@code https://apicheckpayment.onrender.com}). The gateway owns the
 * Bakong token; this service only needs a customer {@code sk_} key
 * (configured via {@code app.bank.bakong.vps-customer-key}).
 *
 * <p>Active only when {@code app.bank.bakong.vps-base-url} is set —
 * which it is by default in {@code application-local.yml} pointing to
 * Render. Override with the {@code APICHECKING_BASE_URL} env var.
 *
 * <p>Heads up: Render's free tier cold-starts after ~15 min idle —
 * the first request can take up to 90s. We tolerate a 60s read timeout
 * and one silent retry on {@link java.util.concurrent.TimeoutException}.
 */
@Service
@ConditionalOnProperty(name = "app.bank.bakong.vps-base-url")
@Slf4j
public class VpsBakongService implements BankIntegration {

    private final WebClient client;
    private final String customerKey;
    private final String baseUrl;

    public VpsBakongService(WebClient.Builder builder,
                            @Value("${app.bank.bakong.vps-base-url}") String baseUrl,
                            @Value("${app.bank.bakong.vps-customer-key:}") String customerKey) {
        this.baseUrl = baseUrl;
        this.client = builder.baseUrl(baseUrl).build();
        this.customerKey = customerKey;
        log.info("VpsBakongService active → {}  (customer key configured: {})",
                baseUrl, customerKey != null && !customerKey.isBlank());
    }

    @Override
    public BankType bankType() { return BankType.BAKONG; }

    @Override
    public PaymentStatus checkStatus(QrCode qr) {
        if (customerKey == null || customerKey.isBlank()) {
            log.debug("VPS customer sk_ key not configured");
            return qr.getStatus();
        }
        String md5 = md5Hex(qr.getQrPayload());

        // Render uses underscore, original VPS used hyphen. Try the canonical
        // underscore path first since that's the public gateway's contract.
        Map<?, ?> resp = doCheck(md5, "/api/check_payment");
        if (resp == null) {
            // One silent retry — soaks up Render cold-start (~30–60s).
            log.debug("VPS check first attempt empty for {}, retrying once", qr.getTransactionId());
            resp = doCheck(md5, "/api/check_payment");
        }
        if (resp == null) return qr.getStatus();
        boolean paid = Boolean.TRUE.equals(resp.get("paid"))
                || "PAID".equalsIgnoreCase(String.valueOf(resp.get("status")));
        return paid ? PaymentStatus.PAID : qr.getStatus();
    }

    /** Returns the response body as a Map, or {@code null} on any error.
     *  Errors are logged but never thrown — payment-status checks are
     *  best-effort and a network blip should leave the QR PENDING. */
    private Map<?, ?> doCheck(String md5, String path) {
        try {
            return client.get()
                    .uri(uri -> uri.path(path)
                            .queryParam("md5", md5)
                            .queryParam("key", customerKey)
                            .build())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(60))   // tolerate Render cold-start
                    .onErrorResume(e -> {
                        log.warn("VPS Bakong check failed via {}{}: {}",
                                baseUrl, path, e.getMessage());
                        return Mono.empty();
                    })
                    .block();
        } catch (Exception e) {
            log.warn("VPS check error", e);
            return null;
        }
    }

    @Override
    public void verifyWebhook(String signature, String rawBody) {
        // Public gateway pushes no webhook to us — we rely on polling
        // /api/check_payment.
    }

    static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(md.digest(input.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
