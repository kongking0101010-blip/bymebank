package com.khmerbank.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.khmerbank.sdk.api.MerchantApi;
import com.khmerbank.sdk.api.PaymentApi;
import com.khmerbank.sdk.http.HttpClient;

/**
 * Main entry point for the KhmerBank Java SDK.
 *
 * <pre>
 *   KhmerBankClient client = KhmerBankClient.builder()
 *       .apiKey("kb_xxx")
 *       .baseUrl("https://api.khmerbank.dev")
 *       .build();
 *
 *   QrCodeResponse qr = client.payments().generateQr(GenerateQrRequest.builder()
 *       .bankType(BankType.BAKONG)
 *       .amount(new BigDecimal("12.50"))
 *       .currency(Currency.USD)
 *       .description("Order #1234")
 *       .build());
 *
 *   PaymentStatusResponse status = client.payments().checkStatus(qr.getTransactionId());
 * </pre>
 */
public class KhmerBankClient {

    private static final String DEFAULT_BASE_URL = "https://api.khmerbank.dev";

    private final HttpClient http;
    private final PaymentApi payments;
    private final MerchantApi merchants;

    private KhmerBankClient(Builder b) {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        this.http = new HttpClient(b.baseUrl, b.apiKey, b.timeoutSeconds, mapper);
        this.payments = new PaymentApi(http);
        this.merchants = new MerchantApi(http);
    }

    public PaymentApi payments()   { return payments; }
    public MerchantApi merchants() { return merchants; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String apiKey;
        private String baseUrl = DEFAULT_BASE_URL;
        private int timeoutSeconds = 30;

        public Builder apiKey(String apiKey)         { this.apiKey = apiKey; return this; }
        public Builder baseUrl(String url)           { this.baseUrl = url; return this; }
        public Builder timeoutSeconds(int seconds)   { this.timeoutSeconds = seconds; return this; }

        public KhmerBankClient build() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalArgumentException("apiKey is required");
            }
            return new KhmerBankClient(this);
        }
    }
}
