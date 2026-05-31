package com.khmerbank.service.bridge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.khmerbank.exception.ApiException;
import com.khmerbank.model.Merchant;
import com.khmerbank.model.RevocationLog;
import com.khmerbank.model.User;
import com.khmerbank.model.UserApiKey;
import com.khmerbank.model.enums.BankType;
import com.khmerbank.repository.MerchantRepository;
import com.khmerbank.repository.RevocationLogRepository;
import com.khmerbank.repository.UserApiKeyRepository;
import com.khmerbank.security.AesEncryptor;
import com.khmerbank.service.bridge.BridgeDtos.BankInput;
import com.khmerbank.service.bridge.BridgeDtos.IssueKeyResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Glues the user's linked merchants in our DB to a fresh sk_ key minted via
 * the Python bridge.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Read {@link Merchant} rows for the user.</li>
 *   <li>Build a list of {@link BankInput} for the bridge.</li>
 *   <li>Call {@link ApiCheckingClient#issueKey}.</li>
 *   <li>Soft-revoke any previously active row, insert the new one.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserApiKeyService {

    /** Strict shape of the bot's keys: lowercase 32-hex after "sk_". */
    private static final java.util.regex.Pattern SK_FORMAT =
            java.util.regex.Pattern.compile("^sk_[a-f0-9]{32}$");

    private final ApiCheckingClient apiChecking;
    private final UserApiKeyRepository repo;
    private final MerchantRepository merchantRepository;
    private final RevocationLogRepository revocationLogRepository;
    private final AesEncryptor encryptor;
    private final com.khmerbank.service.audit.AuditService audit;
    private final com.khmerbank.service.email.EmailService emailService;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Optional payment context for {@link #issueInternal} — populated from
     * the wizard's pay step so the upstream bot can record what the buyer
     * actually paid (vs. the catalog list price). Empty values are dropped
     * before going on the wire.
     */
    public record PaymentContext(
            String planId,
            java.math.BigDecimal amountPaid,
            String paymentMd5,
            String paymentMethod
    ) {
        public static PaymentContext empty() { return new PaymentContext(null, null, null, null); }
        public boolean hasAnything() {
            return (planId != null && !planId.isBlank())
                || amountPaid != null
                || (paymentMd5 != null && !paymentMd5.isBlank())
                || (paymentMethod != null && !paymentMethod.isBlank());
        }
    }

    /** Mint or rotate the user's sk_ key. */
    @Transactional
    public UserApiKey issueOrRefresh(User user, int days, String merchantName) {
        return issueOrRefresh(user, days, merchantName, null);
    }

    /** Mint or rotate the user's sk_ key, recording the planId. */
    @Transactional
    public UserApiKey issueOrRefresh(User user, int days, String merchantName, String planId) {
        return issueInternal(user, days, merchantName, planId,
                /* revokePrevious */ true, /* label */ null,
                /* allowedBanks */ null, /* payment */ null);
    }

    /**
     * Mint or rotate the user's sk_ key, scoped to a specific set of bank
     * types. Anything the user has linked outside this set is ignored.
     */
    @Transactional
    public UserApiKey issueOrRefresh(User user, int days, String merchantName,
                                     String planId, java.util.Set<BankType> allowedBanks) {
        return issueInternal(user, days, merchantName, planId,
                /* revokePrevious */ true, /* label */ null, allowedBanks, /* payment */ null);
    }

    /**
     * Mint or rotate scoped to a specific set of banks AND carrying the
     * payment context (amount paid, md5, method) so the upstream admin
     * Telegram notification shows the real numbers instead of $0.00.
     */
    @Transactional
    public UserApiKey issueOrRefresh(User user, int days, String merchantName,
                                     String planId, java.util.Set<BankType> allowedBanks,
                                     PaymentContext payment) {
        return issueInternal(user, days, merchantName, planId,
                /* revokePrevious */ true, /* label */ null, allowedBanks, payment);
    }

    /**
     * Issue an additional sk_ key WITHOUT revoking existing ones.
     * Used when a user buys a second / third key from the wizard.
     */
    @Transactional
    public UserApiKey issueAdditional(User user, int days, String merchantName,
                                      String planId, String label) {
        return issueInternal(user, days, merchantName, planId,
                /* revokePrevious */ false, label, /* allowedBanks */ null, /* payment */ null);
    }

    /**
     * Issue an additional key scoped to a specific set of bank types.
     */
    @Transactional
    public UserApiKey issueAdditional(User user, int days, String merchantName,
                                      String planId, String label,
                                      java.util.Set<BankType> allowedBanks) {
        return issueInternal(user, days, merchantName, planId,
                /* revokePrevious */ false, label, allowedBanks, /* payment */ null);
    }

    /**
     * Issue an additional key scoped to a specific set of banks AND with
     * full payment context (amount paid, md5, method).
     */
    @Transactional
    public UserApiKey issueAdditional(User user, int days, String merchantName,
                                      String planId, String label,
                                      java.util.Set<BankType> allowedBanks,
                                      PaymentContext payment) {
        return issueInternal(user, days, merchantName, planId,
                /* revokePrevious */ false, label, allowedBanks, payment);
    }

    private UserApiKey issueInternal(User user, int days, String merchantName,
                                     String planId, boolean revokePrevious,
                                     String label, java.util.Set<BankType> allowedBanks,
                                     PaymentContext payment) {
        // The bug we're fixing: when allowedBanks was always implicitly "all
        // active merchants", a key minted with "I only uploaded ABA" silently
        // picked up Bakong/ACLEDA from prior wizard runs. The wizard now
        // passes an explicit set, and we hard-fail if it's empty.
        if (allowedBanks != null && allowedBanks.isEmpty()) {
            throw ApiException.badRequest("NO_BANK_DATA",
                    "Pick at least one bank to register against this key.");
        }
        List<BankInput> banks = collectBanks(user, allowedBanks);
        if (banks.isEmpty()) {
            throw ApiException.badRequest("NO_BANK_DATA",
                    "Link at least one merchant before issuing a key.");
        }
        // If the wizard asked for specific banks but some weren't actually
        // linked (e.g. user picked Wing but never uploaded a Wing QR),
        // surface that clearly instead of silently shipping fewer banks.
        if (allowedBanks != null) {
            java.util.Set<BankType> shipped = new java.util.LinkedHashSet<>();
            for (BankInput b : banks) {
                shipped.add(BankType.valueOf(b.getBank().toUpperCase()));
            }
            java.util.Set<BankType> missing = new java.util.LinkedHashSet<>(allowedBanks);
            missing.removeAll(shipped);
            if (!missing.isEmpty()) {
                throw ApiException.badRequest("MISSING_BANK_LINKS",
                        "You picked these banks but never uploaded a QR for them: "
                                + missing);
            }
        }

        String name = merchantName != null && !merchantName.isBlank()
                ? merchantName.trim()
                : firstMerchantName(user);

        IssueKeyResponse response = apiChecking.issueKey(
                "khmerbank_" + user.getId(),
                null,
                Math.max(1, days),
                name,
                banks,
                payment);

        if (!response.isOk() || response.getKey() == null) {
            throw new ApiCheckingException(502, "UPSTREAM_REJECTED",
                    response.getError() == null ? "issue_key failed"
                                                : response.getError());
        }
        // Reject anything that isn't the bot's expected sk_ shape — never
        // accept a locally-generated kb_ key from any other code path.
        if (!SK_FORMAT.matcher(response.getKey()).matches()) {
            throw new ApiCheckingException(502, "BAD_KEY_FORMAT",
                    "Bot returned key with unexpected format: "
                            + ApiCheckingClient.mask(response.getKey()));
        }

        // Soft-revoke previous active key only when caller asks for it
        // (the legacy "renew" / "rotate" flow).
        if (revokePrevious) {
            repo.findFirstByUserAndRevokedFalseAndExpiresAtAfterOrderByIssuedAtDesc(
                    user, Instant.now())
                    .ifPresent(prev -> {
                        prev.setRevoked(true);
                        prev.setRevokedAt(Instant.now());
                        prev.setRevokeReason("REPLACED_BY_NEW");
                        repo.save(prev);
                    });
        }

        UserApiKey row = UserApiKey.builder()
                .user(user)
                .apiKey(response.getKey())
                .apiKeyHash(sha256(response.getKey()))
                .planId(planId == null || planId.isBlank() ? "1month" : planId)
                .merchantName(name)
                .label(label == null || label.isBlank()
                        ? defaultLabel(user)
                        : label.trim())
                .banksJson(serializeBanks(banks))
                .expiresAt(Instant.now().plus(response.getExpires_in_days() == null
                        ? days : response.getExpires_in_days(), ChronoUnit.DAYS))
                .revoked(false)
                .primary(true)   // newly-issued key becomes the primary by default
                .build();

        // When NOT revoking the previous key, demote it from primary so we
        // still have a single "primary" key for endpoints that pick one.
        if (!revokePrevious) {
            for (UserApiKey existing : repo.findByUserOrderByIssuedAtDesc(user)) {
                if (existing.isPrimary()) {
                    existing.setPrimary(false);
                    repo.save(existing);
                }
            }
        }

        // Mirror onto User for quick "active" lookup (used by overview).
        // When issuing additional keys we still want the latest one to be
        // the "active" one returned by single-key endpoints.
        user.setUpstreamApiKey(response.getKey());
        user.setUpstreamApiKeyIssuedAt(Instant.now());
        user.setUpstreamApiKeyExpiresAt(row.getExpiresAt());

        UserApiKey saved = repo.save(row);
        log.info("Issued sk_ key for {} → {}…  banks={} plan={} expires_in_days={} revokePrevious={}",
                user.getEmail(),
                response.getKey().substring(0, Math.min(8, response.getKey().length())),
                banks.size(),
                row.getPlanId(),
                response.getExpires_in_days(),
                revokePrevious);

        audit.log(user, com.khmerbank.service.audit.AuditService.MINT_KEY,
                "user_api_key", saved.getId().toString(),
                java.util.Map.of(
                        "plan", row.getPlanId(),
                        "expiresAt", row.getExpiresAt().toString(),
                        "banks", banks.size(),
                        "additional", !revokePrevious));

        try {
            emailService.sendKeyIssued(user.getEmail(), java.util.Map.of(
                    "fullName",     user.getFullName() == null ? "there" : user.getFullName(),
                    "maskedKey",    mask(saved.getApiKey()),
                    "plan",         row.getPlanId(),
                    "merchantName", saved.getMerchantName(),
                    "expiresAt",    saved.getExpiresAt().toString().substring(0, 10)));
        } catch (Exception ignore) { /* mail may be unconfigured locally */ }

        return saved;
    }

    private static String mask(String key) {
        if (key == null || key.length() <= 12) return "—";
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }

    private static String sha256(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public Optional<UserApiKey> activeFor(User user) {
        return repo.findFirstByUserAndRevokedFalseAndExpiresAtAfterOrderByIssuedAtDesc(
                user, Instant.now());
    }

    /** Every key the user owns, newest first. */
    public List<UserApiKey> allFor(User user) {
        return repo.findByUserOrderByIssuedAtDesc(user);
    }

    @Transactional
    public void revoke(User user) {
        revoke(user, "USER_REVOKED");
    }

    @Transactional
    public void revoke(User user, String reason) {
        repo.findFirstByUserAndRevokedFalseAndExpiresAtAfterOrderByIssuedAtDesc(
                user, Instant.now())
                .ifPresent(prev -> {
                    prev.setRevoked(true);
                    prev.setRevokedAt(Instant.now());
                    prev.setRevokeReason(reason);
                    repo.save(prev);
                    audit.log(user, com.khmerbank.service.audit.AuditService.REVOKE_KEY,
                            "user_api_key", prev.getId().toString(),
                            java.util.Map.of("reason", reason));
                });
        user.setUpstreamApiKey(null);
        user.setUpstreamApiKeyIssuedAt(null);
        user.setUpstreamApiKeyExpiresAt(null);
    }

    /** Hard-revoke a SPECIFIC key. Calls upstream /api/owner/revoke_key first
     *  (404 = already gone, treated as success). On any other non-2xx the
     *  Oracle row is preserved so the user can retry. On 200/404, the row
     *  is deleted from Oracle and an entry is logged in revocation_log
     *  (sha256 of the sk_, never cleartext). */
    @Transactional
    public RevokedKeyResult revokeOne(User user, java.util.UUID keyId, String reason) {
        UserApiKey k = repo.findById(keyId)
                .filter(x -> x.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> ApiException.notFound("KEY_NOT_FOUND", "Key not found"));

        boolean wasPrimary = k.isPrimary();
        String  apiKey     = k.getApiKey();
        UUID    keyUuid    = k.getId();
        String  label      = k.getLabel();
        String  merchantNm = k.getMerchantName();
        String  planId     = k.getPlanId();
        Instant issuedAt   = k.getIssuedAt();
        Instant expiresAt  = k.getExpiresAt();

        // 1. Upstream first. Anything except 2xx/404 aborts the whole txn.
        try {
            apiChecking.ownerRevokeKey(apiKey);
        } catch (ApiCheckingException e) {
            log.warn("revokeOne upstream failed key={} → {}",
                    apiKey == null ? "—" : "sk_…" + apiKey.substring(Math.max(0, apiKey.length() - 4)),
                    e.getMessage());
            throw e;   // surfaces to controller → user sees the error
        }

        // 2. Audit the kill BEFORE deleting the row so user FK still resolves.
        audit.log(user, com.khmerbank.service.audit.AuditService.REVOKE_KEY,
                "user_api_key", keyUuid.toString(),
                java.util.Map.of(
                    "reason", reason == null || reason.isBlank() ? "USER_REVOKED" : reason,
                    "action", "hard_revoke"));

        revocationLogRepository.save(RevocationLog.builder()
                .user(user)
                .skKeyHash(sha256Hex(apiKey))
                .revokedAt(Instant.now())
                .source("dashboard")
                .build());

        // 3. Hard-delete. bridge_transactions.api_key_id FK is ON DELETE SET
        // NULL (V3 migration), so historical rows survive but lose the link.
        repo.delete(k);

        // 4. Promote next active key if we just killed the primary.
        if (wasPrimary) {
            Optional<UserApiKey> next = activeFor(user);
            if (next.isPresent()) {
                UserApiKey n = next.get();
                n.setPrimary(true);
                repo.save(n);
                user.setUpstreamApiKey(n.getApiKey());
                user.setUpstreamApiKeyIssuedAt(n.getIssuedAt());
                user.setUpstreamApiKeyExpiresAt(n.getExpiresAt());
            } else {
                user.setUpstreamApiKey(null);
                user.setUpstreamApiKeyIssuedAt(null);
                user.setUpstreamApiKeyExpiresAt(null);
            }
        }

        return new RevokedKeyResult(keyUuid, label, merchantNm, planId, issuedAt, expiresAt);
    }

    /**
     * Returns the canonical list of bank slugs (lowercase: aba/wing/acleda/
     * bakong) registered against this key. Hits upstream /key_info via the
     * bridge — no caching layer (the bridge already has retry + cold-start
     * handling so an extra DB cache wasn't earning its keep). Returns an
     * empty list when upstream is unreachable or the key has no banks.
     */
    public java.util.List<String> bankSlugsFor(UserApiKey k) {
        if (k == null || k.getApiKey() == null || k.getApiKey().isBlank()) {
            return java.util.List.of();
        }
        try {
            BridgeDtos.KeyInfoResponse r = apiChecking.keyInfo(k.getApiKey());
            if (r != null && r.isSuccess() && r.isRegistered()) {
                return r.bankSlugs();
            }
        } catch (Exception e) {
            log.warn("bankSlugsFor upstream failed for key id={}: {}",
                    k.getId(), e.getMessage());
        }
        return java.util.List.of();
    }

    /**
     * Richer variant of {@link #bankSlugsFor(UserApiKey)} that exposes WHY
     * the bank list might be empty so the dashboard can render the right
     * banner ("invalid key" vs "no banks linked yet" vs the picker).
     *
     * <p>Cached in-process for 30 s per key — keeps cold-start latency off
     * the critical path and stops every page render from punching upstream.
     *
     * <p>Negative results ({@code valid=false}) are NEVER cached — a key
     * that just got registered should flip to {@code valid=true} the moment
     * upstream confirms it.
     */
    public BanksLookup banksLookupFor(UserApiKey k) {
        if (k == null || k.getApiKey() == null || k.getApiKey().isBlank()) {
            return new BanksLookup(false, java.util.List.of(),
                    k == null ? null : k.getMerchantName(), "format");
        }
        BanksLookup cached = BANKS_CACHE.get(k.getApiKey());
        if (cached != null && cached.valid()
                && (System.currentTimeMillis() - cached.cachedAt()) < BANKS_CACHE_TTL_MS) {
            return cached;
        }

        BridgeDtos.KeyInfoResponse r;
        try {
            r = apiChecking.keyInfo(k.getApiKey());
        } catch (Exception e) {
            log.warn("banksLookupFor upstream failed for key id={}: {}",
                    k.getId(), e.getMessage());
            return new BanksLookup(false, java.util.List.of(),
                    k.getMerchantName(), "upstream_unreachable");
        }

        if (r == null || !r.isSuccess()) {
            String reason = (r == null || r.getReason() == null || r.getReason().isBlank())
                    ? "not_found" : r.getReason();
            return new BanksLookup(false, java.util.List.of(),
                    k.getMerchantName(), reason);
        }

        java.util.List<String> banks = r.bankSlugs();
        if (!r.isRegistered() || banks.isEmpty()) {
            return new BanksLookup(true, java.util.List.of(),
                    k.getMerchantName(), "no_banks_linked");
        }

        BanksLookup fresh = new BanksLookup(true, banks, k.getMerchantName(), null);
        BANKS_CACHE.put(k.getApiKey(), fresh);
        return fresh;
    }

    /** Drop the cached bank list for a key (e.g. after a fresh QR upload
     *  or a soft-revoke). Safe to call with unknown keys. */
    public void invalidateBanksCache(String apiKey) {
        if (apiKey != null) BANKS_CACHE.remove(apiKey);
    }

    /** 30 s aligns with the dashboard's expected staleness window. */
    private static final long BANKS_CACHE_TTL_MS = 30_000L;
    private static final java.util.concurrent.ConcurrentHashMap<String, BanksLookup>
            BANKS_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Result of a banks lookup. Immutable; carries enough info for the
     * dashboard to pick between three render states:
     * <ul>
     *   <li>{@code valid=false}                       → "Invalid key" banner</li>
     *   <li>{@code valid=true && banks.isEmpty()}     → "No banks linked" banner</li>
     *   <li>{@code valid=true && !banks.isEmpty()}    → bank picker</li>
     * </ul>
     */
    public record BanksLookup(
            boolean valid,
            java.util.List<String> banks,
            String merchantName,
            String reason,
            long cachedAt
    ) {
        public BanksLookup(boolean valid, java.util.List<String> banks,
                           String merchantName, String reason) {
            this(valid, banks, merchantName, reason, System.currentTimeMillis());
        }
    }

    /** SHA-256 hex of the input string. Used to fingerprint a revoked sk_
     *  in revocation_log without ever storing the clear value. */
    static String sha256Hex(String s) {
        if (s == null) return "";
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lightweight tombstone returned by {@link #revokeOne}. */
    public record RevokedKeyResult(
            java.util.UUID id,
            String label,
            String merchantName,
            String planId,
            Instant issuedAt,
            Instant expiresAt
    ) {}

    /** Set a custom label on a single key. */
    @Transactional
    public UserApiKey rename(User user, java.util.UUID keyId, String label) {
        UserApiKey k = repo.findById(keyId)
                .filter(x -> x.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> ApiException.notFound("KEY_NOT_FOUND", "Key not found"));
        k.setLabel(label == null || label.isBlank() ? null : label.trim());
        return repo.save(k);
    }

    /** Make a specific key the primary one (the one returned by single-key endpoints). */
    @Transactional
    public UserApiKey makePrimary(User user, java.util.UUID keyId) {
        UserApiKey target = repo.findById(keyId)
                .filter(x -> x.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> ApiException.notFound("KEY_NOT_FOUND", "Key not found"));
        if (target.isRevoked()) {
            throw ApiException.badRequest("KEY_REVOKED", "Cannot make a revoked key primary");
        }
        for (UserApiKey k : repo.findByUserOrderByIssuedAtDesc(user)) {
            if (k.isPrimary() && !k.getId().equals(target.getId())) {
                k.setPrimary(false);
                repo.save(k);
            }
        }
        target.setPrimary(true);
        repo.save(target);
        user.setUpstreamApiKey(target.getApiKey());
        user.setUpstreamApiKeyIssuedAt(target.getIssuedAt());
        user.setUpstreamApiKeyExpiresAt(target.getExpiresAt());
        return target;
    }

    private String defaultLabel(User user) {
        long count = repo.findByUserOrderByIssuedAtDesc(user).size();
        return "Key " + (count + 1);
    }

    /* ────────── helpers ────────── */

    /**
     * Collect the bank inputs to send upstream when minting a key.
     *
     * <p>When {@code allowed} is non-null, ONLY merchants whose bank type
     * is in that set are included. Anything else the user happens to have
     * linked from a previous wizard run is ignored — fixing the bug where
     * a key minted with "I only uploaded ABA" picked up Bakong/ACLEDA from
     * older active merchants.
     *
     * <p>Pass {@code null} to keep the legacy behaviour ("send everything
     * the user has linked") for callers that haven't been updated yet.
     */
    private List<BankInput> collectBanks(User user, java.util.Set<BankType> allowed) {
        // Dedupe by BankType so we only send ONE BankInput per bank type to
        // the upstream bot — even if the user accidentally linked the same
        // bank twice in the wizard. This keeps the issued key's banks_json
        // clean and the Test page's bank picker correct.
        java.util.Map<BankType, BankInput> byBank = new java.util.LinkedHashMap<>();
        for (Merchant m : merchantRepository.findByUserOrderByCreatedAtDesc(user)) {
            if (allowed != null && !allowed.contains(m.getBankType())) {
                continue;
            }
            BankInput b = toBankInput(m);
            if (b == null) continue;
            byBank.putIfAbsent(m.getBankType(), b);
        }
        return new java.util.ArrayList<>(byBank.values());
    }

    private BankInput toBankInput(Merchant m) {
        BankType bank = m.getBankType();
        switch (bank) {
            case BAKONG -> {
                if (m.getMerchantId() == null) return null;
                return BankInput.bakong(m.getMerchantId(),
                        m.getAccountNumber() == null ? "" : m.getAccountNumber());
            }
            case ABA -> {
                String qr = pick(decrypt(m.getEncryptedSecret()), m.getAccountNumber());
                return qr == null ? null : BankInput.aba(qr, m.getMerchantLink());
            }
            case WING -> {
                String qr = pick(decrypt(m.getEncryptedSecret()), m.getAccountNumber());
                return qr == null ? null : BankInput.wing(qr);
            }
            case ACLEDA -> {
                String qr = pick(decrypt(m.getEncryptedSecret()), m.getAccountNumber());
                return qr == null ? null : BankInput.acleda(qr);
            }
        }
        return null;
    }

    private String decrypt(String enc) {
        if (enc == null || enc.isBlank()) return null;
        try { return encryptor.decrypt(enc); }
        catch (Exception e) { return null; }
    }
    private String pick(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }
    private String firstMerchantName(User user) {
        return merchantRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .findFirst().map(Merchant::getMerchantName).orElse("Merchant");
    }
    private String serializeBanks(List<BankInput> banks) {
        try {
            return mapper.writeValueAsString(banks.stream()
                    .map(BankInput::toMap).toList());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
