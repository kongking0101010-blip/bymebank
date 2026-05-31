package com.khmerbank.service.qrcode;

import com.khmerbank.dto.request.GenerateQrRequest;
import com.khmerbank.dto.response.PaymentStatusResponse;
import com.khmerbank.dto.response.QrCodeResponse;
import com.khmerbank.exception.ApiException;
import com.khmerbank.model.Merchant;
import com.khmerbank.model.QrCode;
import com.khmerbank.model.User;
import com.khmerbank.model.enums.PaymentStatus;
import com.khmerbank.repository.QrCodeRepository;
import com.khmerbank.service.bank.BankRouter;
import com.khmerbank.service.merchant.MerchantService;
import com.khmerbank.service.subscription.QuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class QrCodeService {

    private final QrCodeRepository qrCodeRepository;
    private final MerchantService merchantService;
    private final KhqrGenerator khqrGenerator;
    private final QrImageRenderer imageRenderer;
    private final QuotaService quotaService;
    private final BankRouter bankRouter;

    @Transactional
    public QrCodeResponse generate(User user, GenerateQrRequest req) {
        quotaService.assertQuotaAvailable(user);

        Merchant merchant = merchantService.resolveMerchant(user, req.getMerchantId(), req.getBankType());

        String txnId = "KB-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        String payload = khqrGenerator.generate(merchant, req.getAmount(), req.getCurrency(),
                req.getReference() == null ? txnId : req.getReference(),
                req.getDescription());

        String image = imageRenderer.renderPngBase64(payload, 400);

        int ttl = req.getExpiresIn() == null ? 900 : req.getExpiresIn();
        Instant expires = Instant.now().plus(ttl, ChronoUnit.SECONDS);

        QrCode qr = QrCode.builder()
                .transactionId(txnId)
                .user(user)
                .merchant(merchant)
                .bankType(req.getBankType())
                .amount(req.getAmount())
                .currency(req.getCurrency())
                .qrPayload(payload)
                .qrImageBase64(image)
                .description(req.getDescription())
                .reference(req.getReference())
                .status(PaymentStatus.PENDING)
                .expiresAt(expires)
                .build();
        qrCodeRepository.save(qr);
        quotaService.incrementUsage(user);

        return toQrResponse(qr);
    }

    @Transactional(readOnly = true)
    public PaymentStatusResponse checkStatus(User user, String transactionId) {
        QrCode qr = qrCodeRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> ApiException.notFound("QR_NOT_FOUND", "Transaction not found"));
        if (!qr.getUser().getId().equals(user.getId())) {
            throw ApiException.forbidden("ACCESS_DENIED", "Not your transaction");
        }

        if (qr.getStatus() == PaymentStatus.PENDING && qr.getExpiresAt().isAfter(Instant.now())) {
            try {
                PaymentStatus latest = bankRouter.get(qr.getBankType()).checkStatus(qr);
                if (latest != qr.getStatus()) {
                    qr.setStatus(latest);
                    if (latest == PaymentStatus.PAID) qr.setPaidAt(Instant.now());
                }
            } catch (Exception e) {
                log.warn("Live bank check failed for {}: {}", transactionId, e.getMessage());
            }
        } else if (qr.getStatus() == PaymentStatus.PENDING) {
            qr.setStatus(PaymentStatus.EXPIRED);
        }

        return PaymentStatusResponse.builder()
                .transactionId(qr.getTransactionId())
                .status(qr.getStatus())
                .bankType(qr.getBankType())
                .amount(qr.getAmount())
                .currency(qr.getCurrency())
                .bankReference(qr.getBankReference())
                .paidAt(qr.getPaidAt())
                .expiresAt(qr.getExpiresAt())
                .paid(qr.getStatus() == PaymentStatus.PAID)
                .build();
    }

    private QrCodeResponse toQrResponse(QrCode qr) {
        return QrCodeResponse.builder()
                .transactionId(qr.getTransactionId())
                .bankType(qr.getBankType())
                .amount(qr.getAmount())
                .currency(qr.getCurrency())
                .description(qr.getDescription())
                .reference(qr.getReference())
                .qrPayload(qr.getQrPayload())
                .qrImage(qr.getQrImageBase64())
                .md5(com.khmerbank.util.Md5Util.md5Hex(qr.getQrPayload()))
                .checkUrl("/api/v1/payments/" + qr.getTransactionId() + "/status")
                .status(qr.getStatus())
                .expiresAt(qr.getExpiresAt())
                .createdAt(qr.getCreatedAt())
                .build();
    }
}
