package com.khmerbank.service.auth;

import com.khmerbank.dto.request.LoginRequest;
import com.khmerbank.dto.request.RegisterRequest;
import com.khmerbank.dto.response.AuthResponse;
import com.khmerbank.exception.ApiException;
import com.khmerbank.model.Subscription;
import com.khmerbank.model.User;
import com.khmerbank.model.enums.PlanType;
import com.khmerbank.repository.SubscriptionRepository;
import com.khmerbank.repository.UserRepository;
import com.khmerbank.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByEmail(req.getEmail())) {
            throw ApiException.conflict("EMAIL_EXISTS", "Email is already registered");
        }
        User user = User.builder()
                .email(req.getEmail().toLowerCase().trim())
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .fullName(req.getFullName())
                .phone(req.getPhone())
                .company(req.getCompany())
                .emailVerificationToken(UUID.randomUUID().toString())
                .build();
        userRepository.save(user);

        // Auto-create FREE plan
        subscriptionRepository.save(Subscription.builder()
                .user(user)
                .plan(PlanType.FREE)
                .monthlyQuota(100)
                .price(BigDecimal.ZERO)
                .currentPeriodStart(Instant.now())
                .currentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS))
                .build());

        log.info("User registered: {}", user.getEmail());
        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByEmail(req.getEmail().toLowerCase().trim())
                .orElseThrow(() -> ApiException.unauthorized("INVALID_CREDENTIALS",
                        "Invalid email or password"));

        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw ApiException.unauthorized("NO_PASSWORD_SET",
                    "No password on this account. Sign in with Google or email code, then set a password under Profile.");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw ApiException.unauthorized("INVALID_CREDENTIALS",
                    "Invalid email or password");
        }
        if (!user.isEnabled()) {
            throw ApiException.forbidden("ACCOUNT_DISABLED", "Account is disabled");
        }

        user.setLastLoginAt(Instant.now());
        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
        return AuthResponse.builder()
                .accessToken(jwtService.generateAccessToken(user))
                .refreshToken(jwtService.generateRefreshToken(user))
                .tokenType("Bearer")
                .expiresIn(jwtService.getAccessExpirySeconds())
                .user(AuthResponse.UserDto.builder()
                        .id(user.getId())
                        .email(user.getEmail())
                        .fullName(user.getFullName())
                        .role(user.getRole().name())
                        .build())
                .build();
    }
}
