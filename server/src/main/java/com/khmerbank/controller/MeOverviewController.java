package com.khmerbank.controller;

import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.model.BridgeTransaction;
import com.khmerbank.model.User;
import com.khmerbank.model.UserApiKey;
import com.khmerbank.repository.BridgeTransactionRepository;
import com.khmerbank.repository.MerchantRepository;
import com.khmerbank.service.bridge.UserApiKeyService;
import com.khmerbank.service.plan.Plan;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Overview KPIs for the user's dashboard home.
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@Tag(name = "Me — Overview")
public class MeOverviewController {

    private final UserApiKeyService userApiKeyService;
    private final BridgeTransactionRepository txRepo;
    private final MerchantRepository merchantRepository;

    @GetMapping("/overview")
    @Operation(summary = "KPIs for the user's dashboard home")
    public ApiResponse<Map<String, Object>> overview(@AuthenticationPrincipal User user) {
        Map<String, Object> out = new LinkedHashMap<>();

        UserApiKey active = userApiKeyService.activeFor(user).orElse(null);
        if (active != null) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("apiKey",         maskKey(active.getApiKey()));
            k.put("planId",         active.getPlanId());
            k.put("planLabel",      Plan.require(active.getPlanId()).label);
            k.put("expiresAt",      active.getExpiresAt());
            k.put("daysRemaining",  daysRemaining(active.getExpiresAt()));
            k.put("merchantName",   active.getMerchantName());
            out.put("activeKey", k);
        } else {
            out.put("activeKey", null);
        }

        Instant now = Instant.now();
        out.put("tx7d",  bucket(user, now.minus(7, ChronoUnit.DAYS)));
        out.put("tx30d", bucket(user, now.minus(30, ChronoUnit.DAYS)));

        // "Linked banks" must match what the rest of the dashboard shows
        // (Test page, Buy wizard, Keys page) — i.e. banks the active sk_
        // is actually registered for upstream, not local Merchant rows.
        // Falls back to the local list when no active key exists yet.
        List<String> banks;
        if (active != null) {
            UserApiKeyService.BanksLookup lookup = userApiKeyService.banksLookupFor(active);
            if (lookup.valid() && !lookup.banks().isEmpty()) {
                banks = lookup.banks();
            } else {
                banks = merchantRepository.findByUserOrderByCreatedAtDesc(user).stream()
                        .map(m -> m.getBankType().name().toLowerCase())
                        .distinct().toList();
            }
        } else {
            banks = merchantRepository.findByUserOrderByCreatedAtDesc(user).stream()
                    .map(m -> m.getBankType().name().toLowerCase())
                    .distinct().toList();
        }
        out.put("banksLinked", banks);

        return ApiResponse.ok(out);
    }

    private Map<String, Object> bucket(User user, Instant after) {
        BigDecimal usd = nullsafe(txRepo.sumByUserStatusCurrencyAfter(
                user, BridgeTransaction.Status.PAID, "USD", after));
        BigDecimal khr = nullsafe(txRepo.sumByUserStatusCurrencyAfter(
                user, BridgeTransaction.Status.PAID, "KHR", after));
        long paidCount = txRepo.countByUserAndStatus(user, BridgeTransaction.Status.PAID);

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("paidCount", paidCount);
        b.put("paidUsd",   usd);
        b.put("paidKhr",   khr);
        return b;
    }

    private BigDecimal nullsafe(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private long daysRemaining(Instant expiresAt) {
        if (expiresAt == null) return 0;
        long ms = Duration.between(Instant.now(), expiresAt).toDays();
        return Math.max(ms, 0);
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 12) return "—";
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }
}
