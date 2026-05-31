package com.khmerbank.service.subscription;

import com.khmerbank.exception.ApiException;
import com.khmerbank.model.Subscription;
import com.khmerbank.model.User;
import com.khmerbank.model.enums.PlanType;
import com.khmerbank.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;

    public Subscription getMine(User user) {
        return subscriptionRepository.findByUser(user)
                .orElseThrow(() -> ApiException.notFound("NO_SUBSCRIPTION", "Not found"));
    }

    /**
     * Activate a paid plan. In production this is called by the WebhookController
     * after a payment is verified. For dev, the SubscriptionController exposes it directly.
     */
    @Transactional
    public Subscription activatePlan(User user, PlanType plan, String paymentTxnId) {
        Subscription sub = subscriptionRepository.findByUser(user).orElseGet(() ->
                Subscription.builder().user(user).build());

        sub.setPlan(plan);
        sub.setActive(true);
        sub.setPaymentTransactionId(paymentTxnId);
        sub.setCurrentPeriodStart(Instant.now());
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        sub.setUsageThisMonth(0);

        switch (plan) {
            case FREE -> {
                sub.setMonthlyQuota(100);
                sub.setPrice(BigDecimal.ZERO);
            }
            case BASIC -> {
                sub.setMonthlyQuota(5000);
                sub.setPrice(new BigDecimal("10.00"));
            }
            case PRO -> {
                sub.setMonthlyQuota(-1);
                sub.setPrice(new BigDecimal("50.00"));
            }
            case ENTERPRISE -> {
                sub.setMonthlyQuota(-1);
                sub.setPrice(new BigDecimal("200.00"));
            }
        }
        subscriptionRepository.save(sub);
        log.info("Plan {} activated for {}", plan, user.getEmail());
        return sub;
    }
}
