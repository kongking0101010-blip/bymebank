package com.khmerbank.controller;

import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.model.BridgeTransaction;
import com.khmerbank.model.BridgeTransaction.Status;
import com.khmerbank.model.User;
import com.khmerbank.repository.BridgeTransactionRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@Tag(name = "Me — Transactions")
public class MeTransactionsController {

    private final BridgeTransactionRepository repo;

    @GetMapping("/transactions")
    @Operation(summary = "Paginated bridge-flow transactions for the logged-in user")
    public ApiResponse<Map<String, Object>> list(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Pageable pg = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        Page<BridgeTransaction> p = (status == null || status.isBlank())
                ? repo.findByUserOrderByCreatedAtDesc(user, pg)
                : repo.findByUserAndStatusOrderByCreatedAtDesc(
                        user, Status.valueOf(status.toUpperCase()), pg);
        return ApiResponse.ok(envelope(p));
    }

    static Map<String, Object> envelope(Page<BridgeTransaction> p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page",       p.getNumber());
        out.put("size",       p.getSize());
        out.put("total",      p.getTotalElements());
        out.put("totalPages", p.getTotalPages());
        out.put("items",      p.getContent().stream().map(MeTransactionsController::toRow).toList());
        return out;
    }

    static Map<String, Object> toRow(BridgeTransaction t) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id",        t.getId());
        r.put("md5",       t.getMd5());
        r.put("bank",      t.getBank());
        r.put("amount",    t.getAmount());
        r.put("currency",  t.getCurrency());
        r.put("status",    t.getStatus().name());
        r.put("paidAt",    t.getPaidAt());
        r.put("paidFrom",  t.getPaidFrom());
        r.put("createdAt", t.getCreatedAt());
        return r;
    }
}
