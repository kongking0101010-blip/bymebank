package com.khmerbank.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
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
