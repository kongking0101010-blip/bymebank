package com.khmerbank.controller;

import com.khmerbank.dto.request.LinkMerchantRequest;
import com.khmerbank.dto.response.ApiResponse;
import com.khmerbank.dto.response.DecodedQrResponse;
import com.khmerbank.dto.response.MerchantResponse;
import com.khmerbank.exception.ApiException;
import com.khmerbank.model.User;
import com.khmerbank.model.enums.BankType;
import com.khmerbank.service.merchant.MerchantService;
import com.khmerbank.service.qrcode.QrDecoderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/merchants")
@RequiredArgsConstructor
@Tag(name = "Merchants", description = "Link your ABA / Wing / Bakong / ACLEDA accounts")
public class MerchantController {

    private final MerchantService merchantService;
    private final QrDecoderService qrDecoder;

    @PostMapping
    @Operation(summary = "Link a merchant manually (no QR upload)")
    public ApiResponse<MerchantResponse> link(@Valid @RequestBody LinkMerchantRequest req,
                                              @AuthenticationPrincipal User user) {
        return ApiResponse.ok(merchantService.linkMerchant(user, req), "Merchant linked");
    }

    /**
     * Upload-based linking — the dashboard sends a multipart form:
     *
     * <ul>
     *   <li><b>ABA</b>:    qr (file) + merchantId</li>
     *   <li><b>BAKONG</b>: merchantId + phone (no file required)</li>
     *   <li><b>WING</b>:   qr (file) only</li>
     *   <li><b>ACLEDA</b>: qr (file) only</li>
     * </ul>
     */
    @PostMapping(value = "/upload", consumes = { MediaType.MULTIPART_FORM_DATA_VALUE,
                                                  MediaType.APPLICATION_FORM_URLENCODED_VALUE })
    @Operation(summary = "Link by uploading the bank's QR (or merchantId+phone for Bakong)")
    public ApiResponse<MerchantResponse> upload(
            @RequestParam("bankType") BankType bankType,
            @RequestParam(value = "qr", required = false) MultipartFile qr,
            @RequestParam(value = "qrString", required = false) String qrString,
            @RequestParam(value = "merchantId", required = false) String merchantId,
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam(value = "merchantName", required = false) String merchantName,
            @AuthenticationPrincipal User user) throws IOException {

        if (bankType == BankType.BAKONG && (qr == null || qr.isEmpty())
                && (qrString == null || qrString.isBlank())) {
            return ApiResponse.ok(
                    merchantService.linkBakong(user, merchantId, phone, merchantName),
                    "Bakong merchant linked");
        }

        byte[] bytes = qr != null && !qr.isEmpty() ? qr.getBytes() : null;
        if (bytes == null && (qrString == null || qrString.isBlank())) {
            throw ApiException.badRequest("NO_QR",
                    bankType + " requires either a QR image upload or qrString");
        }

        return ApiResponse.ok(
                merchantService.linkFromQr(user, bankType, bytes, qrString,
                        merchantId, phone, merchantName),
                bankType + " merchant linked");
    }

    @PostMapping(value = "/decode", consumes = { MediaType.MULTIPART_FORM_DATA_VALUE,
                                                  MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                                                  MediaType.APPLICATION_JSON_VALUE })
    @Operation(summary = "Decode a QR image OR raw string without linking — preview only")
    public ApiResponse<DecodedQrResponse> decode(
            @RequestParam(value = "qr", required = false) MultipartFile qr,
            @RequestParam(value = "qrString", required = false) String qrString) throws IOException {
        var d = (qrString != null && !qrString.isBlank())
                ? qrDecoder.decodeString(qrString)
                : (qr != null && !qr.isEmpty()
                    ? qrDecoder.decodeImage(qr.getBytes())
                    : null);
        if (d == null) {
            throw ApiException.badRequest("NO_QR", "Send qr (image) or qrString");
        }
        return ApiResponse.ok(DecodedQrResponse.builder()
                .bank(d.getBank())
                .merchantName(d.getMerchantName())
                .merchantCity(d.getMerchantCity())
                .merchantAccount(d.getMerchantAccount())
                .merchantNetwork(d.getMerchantNetwork())
                .merchantIssuer(d.getMerchantIssuer())
                .currency(d.getCurrency())
                .phone(d.getPhone())
                .reference(d.getReference())
                .payload(d.getPayload())
                .build());
    }

    @GetMapping
    @Operation(summary = "List your linked merchants")
    public ApiResponse<List<MerchantResponse>> list(@AuthenticationPrincipal User user) {
        return ApiResponse.ok(merchantService.listMerchants(user));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Unlink a merchant")
    public ApiResponse<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        merchantService.deleteMerchant(user, id);
        return ApiResponse.ok(null, "Merchant unlinked");
    }
}
