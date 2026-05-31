package com.khmerbank.controller;

import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.exception.ApiException;
import com.khmerbank.model.AuditLog;
import com.khmerbank.model.BridgeTransaction;
import com.khmerbank.model.User;
import com.khmerbank.model.UserApiKey;
import com.khmerbank.model.enums.Role;
import com.khmerbank.repository.AuditLogRepository;
import com.khmerbank.repository.BridgeTransactionRepository;
import com.khmerbank.repository.UserApiKeyRepository;
import com.khmerbank.repository.UserRepository;
import com.khmerbank.service.audit.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "ADMIN-only views and actions")
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class AdminController {

    private final UserRepository userRepo;
    private final UserApiKeyRepository keyRepo;
    private final BridgeTransactionRepository txRepo;
    private final AuditLogRepository auditRepo;
    private final AuditService audit;

    /* ── Overview ───────────────────────────────────────────────────────── */

    @GetMapping("/overview")
    @Operation(summary = "Global KPIs")
    public ApiResponse<Map<String, Object>> overview() {
        Instant now = Instant.now();
        Instant in7   = now.plus(7,  ChronoUnit.DAYS);
        Instant after7 = now.minus(7, ChronoUnit.DAYS);
        Instant after30 = now.minus(30, ChronoUnit.DAYS);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("users",       userRepo.count());
        m.put("usersNew7d",  userRepo.countByCreatedAtAfter(after7));
        m.put("activeKeys",  keyRepo.countByRevokedFalseAndExpiresAtAfter(now));
        m.put("expiringThisWeek", keyRepo.countByExpiresAtBetweenAndRevokedFalse(now, in7));

        BigDecimal lifetimeUsd = nz(txRepo.sumGlobalByStatusCurrencyAfter(
                BridgeTransaction.Status.PAID, "USD", Instant.EPOCH));
        BigDecimal lifetimeKhr = nz(txRepo.sumGlobalByStatusCurrencyAfter(
                BridgeTransaction.Status.PAID, "KHR", Instant.EPOCH));
        m.put("revenueLifetimeUsd", lifetimeUsd);
        m.put("revenueLifetimeKhr", lifetimeKhr);

        m.put("revenue30dDaily", buildDailyChart(after30));

        m.put("transactionsTotal",  txRepo.count());
        m.put("transactionsPaid",   txRepo.countByStatus(BridgeTransaction.Status.PAID));
        m.put("transactions30d",    txRepo.countByCreatedAtAfter(after30));
        return ApiResponse.ok(m);
    }

    private Object buildDailyChart(Instant after) {
        var rows = txRepo.paidAmountsAfter("USD", after);
        var byDay = new java.util.TreeMap<String, BigDecimal>();
        for (var r : rows) {
            Instant t = (Instant) r[0];
            BigDecimal amt = (BigDecimal) r[1];
            String day = t.toString().substring(0, 10); // YYYY-MM-DD
            byDay.merge(day, amt, BigDecimal::add);
        }
        return byDay.entrySet().stream()
                .map(e -> Map.of("day", e.getKey(), "usd", e.getValue()))
                .toList();
    }

    /* ── Users ──────────────────────────────────────────────────────────── */

    @GetMapping("/users")
    @Operation(summary = "Search / list users")
    public ApiResponse<Map<String, Object>> users(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pg = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        Page<User> p = (q == null || q.isBlank())
                ? userRepo.findAllByOrderByCreatedAtDesc(pg)
                : userRepo.search(q.trim(), pg);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page",       p.getNumber());
        out.put("size",       p.getSize());
        out.put("total",      p.getTotalElements());
        out.put("totalPages", p.getTotalPages());
        out.put("items",      p.getContent().stream().map(this::userRow).toList());
        return ApiResponse.ok(out);
    }

    @GetMapping("/users/{id}")
    @Operation(summary = "User detail with keys + tx + audit")
    public ApiResponse<Map<String, Object>> userDetail(@PathVariable UUID id) {
        User u = userRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "user not found"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user",  userRow(u));
        out.put("keys",  keyRepo.findByUserOrderByIssuedAtDesc(u)
                .stream().map(this::keyRow).toList());
        out.put("audit", auditRepo.findTop50ByUserOrderByCreatedAtDesc(u)
                .stream().map(this::auditRow).toList());
        return ApiResponse.ok(out);
    }

    @PatchMapping("/users/{id}")
    @Operation(summary = "Update role / status")
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<Map<String, Object>> updateUser(
            @PathVariable UUID id,
            @RequestBody UpdateUserBody body,
            @AuthenticationPrincipal User actor) {
        User u = userRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "user not found"));
        if (body.role != null) {
            Role newRole = Role.valueOf(body.role.toUpperCase());
            Role old = u.getRole();
            u.setRole(newRole);
            audit.log(actor,
                    newRole == Role.ADMIN ? AuditService.ADMIN_PROMOTE : AuditService.ADMIN_DEMOTE,
                    "user", u.getId().toString(),
                    Map.of("from", old.name(), "to", newRole.name()));
        }
        if (body.status != null) {
            u.setStatus(body.status.toUpperCase());
            if ("SUSPENDED".equals(u.getStatus())) {
                audit.log(actor, AuditService.ADMIN_SUSPEND, "user", u.getId().toString());
            }
        }
        userRepo.save(u);
        return ApiResponse.ok(userRow(u), "User updated");
    }

    /* ── Keys ───────────────────────────────────────────────────────────── */

    @GetMapping("/keys")
    @Operation(summary = "List all keys (filterable)")
    public ApiResponse<Map<String, Object>> keys(
            @RequestParam(required = false) UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pg = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        Page<UserApiKey> p = (userId == null)
                ? keyRepo.findAllByOrderByIssuedAtDesc(pg)
                : userRepo.findById(userId)
                    .map(u -> keyRepo.findByUserOrderByIssuedAtDesc(u, pg))
                    .orElse(Page.empty(pg));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page",       p.getNumber());
        out.put("size",       p.getSize());
        out.put("total",      p.getTotalElements());
        out.put("totalPages", p.getTotalPages());
        out.put("items",      p.getContent().stream().map(this::keyRow).toList());
        return ApiResponse.ok(out);
    }

    @PostMapping("/keys/{id}/revoke")
    @Operation(summary = "Force-revoke a key")
    @org.springframework.transaction.annotation.Transactional
    public ApiResponse<Map<String, Object>> revokeKey(
            @PathVariable UUID id,
            @RequestBody(required = false) RevokeBody body,
            @AuthenticationPrincipal User actor) {
        UserApiKey k = keyRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("KEY_NOT_FOUND", "key not found"));
        if (k.isRevoked()) {
            return ApiResponse.ok(keyRow(k), "Already revoked");
        }
        k.setRevoked(true);
        k.setRevokedAt(Instant.now());
        k.setRevokeReason(body == null || body.reason == null ? "ADMIN_REVOKED" : body.reason.trim());
        keyRepo.save(k);
        audit.log(actor, AuditService.ADMIN_REVOKE, "user_api_key", k.getId().toString(),
                Map.of("reason", k.getRevokeReason()));
        return ApiResponse.ok(keyRow(k), "Revoked");
    }

    /* ── Transactions ──────────────────────────────────────────────────── */

    @GetMapping("/transactions")
    @Operation(summary = "List all transactions")
    public ApiResponse<Map<String, Object>> transactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size) {
        Pageable pg = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        Page<BridgeTransaction> p = txRepo.findAllByOrderByCreatedAtDesc(pg);
        return ApiResponse.ok(MeTransactionsController.envelope(p));
    }

    /* ── Audit log ─────────────────────────────────────────────────────── */

    @GetMapping("/audit")
    @Operation(summary = "Paginated global audit log")
    public ApiResponse<Map<String, Object>> audit(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Pageable pg = PageRequest.of(Math.max(0, page), Math.min(200, Math.max(1, size)));
        Page<AuditLog> p = auditRepo.findAllByOrderByCreatedAtDesc(pg);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page",       p.getNumber());
        out.put("size",       p.getSize());
        out.put("total",      p.getTotalElements());
        out.put("totalPages", p.getTotalPages());
        out.put("items",      p.getContent().stream().map(this::auditRow).toList());
        return ApiResponse.ok(out);
    }

    /* ── helpers ───────────────────────────────────────────────────────── */

    private Map<String, Object> userRow(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",            u.getId());
        m.put("email",         u.getEmail());
        m.put("fullName",      u.getFullName());
        m.put("role",          u.getRole().name());
        m.put("status",        u.getStatus());
        m.put("avatarUrl",     u.getAvatarUrl());
        m.put("createdAt",     u.getCreatedAt());
        m.put("lastLoginAt",   u.getLastLoginAt());
        return m;
    }

    private Map<String, Object> keyRow(UserApiKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",           k.getId());
        m.put("userId",       k.getUser().getId());
        m.put("email",        k.getUser().getEmail());
        m.put("maskedKey",    mask(k.getApiKey()));
        m.put("planId",       k.getPlanId());
        m.put("merchantName", k.getMerchantName());
        m.put("issuedAt",     k.getIssuedAt());
        m.put("expiresAt",    k.getExpiresAt());
        m.put("revoked",      k.isRevoked());
        m.put("revokedAt",    k.getRevokedAt());
        m.put("revokeReason", k.getRevokeReason());
        return m;
    }

    private Map<String, Object> auditRow(AuditLog a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id",         a.getId());
        m.put("userId",     a.getUser() == null ? null : a.getUser().getId());
        m.put("email",      a.getUser() == null ? null : a.getUser().getEmail());
        m.put("action",     a.getAction());
        m.put("targetType", a.getTargetType());
        m.put("targetId",   a.getTargetId());
        m.put("metadata",   a.getMetadata());
        m.put("ip",         a.getIp());
        m.put("createdAt",  a.getCreatedAt());
        return m;
    }

    private static String mask(String key) {
        if (key == null || key.length() < 12) return "—";
        return key.substring(0, 4) + "…" + key.substring(key.length() - 4);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /* ── request bodies ───────────────────────────────────────────────── */

    public static class UpdateUserBody {
        @Size(max = 20) public String role;       // USER | ADMIN
        @Size(max = 20) public String status;     // ACTIVE | SUSPENDED | DELETED
    }
    public static class RevokeBody {
        @NotBlank @Size(max = 120) public String reason;
    }
}
