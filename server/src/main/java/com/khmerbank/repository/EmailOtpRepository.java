package com.khmerbank.repository;

import com.khmerbank.model.EmailOtp;
import com.khmerbank.model.EmailOtp.Purpose;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailOtpRepository extends JpaRepository<EmailOtp, UUID> {

    Optional<EmailOtp> findFirstByEmailAndPurposeAndConsumedAtIsNullOrderByCreatedAtDesc(
            String email, Purpose purpose);

    long countByEmailAndPurposeAndCreatedAtAfter(String email, Purpose purpose, Instant after);
}
