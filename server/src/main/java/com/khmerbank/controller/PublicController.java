package com.khmerbank.controller;

import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.model.enums.BankType;
import com.khmerbank.model.enums.PlanType;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "Public", description = "Public endpoints (no auth required)")
public class PublicController {

    @GetMapping("/banks")
    public ApiResponse<List<Map<String, String>>> banks() {
        return ApiResponse.ok(Arrays.stream(BankType.values())
                .map(b -> Map.of(
                        "code", b.name(),
                        "name", b.getDisplayName(),
                        "description", b.getDescription()))
                .toList());
    }

    @GetMapping("/pricing")
    public ApiResponse<List<Map<String, Object>>> pricing() {
        return ApiResponse.ok(List.of(
                Map.of("plan", PlanType.FREE,    "price", 0,   "quota", 100,
                        "features", List.of("100 QR / month", "Email support")),
                Map.of("plan", PlanType.BASIC,   "price", 10,  "quota", 5000,
                        "features", List.of("5,000 QR / month", "Webhooks", "All 3 banks")),
                Map.of("plan", PlanType.PRO,     "price", 50,  "quota", -1,
                        "features", List.of("Unlimited QR", "Priority support",
                                "Multi-merchant", "Custom branding")),
                Map.of("plan", PlanType.ENTERPRISE, "price", 200, "quota", -1,
                        "features", List.of("Unlimited", "SLA 99.99%",
                                "Dedicated account manager"))
        ));
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.ok(Map.of("status", "UP", "service", "khmer-bank-gateway"));
    }
}
