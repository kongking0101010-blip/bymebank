package com.khmerbank.controller;

import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.exception.ApiException;
import com.khmerbank.model.BridgeTransaction;
import com.khmerbank.model.User;
import com.khmerbank.model.UserApiKey;
import com.khmerbank.model.enums.BankType;
import com.khmerbank.repository.BridgeTransactionRepository;
import com.khmerbank.service.audit.AuditService;
import com.khmerbank.service.bridge.ApiCheckingClient;
import com.khmerbank.service.bridge.ApiCheckingException;
import com.khmerbank.service.bridge.BridgeDtos.CheckPaymentResponse;
import com.khmerbank.service.bridge.BridgeDtos.GenerateQrResponse;
import com.khmerbank.service.bridge.UserApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST routes the dashboard uses to mint, view, and exercise the user's
 * sk_ key (issued by the bridge).
 *
 * Endpoints:
 *   GET    /api/v1/me/upstream-key                  → masked / revealed key
 *   POST   /api/v1/me/upstream-key/refresh          → mint or rotate
 *   DELETE /api/v1/me/upstream-key                  → soft-revoke
 *   POST   /api/v1/me/upstream-key/payment-qr       → bridge /generate_qr
 *   GET    /api/v1/me/upstream-key/check-payment    → bridge /check_payment
 *   POST   /api/v1/me/upstream-key/test             → 0.01 KHR sanity charge
 */
@RestController
@RequestMapping("/api/v1/me/upstream-key")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Upstream Key", description = "sk_ keys minted via the Python bridge")
public class UpstreamKeyController {

    private final UserApiKeyService userApiKeyService;
    private final ApiCheckingClient apiChecking;
    private final BridgeTransactionRepository txRepo;
    private final AuditService audit;
    private final com.khmerbank.service.notify.NotifyService notifyService;

    @GetMapping
    @Operation(summary = "Get the user's saved sk_ key (masked unless ?reveal=true)")
    public ApiResponse<Map<String, Object>> get(
            @AuthenticationPrincipal User user,
            @RequestParam(value = "reveal", defaultValue = "false") boolean reveal) {
        return ApiResponse.ok(buildView(user, reveal));
    }

