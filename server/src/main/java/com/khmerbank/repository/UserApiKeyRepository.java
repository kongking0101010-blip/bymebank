package com.khmerbank.repository;

import com.khmerbank.model.User;
import com.khmerbank.model.UserApiKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserApiKeyRepository extends JpaRepository<UserApiKey, UUID> {

    Optional<UserApiKey> findFirstByUserAndRevokedFalseAndExpiresAtAfterOrderByIssuedAtDesc(
            User user, Instant after);

    List<UserApiKey> findByUserOrderByIssuedAtDesc(User user);

    Optional<UserApiKey> findByApiKey(String apiKey);

    Optional<UserApiKey> findByApiKeyHash(String apiKeyHash);

    long countByRevokedFalseAndExpiresAtAfter(Instant after);

    long countByExpiresAtBetweenAndRevokedFalse(Instant from, Instant to);

    Page<UserApiKey> findAllByOrderByIssuedAtDesc(Pageable pageable);

    Page<UserApiKey> findByUserOrderByIssuedAtDesc(User user, Pageable pageable);
}
