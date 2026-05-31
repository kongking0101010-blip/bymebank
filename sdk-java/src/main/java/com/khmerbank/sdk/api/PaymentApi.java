package com.khmerbank.sdk.api;

import com.khmerbank.sdk.http.HttpClient;
import com.khmerbank.sdk.model.GenerateQrRequest;
import com.khmerbank.sdk.model.PaymentStatusResponse;
import com.khmerbank.sdk.model.QrCodeResponse;

import java.time.Duration;

public class PaymentApi {

    private final HttpClient http;

    public PaymentApi(HttpClient http) { this.http = http; }

    /** Generate a QR code for the given amount + bank. */
    public QrCodeResponse generateQr(GenerateQrRequest request) {
        return http.post("/api/v1/payments/qr", request, QrCodeResponse.class);
    }

    /** Check the status of a payment. */
    public PaymentStatusResponse checkStatus(String transactionId) {
        return http.get("/api/v1/payments/" + transactionId + "/status",
                PaymentStatusResponse.class);
    }

    /** Block until the payment is paid, expires, or the timeout elapses. */
    public PaymentStatusResponse waitForPayment(String transactionId, Duration timeout,
                                                Duration pollInterval) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            PaymentStatusResponse s = checkStatus(transactionId);
            if (s.isPaid() || s.getStatus().name().matches("FAILED|EXPIRED|CANCELLED")) {
                return s;
            }
            try { Thread.sleep(pollInterval.toMillis()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        return checkStatus(transactionId);
    }
}
