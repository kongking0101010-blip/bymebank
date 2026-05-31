package com.khmerbank.service.buykey;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.khmerbank.exception.ApiException;
import com.khmerbank.model.Subscription;
import com.khmerbank.model.User;
import com.khmerbank.model.enums.BankType;
import com.khmerbank.model.enums.PlanType;
import com.khmerbank.repository.SubscriptionRepository;
import com.khmerbank.service.apikey.ApiKeyService;
import com.khmerbank.dto.request.CreateApiKeyRequest;
import com.khmerbank.dto.response.ApiKeyResponse;
import com.khmerbank.service.bridge.ApiCheckingClient;
import com.khmerbank.service.bridge.ApiCheckingException;
import com.khmerbank.service.bridge.BridgeDtos.CheckPaymentResponse;
import com.khmerbank.service.bridge.BridgeDtos.GenerateQrResponse;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Guided wizard backend:
 *   1. Select banks → save in subscription draft
 *   2. Pick a plan duration → compute price by bank count × multiplier
 *   3. Generate a payment KHQR via the bot using the platform's own sk_ key
 *   4. Poll until paid (via the bot's /check_payment)
 *   5. Activate subscription + mint API key
 *
 * <p><b>Why we route through the bot</b>: the bot owns the only reliable
 * source of scannable KHQRs for platform-owned merchants. Locally synthesising
 * a KHQR with a placeholder Bakong handle produced unscannable QRs, so we
 * delegate to the same {@code /generate_qr} endpoint the Test API Key page
 * uses, just signed with the platform's customer key.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BuyKeyService {

    private final SubscriptionRepository subscriptionRepository;
    private final ApiKeyService apiKeyService;
    private final ApiCheckingClient apiChecking;
    private final com.khmerbank.service.notify.NotifyService notifyService;

    /** Platform's own customer sk_ key registered with the bot. Used to mint
     *  the QR shown in the Buy-Key wizard so the user can pay us. */
    @Value("${app.platform.customer-key:${app.bank.bakong.vps-customer-key:}}")
    private String platformCustomerKey;

    /** Display name shown on the platform's payment QR. */
    @Value("${app.platform.merchant-name:KhmerBank}")
    private String platformName;

    /* -------------------- pricing -------------------- */

    /**
     * Base USD price for the 1-month plan, indexed by number of banks.
     * Other plan prices are derived from this with a per-plan multiplier
     * (see {@link #PLANS}), so changing one cell in this map updates
     * every plan price for that bank count.
     *
     * <pre>
     *   Banks │ 1 Month │ 2 Months (10%) │ 1 Year (50%)
     *   ──────┼─────────┼───────────────┼──────────────
     *     1   │  $1.50  │     $2.70     │    $9.00
     *     2   │  $2.50  │     $4.50     │   $15.00
     *     3   │  $3.50  │     $6.30     │   $21.00
     *     4   │  $4.00  │     $7.20     │   $24.00
     * </pre>
     */
    private static final Map<Integer, BigDecimal> BANK_PRICES = Map.of(
            1, new BigDecimal("1.50"),
            2, new BigDecimal("2.50"),
            3, new BigDecimal("3.50"),
            4, new BigDecimal("4.00")
    );

    /**
     * Duration multipliers applied to {@link #BANK_PRICES}.
     *  - 2 Months = 1 Month × 1.8 (= 2 months at 10% off → 0.9 × 2)
     *  - 1 Year   = 1 Month × 6   (= 12 months at 50% off → 0.5 × 12)
     */
    public static final List<PricingPlan> PLANS = List.of(
            new PricingPlan("1month", "1 Month",  30,  new BigDecimal("1.0"), "Standard"),
            new PricingPlan("2month", "2 Months", 60,  new BigDecimal("1.8"), "10% off"),
            new PricingPlan("1year",  "1 Year",   365, new BigDecimal("6.0"), "50% off · BEST VALUE")
    );

    public PricingTable pricingFor(int bankCount) {
        if (bankCount < 1) bankCount = 1;
        if (bankCount > 4) bankCount = 4;
        BigDecimal base = BANK_PRICES.get(bankCount);
        return PricingTable.builder()
                .bankCount(bankCount)
                .base(base)
                .plans(PLANS.stream().map(p -> PricedPlan.builder()
                        .id(p.id())
                        .label(p.label())
                        .days(p.days())
                        .discount(p.discount())
                        .price(base.multiply(p.multiplier())
                                .setScale(2, java.math.RoundingMode.HALF_UP))
                        .build()).toList())
                .build();
    }

    /* -------------------- payment methods (live, from bot) -------------------- */

    /**
     * The banks the user can scan to PAY THE PLATFORM. Pulled from the
     * upstream /key_info endpoint for the platform's own customer sk_ key,
     * so the wizard never offers ABA when the platform isn't actually
     * registered for ABA (which produced "Bank aba not registered for this
     * API key" 404s in the past).
     *
     * Returns lowercase bank slugs ({@code "aba"}, {@code "wing"},
     * {@code "acleda"}, {@code "bakong"}). Empty list when the platform
     * key isn't configured or upstream is unreachable — the dashboard
     * shows a "platform payment unavailable" banner in that case.
     */
    public List<String> platformBankSlugs() {
        if (platformCustomerKey == null || platformCustomerKey.isBlank()) {
            return List.of();
        }
        try {
            com.khmerbank.service.bridge.BridgeDtos.KeyInfoResponse r =
                    apiChecking.keyInfo(platformCustomerKey);
            if (r != null && r.isSuccess() && r.isRegistered()) {
                return r.bankSlugs();
            }
        } catch (Exception e) {
            log.warn("platformBankSlugs upstream failed: {}", e.getMessage());
        }
        return List.of();
    }

    /* -------------------- payment QR for the user to pay US -------------------- */

    @Transactional
    public PaymentDraft startPayment(User user, BankType method, BigDecimal amount, int days) {
        if (platformCustomerKey == null || platformCustomerKey.isBlank()) {
            throw ApiException.internal("PLATFORM_NOT_CONFIGURED",
                    "Server not configured: app.platform.customer-key (or app.bank.bakong.vps-customer-key)");
        }

        // Route through the bot — same code path as the Test API Key page —
        // so the user gets a real, scannable KHQR for the platform merchant.
        // We just sign the call with the platform's own customer sk_ key.
        String bank = method == null ? "bakong" : method.name().toLowerCase();
        GenerateQrResponse r;
        try {
            r = apiChecking.generatePaymentQr(platformCustomerKey, bank, amount, "USD");
        } catch (ApiCheckingException e) {
            log.warn("BuyKey QR generation failed via bot: {}", e.getMessage());
            throw ApiException.badRequest(
                    e.getUpstreamCode() == null ? "BRIDGE_FAILED" : e.getUpstreamCode(),
                    e.getMessage());
        }

        if (!r.isSuccess() || r.getMd5() == null || r.getQr_image() == null) {
            String why = r.getError() == null ? "Bot rejected the request" : r.getError();
            log.warn("BuyKey QR generation upstream failure: {}", why);
            throw ApiException.badRequest("UPSTREAM_FAIL", why);
        }

        String txnId = "BK-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        return PaymentDraft.builder()
                .transactionId(txnId)
                .qrPayload(r.getQr_string())
                .qrImage(r.getQr_image())
                .md5(r.getMd5())
                .amount(amount)
                .days(days)
                .method(method == null ? "BAKONG" : method.name())
                .build();
    }

    /**
     * Polls the bot's /check_payment endpoint to see if the platform has
     * received the customer's payment for this QR.
     *
     * @param md5       md5 returned by {@link #startPayment}
     * @param qrPayload the raw EMV string (kept in the call signature for
     *                  back-compat; not actually needed when checking via
     *                  the bot, only the md5 + key matter).
     */
    public boolean isPaid(String md5, String qrPayload) {
        if (platformCustomerKey == null || platformCustomerKey.isBlank()) return false;
        try {
            CheckPaymentResponse r = apiChecking.checkPayment(md5, platformCustomerKey);
            boolean paid = r != null && r.isPaid();
            if (paid) {
                // The buy-key flow is paying the PLATFORM's customer key,
                // so the DM lands in whichever Telegram chat the platform
                // owner linked. Fire-and-forget — never block the wizard
                // poll on a Telegram round-trip. Idempotent upstream.
                notifyService.firePaidAsync(new com.khmerbank.service.notify
                        .NotifyService.PaidNotification(
                                platformCustomerKey,
                                md5,
                                r.getAmount(),
                                r.getCurrency(),
                                r.getFrom(),
                                null, // bank slug isn't carried by check_payment for buy-key
                                r.getTimestamp(),
                                r.getHash(),
                                r.getExternalRef(),
                                r.getDescription(),
                                r.getTo(),
                                r.getCreatedAtMs()));
            }
            return paid;
        } catch (Exception e) {
            log.warn("BuyKey isPaid check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * After payment, activate the chosen plan and mint an API key in one go.
     */
    @Transactional
    public ActivationResult activate(User user, int days, BigDecimal amount, String keyName) {
        Subscription sub = subscriptionRepository.findByUser(user)
                .orElseGet(() -> Subscription.builder().user(user).build());

        PlanType chosen = (days >= 365) ? PlanType.PRO
                       : (days >= 90)  ? PlanType.BASIC
                                       : PlanType.FREE;

        sub.setPlan(chosen);
        sub.setActive(true);
        sub.setMonthlyQuota(chosen == PlanType.PRO ? -1
                          : chosen == PlanType.BASIC ? 5000 : 100);
        sub.setPrice(amount);
        sub.setUsageThisMonth(0);
        sub.setCurrentPeriodStart(Instant.now());
        sub.setCurrentPeriodEnd(Instant.now().plus(days, ChronoUnit.DAYS));
        subscriptionRepository.save(sub);

        ApiKeyResponse key;
        CreateApiKeyRequest req = new CreateApiKeyRequest();
        req.setName(keyName == null || keyName.isBlank() ? "Default" : keyName);
        req.setExpiresInDays(days);
        key = apiKeyService.createKey(user, req);

        log.info("Buy-key: activated {} for {} ({} days, ${})",
                chosen, user.getEmail(), days, amount);
        return ActivationResult.builder()
                .apiKey(key.getKey())
                .planType(chosen.name())
                .days(days)
                .expiresAt(sub.getCurrentPeriodEnd())
                .build();
    }

    /* -------------------- DTOs -------------------- */

    public record PricingPlan(String id, String label, int days,
                              BigDecimal multiplier, String discount) {}

    @Data @Builder @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PricingTable {
        private int bankCount;
        private BigDecimal base;
        private List<PricedPlan> plans;
    }

    @Data @Builder
    public static class PricedPlan {
        private String id;
        private String label;
        private int days;
        private BigDecimal price;
        private String discount;
    }

    @Data @Builder
    public static class PaymentDraft {
        private String transactionId;
        private String qrPayload;
        private String qrImage;
        private String md5;
        private BigDecimal amount;
        private int days;
        private String method;
    }

    @Data @Builder
    public static class ActivationResult {
        private String apiKey;
        private String planType;
        private int days;
        private Instant expiresAt;
    }
}
