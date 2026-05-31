# KhmerBank Java SDK

Official Java client for the Khmer Bank Gateway — supports **ABA**, **Wing**, and **Bakong KHQR**.

## Install

```xml
<dependency>
  <groupId>com.khmerbank</groupId>
  <artifactId>khmerbank-sdk</artifactId>
  <version>1.0.0</version>
</dependency>
```

## Usage

```java
import com.khmerbank.sdk.KhmerBankClient;
import com.khmerbank.sdk.model.*;
import java.math.BigDecimal;

KhmerBankClient client = KhmerBankClient.builder()
        .apiKey(System.getenv("KHMERBANK_API_KEY"))
        .baseUrl("https://api.khmerbank.dev")
        .build();

QrCodeResponse qr = client.payments().generateQr(GenerateQrRequest.builder()
        .bankType(BankType.BAKONG)
        .amount(new BigDecimal("12.50"))
        .currency(Currency.USD)
        .description("Order #1234")
        .build());

System.out.println(qr.getQrPayload());

PaymentStatusResponse paid = client.payments()
        .waitForPayment(qr.getTransactionId(),
                java.time.Duration.ofMinutes(15),
                java.time.Duration.ofSeconds(3));
System.out.println("Paid: " + paid.isPaid());
```

## License

MIT — © KhmerBank
