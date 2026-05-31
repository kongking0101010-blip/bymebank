import com.khmerbank.sdk.KhmerBankClient;
import com.khmerbank.sdk.model.*;

import java.math.BigDecimal;
import java.time.Duration;

public class GenerateQrExample {
    public static void main(String[] args) {
        KhmerBankClient client = KhmerBankClient.builder()
                .apiKey(System.getenv("KHMERBANK_API_KEY"))
                .baseUrl("http://localhost:8080")
                .build();

        QrCodeResponse qr = client.payments().generateQr(GenerateQrRequest.builder()
                .bankType(BankType.BAKONG)
                .amount(new BigDecimal("12.50"))
                .currency(Currency.USD)
                .description("Order #1234")
                .reference("ORDER-1234")
                .expiresIn(900)
                .build());

        System.out.println("Transaction ID: " + qr.getTransactionId());
        System.out.println("QR Payload    : " + qr.getQrPayload());
        System.out.println("QR Image (b64): " + qr.getQrImage().substring(0, 60) + "...");

        PaymentStatusResponse paid = client.payments()
                .waitForPayment(qr.getTransactionId(), Duration.ofMinutes(15), Duration.ofSeconds(3));

        System.out.println("Final status: " + paid.getStatus());
    }
}
