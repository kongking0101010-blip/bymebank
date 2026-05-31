"""Auth helpers for the bridge."""
from __future__ import annotations

import hmac

from fastapi import Header, HTTPException, status

from .config import settings


def mask_key(key: str) -> str:
    """Return e.g. ``sk_a…f9b3`` for logging."""
    if not key:
        return "(empty)"
    if len(key) <= 8:
        return key[0] + "…"
    return f"{key[:4]}…{key[-4:]}"


async def require_bridge_token(
    x_bridge_token: str | None = Header(default=None, alias="X-Bridge-Token"),
) -> None:
    """Enforce the X-Bridge-Token header."""
    if not settings.bridge_token:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Bridge not configured (BRIDGE_AUTH_TOKEN missing)",
        )
    if not x_bridge_token or not hmac.compare_digest(
        x_bridge_token, settings.bridge_token
    ):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid bridge token",
        )
