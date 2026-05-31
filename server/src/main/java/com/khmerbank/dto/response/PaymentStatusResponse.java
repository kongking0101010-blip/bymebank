package com.khmerbank.dto.response;

import com.khmerbank.model.enums.BankType;
import com.khmerbank.model.enums.Currency;
import com.khmerbank.model.enums.PaymentStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
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
