package com.khmerbank.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateApiKeyRequest {
    @NotBlank @Size(max = 100)
    private String name;

    /** Optional expiry in days */
    private Integer expiresInDays;
}
