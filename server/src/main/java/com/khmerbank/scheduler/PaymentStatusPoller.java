package com.khmerbank.scheduler;

import com.khmerbank.model.QrCode;
import com.khmerbank.model.enums.PaymentStatus;
import com.khmerbank.repository.QrCodeRepository;
import com.khmerbank.service.bank.BankRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentStatusPoller {

    private final QrCodeRepository qrCodeRepository;
    private final BankRouter bankRouter;

    /** Mark expired pending QRs every 30 seconds. */
    @Scheduled(fixedDelayString = "30000", initialDelayString = "15000")
    @Transactional
    public void expireOldPending() {
        List<QrCode> stale = qrCodeRepository.findExpiredPending(PaymentStatus.PENDING, Instant.now());
        for (QrCode qr : stale) qr.setStatus(PaymentStatus.EXPIRED);
        if (!stale.isEmpty()) log.info("Expired {} stale QR codes", stale.size());
    }
}
