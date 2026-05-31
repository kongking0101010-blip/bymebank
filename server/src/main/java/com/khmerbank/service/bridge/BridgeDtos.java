package com.khmerbank.service.bridge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * DTOs for talking to the Python bridge.
 */
public final class BridgeDtos {
    private BridgeDtos() {}

    /** One bank entry, sent to {@code /bridge/issue_key}. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BankInput {
        private String bank;            // aba | wing | acleda | bakong
        private String qrString;
        private String merchantLink;    // ABA only
        private String merchantId;      // BAKONG only (e.g. user@aclb)
        private String phone;           // BAKONG only

        public static BankInput bakong(String merchantId, String phone) {
            return BankInput.builder().bank("bakong")
                    .merchantId(merchantId).phone(phone).build();
        }
        public static BankInput aba(String qr, String merchantLink) {
            return BankInput.builder().bank("aba")
                    .qrString(qr).merchantLink(merchantLink).build();
        }
        public static BankInput wing(String qr) {
            return BankInput.builder().bank("wing").qrString(qr).build();
        }
        public static BankInput acleda(String qr) {
            return BankInput.builder().bank("acleda").qrString(qr).build();
        }

        /** snake_case body for the bridge — bank is always lowercased
         *  defensively in case a caller passed an enum name. */
        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("bank", bank == null ? null : bank.toLowerCase());
            if (qrString != null    && !qrString.isBlank())    m.put("qr_string",     qrString);
            if (merchantLink != null && !merchantLink.isBlank()) m.put("merchant_link", merchantLink);
            if (merchantId != null  && !merchantId.isBlank())  m.put("merchant_id",   merchantId);
            if (phone != null       && !phone.isBlank())       m.put("phone",         phone);
            return m;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IssueKeyResponse {
        private boolean ok;
        private String key;
        private Integer expires_in_days;
        private String error;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class GenerateQrResponse {
        private boolean success;
        private String md5;
        private String qr_string;
        private String qr_image;
        private String merchant_name;
        private String bank;
        private Boolean demo;
        private String error;

        @JsonIgnoreProperties(ignoreUnknown = true)
        public BigDecimal amount;
        public String currency;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CheckPaymentResponse {
        private boolean success;
        private String md5;
        private String status;        // PAID / UNPAID / ERROR
        private BigDecimal amount;
        private String currency;
        private String from;
        /** Receiver account id (your linked merchant). */
        private String to;
        private String timestamp;
        /** Exact tx time in epoch millis. Aliased on the wire. */
        @com.fasterxml.jackson.annotation.JsonAlias({"created_at_ms", "createdAtMs"})
        private Long createdAtMs;
        private String error;

        // ── Real Bakong transaction context (only populated when PAID) ──
        // The upstream proxy adds these to /api/check_payment so admin
        // notifications can show the SHORT HASH that validates against
        // api-bakong.nbc.gov.kh, instead of md5[:8] which doesn't.
        /** Full Bakong transaction hash (~64 hex chars). */
        private String hash;
        /** Bank tran_id, e.g. "100FT37845835941". Aliased on the wire. */
        @com.fasterxml.jackson.annotation.JsonAlias({"external_ref", "externalRef", "tran_id"})
        private String externalRef;
        /** Remark / note attached to the payment. Aliased on the wire. */
        @com.fasterxml.jackson.annotation.JsonAlias({"description", "remark", "note"})
        private String description;

        public boolean isPaid() {
            return "PAID".equalsIgnoreCase(status);
        }
    }

    /**
     * Response from {@code /key_info} on the bot — the authoritative list
     * of banks registered against an sk_ key.
     *
     * <p>Live upstream contract (verified against
     * {@code https://apicheckpayment.onrender.com/key_info}):
     * <pre>
     *   200 OK { "valid": true,
     *            "registered_banks": ["aba","wing","acleda","bakong"],
     *            "merchant_name":    "HUT SOKSITCHEY" }
     *   200 OK { "valid": false,
     *            "reason": "not_found" | "format" | "no_banks_linked" }
     * </pre>
     *
     * <p>The legacy {@code success/registered/banks[]} shape some older
     * bridges return is also accepted via the {@code @JsonAlias} fallbacks
     * on each field, so this DTO works against both upstream variants.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KeyInfoResponse {
        /** Live upstream uses {@code valid}; older bridge used {@code success}. */
        @com.fasterxml.jackson.annotation.JsonAlias({"success"})
        private Boolean valid;

        /** Live upstream returns lowercase slugs directly. */
        @com.fasterxml.jackson.annotation.JsonProperty("registered_banks")
        private java.util.List<String> registeredBanksDirect;

        /** Legacy bridge nested objects under {@code banks}. */
        private java.util.List<KeyInfoBank> banks;

        /** Legacy {@code registered} flag. Newer upstream packs this into
         *  {@code valid} + non-empty {@code registered_banks}. */
        private Boolean registered;

        @com.fasterxml.jackson.annotation.JsonProperty("merchant_name")
        private String merchantName;

        private Integer total;
        private String error;
        private String reason;

        /** True when the upstream confirmed the key. */
        public boolean isSuccess() {
            return Boolean.TRUE.equals(valid);
        }

        /** True when the key has at least one bank linked. */
        public boolean isRegistered() {
            if (Boolean.TRUE.equals(registered)) return true;
            return !bankSlugs().isEmpty();
        }

        /** Authoritative lowercase slug list, regardless of upstream shape. */
        public java.util.List<String> bankSlugs() {
            java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
            // New shape: registered_banks: ["aba", ...]
            if (registeredBanksDirect != null) {
                for (String s : registeredBanksDirect) {
                    if (s != null && !s.isBlank()) out.add(s.toLowerCase());
                }
            }
            // Legacy shape: banks: [{bank: "aba", ...}, ...]
            if (banks != null) {
                for (KeyInfoBank b : banks) {
                    if (b != null && b.bank != null && !b.bank.isBlank()) {
                        out.add(b.bank.toLowerCase());
                    }
                }
            }
            return java.util.List.copyOf(out);
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KeyInfoBank {
        /** "aba" / "wing" / "acleda" / "bakong". */
        private String bank;
        /** Merchant display name returned for that bank. */
        @com.fasterxml.jackson.annotation.JsonProperty("account_name")
        private String accountName;
        /** True if the bot has a QR string registered for this slot. */
        @com.fasterxml.jackson.annotation.JsonProperty("has_qr")
        private boolean hasQr;
    }
}
