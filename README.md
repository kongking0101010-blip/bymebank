# 🏦 KhmerBank — Cambodia's Modern Payment Gateway

> One API for ABA · ACLEDA · Wing · Bakong. Generate KHQR codes, accept payments, ship faster.

KhmerBank is an end-to-end payment gateway built for Cambodia.
Developers buy an API key, link their merchant accounts, and use our
Java or Python SDK to generate KHQR codes and check payment status —
across **ABA**, **ACLEDA**, **Wing**, and **Bakong**.

---

## ✨ Features

- 🔑 **API key auth** — secure, hashed, scoped, revocable.
- 🧾 **KHQR / EMV-MPM** — fully spec-compliant QR codes (CRC16, tags 00–63).
- 🏦 **3 banks supported** — ABA PayWay, Wing, Bakong KHQR.
- 💸 **Subscription billing** — Free, Basic, Pro, Enterprise.
- 🔁 **Webhooks** — HMAC-signed payment notifications.
- 📊 **Beautiful dashboard** — built with React, Vite, Tailwind, Framer Motion.
- 🐍🟦 **Official SDKs** — Java + Python with typed responses and helpers.

---

## 🗂 Project layout

```
khmer-bank-gateway/
├── server/             Spring Boot 3 + Java 21 backend
├── sdk-java/           Official Java client SDK
├── sdk-python/         Official Python client SDK
├── admin-dashboard/    React + Vite + Tailwind dashboard
├── docker/             docker-compose for local stack
├── .env.example        Sample environment variables
└── README.md
```

---

## 🚀 Quick start

### 1. Boot the stack with Docker

Postgres (default):

```bash
cp .env.example .env
docker compose -f docker/docker-compose.yml up --build
```

Oracle 23ai:

```bash
cp .env.example .env
docker compose -f docker/docker-compose.oracle.yml up --build
```

- Backend → http://localhost:8080
- Dashboard → http://localhost:5173
- Swagger → http://localhost:8080/swagger-ui.html

### 2. Run backend without Docker

```bash
cd server
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```
The dev profile uses an in-memory H2 database (no Postgres or Oracle required).

To run against your own Oracle instance:

```bash
SPRING_PROFILES_ACTIVE=oracle \
DB_URL=jdbc:oracle:thin:@//host:1521/PDB \
DB_USER=KHMERBANK DB_PASS=secret \
mvn spring-boot:run
```

See [`docs/ORACLE.md`](docs/ORACLE.md) for full Oracle setup, including
Autonomous Database (Wallet) instructions.

### 3. Run the dashboard

```bash
cd admin-dashboard
npm install
npm run dev
```

---

## 🔑 Using the API

### Java SDK

```java
KhmerBankClient client = KhmerBankClient.builder()
    .apiKey(System.getenv("KHMERBANK_API_KEY"))
    .build();

QrCodeResponse qr = client.payments().generateQr(GenerateQrRequest.builder()
    .bankType(BankType.BAKONG)
    .amount(new BigDecimal("12.50"))
    .currency(Currency.USD)
    .description("Order #1234")
    .build());

System.out.println(qr.getQrPayload());
```

### Python SDK

```python
from khmerbank import KhmerBank, BankType, Currency

client = KhmerBank(api_key="kb_xxxxxxxxxxxxxxxx")
qr = client.generate_qr(
    bank=BankType.BAKONG,
    amount="12.50",
    currency=Currency.USD,
    description="Order #1234",
)
print(qr.qr_payload)
status = client.wait_for_payment(qr.transaction_id, timeout=900)
print("Paid:", status.paid)
```

---

## 🧱 Architecture

```
┌──────────────┐   X-API-Key    ┌─────────────────────┐  HTTPS   ┌──────────────────┐
│  Your App    │ ─────────────► │ KhmerBank Gateway   │ ───────► │ ABA / Wing /     │
│  (Java/Py)   │                │ (Spring Boot · JVM) │          │ Bakong APIs      │
└──────┬───────┘                │                     │ ◄─ webhook                  │
       │                        │ • Auth + API keys   │
       │   Webhook (signed)     │ • KHQR + CRC16      │
       └◄──────────────────────►│ • Quotas + billing  │
                                │ • Postgres + Redis  │
                                └─────────────────────┘
```

