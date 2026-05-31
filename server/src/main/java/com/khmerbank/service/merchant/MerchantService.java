package com.khmerbank.service.merchant;

import com.khmerbank.dto.request.LinkMerchantRequest;
import com.khmerbank.dto.response.MerchantResponse;
import com.khmerbank.exception.ApiException;
import com.khmerbank.model.Merchant;
import com.khmerbank.model.User;
import com.khmerbank.model.enums.BankType;
import com.khmerbank.repository.MerchantRepository;
import com.khmerbank.security.AesEncryptor;
import com.khmerbank.service.qrcode.QrDecoderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final AesEncryptor encryptor;
    private final QrDecoderService qrDecoder;

    @Transactional
    public MerchantResponse linkMerchant(User user, LinkMerchantRequest req) {
        validateForBank(req);

        Merchant m = Merchant.builder()
                .user(user)
                .bankType(req.getBankType())
                .merchantName(req.getMerchantName())
                .merchantCity(req.getMerchantCity() == null ? "Phnom Penh" : req.getMerchantCity())
                .merchantId(req.getMerchantId())
                .merchantLink(req.getMerchantLink())
                .accountNumber(req.getAccountNumber())
                .encryptedSecret(encryptor.encrypt(req.getSecret()))
                .verified(false)
                .build();
        merchantRepository.save(m);
        return toResponse(m);
    }

    /**
     * Link a merchant by uploading the bank's QR (image OR raw string).
     * The decoded data is used to fill in account / name / phone — the BANK
     * the merchant gets stored under is whatever the user picked. We refuse
     * the upload when the QR was issued by a different bank than the slot
     * the user is filling, so an ABA upload can't accidentally store a Wing
     * QR string.
     *
     * <p>BAKONG is a special case: it's the unified KHQR network, so any
     * Bakong-network handle ({@code …@aclb}, {@code …@aba}, {@code …@wing})
     * is acceptable for the BAKONG slot.
     */
    @Transactional
    public MerchantResponse linkFromQr(User user, BankType expected, byte[] qrImage,
                                       String qrString,
                                       String merchantId, String phone, String overrideName) {
        QrDecoderService.DecodedQr decoded;
        String rawQrString = qrString;
        if (qrString != null && !qrString.isBlank()) {
            decoded = qrDecoder.decodeString(qrString);
        } else if (qrImage != null && qrImage.length > 0) {
            decoded = qrDecoder.decodeImage(qrImage);
            rawQrString = decoded.getPayload();
        } else {
            throw ApiException.badRequest("NO_QR",
                    "Provide a QR image or a raw qrString (starts with 0002…)");
        }

        // ── Bank-mismatch guard ──
        // The QR's detected bank MUST match the slot the user is filling.
        // Bakong accepts any Bakong-network handle since that's the whole
        // network's purpose.
        BankType detected = decoded.getBank();
        if (expected != BankType.BAKONG && detected != null && detected != expected) {
            throw ApiException.badRequest(
                    "BANK_MISMATCH",
                    String.format(
                        "This QR is a %s code. You picked %s — please upload a %s QR " +
                        "or switch the bank.",
                        detected, expected, expected));
        }

        Merchant.MerchantBuilder b = Merchant.builder()
                .user(user)
                .bankType(expected)
                .merchantName(overrideName != null && !overrideName.isBlank()
                        ? overrideName
                        : (decoded.getMerchantName().isBlank() ? "MERCHANT" : decoded.getMerchantName()))
                .merchantCity(decoded.getMerchantCity().isBlank()
                        ? "Phnom Penh" : decoded.getMerchantCity())
                .verified(true);

        // Persist the raw QR string (encrypted) so the bot-bridge can replay it
        // later when minting an sk_ key for ABA / ACLEDA / Wing.
        if (rawQrString != null && !rawQrString.isBlank()) {
            try { b.encryptedSecret(encryptor.encrypt(rawQrString)); }
            catch (Exception ignore) { /* encryption optional */ }
        }

        switch (expected) {
            case ABA -> {
                if (merchantId == null || merchantId.isBlank())
                    throw ApiException.badRequest("MISSING_MERCHANT_ID",
                            "ABA requires the PayWay merchantId");
                String id = normalizeAbaMerchantId(merchantId);
                b.merchantId(id);
                b.merchantLink("https://link.payway.com.kh/" + id);
                // Account from the QR (e.g. "yourname@aba")
                b.accountNumber(decoded.getMerchantAccount());
            }
            case BAKONG -> {
                String id = (merchantId == null || merchantId.isBlank())
                        ? decoded.getMerchantAccount() : merchantId;
                if (id == null || !id.contains("@"))
                    throw ApiException.badRequest("INVALID_BAKONG_ID",
                            "Bakong merchantId must look like user@bank");
                b.merchantId(id);
                b.accountNumber(phone != null && !phone.isBlank()
                        ? phone : decoded.getPhone());
            }
            case WING, ACLEDA -> {
                String acct = decoded.getMerchantAccount();
                if (acct == null || acct.isBlank()) {
                    throw ApiException.badRequest("NO_ACCOUNT",
                            "Could not extract an account number from the QR");
                }
                b.merchantId(acct);
                b.accountNumber(acct);
            }
        }

        // Upsert: if the user already linked this bank, replace it instead
        // of creating a duplicate row. This is what was causing the bank
        // picker to show "ABA · ABA · ABA · ACLEDA …" — every wizard run
        // was creating a fresh merchant row.
        merchantRepository.findByUserAndBankTypeAndActiveTrue(user, expected)
                .ifPresent(merchantRepository::delete);

        Merchant saved = merchantRepository.save(b.build());
        return toResponse(saved);
    }

    /**
     * Bakong shortcut: user provides merchantId + phone, no QR needed.
     */
    @Transactional
    public MerchantResponse linkBakong(User user, String merchantId, String phone,
                                       String merchantName) {
        if (merchantId == null || !merchantId.contains("@")) {
            throw ApiException.badRequest("INVALID_BAKONG_ID",
                    "Bakong merchantId must look like user@bank");
        }
        if (phone == null || phone.isBlank()) {
            throw ApiException.badRequest("MISSING_PHONE", "Phone number is required for Bakong");
        }
        // Upsert — replace any existing Bakong link for this user.
        merchantRepository.findByUserAndBankTypeAndActiveTrue(user, BankType.BAKONG)
                .ifPresent(merchantRepository::delete);
        Merchant saved = merchantRepository.save(Merchant.builder()
                .user(user)
                .bankType(BankType.BAKONG)
                .merchantName(merchantName == null || merchantName.isBlank()
                        ? "BAKONG MERCHANT" : merchantName)
                .merchantCity("Phnom Penh")
                .merchantId(merchantId)
                .accountNumber(phone)
                .verified(true)
                .build());
        return toResponse(saved);
    }

    public List<MerchantResponse> listMerchants(User user) {
        return merchantRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(this::toResponse)
                .toList();
    }

    public Merchant resolveMerchant(User user, UUID merchantId, BankType bankType) {
        if (merchantId != null) {
            Merchant m = merchantRepository.findByIdAndUser(merchantId, user)
                    .orElseThrow(() -> ApiException.notFound("MERCHANT_NOT_FOUND", "Merchant not found"));
            if (m.getBankType() != bankType) {
                throw ApiException.badRequest("BANK_MISMATCH",
                        "Merchant bank does not match requested bank");
            }
            return m;
        }
        return merchantRepository.findByUserAndBankTypeAndActiveTrue(user, bankType)
                .orElseThrow(() -> ApiException.notFound("NO_MERCHANT_LINKED",
                        "Link a " + bankType + " merchant before generating QR"));
    }

    @Transactional
    public void deleteMerchant(User user, UUID id) {
        Merchant m = merchantRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> ApiException.notFound("MERCHANT_NOT_FOUND", "Not found"));
        merchantRepository.delete(m);
    }

    private void validateForBank(LinkMerchantRequest req) {
        switch (req.getBankType()) {
            case ABA -> {
                if (req.getMerchantId() == null || req.getMerchantId().isBlank())
                    throw ApiException.badRequest("INVALID_INPUT", "ABA requires merchantId");
            }
            case ACLEDA -> {
                if (req.getMerchantId() == null || req.getMerchantId().isBlank())
                    throw ApiException.badRequest("INVALID_INPUT",
                            "ACLEDA requires merchantId");
            }
            case WING -> {
                if (req.getAccountNumber() == null || req.getAccountNumber().isBlank())
                    throw ApiException.badRequest("INVALID_INPUT",
                            "Wing requires accountNumber");
            }
            case BAKONG -> {
                if (req.getMerchantId() == null || !req.getMerchantId().contains("@"))
                    throw ApiException.badRequest("INVALID_INPUT",
                            "Bakong merchantId must look like user@bank");
            }
        }
    }

    private MerchantResponse toResponse(Merchant m) {
        return MerchantResponse.builder()
                .id(m.getId())
                .bankType(m.getBankType())
                .merchantName(m.getMerchantName())
                .merchantCity(m.getMerchantCity())
                .merchantId(m.getMerchantId())
                .merchantLink(m.getMerchantLink())
                .accountNumber(m.getAccountNumber())
                .verified(m.isVerified())
                .active(m.isActive())
                .createdAt(m.getCreatedAt())
                .build();
    }

    /**
     * Normalize either {@code ABAPAYAE...} or
     * {@code https://link.payway.com.kh/ABAPAYAE...} into the bare id.
     */
    private String normalizeAbaMerchantId(String input) {
        String v = input.trim();
        if (v.contains("payway.com.kh")) {
            int slash = v.replaceAll("/$", "").lastIndexOf('/');
            if (slash >= 0 && slash < v.length() - 1) {
                v = v.substring(slash + 1).replaceAll("/$", "");
            }
        }
        return v;
    }
}
