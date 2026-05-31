# bot-bridge

Stateless FastAPI proxy that lets the KhmerBank Spring Boot backend talk
to the upstream `apicheckpayment` Telegram bot without ever seeing the
upstream `EXTERNAL_ISSUE_SECRET`.

## Why

- The bot already exists and works. We don't want to reimplement its
  bank-specific KHQR logic in Java.
- Spring sends signed-with-`X-Bridge-Token` requests to this bridge.
  This bridge knows the upstream secret and forwards calls.
- Customer keys (`sk_xxx`) flow back to Spring; the master secret stays
  inside this container.

## Endpoints

| Method | Path                    | Purpose                                  |
| ------ | ----------------------- | ---------------------------------------- |
| GET    | `/health`               | Liveness + config-status check           |
| POST   | `/bridge/issue_key`     | Mint an `sk_` key for a user             |
| POST   | `/bridge/generate_qr`   | Generate a payment QR for a customer     |
| GET    | `/bridge/check_payment` | Poll a payment by MD5                    |

All `/bridge/*` routes require `X-Bridge-Token: <BRIDGE_AUTH_TOKEN>`.

## Run locally

```bash
cd bot-bridge
cp .env.example .env
# edit .env and paste the real EXTERNAL_ISSUE_SECRET
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8090 --reload
```

Then from another terminal:

```bash
TOKEN=<BRIDGE_AUTH_TOKEN value>

curl http://localhost:8090/health

curl -X POST http://localhost:8090/bridge/issue_key \
  -H "X-Bridge-Token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "external_id": "user_42",
    "merchant_name": "Demo Cafe",
    "days": 30,
    "banks": [
      {"bank":"bakong", "merchant_id":"alice@aclb", "phone":"012345678"}
    ]
  }'
```

## Run with Docker

```bash
docker build -t khmerbank/bot-bridge .
docker run --rm -p 8090:8090 --env-file .env khmerbank/bot-bridge
```

The `docker-compose.yml` at the repo root already wires this up.
