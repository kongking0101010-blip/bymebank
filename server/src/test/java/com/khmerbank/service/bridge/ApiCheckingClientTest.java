package com.khmerbank.service.bridge;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.khmerbank.service.bridge.BridgeDtos.BankInput;
import com.khmerbank.service.bridge.BridgeDtos.CheckPaymentResponse;
import com.khmerbank.service.bridge.BridgeDtos.GenerateQrResponse;
import com.khmerbank.service.bridge.BridgeDtos.IssueKeyResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ApiCheckingClient} using WireMock to stub the
 * Python bridge.
 */
class ApiCheckingClientTest {

    private WireMockServer server;
    private ApiCheckingClient client;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
        client = new ApiCheckingClient(
                WebClient.builder(),
                "http://localhost:" + server.port(),
                "test-bridge-token",
                30_000,
                "https://apicheckpayment.onrender.com");
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    /* ────────── issue_key ────────── */

    @Test
    void issueKey_succeeds_andSendsBridgeToken() {
        server.stubFor(post(urlEqualTo("/bridge/issue_key"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true,\"key\":\"sk_abcdef1234567890\",\"expires_in_days\":30}")));

        IssueKeyResponse r = client.issueKey(
                "user_42", null, 30, "Demo Cafe",
                List.of(BankInput.bakong("alice@aclb", "012345678")));

        assertThat(r.isOk()).isTrue();
        assertThat(r.getKey()).isEqualTo("sk_abcdef1234567890");
        assertThat(r.getExpires_in_days()).isEqualTo(30);

        server.verify(postRequestedFor(urlEqualTo("/bridge/issue_key"))
                .withHeader("X-Bridge-Token", equalTo("test-bridge-token"))
                .withRequestBody(matchingJsonPath("$.external_id", equalTo("user_42")))
                .withRequestBody(matchingJsonPath("$.merchant_name", equalTo("Demo Cafe")))
                .withRequestBody(matchingJsonPath("$.banks[0].bank", equalTo("bakong"))));
    }

    @Test
    void issueKey_401_throwsTypedException() {
        server.stubFor(post(urlEqualTo("/bridge/issue_key"))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":false,\"error\":\"Bad secret\"}")));

        assertThatThrownBy(() -> client.issueKey(
                "u", null, 30, "Demo",
                List.of(BankInput.bakong("a@aclb", "1"))))
                .isInstanceOf(ApiCheckingException.class)
                .satisfies(ex -> {
                    ApiCheckingException e = (ApiCheckingException) ex;
                    assertThat(e.isUnauthorized()).isTrue();
                    assertThat(e.getMessage()).contains("Bad secret");
                });
    }

    @Test
    void issueKey_503_throwsUnavailable() {
        server.stubFor(post(urlEqualTo("/bridge/issue_key"))
                .willReturn(aResponse().withStatus(503)
                        .withBody("{\"error\":\"Bot not ready\"}")));

        assertThatThrownBy(() -> client.issueKey(
                "u", null, 30, "Demo",
                List.of(BankInput.bakong("a@aclb", "1"))))
                .isInstanceOf(ApiCheckingException.class)
                .extracting("statusCode").isEqualTo(503);
    }

    /* ────────── generate_qr ────────── */

    @Test
    void generateQr_returnsMd5AndImage() {
        server.stubFor(post(urlEqualTo("/bridge/generate_qr"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"md5\":\"abc123\",\"qr_string\":\"0002...\","
                                + "\"qr_image\":\"data:image/png;base64,xyz\"}")));

        GenerateQrResponse r = client.generatePaymentQr(
                "sk_abc", "bakong", new BigDecimal("1.50"), "USD");

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getMd5()).isEqualTo("abc123");
        assertThat(r.getQr_image()).startsWith("data:image/png");
    }

    /* ────────── check_payment ────────── */

    @Test
    void checkPayment_paid() {
        server.stubFor(get(urlPathEqualTo("/bridge/check_payment"))
                .withQueryParam("md5", equalTo("abc"))
                .withQueryParam("key", equalTo("sk_xyz"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"md5\":\"abc\",\"status\":\"PAID\","
                                + "\"amount\":1.5,\"currency\":\"USD\"}")));

        CheckPaymentResponse r = client.checkPayment("abc", "sk_xyz");
        assertThat(r.isPaid()).isTrue();
        assertThat(r.getStatus()).isEqualTo("PAID");
    }

    @Test
    void checkPayment_unpaid() {
        server.stubFor(get(urlPathEqualTo("/bridge/check_payment"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"md5\":\"abc\",\"status\":\"UNPAID\"}")));

        CheckPaymentResponse r = client.checkPayment("abc", "sk_xyz");
        assertThat(r.isPaid()).isFalse();
        assertThat(r.getStatus()).isEqualTo("UNPAID");
    }

    /* ────────── network ────────── */

    @Test
    void networkFailure_translatesToTypedException() {
        // Stop WireMock so the connection refuses
        server.stop();
        try {
            assertThatThrownBy(() -> client.checkPayment("abc", "sk_xyz"))
                    .isInstanceOf(ApiCheckingException.class)
                    .hasMessageContaining("check_payment failed");
        } finally {
            server.start();
        }
    }
}
