"""Example: Flask webhook handler that verifies the signature."""

import os
from flask import Flask, request, jsonify

from khmerbank.webhook import verify_signature

app = Flask(__name__)
WEBHOOK_SECRET = os.environ.get("KHMERBANK_WEBHOOK_SECRET", "")


@app.route("/webhook/khmerbank", methods=["POST"])
def webhook():
    raw = request.get_data(as_text=True)
    sig = request.headers.get("X-KhmerBank-Signature", "")

    if not verify_signature(WEBHOOK_SECRET, raw, sig):
        return jsonify({"error": "invalid signature"}), 401

    payload = request.get_json()
    print(f"✅ Payment received: {payload}")
    # TODO: update your order DB here
    return jsonify({"ok": True})


if __name__ == "__main__":
    app.run(port=4000)
