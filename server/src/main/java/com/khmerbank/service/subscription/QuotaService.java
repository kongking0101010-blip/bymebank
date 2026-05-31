package com.khmerbank.service.subscription;

import com.khmerbank.exception.ApiException;
import com.khmerbank.model.Subscription;
import com.khmerbank.model.User;
import com.khmerbank.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class QuotaService {

    private final SubscriptionRepository subscriptionRepository;

    public void assertQuotaAvailable(User user) {
        Subscription sub = getOrThrow(user);
        rolloverIfNeeded(sub);
        if (sub.getMonthlyQuota() < 0) return; // unlimited
        if (sub.getUsageThisMonth() >= sub.getMonthlyQuota()) {
            throw ApiException.tooManyRequests("QUOTA_EXCEEDED",
                    "Monthly quota exceeded. Upgrade your plan to continue.");
        }
    }

    @Transactional
    public void incrementUsage(User user) {
        Subscription sub = getOrThrow(user);
        rolloverIfNeeded(sub);
        sub.setUsageThisMonth(sub.getUsageThisMonth() + 1);
    }

    private Subscription getOrThrow(User user) {
        return subscriptionRepository.findByUser(user)
                .orElseThrow(() -> ApiException.forbidden("NO_SUBSCRIPTION",
                        "No active subscription. Choose a plan first."));
    }

    private void rolloverIfNeeded(Subscription sub) {
        if (sub.getCurrentPeriodEnd() == null
                || sub.getCurrentPeriodEnd().isBefore(Instant.now())) {
            sub.setCurrentPeriodStart(Instant.now());
            sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
            sub.setUsageThisMonth(0);
        }
    }
}
