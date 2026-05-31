"""Example: generate a Bakong QR and wait for payment."""

import os
import sys

from khmerbank import BankType, Currency, KhmerBank


def main() -> None:
    api_key = os.environ.get("KHMERBANK_API_KEY")
    if not api_key:
        sys.exit("Please set KHMERBANK_API_KEY")

    client = KhmerBank(api_key=api_key, base_url="http://localhost:8080")

    qr = client.generate_qr(
        bank=BankType.BAKONG,
        amount="12.50",
        currency=Currency.USD,
        description="Order #1234",
        reference="ORDER-1234",
        expires_in=900,
    )

    print(f"✅ Transaction ID : {qr.transaction_id}")
    print(f"📱 QR payload     : {qr.qr_payload}")
    print(f"🖼️  QR image data : {qr.qr_image[:60]}...")
    print()
    print("⏳ Waiting for payment (max 5 min)...")

    status = client.wait_for_payment(qr.transaction_id, timeout=300, poll_interval=3)
    print(f"🎯 Final status: {status.status} (paid={status.paid})")
    if status.paid:
        print(f"💰 Bank reference: {status.bank_reference}")


if __name__ == "__main__":
    main()
