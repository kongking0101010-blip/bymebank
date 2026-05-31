package com.khmerbank.controller;

import com.khmerbank.dto.request.GenerateQrRequest;
import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.dto.response.PaymentStatusResponse;
import com.khmerbank.dto.response.QrCodeResponse;
import com.khmerbank.model.User;
import com.khmerbank.service.qrcode.QrCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Generate QR codes and check payment status")
public class PaymentController {

    private final QrCodeService qrCodeService;

    @PostMapping("/qr")
    @Operation(summary = "Generate a payment QR code (KHQR / EMV)")
    public ApiResponse<QrCodeResponse> generateQr(@Valid @RequestBody GenerateQrRequest req,
                                                  @AuthenticationPrincipal User user) {
        return ApiResponse.ok(qrCodeService.generate(user, req), "QR generated");
    }

    @GetMapping("/{transactionId}/status")
    @Operation(summary = "Check the status of a payment")
    public ApiResponse<PaymentStatusResponse> status(@PathVariable String transactionId,
                                                     @AuthenticationPrincipal User user) {
        return ApiResponse.ok(qrCodeService.checkStatus(user, transactionId));
    }
}
