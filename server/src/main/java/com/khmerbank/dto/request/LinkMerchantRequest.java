package com.khmerbank.dto.request;

import com.khmerbank.model.enums.BankType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LinkMerchantRequest {

    @NotNull
    private BankType bankType;

    @NotBlank @Size(max = 100)
    private String merchantName;

    @Size(max = 50)
    private String merchantCity;

    /** Bank-specific id (ABA merchant id, Wing acc, Bakong id like user@bank) */
    @NotBlank @Size(max = 100)
    private String merchantId;

    /** Optional ABA PayWay link */
    @Size(max = 500)
    private String merchantLink;

    /** Optional account number */
    @Size(max = 50)
    private String accountNumber;

    /** Optional per-merchant API secret to be encrypted at rest */
    @Size(max = 500)
    private String secret;
}