### Bridge to apicheckpayment Telegram bot

For real-time multi-bank payment monitoring (ABA / ACLEDA / Wing / Bakong) we
forward through a small Python micro-service called **bot-bridge**. Spring
never sees the upstream `EXTERNAL_ISSUE_SECRET` — only the bridge does.

```
Browser ──► Spring Boot ──[X-Bridge-Token]──► bot-bridge (FastAPI :8090)
                                                      │
                                                      └─[secret]──► apicheckpayment.onrender.com
```

| Hop                       | Auth                              | Owns                                           |
|---------------------------|-----------------------------------|------------------------------------------------|
| Browser → Spring          | JWT cookie                        | User profile, API key DB rows                  |
| Spring → bot-bridge       | `X-Bridge-Token: $BRIDGE_AUTH_TOKEN` | Per-user `sk_` issuance, QR mint, payment poll |
| bot-bridge → bot          | `secret: $EXTERNAL_ISSUE_SECRET`  | bank logic, MD5 ↔ payment matching             |

**Required env vars:**

| Var                       | Where it lives        | Example                                       |
|---------------------------|-----------------------|-----------------------------------------------|
| `BRIDGE_URL`              | Spring                | `http://bot-bridge:8090`                      |
| `BRIDGE_AUTH_TOKEN`       | Spring **and** bridge | `secrets.token_urlsafe(32)`                   |
| `APICHECKING_BOT_URL`     | bridge only           | `https://apicheckpayment.onrender.com`        |
| `EXTERNAL_ISSUE_SECRET`   | bridge **and** Render | `secrets.token_urlsafe(32)`                   |

**Run the bridge locally:**

```bash
cd bot-bridge
cp .env.example .env        # then fill in real secrets
pip install -r requirements.txt
python -m uvicorn app.main:app --host 0.0.0.0 --port 8090
```

**End-to-end smoke test:**

```bash
python bot-bridge/test_buy_key_wizard.py \
       --bridge http://localhost:8090 \
       --merchant "Demo Cafe" \
       --bakong-id alice@aclb --phone 0123456789
```

The script prints a real `sk_` key, mints a 0.01 KHR Bakong test QR, and
polls `/bridge/check_payment` once. Expected end state: `status=UNPAID`
(unless you actually pay the QR).

**Endpoints exposed by the bridge:**

| Method | Path                       | Forwards to                              |
|--------|----------------------------|------------------------------------------|
| GET    | `/health`                  | (local)                                  |
| POST   | `/bridge/issue_key`        | `POST /api/external/issue_key`           |
| POST   | `/bridge/generate_qr`      | `POST /generate_qr`                      |
| GET    | `/bridge/check_payment`    | `GET /api/check_payment`                 |

**On the Spring side** the controller `UpstreamKeyController` exposes:

| Method | Path                                       | Purpose                                    |
|--------|--------------------------------------------|--------------------------------------------|
| GET    | `/api/v1/me/upstream-key?reveal=…`         | Read masked / revealed key                 |
| POST   | `/api/v1/me/upstream-key/refresh`          | Mint or rotate the user's `sk_` key        |
| DELETE | `/api/v1/me/upstream-key`                  | Soft-revoke                                |
| POST   | `/api/v1/me/upstream-key/test`             | 0.01 KHR Bakong sanity charge              |
| POST   | `/api/v1/me/upstream-key/payment-qr`       | Generic payment QR                         |
| GET    | `/api/v1/me/upstream-key/check-payment`    | Payment status by MD5                      |

Reliability: `issueKey` is `@Retryable(maxAttempts=3, backoff=500ms→8s)`.
`generatePaymentQr` and `checkPayment` are user-driven and never retried.

---

## 🔒 Security

- BCrypt for user passwords (cost 12)
- SHA-256 for API key storage (lookup by hash, never store raw)
- AES-256-GCM for per-merchant secrets at rest
- HMAC-SHA-256 for webhook signing
- JWT (HS256) for dashboard sessions
- Rate-limit per API key + plan quota

---

## 📜 License

Proprietary — © KhmerBank, Phnom Penh.
