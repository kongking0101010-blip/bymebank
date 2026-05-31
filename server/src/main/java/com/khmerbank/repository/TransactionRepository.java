package com.khmerbank.repository;

import com.khmerbank.model.Transaction;
import com.khmerbank.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    Page<Transaction> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);
}
