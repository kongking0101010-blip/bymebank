package com.khmerbank.sdk.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LinkMerchantRequest {
    private BankType bankType;
    private String merchantName;
    private String merchantCity;
    private String merchantId;
    private String merchantLink;
    private String accountNumber;
    private String secret;
}
