package com.khmerbank.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * An sk_ key issued by the bridge for a user. Multiple rows may exist per
 * user; only the latest non-revoked + non-expired one is "active".
 */
@Entity
@Table(name = "user_api_keys", indexes = {
        @Index(name = "idx_user_api_keys_user_id", columnList = "user_id"),
        @Index(name = "idx_user_api_keys_user_active",
                columnList = "user_id,revoked,expires_at"),
        @Index(name = "idx_user_api_keys_hash", columnList = "api_key_hash", unique = true),
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, unique = true, length = 64)
    private String apiKey;

    /** SHA-256(api_key) for indexed lookup without exposing the raw key. */
    @Column(nullable = false, length = 128)
    private String apiKeyHash;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String planId = "1month";   // 1month | 2month | 3month | 1year

    @Column(nullable = false, length = 64)
    private String merchantName;

    /** Optional user-facing label so users can name their keys ("Production", "Dev"). */
    @Column(length = 80)
    private String label;

    /** Whether this is the user's primary key (the one returned by single-key endpoints). */
    @Column(name = "is_primary", nullable = false)
    @Builder.Default
    private boolean primary = false;

    /** JSON snapshot of the {@code BankInput} list sent to the bridge. */
    @Column(columnDefinition = "TEXT")
    private String banksJson;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    @Builder.Default
    private boolean revoked = false;

    private Instant revokedAt;

    @Column(length = 120)
    private String revokeReason;

    private Instant lastUsedAt;
}
