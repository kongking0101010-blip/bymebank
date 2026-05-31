package com.khmerbank.repository;

import com.khmerbank.model.Merchant;
import com.khmerbank.model.User;
import com.khmerbank.model.enums.BankType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MerchantRepository extends JpaRepository<Merchant, UUID> {
    List<Merchant> findByUserOrderByCreatedAtDesc(User user);
    Optional<Merchant> findByIdAndUser(UUID id, User user);
    Optional<Merchant> findByUserAndBankTypeAndActiveTrue(User user, BankType bankType);
}
