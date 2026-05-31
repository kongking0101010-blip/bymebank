package com.khmerbank.service.apikey;

import com.khmerbank.dto.request.CreateApiKeyRequest;
import com.khmerbank.dto.response.ApiKeyResponse;
import com.khmerbank.exception.ApiException;
import com.khmerbank.model.ApiKey;
import com.khmerbank.model.User;
import com.khmerbank.repository.ApiKeyRepository;
import com.khmerbank.security.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;

    @Value("${app.api-key.prefix}")
    private String prefix;

    @Value("${app.api-key.length}")
    private int length;

    private static final SecureRandom RNG = new SecureRandom();

    @Transactional
    public ApiKeyResponse createKey(User user, CreateApiKeyRequest req) {
        if (apiKeyRepository.countByUserAndActiveTrue(user) >= 10) {
            throw ApiException.badRequest("KEY_LIMIT", "Maximum 10 active keys per account");
        }

        String rawKey = generateRawKey();
        String hash = HashUtil.sha256(rawKey);
        String visiblePrefix = rawKey.substring(0, Math.min(8, rawKey.length()));
        String last4 = rawKey.substring(rawKey.length() - 4);

        ApiKey apiKey = ApiKey.builder()
                .user(user)
                .name(req.getName())
                .prefix(visiblePrefix)
                .last4(last4)
                .keyHash(hash)
                .expiresAt(req.getExpiresInDays() == null ? null
                        : Instant.now().plus(req.getExpiresInDays(), ChronoUnit.DAYS))
                .build();
        apiKeyRepository.save(apiKey);
        log.info("API key created for user {}", user.getEmail());

        return toResponse(apiKey, rawKey);
    }

    public List<ApiKeyResponse> listKeys(User user) {
        return apiKeyRepository.findByUserOrderByCreatedAtDesc(user)
                .stream()
                .map(k -> toResponse(k, null))
                .toList();
    }

    @Transactional
    public void revokeKey(User user, UUID keyId) {
        ApiKey key = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> ApiException.notFound("KEY_NOT_FOUND", "API key not found"));
        if (!key.getUser().getId().equals(user.getId())) {
            throw ApiException.forbidden("ACCESS_DENIED", "Not your key");
        }
        key.setActive(false);
    }

    @Transactional
    public ApiKey validateAndTouch(String rawKey) {
        if (rawKey == null || !rawKey.startsWith(prefix)) {
            throw ApiException.unauthorized("INVALID_API_KEY", "Invalid API key format");
        }
        String hash = HashUtil.sha256(rawKey);
        ApiKey key = apiKeyRepository.findByKeyHash(hash)
                .orElseThrow(() -> ApiException.unauthorized("INVALID_API_KEY", "API key not found"));

        if (!key.isActive()) {
            throw ApiException.unauthorized("KEY_REVOKED", "API key has been revoked");
        }
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(Instant.now())) {
            throw ApiException.unauthorized("KEY_EXPIRED", "API key has expired");
        }
        key.setLastUsedAt(Instant.now());
        key.setUsageCount(key.getUsageCount() + 1);

        // Force-init the lazy User association so it's usable outside this transaction
        key.getUser().getEmail();
        return key;
    }

    private String generateRawKey() {
        byte[] random = new byte[length];
        RNG.nextBytes(random);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        return prefix + body.substring(0, Math.min(length, body.length()));
    }

    private ApiKeyResponse toResponse(ApiKey k, String rawKey) {
        return ApiKeyResponse.builder()
                .id(k.getId())
                .name(k.getName())
                .prefix(k.getPrefix())
                .last4(k.getLast4())
                .key(rawKey)
                .active(k.isActive())
                .usageCount(k.getUsageCount())
                .lastUsedAt(k.getLastUsedAt())
                .expiresAt(k.getExpiresAt())
                .createdAt(k.getCreatedAt())
                .build();
    }
}
