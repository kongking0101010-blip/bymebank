package com.khmerbank.repository;

import com.khmerbank.model.RevocationLog;
import com.khmerbank.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RevocationLogRepository extends JpaRepository<RevocationLog, UUID> {

    List<RevocationLog> findByUserOrderByRevokedAtDesc(User user);
}
