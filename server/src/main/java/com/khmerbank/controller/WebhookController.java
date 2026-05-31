package com.khmerbank.controller;

import com.khmerbank.exception.ApiException;
import com.khmerbank.model.QrCode;
import com.khmerbank.model.enums.PaymentStatus;
import com.khmerbank.repository.QrCodeRepository;
import com.khmerbank.service.bank.BankRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

import com.khmerbank.model.enums.BankType;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receives callbacks from ABA / Wing / Bakong (when supported).
 * After verifying the signature, the QR code is marked PAID and the
 * developer's webhook URL is fired (if configured).
 */
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final BankRouter bankRouter;
    private final QrCodeRepository qrCodeRepository;

    @PostMapping("/aba")
    @Transactional
    public Map<String, Object> aba(@RequestHeader(value = "X-Signature", required = false) String sig,
                                   @RequestBody String rawBody) {
        bankRouter.get(BankType.ABA).verifyWebhook(sig, rawBody);
        return handleCallback(BankType.ABA, rawBody);
    }

    @PostMapping("/acleda")
    @Transactional
    public Map<String, Object> acleda(@RequestHeader(value = "X-Signature", required = false) String sig,
                                      @RequestBody String rawBody) {
        bankRouter.get(BankType.ACLEDA).verifyWebhook(sig, rawBody);
        return handleCallback(BankType.ACLEDA, rawBody);
    }

    @PostMapping("/wing")
    @Transactional
    public Map<String, Object> wing(@RequestHeader(value = "X-Signature", required = false) String sig,
                                    @RequestBody String rawBody) {
        bankRouter.get(BankType.WING).verifyWebhook(sig, rawBody);
        return handleCallback(BankType.WING, rawBody);
    }

    @PostMapping("/bakong")
    @Transactional
    public Map<String, Object> bakong(@RequestBody String rawBody) {
        bankRouter.get(BankType.BAKONG).verifyWebhook(null, rawBody);
        return handleCallback(BankType.BAKONG, rawBody);
    }

    private Map<String, Object> handleCallback(BankType bank, String body) {
        log.info("Webhook from {}: {}", bank, body);
        // The bank-specific service can also extract txnId; here we trust a simple field.
        // In production, parse JSON properly.
        String txnId = extractTxnId(body);
        if (txnId == null) {
            throw ApiException.badRequest("INVALID_PAYLOAD", "Missing transaction id");
        }
        QrCode qr = qrCodeRepository.findByTransactionId(txnId)
                .orElseThrow(() -> ApiException.notFound("QR_NOT_FOUND", "Unknown transaction"));
        qr.setStatus(PaymentStatus.PAID);
        qr.setPaidAt(Instant.now());
        return Map.of("ok", true);
    }

    private String extractTxnId(String body) {
        // very small extractor – production code should use Jackson
        int i = body.indexOf("\"transactionId\"");
        if (i == -1) i = body.indexOf("\"tran_id\"");
        if (i == -1) return null;
        int colon = body.indexOf(':', i);
        int start = body.indexOf('"', colon + 1);
        int end = body.indexOf('"', start + 1);
        return (start < 0 || end < 0) ? null : body.substring(start + 1, end);
    }
}
