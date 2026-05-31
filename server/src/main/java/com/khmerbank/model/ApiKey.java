package com.khmerbank.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_keys", indexes = {
        @Index(name = "idx_apikey_hash", columnList = "keyHash", unique = true),
        @Index(name = "idx_apikey_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String name;

    /** Public prefix for display: kb_xxxx... */
    @Column(nullable = false, length = 16)
    private String prefix;

    /** Last 4 chars for display only */
    @Column(nullable = false, length = 4)
    private String last4;

    /** SHA-256 hash of the full key */
    @Column(nullable = false, unique = true, length = 128)
    private String keyHash;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    private Instant expiresAt;

    private Instant lastUsedAt;

    @Column(nullable = false)
    @Builder.Default
    private long usageCount = 0;

    @CreationTimestamp
    private Instant createdAt;
}
