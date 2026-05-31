"""Webhook signature verification helper.

Use this on your server to confirm a callback genuinely came from
the KhmerBank gateway.
"""

from __future__ import annotations

import hashlib
import hmac


def verify_signature(secret: str, raw_body: str, signature_header: str) -> bool:
    """Return True if `signature_header` is a valid HMAC-SHA256 of `raw_body`."""
    if not signature_header:
        return False
    expected = hmac.new(
        secret.encode("utf-8"),
        raw_body.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    return hmac.compare_digest(expected, signature_header.lower())