        @PostMapping("/refresh")
        @Operation(summary = "Issue or rotate the sk_ key from the user's linked merchants")
        public ApiResponse<Map<String, Object>> refresh(
                @AuthenticationPrincipal User user,
                @RequestBody(required = false) Map<String, Object> body) {

            // Hard rule: at most ONE non-revoked, non-expired key per user.
            // Even though refresh would soft-revoke the previous key, we still
            // refuse if the user already has an active one — they can come back
            // here AFTER they've explicitly removed it (or it expired).
            userApiKeyService.activeFor(user).ifPresent(existing -> {
                String when = existing.getExpiresAt() == null
                        ? "later"
                        : existing.getExpiresAt().toString().substring(0, 10);
                throw ApiException.badRequest("KEY_LIMIT",
                        "You already have an active sk_ key (" +
                        (existing.getLabel() == null ? "Untitled" : existing.getLabel()) +
                        ", expires " + when + "). Remove it from API Keys to mint a new one.");
            });

            String planId = "1month";
            String name = null;
            java.util.Set<BankType> requested = parseRequestedBanks(body);
            UserApiKeyService.PaymentContext payment = parsePaymentContext(body, planId);
            if (body != null) {
                if (body.get("planId") != null) planId = String.valueOf(body.get("planId"));
                if (body.get("merchantName") != null) name = String.valueOf(body.get("merchantName"));
            }
            // Re-parse with the resolved planId so it lands in the context.
            payment = parsePaymentContext(body, planId);
            com.khmerbank.service.plan.Plan plan = com.khmerbank.service.plan.Plan.require(planId);

            UserApiKey saved;
            try {
                saved = userApiKeyService.issueOrRefresh(user, plan.days, name, plan.id, requested, payment);
            } catch (ApiCheckingException e) {
                log.warn("issueOrRefresh failed: {}", e.getMessage());
                throw ApiException.badRequest(
                        e.getUpstreamCode() == null ? "BRIDGE_FAILED" : e.getUpstreamCode(),
                        e.getMessage());
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("key",          saved.getApiKey());      // real sk_ — wizard shows this
            view.put("expiresAt",    saved.getExpiresAt());
            view.put("issuedAt",     saved.getIssuedAt());
            view.put("merchantName", saved.getMerchantName());
            view.put("planId",       saved.getPlanId());
            view.put("planLabel",    plan.label);
            view.put("hasKey",       true);
            view.put("masked",       false);
            return ApiResponse.ok(view, "Upstream key issued");
        }

        @DeleteMapping
        @Operation(summary = "Forget the saved sk_ key")
    public ApiResponse<Void> clear(@AuthenticationPrincipal User user) {
        userApiKeyService.revoke(user);
        return ApiResponse.ok(null, "Cleared");
    }

    /* ────────── Multi-key endpoints ────────── */

    @GetMapping("/list")
    @Operation(summary = "List ALL of the user's keys (active + revoked + expired)")
    public ApiResponse<java.util.List<KeyView>> list(
            @AuthenticationPrincipal User user,
            @RequestParam(value = "reveal", defaultValue = "false") boolean reveal) {
        return ApiResponse.ok(
                userApiKeyService.allFor(user).stream()
                        .map(k -> KeyView.from(k, reveal))
                        .toList());
    }

    @PostMapping("/buy-additional")
    @Operation(summary = "Mint an ADDITIONAL key — blocked while another active key exists")
    public ApiResponse<Map<String, Object>> buyAdditional(
            @AuthenticationPrincipal User user,
            @RequestBody(required = false) Map<String, Object> body) {
        // Hard rule: at most ONE non-revoked, non-expired key per user.
        // The user must remove the current key (or wait for it to expire)
        // before they can mint another one.
        userApiKeyService.activeFor(user).ifPresent(existing -> {
            String when = existing.getExpiresAt() == null
                    ? "later"
                    : existing.getExpiresAt().toString().substring(0, 10);
            throw ApiException.badRequest("KEY_LIMIT",
                    "You already have an active sk_ key (" +
                    (existing.getLabel() == null ? "Untitled" : existing.getLabel()) +
                    ", expires " + when + "). Remove it from API Keys to mint a new one.");
        });

        String planId = "1month";
        String name   = null;
        String label  = null;
        java.util.Set<BankType> requested = parseRequestedBanks(body);
        if (body != null) {
            if (body.get("planId")       != null) planId = String.valueOf(body.get("planId"));
            if (body.get("merchantName") != null) name   = String.valueOf(body.get("merchantName"));
            if (body.get("label")        != null) label  = String.valueOf(body.get("label"));
        }
        UserApiKeyService.PaymentContext payment = parsePaymentContext(body, planId);
        com.khmerbank.service.plan.Plan plan = com.khmerbank.service.plan.Plan.require(planId);
        UserApiKey saved;
        try {
            saved = userApiKeyService.issueAdditional(user, plan.days, name, plan.id, label, requested, payment);
        } catch (ApiCheckingException e) {
            log.warn("buyAdditional failed: {}", e.getMessage());
            throw ApiException.badRequest(
                    e.getUpstreamCode() == null ? "BRIDGE_FAILED" : e.getUpstreamCode(),
                    e.getMessage());
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("key",          saved.getApiKey());
        view.put("expiresAt",    saved.getExpiresAt());
        view.put("issuedAt",     saved.getIssuedAt());
        view.put("merchantName", saved.getMerchantName());
        view.put("planId",       saved.getPlanId());
        view.put("planLabel",    plan.label);
        view.put("label",        saved.getLabel());
        view.put("primary",      saved.isPrimary());
        return ApiResponse.ok(view, "Additional key issued");
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Permanently revoke a specific key. "
            + "Calls upstream /api/owner/revoke_key first so the key dies "
            + "on the bot too — every /generate_qr and /api/check_payment "
            + "with this key will return 401 afterwards. Cannot be undone.")
    public ApiResponse<Map<String, Object>> revokeOne(
            @org.springframework.web.bind.annotation.PathVariable("id") java.util.UUID id,
            @AuthenticationPrincipal User user,
            @RequestBody(required = false) Map<String, Object> body) {
        // Optional sanity check from the dashboard's confirm modal:
        // body { "confirm": "REVOKE" }. Other clients can omit it.
        if (body != null && body.get("confirm") != null
                && !"REVOKE".equals(String.valueOf(body.get("confirm")))) {
            throw ApiException.badRequest("BAD_CONFIRM",
                    "confirm must equal \"REVOKE\"");
        }
        try {
            com.khmerbank.service.bridge.UserApiKeyService.RevokedKeyResult r =
                    userApiKeyService.revokeOne(user, id, "USER_REVOKED");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("revoked", true);
            out.put("id", r.id());
            out.put("label", r.label());
            return ApiResponse.ok(out, "Key revoked");
        } catch (com.khmerbank.service.bridge.ApiCheckingException e) {
            // Surface upstream's own message — usually:
            //   "X-API-Key header must equal body.key (proof of ownership)"
            //   "Invalid sk_ key format"
            throw ApiException.badRequest(
                    e.getUpstreamCode() == null ? "UPSTREAM_REVOKE_FAILED"
                                                : e.getUpstreamCode(),
                    e.getMessage());
        }
    }

    @GetMapping("/{id}/banks")
    @Operation(summary = "Canonical bank list registered against this key, "
            + "pulled live from upstream /key_info via the bridge.")
    public ApiResponse<Map<String, Object>> banksFor(
            @org.springframework.web.bind.annotation.PathVariable("id") java.util.UUID id,
            @AuthenticationPrincipal User user) {
        UserApiKey k = userApiKeyService.allFor(user).stream()
                .filter(x -> x.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("KEY_NOT_FOUND", "Key not found"));

        UserApiKeyService.BanksLookup lookup = userApiKeyService.banksLookupFor(k);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keyId",            k.getId());
        out.put("valid",            lookup.valid());
        out.put("registeredBanks",  lookup.banks());
        out.put("merchantName",     lookup.merchantName());
        if (lookup.reason() != null) {
            out.put("reason", lookup.reason());
        }
        return ApiResponse.ok(out);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Rename a specific key")
    public ApiResponse<KeyView> rename(
            @org.springframework.web.bind.annotation.PathVariable("id") java.util.UUID id,
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {
        String label = body == null ? null : (String) body.get("label");
        UserApiKey k = userApiKeyService.rename(user, id, label);
        return ApiResponse.ok(KeyView.from(k, false));
    }

    @PostMapping("/{id}/primary")
    @Operation(summary = "Make a specific key the primary one")
    public ApiResponse<KeyView> makePrimary(
            @org.springframework.web.bind.annotation.PathVariable("id") java.util.UUID id,
            @AuthenticationPrincipal User user) {
        UserApiKey k = userApiKeyService.makePrimary(user, id);
        return ApiResponse.ok(KeyView.from(k, false));
    }

    /**
     * Dry-run verification — quick check that a key is the user's, what banks
     * it supports, and that the bridge accepts it. Used by the Test page when
     * the user pastes a key by hand.
     */
    @PostMapping("/verify")
    @Operation(summary = "Verify a sk_ key belongs to the user and is usable")
    public ApiResponse<Map<String, Object>> verify(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {
        String candidate = body == null ? null : (String) body.get("key");
        if (candidate == null || !candidate.matches("^sk_[a-f0-9]{32}$")) {
            throw ApiException.badRequest("BAD_KEY", "Key must look like sk_<32 hex chars>");
        }
        UserApiKey match = userApiKeyService.allFor(user).stream()
                .filter(k -> candidate.equals(k.getApiKey()))
                .findFirst()
                .orElseThrow(() -> ApiException.badRequest("NOT_YOURS",
                        "This key isn't on your account"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok",       !match.isRevoked() && match.getExpiresAt() != null
                                && match.getExpiresAt().isAfter(Instant.now()));
        out.put("revoked",  match.isRevoked());
        out.put("expired",  match.getExpiresAt() != null
                                && match.getExpiresAt().isBefore(Instant.now()));
        out.put("label",    match.getLabel());
        out.put("planId",   match.getPlanId());
        out.put("expiresAt",match.getExpiresAt());
        out.put("banks",    KeyView.from(match, false).banks());
        return ApiResponse.ok(out);
    }

    @PostMapping("/payment-qr")
    @Operation(summary = "Generate a payment QR via the bridge")
    public ApiResponse<GenerateQrResponse> paymentQr(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {
        UserApiKey key = activeOrFail(user);
        String bank   = String.valueOf(body.getOrDefault("bank", "bakong"));
        BigDecimal amt = new BigDecimal(String.valueOf(body.getOrDefault("amount", "0")));
        String ccy    = String.valueOf(body.getOrDefault("currency", "USD"));
        try {
            return ApiResponse.ok(apiChecking.generatePaymentQr(
                    key.getApiKey(), bank, amt, ccy));
        } catch (ApiCheckingException e) {
            throw ApiException.badRequest(
                    e.getUpstreamCode() == null ? "BRIDGE_FAILED" : e.getUpstreamCode(),
                    e.getMessage());
        }
    }

    /* ────────── Test API Key page wrappers (clean DTOs, sk_-only) ────────── */

    /**
     * Build a KHQR for the dashboard's "Test API Key" page.
     * Always goes through the bridge using the user's saved sk_ key.
     */
    @PostMapping(path = "/payment-qr/test", produces = "application/json")
    @Operation(summary = "Test API Key page — generate a QR for the saved sk_ key")
    public ApiResponse<TestQrView> testQr(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {
        // Allow the caller to pick WHICH of their keys to test with.
        // Body { "keyId": "<uuid>" } uses that specific key; otherwise the
        // primary / latest active one is used.
        UserApiKey key;
        Object keyIdRaw = body == null ? null : body.get("keyId");
        if (keyIdRaw != null && !String.valueOf(keyIdRaw).isBlank()) {
            java.util.UUID id;
            try { id = java.util.UUID.fromString(String.valueOf(keyIdRaw)); }
            catch (Exception e) { throw ApiException.badRequest("BAD_KEY_ID", "Invalid keyId"); }
            key = userApiKeyService.allFor(user).stream()
                    .filter(k -> k.getId().equals(id) && !k.isRevoked()
                              && k.getExpiresAt() != null
                              && k.getExpiresAt().isAfter(Instant.now()))
                    .findFirst()
                    .orElseThrow(() -> ApiException.badRequest("KEY_NOT_USABLE",
                            "That key is missing, revoked, or expired"));
        } else {
            key = activeOrFail(user);
        }
        String bank   = String.valueOf(body.getOrDefault("bank", "bakong")).toLowerCase();
        BigDecimal amt = new BigDecimal(String.valueOf(body.getOrDefault("amount", "1.00")));
        String ccy    = String.valueOf(body.getOrDefault("currency", "USD")).toUpperCase();
        if (amt.signum() <= 0)
            throw ApiException.badRequest("BAD_AMOUNT", "Amount must be greater than zero.");
        try {
            GenerateQrResponse r = apiChecking.generatePaymentQr(
                    key.getApiKey(), bank, amt, ccy);
            if (!r.isSuccess() || r.getMd5() == null)
                throw ApiException.badRequest("UPSTREAM_FAIL",
                        r.getError() == null ? "Bot rejected the request" : r.getError());
            // Record transaction (idempotent on md5 unique constraint)
            txRepo.findByMd5(r.getMd5()).orElseGet(() -> txRepo.save(
                    BridgeTransaction.builder()
                            .user(user)
                            .apiKey(key)
                            .md5(r.getMd5())
                            .bank(bank)
                            .amount(amt)
                            .currency(ccy)
                            .status(BridgeTransaction.Status.PENDING)
                            .qrString(r.getQr_string())
                            .build()));
            audit.log(user, AuditService.GENERATE_QR, "tx", r.getMd5(),
                    Map.of("bank", bank, "amount", amt.toPlainString(), "currency", ccy,
                           "keyId", key.getId().toString()));
            return ApiResponse.ok(TestQrView.from(r, bank, amt, ccy));
        } catch (ApiCheckingException e) {
            throw ApiException.badRequest(
                    e.getUpstreamCode() == null ? "BRIDGE_FAILED" : e.getUpstreamCode(),
                    e.getMessage());
        }
    }

    @GetMapping("/check-payment")
    @Operation(summary = "Check payment status by MD5")
    public ApiResponse<CheckPaymentResponse> check(
            @RequestParam("md5") String md5,
            @AuthenticationPrincipal User user) {
        UserApiKey key = activeOrFail(user);
        try {
            return ApiResponse.ok(apiChecking.checkPayment(md5, key.getApiKey()));
        } catch (ApiCheckingException e) {
            throw ApiException.badRequest(
                    e.getUpstreamCode() == null ? "BRIDGE_FAILED" : e.getUpstreamCode(),
                    e.getMessage());
        }
    }

    @GetMapping(path = "/payment-status/{md5}", produces = "application/json")
    @Operation(summary = "Test API Key page — clean status DTO (used by 3-second polling)")
    public ApiResponse<PaymentStatusView> paymentStatus(
            @org.springframework.web.bind.annotation.PathVariable("md5") String md5,
            @AuthenticationPrincipal User user) {
        UserApiKey key = activeOrFail(user);
        try {
            CheckPaymentResponse r = apiChecking.checkPayment(md5, key.getApiKey());
            // Persist the latest snapshot
            txRepo.findByMd5(md5).ifPresent(tx -> {
                if (r != null && r.isPaid() && tx.getStatus() != BridgeTransaction.Status.PAID) {
                    tx.setStatus(BridgeTransaction.Status.PAID);
                    tx.setPaidAt(Instant.now());
                    tx.setPaidFrom(r.getFrom());
                    txRepo.save(tx);
                    audit.log(user, AuditService.PAYMENT_PAID, "tx", md5,
                            Map.of("amount", r.getAmount(), "from", String.valueOf(r.getFrom())));

                    // Fire-and-forget Telegram DM. Idempotent upstream
                    // (dedupes by key+md5) so even if the FE also fires
                    // its sidecar call, only one DM lands. Guard on the
                    // status-flip above so we never trigger on every
                    // 3-second poll once PAID is observed.
                    notifyService.firePaidAsync(new com.khmerbank.service.notify
                            .NotifyService.PaidNotification(
                                    key.getApiKey(),
                                    md5,
                                    r.getAmount(),
                                    r.getCurrency(),
                                    r.getFrom(),
                                    tx.getBank() == null ? null : tx.getBank().toLowerCase(),
                                    r.getTimestamp(),
                                    // Real Bakong tx context — fixes the
                                    // "Short Hash" line so the user can
                                    // verify on api-bakong.nbc.gov.kh.
                                    r.getHash(),
                                    r.getExternalRef(),
                                    r.getDescription(),
                                    r.getTo(),
                                    r.getCreatedAtMs()));
                } else if (r != null && "UNPAID".equalsIgnoreCase(r.getStatus())
                        && tx.getStatus() == BridgeTransaction.Status.PENDING) {
                    // leave PENDING; the bot says UNPAID just means "not yet"
                }
            });
            return ApiResponse.ok(PaymentStatusView.from(md5, r));
        } catch (ApiCheckingException e) {
            throw ApiException.badRequest(
                    e.getUpstreamCode() == null ? "BRIDGE_FAILED" : e.getUpstreamCode(),
                    e.getMessage());
        }
    }

    @PostMapping("/test")
    @Operation(summary = "Mint a 0.01 KHR Bakong test QR (sanity check)")
    public ApiResponse<GenerateQrResponse> test(@AuthenticationPrincipal User user) {
        UserApiKey key = activeOrFail(user);
        try {
            return ApiResponse.ok(apiChecking.generatePaymentQr(
                    key.getApiKey(), "bakong", new BigDecimal("0.01"), "KHR"),
                    "Test QR generated");
        } catch (ApiCheckingException e) {
            throw ApiException.badRequest(
                    e.getUpstreamCode() == null ? "BRIDGE_FAILED" : e.getUpstreamCode(),
                    e.getMessage());
        }
    }

    @PostMapping("/{id}/rebuild-banks")
    @Operation(summary = "Repair an existing key's bank registration. "
            + "Hard-revokes the old key upstream, then mints a fresh one with "
            + "ONLY the banks listed in the request body. The user gets a new "
            + "sk_ — the old one stops working immediately.")
    public ApiResponse<Map<String, Object>> rebuildBanks(
            @org.springframework.web.bind.annotation.PathVariable("id") java.util.UUID id,
            @AuthenticationPrincipal User user,
            @RequestBody(required = false) Map<String, Object> body) {
        // Ownership check + grab the existing key for plan/label preservation.
        UserApiKey old = userApiKeyService.allFor(user).stream()
                .filter(k -> k.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("KEY_NOT_FOUND",
                        "Key not found"));

        java.util.Set<BankType> requested = parseRequestedBanks(body);
        if (requested == null || requested.isEmpty()) {
            throw ApiException.badRequest("NO_BANKS",
                    "Pick at least one bank to register against the new key.");
        }

        // 1. Hard-revoke the old key (kills it on the bot too).
        try {
            userApiKeyService.revokeOne(user, id, "REBUILD_BANKS");
        } catch (ApiCheckingException e) {
            throw ApiException.badRequest(
                    e.getUpstreamCode() == null ? "UPSTREAM_REVOKE_FAILED"
                                                : e.getUpstreamCode(),
                    e.getMessage());
        }

        // 2. Mint a fresh key scoped to ONLY the requested banks.
        String planId = old.getPlanId() == null ? "1month" : old.getPlanId();
        com.khmerbank.service.plan.Plan plan = com.khmerbank.service.plan.Plan.require(planId);
        String name  = old.getMerchantName();
        String label = old.getLabel();
        UserApiKey saved;
        try {
            // We just hard-revoked the old key, so issueOrRefresh's
            // soft-revoke fast-path is fine here.
            saved = userApiKeyService.issueOrRefresh(user, plan.days, name, plan.id, requested);
            if (label != null && !label.isBlank()) {
                userApiKeyService.rename(user, saved.getId(), label);
            }
        } catch (ApiCheckingException e) {
            throw ApiException.badRequest(
                    e.getUpstreamCode() == null ? "BRIDGE_FAILED" : e.getUpstreamCode(),
                    e.getMessage());
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("oldKeyId",     id);
        view.put("newKeyId",     saved.getId());
        view.put("key",          saved.getApiKey());
        view.put("label",        saved.getLabel());
        view.put("expiresAt",    saved.getExpiresAt());
        view.put("merchantName", saved.getMerchantName());
        return ApiResponse.ok(view,
                "Banks rebuilt — old key revoked, new key issued.");
    }

    /* ────────── helpers ────────── */

    /**
     * Parse the {@code banks} array from a wizard request body and turn it
     * into a typed {@link BankType} set. Accepts case-insensitive slugs
     * ("aba", "BAKONG", "Acleda" etc.). Returns {@code null} when the body
     * doesn't include the field at all (legacy callers); returns an empty
     * set for an explicit empty array (caller error — handled downstream).
     */
    private static java.util.Set<BankType> parseRequestedBanks(Map<String, Object> body) {
        if (body == null) return null;
        Object raw = body.get("banks");
        if (raw == null) return null;
        if (!(raw instanceof java.util.List<?> list)) {
            throw ApiException.badRequest("BAD_BANKS",
                    "`banks` must be an array of slugs like [\"aba\",\"wing\"]");
        }
        java.util.LinkedHashSet<BankType> out = new java.util.LinkedHashSet<>();
        for (Object o : list) {
            if (o == null) continue;
            String s = String.valueOf(o).trim();
            if (s.isEmpty()) continue;
            try {
                out.add(BankType.valueOf(s.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("BAD_BANK_SLUG",
                        "Unknown bank slug: " + s);
            }
        }
        return out;
    }

    /**
     * Pull the buyer-paid context out of the wizard body so the upstream
     * admin Telegram DM shows the real numbers ({@code Amount: $1.50 USD}
     * etc.) instead of the {@code Amount: $0.00 USD / Method: EXTERNAL}
     * default that lands when {@code amount_paid}/{@code payment_md5}/
     * {@code payment_method} are missing.
     *
     * <p>Accepts these wizard fields:
     * <ul>
     *   <li>{@code paymentMd5}     — md5 of the QR the buyer paid against</li>
     *   <li>{@code paymentMethod}  — e.g. "khqr_aba" or just "aba"</li>
     *   <li>{@code amountPaid}     — actual paid amount (may differ from list price)</li>
     * </ul>
     *
     * <p>Returns {@code null} when the wizard didn't supply any of them
     * (legacy /refresh callers that bypass the wizard).
     */
    private static UserApiKeyService.PaymentContext parsePaymentContext(
            Map<String, Object> body, String planId) {
        if (body == null) return null;
        String md5    = strOrNull(body.get("paymentMd5"));
        String method = strOrNull(body.get("paymentMethod"));
        java.math.BigDecimal amount = null;
        Object rawAmount = body.get("amountPaid");
        if (rawAmount != null) {
            try { amount = new java.math.BigDecimal(String.valueOf(rawAmount)); }
            catch (NumberFormatException ignore) { /* leave null */ }
        }
        // Normalise the method to "khqr_<bank>" when the wizard sent just
        // the bank slug — keeps the upstream admin DM's "Method:" line
        // clean.
        if (method != null && !method.toLowerCase().startsWith("khqr_")) {
            method = "khqr_" + method.toLowerCase();
        }
        UserApiKeyService.PaymentContext ctx = new UserApiKeyService.PaymentContext(
                planId, amount, md5, method);
        return ctx.hasAnything() ? ctx : null;
    }

    private static String strOrNull(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private UserApiKey activeOrFail(User user) {
        return userApiKeyService.activeFor(user)
                .orElseThrow(() -> ApiException.badRequest("NO_ACTIVE_KEY",
                        "No active sk_ key. Issue one with /refresh first."));
    }

    private Map<String, Object> buildView(User user, boolean reveal) {
        Map<String, Object> out = new LinkedHashMap<>();
        UserApiKey active = userApiKeyService.activeFor(user).orElse(null);
        boolean has = active != null;
        out.put("hasKey", has);
        if (has) {
            String key = active.getApiKey();
            out.put("key", reveal ? key : mask(key));
            out.put("masked", !reveal);
            out.put("issuedAt", active.getIssuedAt());
            out.put("expiresAt", active.getExpiresAt());
            out.put("merchantName", active.getMerchantName());
            // Expose the banks this key was linked with — used by Test page
            // to only show banks the key actually supports.
            out.put("banks", parseBanksJson(active.getBanksJson()));
        }
        out.put("expired", active != null
                && active.getExpiresAt().isBefore(Instant.now()));
        return out;
    }

    /** Pull the list of bank slugs ("aba","wing",…) out of the stored
     *  banksJson snapshot. Returns an empty list on any parse failure. */
    private static java.util.List<String> parseBanksJson(String json) {
        if (json == null || json.isBlank()) return java.util.List.of();
        try {
            java.util.List<?> raw = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, java.util.List.class);
            java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
            for (Object o : raw) {
                if (o instanceof Map<?,?> m) {
                    Object v = m.get("bank");
                    if (v != null) out.add(String.valueOf(v).toUpperCase());
                }
            }
            return java.util.List.copyOf(out);
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    private String mask(String key) {
        if (key == null) return null;
        if (key.length() <= 12) return key.charAt(0) + "•".repeat(8);
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }

    /* ────────── view models ────────── */

    /** Minimal payload returned to the Test API Key page. */
    public record TestQrView(
            String md5,
            String qrImage,
            String qrString,
            String merchantName,
            String bank,
            BigDecimal amount,
            String currency
    ) {
        static TestQrView from(GenerateQrResponse r, String bank, BigDecimal amt, String ccy) {
            return new TestQrView(
                    r.getMd5(),
                    r.getQr_image(),
                    r.getQr_string(),
                    r.getMerchant_name(),
                    bank,
                    amt,
                    ccy);
        }
    }

    /** Minimal poll-result for the Test API Key page. */
    public record PaymentStatusView(
            String md5,
            String status,
            boolean paid,
            BigDecimal amount,
            String currency,
            String from,
            String timestamp,
            /** Real Bakong tx hash — full 64-char hex when PAID, null otherwise. */
            String hash,
            /** Bank tran_id (e.g. "100FT37845835941") when PAID. */
            String externalRef,
            /** Remark / note attached to the payment. */
            String description,
            /** Receiver account id ("To (You)" line). */
            String to,
            /** Exact tx time in epoch millis. */
            Long createdAtMs
    ) {
        static PaymentStatusView from(String md5, CheckPaymentResponse r) {
            return new PaymentStatusView(
                    md5,
                    r == null ? "ERROR" : r.getStatus(),
                    r != null && r.isPaid(),
                    r == null ? null : r.getAmount(),
                    r == null ? null : r.getCurrency(),
                    r == null ? null : r.getFrom(),
                    r == null ? null : r.getTimestamp(),
                    r == null ? null : r.getHash(),
                    r == null ? null : r.getExternalRef(),
                    r == null ? null : r.getDescription(),
                    r == null ? null : r.getTo(),
                    r == null ? null : r.getCreatedAtMs());
        }
    }

    /** Row in the multi-key list. */
    public record KeyView(
            java.util.UUID id,
            String key,
            String label,
            String merchantName,
            String planId,
            Instant issuedAt,
            Instant expiresAt,
            boolean revoked,
            boolean primary,
            boolean expired,
            int daysRemaining,
            java.util.List<String> banks
    ) {
        static KeyView from(UserApiKey k, boolean reveal) {
            Instant now = Instant.now();
            boolean exp = k.getExpiresAt() != null && k.getExpiresAt().isBefore(now);
            int days = exp ? -1 :
                    (int) Math.max(0, java.time.Duration.between(now, k.getExpiresAt()).toDays());
            return new KeyView(
                    k.getId(),
                    reveal ? k.getApiKey() : maskOne(k.getApiKey()),
                    k.getLabel(),
                    k.getMerchantName(),
                    k.getPlanId(),
                    k.getIssuedAt(),
                    k.getExpiresAt(),
                    k.isRevoked(),
                    k.isPrimary(),
                    exp,
                    days,
                    banksFromJson(k.getBanksJson()));
        }
        private static String maskOne(String key) {
            if (key == null || key.length() <= 12) return "—";
            return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
        }
        private static java.util.List<String> banksFromJson(String json) {
            if (json == null || json.isBlank()) return java.util.List.of();
            try {
                java.util.List<?> raw = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readValue(json, java.util.List.class);
                java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
                for (Object o : raw) {
                    if (o instanceof Map<?,?> m) {
                        Object v = m.get("bank");
                        if (v != null) out.add(String.valueOf(v).toUpperCase());
                    }
                }
                return java.util.List.copyOf(out);
            } catch (Exception e) {
                return java.util.List.of();
            }
        }
    }
}
