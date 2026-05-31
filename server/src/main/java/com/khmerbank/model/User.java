package com.khmerbank.model;

import com.khmerbank.model.enums.Role;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_user_email", columnList = "email", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    /** Nullable for Google-only sign-ins. */
    @Column
    private String passwordHash;

    @Column(nullable = false)
    private String fullName;

    private String phone;

    private String company;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Role role = Role.USER;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";   // ACTIVE | SUSPENDED | DELETED

    @Column(unique = true, length = 64)
    private String googleSub;

    @Column(length = 500)
    private String avatarUrl;

    private Instant lockedUntil;

    @Column(nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    private String emailVerificationToken;

    private Instant lastLoginAt;

    /** sk_xxxx key issued by the upstream apicheckpayment service. */
    @Column(length = 128)
    private String upstreamApiKey;

    private Instant upstreamApiKeyIssuedAt;
    private Instant upstreamApiKeyExpiresAt;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
