package com.khmerbank.service.qrcode;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.khmerbank.exception.ApiException;
import com.khmerbank.model.enums.BankType;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a KHQR/EMV QR-code PNG/JPG and parses the TLV payload to figure out
 * which bank it belongs to and what account info to store.
 *
 * <p>Real bank QRs in Cambodia use the Bakong-network EMV-MPM format:
 * <pre>
 *   Tag 29  – Merchant Account Information
 *      00 – Network handle (e.g. "user@aclb", "khqr@aclb", "wing_khqr@wing", "abaakhppxxx@aba")
 *      01 – Account number      (only present in direct-bank format)
 *      02 – Bank label           (only present in direct-bank format)
 *   Tag 59  – Merchant name
 *   Tag 60  – Merchant city
 *   Tag 62  – Additional data (reference, phone, store label, terminal)
 * </pre>
 *
 * <p>The bank is identified primarily by the suffix of the network handle
 * ({@code @aclb}, {@code @aba}, {@code @wing}, etc).
 */
@Component
@Slf4j
public class QrDecoderService {

    /** Decode an image into an EMV/KHQR string, then parse it. */
    public DecodedQr decodeImage(byte[] imageBytes) {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (Exception e) {
            throw ApiException.badRequest("INVALID_IMAGE", "Could not read uploaded image");
        }
        if (img == null) {
            throw ApiException.badRequest("INVALID_IMAGE", "Unsupported image format");
        }
        int[] pixels = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(img.getWidth(), img.getHeight(), pixels)));
        Result result;
        try {
            result = new MultiFormatReader().decode(bitmap);
        } catch (NotFoundException e) {
            throw ApiException.badRequest("QR_NOT_FOUND", "No QR code found in image");
        }
        return decodeString(result.getText());
    }

    /** Parse a raw KHQR/EMV string the user pasted directly. */
    public DecodedQr decodeString(String payload) {
        if (payload == null) {
            throw ApiException.badRequest("EMPTY_QR", "QR string is empty");
        }
        payload = payload.trim();
        if (!payload.startsWith("0002")) {
            throw ApiException.badRequest("NOT_KHQR",
                    "Not an EMV/KHQR string (must start with '0002…')");
        }
        return parse(payload);
    }

    /** Backwards-compat alias used by older callers. */
    public DecodedQr decode(byte[] imageBytes) {
        return decodeImage(imageBytes);
    }

    public DecodedQr parse(String payload) {
        Map<String, String> top = readTlv(payload);

        // Merchant info can live in any tag 26..51. Find the first one present.
        Map<String, String> sub = new HashMap<>();
        String merchantTag = null;
        for (int i = 26; i <= 51; i++) {
            String t = String.format("%02d", i);
            if (top.containsKey(t)) {
                sub = readTlv(top.get(t));
                merchantTag = t;
                break;
            }
        }

        String network = sub.getOrDefault("00", "");   // e.g. "user@aclb" or "khqr@aclb"
        String account = sub.getOrDefault("01", "");
        String issuer  = sub.getOrDefault("02", "");

        // Bakong-network handle pattern: only sub-00 set, value contains '@'.
        // The handle itself is the destination account.
        if (account.isEmpty() && network.contains("@")) {
            account = network;
        }

        BankType bank = detectBank(network, issuer);

        Map<String, String> extra = top.containsKey("62") ? readTlv(top.get("62")) : Map.of();

        return DecodedQr.builder()
                .payload(payload)
                .bank(bank)
                .merchantInfoTag(merchantTag)
                .merchantName(top.getOrDefault("59", ""))
                .merchantCity(top.getOrDefault("60", ""))
                .currency("116".equals(top.get("53")) ? "KHR" : "USD")
                .amount(top.getOrDefault("54", null))
                .countryCode(top.getOrDefault("58", ""))
                .merchantNetwork(network)
                .merchantAccount(account)
                .merchantIssuer(issuer)
                .reference(extra.getOrDefault("01", null))
                .phone(extra.getOrDefault("02", null))
                .storeLabel(extra.getOrDefault("03", null))
                .terminalLabel(extra.getOrDefault("07", null))
                .build();
    }

    /**
     * Identify the destination bank from the handle suffix and issuer label.
     *
     * <ul>
     *   <li>{@code xxx@aclb}  → ACLEDA</li>
     *   <li>{@code xxx@aba} / {@code xxx@ababank}  → ABA</li>
     *   <li>{@code xxx@wing} / contains "wing"      → WING</li>
     *   <li>otherwise → BAKONG (generic Bakong-network handle)</li>
     * </ul>
     */
    private BankType detectBank(String network, String issuer) {
        String hay = ((network == null ? "" : network) + " "
                    + (issuer == null ? "" : issuer)).toLowerCase(Locale.ROOT);
        if (hay.contains("@aclb") || hay.contains("acleda")) return BankType.ACLEDA;
        if (hay.contains("@wing") || hay.contains("wing"))   return BankType.WING;
        if (hay.contains("@aba")  || hay.contains("@ababank")
                || hay.contains("ababank") || hay.contains("aba bank")) return BankType.ABA;
        return BankType.BAKONG;
    }

    private Map<String, String> readTlv(String input) {
        Map<String, String> out = new HashMap<>();
        if (input == null || input.length() < 4) return out;
        int i = 0;
        while (i + 4 <= input.length()) {
            String tag = input.substring(i, i + 2);
            int len;
            try { len = Integer.parseInt(input.substring(i + 2, i + 4)); }
            catch (NumberFormatException e) { break; }
            int valStart = i + 4;
            int valEnd = valStart + len;
            if (valEnd > input.length()) break;
            out.put(tag, input.substring(valStart, valEnd));
            i = valEnd;
        }
        return out;
    }

    @Data
    @Builder
    public static class DecodedQr {
        private String payload;
        private BankType bank;
        private String merchantInfoTag;
        private String merchantName;
        private String merchantCity;
        private String currency;
        private String amount;
        private String countryCode;
        private String merchantNetwork;
        private String merchantAccount;
        private String merchantIssuer;
        private String reference;
        private String phone;
        private String storeLabel;
        private String terminalLabel;
    }
}
