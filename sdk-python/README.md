# KhmerBank Python SDK

Official Python client for the Khmer Bank Gateway — supports **ABA**, **Wing**, and **Bakong KHQR**.

## Install

```bash
pip install khmerbank
```

## Usage

```python
from khmerbank import KhmerBank, BankType, Currency

client = KhmerBank(api_key="kb_xxxxxxxxxxxxxxxx")

qr = client.generate_qr(
    bank=BankType.BAKONG,
    amount="12.50",
    currency=Currency.USD,
    description="Order #1234",
    reference="ORDER-1234",
    expires_in=900,  # seconds
)
print(qr.qr_payload)
print(qr.qr_image)         # data:image/png;base64,...

status = client.wait_for_payment(qr.transaction_id, timeout=900)
print("Paid:", status.paid)
```

### Webhook verification

```python
from flask import Flask, request, jsonify
from khmerbank import verify_signature

app = Flask(__name__)
SECRET = "your-webhook-secret"

@app.post("/webhook")
def hook():
    raw = request.get_data(as_text=True)
    sig = request.headers.get("X-KhmerBank-Signature", "")
    if not verify_signature(SECRET, raw, sig):
        return jsonify(error="invalid signature"), 401
    return jsonify(ok=True)
```

### Linking merchants

```python
client.link_merchant(
    bank=BankType.ABA,
    merchant_name="My Coffee Shop",
    merchant_id="ABAPAY-MERCHANT-123",
    account_number="000123456",
)
```

## License

MIT — © KhmerBank
