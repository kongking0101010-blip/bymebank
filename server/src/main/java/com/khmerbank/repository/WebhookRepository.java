package com.khmerbank.repository;

import com.khmerbank.model.User;
import com.khmerbank.model.Webhook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WebhookRepository extends JpaRepository<Webhook, UUID> {
    List<Webhook> findByUserAndActiveTrue(User user);
}
