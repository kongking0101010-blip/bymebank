package com.khmerbank.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Bridge-flow payment transaction. One row per QR generated through the
 * bot-bridge. Status flips from PENDING → PAID/UNPAID/EXPIRED as polling
 * resolves it.
 */
@Entity
@Table(name = "bridge_transactions", indexes = {
        @Index(name = "idx_bridge_tx_user_created", columnList = "user_id,created_at"),
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BridgeTransaction {

    public enum Status { PENDING, PAID, UNPAID, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "api_key_id")
    private UserApiKey apiKey;

    @Column(nullable = false, unique = true, length = 32)
    private String md5;

    @Column(nullable = false, length = 20)
    private String bank;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    private Instant paidAt;

    @Column(length = 120)
    private String paidFrom;

    @Column(columnDefinition = "TEXT")
    private String qrString;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
}
