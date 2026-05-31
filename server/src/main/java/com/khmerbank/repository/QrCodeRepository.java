package com.khmerbank.repository;

import com.khmerbank.model.QrCode;
import com.khmerbank.model.User;
import com.khmerbank.model.enums.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QrCodeRepository extends JpaRepository<QrCode, UUID> {
    Optional<QrCode> findByTransactionId(String transactionId);

    Page<QrCode> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    @Query("select q from QrCode q where q.status = :status and q.expiresAt < :now")
    List<QrCode> findExpiredPending(PaymentStatus status, Instant now);
}
