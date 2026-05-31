package com.khmerbank.dto.request;

import com.khmerbank.model.enums.BankType;
import com.khmerbank.model.enums.Currency;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

@Data
public class GenerateQrRequest {

    @NotNull
    private BankType bankType;

    /**
     * Optional merchant id to use. If null, the user's default merchant for the bank is used.
     */
    private UUID merchantId;

    @NotNull
    @DecimalMin(value = "0.01", message = "Amount must be > 0")
    @DecimalMax(value = "100000.00", message = "Amount too large")
    private BigDecimal amount;

    @NotNull
    private Currency currency;

    @Size(max = 250)
    private String description;

    /** Custom merchant reference / order id */
    @Size(max = 64)
    private String reference;

    /** Expiry in seconds (default 15 min) */
    @Min(60) @Max(86400)
    private Integer expiresIn;
}
