package com.khmerbank.repository;

import com.khmerbank.model.AuditLog;
import com.khmerbank.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findTop50ByUserOrderByCreatedAtDesc(User user);

    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
