package com.khmerbank.sdk.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GenerateQrRequest {
    private BankType bankType;
    private UUID merchantId;
    private BigDecimal amount;
    private Currency currency;
    private String description;
    private String reference;
    private Integer expiresIn;
}
