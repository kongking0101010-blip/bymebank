package com.khmerbank.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "webhooks", indexes = {
        @Index(name = "idx_webhook_user", columnList = "user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Webhook {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 1024)
    private String callbackUrl;

    /** HMAC secret used to sign webhook payloads */
    @Column(nullable = false, length = 256)
    private String secret;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Builder.Default
    private long deliveryCount = 0;

    @Builder.Default
    private long failureCount = 0;

    private Instant lastDeliveredAt;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
