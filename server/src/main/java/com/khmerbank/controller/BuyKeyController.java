package com.khmerbank.controller;

import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.exception.ApiException;
import com.khmerbank.model.User;
import com.khmerbank.model.enums.BankType;
import com.khmerbank.service.buykey.BuyKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Wizard-style "Buy API Key" flow used by the dashboard.
 * Mirrors the Python register_web blueprint:
 *   1. select banks
 *   2. pick a plan duration → get price
 *   3. generate a payment KHQR for the platform's own account
 *   4. poll for payment
 *   5. activate plan + mint API key
 *
 * Linking the user's own bank QRs (so they can ACCEPT payments) is a
 * separate flow — see {@link MerchantController}.
 */
@RestController
@RequestMapping("/api/v1/buy-key")
@RequiredArgsConstructor
@Tag(name = "Buy API Key", description = "Guided wizard for purchasing an API key")
public class BuyKeyController {

    private final BuyKeyService buyKeyService;

    @GetMapping("/pricing")
    @Operation(summary = "Get the price table for N selected banks")
    public ApiResponse<BuyKeyService.PricingTable> pricing(
            @RequestParam(defaultValue = "1") int bankCount) {
        return ApiResponse.ok(buyKeyService.pricingFor(bankCount));
    }

    @GetMapping("/payment-methods")
    @Operation(summary = "Banks the user can scan to pay the platform — "
            + "live from upstream /key_info for the platform's customer key. "
            + "Empty list = platform payment temporarily unavailable.")
    public ApiResponse<Map<String, Object>> paymentMethods() {
        java.util.List<String> slugs = buyKeyService.platformBankSlugs();
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("banks", slugs);
        return ApiResponse.ok(out);
    }

    @PostMapping("/payment-qr")
    @Operation(summary = "Generate the platform payment KHQR (you scan to pay us)")
    public ApiResponse<BuyKeyService.PaymentDraft> paymentQr(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {
        String method = String.valueOf(body.getOrDefault("method", "BAKONG")).toUpperCase();
        BankType bank;
        try { bank = BankType.valueOf(method); }
        catch (Exception e) { throw ApiException.badRequest("INVALID_METHOD", "Bad method"); }

        BigDecimal amount = new BigDecimal(String.valueOf(body.getOrDefault("amount", "0")));
        int days = (int) Double.parseDouble(String.valueOf(body.getOrDefault("days", 30)));
        if (amount.signum() <= 0)
            throw ApiException.badRequest("BAD_AMOUNT", "amount must be > 0");

        return ApiResponse.ok(buyKeyService.startPayment(user, bank, amount, days));
    }

    @GetMapping("/poll-payment")
    @Operation(summary = "Check if the platform has received the payment")
    public ApiResponse<Map<String, Object>> poll(
            @RequestParam("md5") String md5,
            @RequestParam("qrPayload") String qrPayload) {
        boolean paid = buyKeyService.isPaid(md5, qrPayload);
        return ApiResponse.ok(Map.of("paid", paid, "md5", md5));
    }

    @PostMapping("/activate")
    @Operation(summary = "Finalize the purchase — activate the plan and mint the API key")
    public ApiResponse<BuyKeyService.ActivationResult> activate(
            @RequestBody Map<String, Object> body,
            @AuthenticationPrincipal User user) {
        int days = (int) Double.parseDouble(String.valueOf(body.getOrDefault("days", 30)));
        BigDecimal amount = new BigDecimal(String.valueOf(body.getOrDefault("amount", "0")));
        String keyName = String.valueOf(body.getOrDefault("keyName", "Default"));
        return ApiResponse.ok(
                buyKeyService.activate(user, days, amount, keyName),
                "Plan activated and API key issued");
    }
}
