package demo;

import com.khmerbank.sdk.KhmerBankClient;
import com.khmerbank.sdk.model.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Java SDK end-to-end demo.
 * Reads KHMERBANK_API_KEY and KHMERBANK_BASE_URL from env.
 */
public class JavaSdkDemo {

    public static void main(String[] args) {
        String apiKey  = System.getenv("KHMERBANK_API_KEY");
        String baseUrl = System.getenv().getOrDefault("KHMERBANK_BASE_URL", "http://localhost:8080");

        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("Set KHMERBANK_API_KEY first.");
            System.exit(1);
        }

        KhmerBankClient client = KhmerBankClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();

        System.out.println("=== Java SDK calling Java backend ===");

        List<MerchantResponse> merchants = client.merchants().list();
        System.out.println("\n[1] Merchants linked: " + merchants.size());
        for (MerchantResponse m : merchants) {
            System.out.printf("    %-6s · %s · %s%n",
                    m.getBankType(), m.getMerchantName(), m.getMerchantId());
        }

        BankType[] banks = { BankType.BAKONG, BankType.ABA, BankType.ACLEDA, BankType.WING };
        BigDecimal[] amounts = {
                new BigDecimal("12.50"),
                new BigDecimal("35.00"),
                new BigDecimal("8.75"),
                new BigDecimal("100.00")
        };

        System.out.println("\n[2] Generate KHQR for every bank:");
        for (int i = 0; i < banks.length; i++) {
            BankType b = banks[i];
            try {
                QrCodeResponse qr = client.payments().generateQr(GenerateQrRequest.builder()
                        .bankType(b)
                        .amount(amounts[i])
                        .currency(Currency.USD)
                        .description("Java SDK · " + b)
                        .reference("JV-" + b + "-001")
                        .expiresIn(900)
                        .build());

                System.out.printf("    %-6s -> %s%n", b, qr.getTransactionId());
                System.out.println("           " + qr.getQrPayload());

                PaymentStatusResponse status = client.payments().checkStatus(qr.getTransactionId());
                System.out.printf("           status=%s  paid=%s%n",
                        status.getStatus(), status.isPaid());

            } catch (Exception e) {
                System.out.printf("    %-6s -> ERROR: %s%n", b, e.getMessage());
            }
        }

        System.out.println("\nJava SDK end-to-end test passed.");
    }
}
