package com.khmerbank.service.qrcode;

import com.khmerbank.model.Merchant;
import com.khmerbank.model.enums.BankType;
import com.khmerbank.model.enums.Currency;
import com.khmerbank.util.CrcUtil;
import com.khmerbank.util.TlvBuilder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;

/**
 * Generates KHQR (Cambodia EMV-compliant) and bank-specific QR payloads.
 *
 * <p>Each bank has a slightly different EMV layout and merchant info block —
 * matching the working Python {@code khqr_all_in_one.py} reference:
 *
 * <ul>
 *   <li><b>BAKONG</b> — Tag 29 with sub-00 only. MCC 5999. Tag 99 has timestamps + nonce.</li>
 *   <li><b>ACLEDA</b> — Tag 29 with khqr@aclb / acct / "ACLEDA". MCC 2000. Tag 99: timestamps only.</li>
 *   <li><b>WING</b>   — Tag 29 with wing_khqr@wing / acct / "Wing Bank". MCC 5999. Tag 99 with nonce.</li>
 *   <li><b>ABA</b>    — Special: built via {@code AbaPayWayService.generateQrFromMerchantLink}
 *       calling ABA's PayWay API. This generator returns a placeholder for ABA.</li>
 * </ul>
 */
@Component
public class KhqrGenerator {

    private static final String PAYLOAD_FORMAT = "01";
    private static final String COUNTRY_CODE   = "KH";
    private static final SecureRandom RNG = new SecureRandom();

    public String generate(Merchant merchant, BigDecimal amount, Currency currency,
                           String reference, String description) {

        boolean isDynamic = amount != null && amount.compareTo(BigDecimal.ZERO) > 0;

        TlvBuilder tlv = new TlvBuilder()
                .add("00", PAYLOAD_FORMAT)
                .add("01", isDynamic ? "12" : "11");

        // Tag 29 / 30 / 31 / 32 — bank-specific merchant info
        tlv.add(merchantInfoTag(merchant.getBankType()),
                buildMerchantAccountInfo(merchant));

        // Tag 52 — MCC
        tlv.add("52", mccFor(merchant.getBankType()));

        // Tag 53 — currency code (USD = 840, KHR = 116)
        tlv.add("53", currency.getNumericCode());

        // Tag 54 — amount (only for dynamic QR)
        if (isDynamic) {
            tlv.add("54", formatAmount(amount, currency));
        }

        // Tag 58/59/60 — country, merchant name, city
        tlv.add("58", COUNTRY_CODE);
        tlv.add("59", truncate(merchant.getMerchantName(), 25));
        tlv.add("60", truncate(merchant.getMerchantCity() == null
                ? "Phnom Penh" : merchant.getMerchantCity(), 15));

        // Tag 62 — additional data
        TlvBuilder extra = new TlvBuilder();
        switch (merchant.getBankType()) {
            case BAKONG -> {
                if (reference != null && !reference.isBlank())
                    extra.add("01", truncate(reference, 25));
                if (description != null && !description.isBlank())
                    extra.add("07", truncate(description, 25));
            }
            case ACLEDA -> {
                if (reference != null && !reference.isBlank())
                    extra.add("01", truncate(reference, 25));
                if (merchant.getAccountNumber() != null && !merchant.getAccountNumber().isBlank())
                    extra.add("02", truncate(merchant.getAccountNumber(), 13)); // phone-style
            }
            case WING -> {
                if (reference != null && !reference.isBlank())
                    extra.add("01", truncate(reference, 25));
                extra.add("07", "001"); // terminal label
            }
            default -> {
                if (reference != null && !reference.isBlank())
                    extra.add("01", truncate(reference, 25));
            }
        }
        if (!extra.build().isEmpty()) {
            tlv.add("62", extra.build());
        }

        // Tag 99 — timestamps (+ nonce for Bakong/Wing). ACLEDA omits nonce.
        if (isDynamic) {
            long now = System.currentTimeMillis();
            long expire = now + 86_400_000L; // 24h

            TlvBuilder t99 = new TlvBuilder()
                    .add("00", String.valueOf(now))
                    .add("01", String.valueOf(expire));

            if (merchant.getBankType() == BankType.BAKONG
                    || merchant.getBankType() == BankType.WING) {
                t99.add("02", randomNonce(8));
            }
            tlv.add("99", t99.build());
        }

        // CRC: append "6304" then compute over the whole string (matches Python _crc())
        String partial = tlv.build() + "6304";
        String crc = CrcUtil.crc16Hex(partial);
        return partial + crc;
    }

    /* -------------------- helpers -------------------- */

    private String merchantInfoTag(BankType type) {
        return switch (type) {
            case BAKONG -> "29";
            case ABA    -> "30";
            case WING   -> "31";
            case ACLEDA -> "32";
        };
    }

    private String mccFor(BankType type) {
        return type == BankType.ACLEDA ? "2000" : "5999";
    }

    private String buildMerchantAccountInfo(Merchant m) {
        TlvBuilder b = new TlvBuilder();
        switch (m.getBankType()) {
            case BAKONG -> b.add("00", m.getMerchantId()); // e.g. user@aclb
            case ACLEDA -> b
                    .add("00", "khqr@aclb")
                    .add("01", stripToAccount(m.getMerchantId(), m.getAccountNumber()))
                    .add("02", "ACLEDA");
            case WING -> b
                    .add("00", "wing_khqr@wing")
                    .add("01", m.getAccountNumber() == null || m.getAccountNumber().isBlank()
                            ? m.getMerchantId() : m.getAccountNumber())
                    .add("02", "Wing Bank");
            case ABA -> b
                    .add("00", "abaakhppxxx@aba")
                    .add("01", m.getMerchantId());
        }
        return b.build();
    }

    /** ACLEDA needs a digits-only account; strip @anything and non-digits. */
    private String stripToAccount(String merchantId, String accountNumber) {
        String s = accountNumber != null && !accountNumber.isBlank()
                ? accountNumber : merchantId;
        if (s == null) return "";
        if (s.contains("@")) s = s.substring(0, s.indexOf('@'));
        return s.replaceAll("[^0-9]", "");
    }

    private String formatAmount(BigDecimal amount, Currency currency) {
        BigDecimal scaled = amount.setScale(currency == Currency.KHR ? 0 : 2, RoundingMode.HALF_UP);
        // Match Python behaviour: f"{x:.2f}".rstrip("0").rstrip(".")
        String s = scaled.toPlainString();
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s.isEmpty() ? "0" : s;
    }

    private String randomNonce(int len) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(RNG.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }
}
