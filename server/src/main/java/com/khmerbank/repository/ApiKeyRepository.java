package com.khmerbank.repository;

import com.khmerbank.model.ApiKey;
import com.khmerbank.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {
    Optional<ApiKey> findByKeyHash(String keyHash);
    List<ApiKey> findByUserOrderByCreatedAtDesc(User user);
    long countByUserAndActiveTrue(User user);
}
