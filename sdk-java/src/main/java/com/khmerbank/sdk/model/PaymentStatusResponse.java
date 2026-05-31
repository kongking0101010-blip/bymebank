package com.khmerbank.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentStatusResponse {
    private String transactionId;
    private PaymentStatus status;
    private BankType bankType;
    private BigDecimal amount;
    private Currency currency;
    private String bankReference;
    private Instant paidAt;
    private Instant expiresAt;
    private boolean paid;
}
