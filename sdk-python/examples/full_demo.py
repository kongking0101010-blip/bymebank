"""End-to-end demo using the Python SDK against a running gateway.

Steps:
1. Register a fresh dev account (raw HTTP — SDK doesn't expose auth yet).
2. Use the JWT to create an API key and link merchants for ABA, ACLEDA, Wing, Bakong.
3. Switch to the SDK with the API key and generate a KHQR for each bank.
4. Check the payment status of each.
"""

from __future__ import annotations

import random
import sys

import requests

from khmerbank import (
    BankType,
    Currency,
    KhmerBank,
    KhmerBankAPIError,
)

BASE = "http://localhost:8080"


def step(n: int, msg: str) -> None:
    print(f"\n[{n}] {msg}")


def main() -> None:
    email = f"py-demo-{random.randint(1000, 99999)}@khmerbank.test"
    step(1, f"Register {email}")
    r = requests.post(
        f"{BASE}/api/v1/auth/register",
        json={
            "email": email,
            "password": "SuperSecret123",
            "fullName": "Python Demo",
            "company": "Demo Co",
        },
        timeout=10,
    )
    r.raise_for_status()
    jwt = r.json()["data"]["accessToken"]
    auth_h = {"Authorization": f"Bearer {jwt}"}
    print(f"    JWT issued ({len(jwt)} chars)")

    step(2, "Create API key")
    r = requests.post(
        f"{BASE}/api/v1/api-keys",
        json={"name": "Python SDK key"},
        headers=auth_h,
        timeout=10,
    )
    r.raise_for_status()
    api_key = r.json()["data"]["key"]
    print(f"    API key: {api_key[:14]}... ({len(api_key)} chars)")

    step(3, "Link 4 merchants (ABA, ACLEDA, Wing, Bakong)")
    merchants = [
        {"bankType": "ABA",    "merchantName": "ABA Coffee",    "merchantId": "ABAPAY-123"},
        {"bankType": "ACLEDA", "merchantName": "ACLEDA Coffee", "merchantId": "ACLEDA-456"},
        {"bankType": "WING",   "merchantName": "Wing Coffee",   "merchantId": "WING-789",  "accountNumber": "012345678"},
        {"bankType": "BAKONG", "merchantName": "Bakong Coffee", "merchantId": "demo@aclb"},
    ]
    for m in merchants:
        r = requests.post(f"{BASE}/api/v1/merchants", json=m, headers=auth_h, timeout=10)
        r.raise_for_status()
        d = r.json()["data"]
        print(f"    ✓ {d['bankType']:6} → {d['merchantName']} ({d['merchantId']})")

    step(4, "Generate KHQR via SDK for every bank")
    client = KhmerBank(api_key=api_key, base_url=BASE)
    for bank, amount in [
        (BankType.BAKONG, "12.50"),
        (BankType.ABA,    "35.00"),
        (BankType.ACLEDA, "8.75"),
        (BankType.WING,   "100.00"),
    ]:
        try:
            qr = client.generate_qr(
                bank=bank,
                amount=amount,
                currency=Currency.USD,
                description=f"Test order via {bank.value}",
                reference=f"PY-{bank.value}-001",
                expires_in=900,
            )
            print(f"    ✓ {bank.value:6} → {qr.transaction_id}")
            print(f"             payload: {qr.qr_payload}")

            status = client.check_status(qr.transaction_id)
            print(f"             status : {status.status.value} (paid={status.paid})")
        except KhmerBankAPIError as e:
            print(f"    ✗ {bank.value}: [{e.status_code}] {e.message}")

    step(5, "List merchants & summary")
    ms = client.list_merchants()
    for m in ms:
        print(f"    {m.bank_type.value:6} · {m.merchant_name} · {m.merchant_id}")

    print("\n✅ Python SDK end-to-end test passed.")


if __name__ == "__main__":
    try:
        main()
    except requests.HTTPError as e:
        sys.exit(f"❌ HTTP error: {e.response.status_code} {e.response.text}")
    except KhmerBankAPIError as e:
        sys.exit(f"❌ SDK error: {e}")
