package com.khmerbank.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class ApiKeyResponse {
    private UUID id;
    private String name;
    private String prefix;
    private String last4;
    /** Only returned right after creation. Never returned again. */
    private String key;
    private boolean active;
    private long usageCount;
    private Instant lastUsedAt;
    private Instant expiresAt;
    private Instant createdAt;
}
