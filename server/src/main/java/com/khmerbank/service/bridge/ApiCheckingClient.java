package com.khmerbank.service.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.khmerbank.service.bridge.BridgeDtos.BankInput;
import com.khmerbank.service.bridge.BridgeDtos.CheckPaymentResponse;
import com.khmerbank.service.bridge.BridgeDtos.GenerateQrResponse;
import com.khmerbank.service.bridge.BridgeDtos.IssueKeyResponse;
import com.khmerbank.service.bridge.BridgeDtos.KeyInfoResponse;
import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin Spring client for the Python bridge ({@code bot-bridge/}).
 *
 * <p>Spring never knows the upstream {@code EXTERNAL_ISSUE_SECRET}.
 * It only knows {@code BRIDGE_AUTH_TOKEN}.
 *
 * <p>Endpoints used:
 * <ul>
 *   <li>{@code POST /bridge/issue_key}   — retried 3× exponential</li>
 *   <li>{@code POST /bridge/generate_qr} — no retry (user-driven)</li>
 *   <li>{@code GET  /bridge/check_payment} — no retry (user-driven)</li>
 * </ul>
 *
 * <p>Timeouts: connect 15s, read 30s.
 */
@Service
@Slf4j
public class ApiCheckingClient {

    private final WebClient client;
    private final String bridgeToken;
    private final String upstreamBaseUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiCheckingClient(
            WebClient.Builder builder,
            @Value("${bridge.url:${BRIDGE_URL:http://localhost:8090}}") String bridgeUrl,
            @Value("${bridge.token:${BRIDGE_AUTH_TOKEN:}}") String bridgeToken,
            @Value("${bridge.timeoutMs:90000}") long timeoutMs,
            @Value("${khmerbank.upstream.base-url:${UPSTREAM_BASE_URL:https://apicheckpayment.onrender.com}}") String upstreamBaseUrl) {
        // Bumped to 90s read timeout because the public Render gateway
        // cold-starts after ~15min idle (free tier). One silent retry
        // on the user-facing endpoints absorbs that warm-up.
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofMillis(timeoutMs <= 0 ? 90_000 : timeoutMs));
        this.client = builder
                .baseUrl(normalizeBridgeUrl(bridgeUrl))
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("X-Bridge-Token", bridgeToken)
                .build();
        this.bridgeToken = bridgeToken;
        this.upstreamBaseUrl = upstreamBaseUrl;
        log.info("ApiCheckingClient → {}  tokenConfigured={}  upstream={}",
                normalizeBridgeUrl(bridgeUrl), bridgeToken != null && !bridgeToken.isBlank(),
                upstreamBaseUrl);
    }

    /**
     * Render injects {@code BRIDGE_URL} from the bridge service's
     * {@code hostport} property (e.g. {@code bymebank-bridge.onrender.com:443})
     * which has no scheme. WebClient requires an absolute URL or every call
     * fails silently, so prepend https:// when the scheme is missing.
     */
    static String normalizeBridgeUrl(String raw) {
        if (raw == null || raw.isBlank()) return "http://localhost:8090";
        String url = raw.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = url.replaceFirst(":443$", "").replaceFirst(":80$", "");
            url = "https://" + url;
        }
        return url;
    }

    /* ────────── 1. issue_key (retried) ────────── */

    /**
     * Mint a new sk_ key. Retried 3× total (initial + 2 retries) on transient
     * bridge / upstream errors. 4xx errors (Bad secret, etc.) do NOT retry
     * (we throw {@link NoRetryException} which is excluded via noRetryFor).
     */
    @Retryable(
            retryFor = ApiCheckingException.class,
            noRetryFor = NoRetryException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, maxDelay = 8_000, multiplier = 4.0),
            recover = "issueKeyRecover"
    )
    public IssueKeyResponse issueKey(String externalId, Long telegramId,
                                     int days, String merchantName,
                                     List<BankInput> banks) {
        return issueKey(externalId, telegramId, days, merchantName, banks, null);
    }

    /**
     * Mint a new sk_ key, carrying buyer-paid context so the upstream
     * admin Telegram notification shows the real numbers (amount, method,
     * md5, plan) instead of the {@code Amount: $0.00 USD / Method:
     * EXTERNAL / MD5: (empty)} default. Idempotent on the upstream side.
     */
    @Retryable(
            retryFor = ApiCheckingException.class,
            noRetryFor = NoRetryException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 500, maxDelay = 8_000, multiplier = 4.0),
            recover = "issueKeyRecoverWithContext"
    )
    public IssueKeyResponse issueKey(String externalId, Long telegramId,
                                     int days, String merchantName,
                                     List<BankInput> banks,
                                     com.khmerbank.service.bridge.UserApiKeyService.PaymentContext payment) {
        ensureConfigured();
        Map<String, Object> body = new HashMap<>();
        body.put("external_id",   externalId);
        if (telegramId != null) body.put("telegram_id", telegramId);
        body.put("days",          Math.max(1, days));
        body.put("merchant_name", merchantName);
        body.put("banks",         banks.stream().map(BankInput::toMap).toList());
        body.put("deliver_to_telegram", false);

        // Payment context — passed straight through the bot-bridge to the
        // Render bot's /api/external/issue_key. Empty values are dropped
        // so we don't poison the upstream record with empty strings or
        // zero amounts when the caller (e.g. legacy /refresh path) hasn't
        // gone through the buy-key wizard.
        if (payment != null) {
            if (payment.planId() != null && !payment.planId().isBlank()) {
                body.put("plan_id", payment.planId());
            }
            if (payment.amountPaid() != null) {
                body.put("amount_paid", payment.amountPaid());
            }
            if (payment.paymentMd5() != null && !payment.paymentMd5().isBlank()) {
                body.put("payment_md5", payment.paymentMd5());
            }
            if (payment.paymentMethod() != null && !payment.paymentMethod().isBlank()) {
                body.put("payment_method", payment.paymentMethod());
            }
        }

        IssueKeyResponse r;
        try {
            r = call("POST", "/bridge/issue_key", body, IssueKeyResponse.class);
        } catch (ApiCheckingException ex) {
            // Don't burn retries on permanent client errors (400/401/403/422 etc.).
            // 5xx and network failures DO retry.
            if (ex.isClientError()) {
                throw new NoRetryException(ex);
            }
            throw ex;
        }
        log.info("issue_key external_id={} bank_count={} masked_key={} expires_in_days={} amount={} method={} md5={}",
                externalId, banks.size(), mask(r.getKey()), r.getExpires_in_days(),
                payment == null ? null : payment.amountPaid(),
                payment == null ? null : payment.paymentMethod(),
                payment == null || payment.paymentMd5() == null ? null
                        : (payment.paymentMd5().length() > 12
                                ? payment.paymentMd5().substring(0, 12) : payment.paymentMd5()));
        return r;
    }

    /** Fallback after retries are exhausted. */
    @Recover
    public IssueKeyResponse issueKeyRecover(ApiCheckingException ex,
                                            String externalId, Long telegramId,
                                            int days, String merchantName,
                                            List<BankInput> banks) {
        log.error("issue_key gave up after retries external_id={} bank_count={} status={} body={}",
                externalId, banks == null ? 0 : banks.size(),
                ex.getStatusCode(), truncate(ex.getResponseBody()));
        // Unwrap NoRetryException so the caller sees the real ApiCheckingException
        if (ex instanceof NoRetryException w) throw (ApiCheckingException) w.getCause();
        throw ex;
    }

    /** Fallback for the payment-context overload. Same behaviour, different signature. */
    @Recover
    public IssueKeyResponse issueKeyRecoverWithContext(ApiCheckingException ex,
                                                       String externalId, Long telegramId,
                                                       int days, String merchantName,
                                                       List<BankInput> banks,
                                                       com.khmerbank.service.bridge.UserApiKeyService.PaymentContext payment) {
        log.error("issue_key gave up after retries external_id={} bank_count={} amount={} status={} body={}",
                externalId, banks == null ? 0 : banks.size(),
                payment == null ? null : payment.amountPaid(),
                ex.getStatusCode(), truncate(ex.getResponseBody()));
        if (ex instanceof NoRetryException w) throw (ApiCheckingException) w.getCause();
        throw ex;
    }

    /* ────────── 2. generate_qr (one silent cold-start retry) ────────── */

    public GenerateQrResponse generatePaymentQr(String apiKey, String bank,
                                                BigDecimal amount, String currency) {
        ensureConfigured();
        Map<String, Object> body = new HashMap<>();
        body.put("api_key",  apiKey);
        body.put("bank",     bank == null ? null : bank.toLowerCase());
        body.put("amount",   amount);
        body.put("currency", currency == null ? "USD" : currency.toUpperCase());

        GenerateQrResponse r = withColdStartRetry(
                "/bridge/generate_qr",
                () -> call("POST", "/bridge/generate_qr", body, GenerateQrResponse.class));
        log.info("generate_qr key={} bank={} amount={} {} success={} md5={}",
                mask(apiKey), bank == null ? "—" : bank.toLowerCase(),
                amount, currency, r.isSuccess(),
                r.getMd5() == null ? "—" : r.getMd5().substring(0, Math.min(8, r.getMd5().length())));
        return r;
    }

    /* ────────── 3. check_payment (one silent cold-start retry) ────────── */

    public CheckPaymentResponse checkPayment(String md5, String apiKey) {
        ensureConfigured();
        CheckPaymentResponse r = withColdStartRetry("/bridge/check_payment", () -> {
            try {
                return client.get()
                        .uri(uri -> uri.path("/bridge/check_payment")
                                .queryParam("md5", md5)
                                .queryParam("key", apiKey)
                                .build())
                        .retrieve()
                        .bodyToMono(CheckPaymentResponse.class)
                        .timeout(Duration.ofSeconds(90))
                        .block();
            } catch (WebClientResponseException e) {
                throw translate(e, "/bridge/check_payment");
            } catch (WebClientRequestException e) {
                throw new ApiCheckingException("check_payment failed: " + e.getMessage(), e);
            } catch (Exception e) {
                throw new ApiCheckingException("check_payment failed: " + e.getMessage(), e);
            }
        });
        if (r != null) {
            log.debug("check_payment key={} md5={} status={}",
                    mask(apiKey), md5.substring(0, Math.min(8, md5.length())), r.getStatus());
        }
        return r;
    }

    /* ────────── 4. key_info — canonical bank list (one cold-start retry) ────────── */

    /**
     * Returns the upstream's authoritative bank list registered against
     * a sk_ key. Used by the dashboard to filter the bank picker so
     * users never see a bank that isn't actually linked to their key.
     */
    public KeyInfoResponse keyInfo(String apiKey) {
        ensureConfigured();
        KeyInfoResponse r = withColdStartRetry("/bridge/key_info", () -> {
            try {
                return client.get()
                        .uri(uri -> uri.path("/bridge/key_info")
                                .queryParam("key", apiKey)
                                .build())
                        .retrieve()
                        .bodyToMono(KeyInfoResponse.class)
                        .timeout(Duration.ofSeconds(90))
                        .block();
            } catch (WebClientResponseException e) {
                throw translate(e, "/bridge/key_info");
            } catch (WebClientRequestException e) {
                throw new ApiCheckingException("key_info failed: " + e.getMessage(), e);
            } catch (Exception e) {
                throw new ApiCheckingException("key_info failed: " + e.getMessage(), e);
            }
        });
        if (r != null) {
            log.debug("key_info key={} registered={} banks={}",
                    mask(apiKey), r.isRegistered(),
                    r.bankSlugs());
        }
        return r;
    }

    /**
     * Wraps a single bridge call so that the FIRST cold-start failure
     * (timeout or 502/503/504) auto-retries once after a short pause.
     * Permanent failures (4xx other than 408/429) propagate immediately.
     */
    private <T> T withColdStartRetry(String path, java.util.function.Supplier<T> op) {
        try {
            return op.get();
        } catch (ApiCheckingException ex) {
            if (!isColdStartCandidate(ex)) {
                throw friendlyOrSame(ex);
            }
            log.warn("bridge {} cold-start retry after: {}", path, ex.getMessage());
            try {
                Thread.sleep(750);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            try {
                return op.get();
            } catch (ApiCheckingException retryEx) {
                throw friendlyOrSame(retryEx);
            }
        }
    }

    /** Treat connect/read timeouts and 5xx (except a real upstream business
     *  error like 422) as transient cold-start hiccups worth one retry. */
    private boolean isColdStartCandidate(ApiCheckingException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof java.util.concurrent.TimeoutException) return true;
        if (cause instanceof java.net.SocketTimeoutException) return true;
        if (cause instanceof WebClientRequestException) return true;
        int status = ex.getStatusCode();
        return status == 408 || status == 502 || status == 503 || status == 504;
    }

    /** Replace raw stack-trace text with a user-friendly message for known
     *  network errors before letting it bubble to the controller layer. */
    private ApiCheckingException friendlyOrSame(ApiCheckingException ex) {
        if (isColdStartCandidate(ex)) {
            return new ApiCheckingException(
                    503,
                    "BRIDGE_WARMING_UP",
                    "Payment service warming up, please retry in a moment.");
        }
        return ex;
    }

    /**
     * Hard-revokes an sk_ key on the upstream bot. Requires the bot's
     * "self-proof" auth model where the {@code X-API-Key} header equals the
     * key being revoked.
     *
     * <p>Treats upstream 404 ("Key not found in any local store") as success
     * — the key is already gone, the dashboard can still finish cleaning up
     * Oracle. Anything else non-2xx propagates as an
     * {@link ApiCheckingException}.
     */
    public void ownerRevokeKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiCheckingException(400, "BAD_KEY", "Missing api key");
        }
        // Resolve the upstream public base URL — preferring the configured
        // VPS / Render gateway, falling back to the live default.
        String base = upstreamBaseUrl == null || upstreamBaseUrl.isBlank()
                ? "https://apicheckpayment.onrender.com"
                : upstreamBaseUrl.replaceAll("/+$", "");
        String url = base + "/api/owner/revoke_key";

        try {
            org.springframework.web.reactive.function.client.WebClient direct =
                org.springframework.web.reactive.function.client.WebClient.builder()
                    .baseUrl(base)
                    .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create()
                            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                            .responseTimeout(Duration.ofSeconds(30))))
                    .build();

            Map<String, Object> body = new HashMap<>();
            body.put("key", apiKey);

            direct.post()
                    .uri("/api/owner/revoke_key")
                    .header("X-API-Key", apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            log.info("owner/revoke_key key={} → 200 ok", mask(apiKey));
        } catch (WebClientResponseException e) {
            int sc = e.getRawStatusCode();
            String b = e.getResponseBodyAsString();
            // Bot's "key already gone" — treat as success.
            if (sc == 404) {
                log.info("owner/revoke_key key={} → 404 (already gone, ok)",
                        mask(apiKey));
                return;
            }
            log.warn("owner/revoke_key key={} → {} body={}",
                    mask(apiKey), sc, b.length() > 200 ? b.substring(0, 200) : b);
            throw translate(e, url);
        } catch (WebClientRequestException e) {
            throw new ApiCheckingException("owner/revoke_key network error: "
                    + e.getMessage(), e);
        } catch (Exception e) {
            throw new ApiCheckingException("owner/revoke_key failed: "
                    + e.getMessage(), e);
        }
    }

    /* ────────── helpers ────────── */

    private <T> T call(String method, String path, Map<String, Object> body, Class<T> type) {
        try {
            WebClient.RequestBodySpec spec = client.method(
                    org.springframework.http.HttpMethod.valueOf(method)).uri(path);
            if (body != null) spec.bodyValue(body);
            return spec.retrieve()
                    .bodyToMono(type)
                    .timeout(Duration.ofSeconds(90))
                    .block();
        } catch (WebClientResponseException e) {
            throw translate(e, path);
        } catch (WebClientRequestException e) {
            throw new ApiCheckingException("Bridge call to " + path + " failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ApiCheckingException("Bridge call to " + path + " failed: " + e.getMessage(), e);
        }
    }

    private ApiCheckingException translate(WebClientResponseException e, String path) {
        String body = e.getResponseBodyAsString();
        log.warn("bridge {} HTTP {}: {}", path, e.getRawStatusCode(),
                body.length() > 200 ? body.substring(0, 200) : body);
        String code = "UPSTREAM_HTTP_" + e.getRawStatusCode();
        String message;
        try {
            Map<?, ?> json = mapper.readValue(body, Map.class);
            Object errObj = json.get("error");
            if (errObj == null) errObj = json.get("detail");
            if (errObj == null) errObj = "Bridge error";
            message = String.valueOf(errObj);
        } catch (Exception ignore) {
            message = body.isBlank() ? e.getMessage() : body;
        }
        return new ApiCheckingException(e.getRawStatusCode(), code, message, body);
    }

    private void ensureConfigured() {
        if (bridgeToken == null || bridgeToken.isBlank()) {
            throw new ApiCheckingException(503, "BRIDGE_NOT_CONFIGURED",
                    "Set BRIDGE_AUTH_TOKEN to enable bridge calls.");
        }
    }

    /** Returns e.g. {@code sk_a…2a16}. Used in every log line. */
    static String mask(String value) {
        if (value == null || value.isBlank()) return "—";
        if (value.length() <= 8) return value.charAt(0) + "…";
        return value.substring(0, 4) + "…" + value.substring(value.length() - 4);
    }

    private static String truncate(String s) {
        if (s == null) return "—";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** Tag exception so spring-retry skips retries. Caller should expect the
     *  cause ({@link ApiCheckingException}). */
    static final class NoRetryException extends ApiCheckingException {
        NoRetryException(ApiCheckingException src) {
            super(src.getStatusCode(), src.getUpstreamCode(), src.getMessage(), src.getResponseBody());
            initCause(src);
        }
    }
}
