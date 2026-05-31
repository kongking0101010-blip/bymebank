package com.khmerbank.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QrCodeResponse {
    private String transactionId;
    private BankType bankType;
    private BigDecimal amount;
    private Currency currency;
    private String description;
    private String reference;
    private String qrPayload;
    private String qrImage;
    private String checkUrl;
    private PaymentStatus status;
    private Instant expiresAt;
    private Instant createdAt;
}
