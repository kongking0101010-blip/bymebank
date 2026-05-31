package com.khmerbank.service.bank;

import com.khmerbank.model.QrCode;
import com.khmerbank.model.enums.BankType;
import com.khmerbank.model.enums.PaymentStatus;

/**
 * Common interface every bank integration implements.
 * Each bank knows how to:
 *  - check the payment status of a previously generated QR
 *  - parse webhook callbacks
 */
public interface BankIntegration {

    BankType bankType();

    /**
     * Query the bank API for the current status of a transaction.
     */
    PaymentStatus checkStatus(QrCode qrCode);

    /**
     * Verify a webhook signature header.
     * Should throw if the signature is invalid.
     */
    void verifyWebhook(String signatureHeader, String rawBody);
}
