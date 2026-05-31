package com.khmerbank.model;

import com.khmerbank.model.enums.BankType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Linked bank account / merchant for a user.
 *
 * <p>For each bank we store the relevant identifier:
 * <ul>
 *     <li><b>ABA</b>: merchant_id + merchant_link (ABA PayWay link)</li>
 *     <li><b>WING</b>: account number (phone or wing acc)</li>
 *     <li><b>BAKONG</b>: bakong_id (e.g. user@bank)</li>
 * </ul>
 */
@Entity
@Table(name = "merchants", indexes = {
        @Index(name = "idx_merchant_user", columnList = "user_id"),
        @Index(name = "idx_merchant_bank", columnList = "bank_type")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "bank_type", nullable = false, length = 16)
    private BankType bankType;

    /** Display name shown on receipts / QR */
    @Column(nullable = false)
    private String merchantName;

    /** City of the merchant (KHQR field 60) */
    @Builder.Default
    private String merchantCity = "Phnom Penh";

    /** Bank-specific identifier */
    @Column(nullable = false)
    private String merchantId;

    /** ABA PayWay merchant link (for ABA only) */
    private String merchantLink;

    /** Wing account number / Bakong account string */
    private String accountNumber;

    /** Encrypted secret/API key (per-merchant credentials if any) */
    @Column(length = 1024)
    private String encryptedSecret;

    @Column(nullable = false)
    @Builder.Default
    private boolean verified = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
