package com.khmerbank.repository;

import com.khmerbank.model.BridgeTransaction;
import com.khmerbank.model.BridgeTransaction.Status;
import com.khmerbank.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BridgeTransactionRepository
        extends JpaRepository<BridgeTransaction, UUID> {

    Optional<BridgeTransaction> findByMd5(String md5);

    Page<BridgeTransaction> findByUserOrderByCreatedAtDesc(User user, Pageable pageable);

    Page<BridgeTransaction> findByUserAndStatusOrderByCreatedAtDesc(
            User user, Status status, Pageable pageable);

    Page<BridgeTransaction> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByUserAndStatus(User user, Status status);

    @org.springframework.data.jpa.repository.Query("""
        select coalesce(sum(t.amount), 0)
        from BridgeTransaction t
        where t.user = :user and t.status = :status and t.currency = :currency
          and t.createdAt > :after
    """)
    java.math.BigDecimal sumByUserStatusCurrencyAfter(User user, Status status,
                                                      String currency, Instant after);

    @org.springframework.data.jpa.repository.Query("""
        select coalesce(sum(t.amount), 0)
        from BridgeTransaction t
        where t.status = :status and t.currency = :currency
          and t.createdAt > :after
    """)
    java.math.BigDecimal sumGlobalByStatusCurrencyAfter(Status status,
                                                        String currency, Instant after);

    long countByStatus(Status status);

    long countByCreatedAtAfter(Instant after);

    /** Raw rows for service-side daily aggregation (portable across H2/PG/Oracle). */
    @org.springframework.data.jpa.repository.Query("""
        select t.createdAt, t.amount
        from BridgeTransaction t
        where t.status = com.khmerbank.model.BridgeTransaction$Status.PAID
          and t.currency = :currency
          and t.createdAt > :after
    """)
    List<Object[]> paidAmountsAfter(String currency, Instant after);
}
