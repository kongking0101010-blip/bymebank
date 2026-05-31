package com.khmerbank.model;

import com.khmerbank.model.enums.BankType;
import com.khmerbank.model.enums.Currency;
import com.khmerbank.model.enums.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "qr_codes", indexes = {
        @Index(name = "idx_qr_txn", columnList = "transactionId", unique = true),
        @Index(name = "idx_qr_user", columnList = "user_id"),
        @Index(name = "idx_qr_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QrCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 64)
    private String transactionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "merchant_id", nullable = false)
    private Merchant merchant;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BankType bankType;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 4)
    private Currency currency;

    /** EMV / KHQR raw payload */
    @Column(nullable = false, length = 4096)
    private String qrPayload;

    /** PNG base64 (cached for quick display) */
    @Lob
    private String qrImageBase64;

    @Column(length = 512)
    private String description;

    @Column(length = 256)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    private String bankReference;

    private Instant paidAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
