package com.khmerbank.dto.response;

import com.khmerbank.model.enums.BankType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class MerchantResponse {
    private UUID id;
    private BankType bankType;
    private String merchantName;
    private String merchantCity;
    private String merchantId;
    private String merchantLink;
    private String accountNumber;
    private boolean verified;
    private boolean active;
    private Instant createdAt;
}
