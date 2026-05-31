package com.khmerbank.repository;

import com.khmerbank.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailIgnoreCase(String email);
    boolean existsByEmail(String email);
    Optional<User> findByGoogleSub(String googleSub);
    Optional<User> findByEmailVerificationToken(String token);

    @Query("""
        select u from User u
        where lower(u.email) like lower(concat('%', :q, '%'))
           or lower(u.fullName) like lower(concat('%', :q, '%'))
        order by u.createdAt desc
    """)
    Page<User> search(String q, Pageable pageable);

    Page<User> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByCreatedAtAfter(Instant after);
}
