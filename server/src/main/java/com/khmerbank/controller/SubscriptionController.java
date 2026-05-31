package com.khmerbank.controller;

import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.model.Subscription;
import com.khmerbank.model.User;
import com.khmerbank.model.enums.PlanType;
import com.khmerbank.service.subscription.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/subscriptions")
@RequiredArgsConstructor
@Tag(name = "Subscriptions", description = "Plan and quota management")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    @GetMapping("/me")
    @Operation(summary = "Get my current subscription")
    public ApiResponse<Subscription> me(@AuthenticationPrincipal User user) {
        return ApiResponse.ok(subscriptionService.getMine(user));
    }

    @PostMapping("/upgrade")
    @Operation(summary = "Upgrade plan (currently free for everyone)")
    public ApiResponse<Subscription> upgrade(@RequestParam PlanType plan,
                                             @RequestParam(required = false) String paymentTxnId,
                                             @AuthenticationPrincipal User user) {
        return ApiResponse.ok(
                subscriptionService.activatePlan(user, plan,
                        paymentTxnId == null ? "FREE" : paymentTxnId),
                "Plan upgraded");
    }
}
