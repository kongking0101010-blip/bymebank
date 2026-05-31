package com.khmerbank.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit row written every time an sk_ key is hard-revoked. The sk_ value
 * itself is never stored — only its sha256 hash, so this table can survive
 * indefinitely without ever leaking a live key string.
 */
@Entity
@Table(name = "revocation_log", indexes = {
        @Index(name = "idx_revocation_log_user", columnList = "user_id, revoked_at"),
        @Index(name = "idx_revocation_log_hash", columnList = "sk_key_hash"),
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RevocationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** sha256 hex of the sk_ string at the moment of revocation. */
    @Column(name = "sk_key_hash", nullable = false, length = 64)
    private String skKeyHash;

    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt;

    /** Free-form origin tag — e.g. "dashboard", "admin", "cron". */
    @Column(nullable = false, length = 32)
    private String source;
}
