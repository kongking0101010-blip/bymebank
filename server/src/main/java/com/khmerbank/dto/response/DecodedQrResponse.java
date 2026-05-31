package com.khmerbank.dto.response;

import com.khmerbank.model.enums.BankType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DecodedQrResponse {
    private BankType bank;
    private String merchantName;
    private String merchantCity;
    private String merchantAccount;
    private String merchantNetwork;
    private String merchantIssuer;
    private String currency;
    private String phone;
    private String reference;
    private String payload;
}
