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
public class QrCodeResponse {
    private String transactionId;
    private BankType bankType;
    private BigDecimal amount;
    private Currency currency;
    private String description;
    private String reference;
    private String qrPayload;
    private String qrImage;        // data:image/png;base64,...
    private String md5;            // MD5 of qrPayload — use this with VPS /api/check-payment
    private String checkUrl;       // url client can poll
    private PaymentStatus status;
    private Instant expiresAt;
    private Instant createdAt;
}
