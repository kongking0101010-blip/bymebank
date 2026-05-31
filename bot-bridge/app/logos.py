"""In-memory + on-disk cache for branding logos.

Two paths:

1. **Bot mode** — when ``BOT_ADMIN_KEY`` is set, fetch from
   ``GET /r-admin/logos?k=<key>`` on the upstream Telegram bot. 60s TTL.

2. **Local mode** — when the upstream endpoint doesn't exist (HTTP 404 / 401),
   fall back to a small JSON store at ``BRIDGE_LOGOS_FILE`` (default
   ``./logos_store.json``). Admins can write logos via
   ``POST /bridge/logos`` (auth-gated, same X-Bridge-Token).

Either way the read API is the same:
    GET  /bridge/logos              → { ok, logos: { slot: dataUrl, ... } }
    POST /bridge/logos              → upsert one slot   (local mode)
    DELETE /bridge/logos/<slot>     → remove one slot   (local mode)
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .bot_client import get_client
from .config import settings

log = logging.getLogger("bot-bridge.logos")

ALLOWED_SLOTS = frozenset({"brand", "aba", "wing", "acleda", "bakong"})
MAX_LOGO_BYTES = 500 * 1024  # 500 KB per logo
DATA_URL_PREFIX = "data:image/"

CACHE_TTL_SECONDS = 60

LOCAL_STORE_PATH = Path(os.getenv("BRIDGE_LOGOS_FILE", "logos_store.json"))


@dataclass
class _Snapshot:
    fetched_at: float = 0.0
    logos: dict[str, str] = field(default_factory=dict)
    error: str | None = None
    source: str = "none"  # "bot" | "local" | "none"


_lock = asyncio.Lock()
_snapshot: _Snapshot = _Snapshot()


def _validate(raw: dict[str, Any]) -> dict[str, str]:
    """Filter out anything that isn't a valid data:image/* URL ≤ 500 KB."""
    if not isinstance(raw, dict):
        return {}
    out: dict[str, str] = {}
    for slot, value in raw.items():
        if slot not in ALLOWED_SLOTS:
            continue
        if not isinstance(value, str):
            continue
        if not value.startswith(DATA_URL_PREFIX):
            log.warning("skip slot=%s reason=not_data_url", slot)
            continue
        if len(value.encode("utf-8")) > MAX_LOGO_BYTES:
            log.warning("skip slot=%s reason=too_large bytes=%d",
                        slot, len(value.encode("utf-8")))
            continue
        out[slot] = value
    return out


# ── Local on-disk store (used when the bot doesn't expose /r-admin/logos) ──

def _load_local() -> dict[str, str]:
    try:
        if not LOCAL_STORE_PATH.exists():
            return {}
        body = json.loads(LOCAL_STORE_PATH.read_text(encoding="utf-8"))
        return _validate(body if isinstance(body, dict) else {})
    except Exception as e:
        log.warning("local logos read failed: %s", e)
        return {}


def _save_local(logos: dict[str, str]) -> None:
    try:
        LOCAL_STORE_PATH.write_text(
            json.dumps(logos, ensure_ascii=False),
            encoding="utf-8",
        )
    except Exception as e:
        log.warning("local logos write failed: %s", e)


def upsert_local(slot: str, data_url: str) -> dict[str, str]:
    """Add or replace a single slot in the local store. Validates first."""
    if slot not in ALLOWED_SLOTS:
        raise ValueError(f"unknown slot: {slot}")
    cleaned = _validate({slot: data_url})
    if slot not in cleaned:
        raise ValueError("invalid data: URL or oversize image")
    current = _load_local()
    current[slot] = cleaned[slot]
    _save_local(current)
    # Invalidate cache so next read picks the new value.
    _invalidate()
    return current


def remove_local(slot: str) -> dict[str, str]:
    if slot not in ALLOWED_SLOTS:
        raise ValueError(f"unknown slot: {slot}")
    current = _load_local()
    current.pop(slot, None)
    _save_local(current)
    _invalidate()
    return current


def _invalidate() -> None:
    global _snapshot
    _snapshot = _Snapshot()


# ── Bot fetch ─────────────────────────────────────────────────────────────

async def _fetch_from_bot() -> dict[str, str]:
    """Hit the upstream Telegram bot. Raises if the endpoint is missing."""
    admin_key = settings.bot_admin_key
    if not admin_key:
        raise RuntimeError("BOT_ADMIN_KEY not configured")

    client = get_client()
    r = await client.get("/r-admin/logos", params={"k": admin_key})
    if r.status_code in (401, 403):
        raise RuntimeError(f"upstream auth failed (HTTP {r.status_code})")
    if r.status_code == 404:
        # Endpoint not deployed — caller will fall back to local.
        raise FileNotFoundError("/r-admin/logos not available on upstream bot")
    if r.status_code != 200:
        raise RuntimeError(f"upstream HTTP {r.status_code}: {r.text[:120]}")
    body = r.json()
    if not body.get("ok"):
        raise RuntimeError(f"upstream not ok: {body.get('error', 'unknown')}")
    return _validate(body.get("logos") or {})


# ── Public API ────────────────────────────────────────────────────────────

async def get_logos(force_refresh: bool = False) -> dict[str, str]:
    """Return the cached logo map, refreshing if stale or forced.

    Order:
      1. If BOT_ADMIN_KEY is set, try the upstream bot.
      2. On 404 / RuntimeError, fall back to the local JSON store.
      3. On any error, keep serving the last good snapshot.
    """
    global _snapshot
    now = time.time()
    fresh = (now - _snapshot.fetched_at) < CACHE_TTL_SECONDS

    if fresh and not force_refresh and _snapshot.error is None:
        return _snapshot.logos

    async with _lock:
        # Re-check after acquiring the lock — another caller may have refreshed.
        now = time.time()
        fresh = (now - _snapshot.fetched_at) < CACHE_TTL_SECONDS
        if fresh and not force_refresh and _snapshot.error is None:
            return _snapshot.logos

        # Try bot first if configured.
        if settings.bot_admin_key:
            try:
                logos = await _fetch_from_bot()
                _snapshot = _Snapshot(time.time(), logos, None, "bot")
                log.info("logos refreshed from bot slots=%s", sorted(logos.keys()))
                return logos
            except FileNotFoundError as e:
                log.info("bot logos endpoint missing — falling back to local: %s", e)
            except Exception as e:
                log.warning("bot logos fetch failed — falling back to local: %s", e)

        # Local file store fallback.
        try:
            logos = _load_local()
            _snapshot = _Snapshot(time.time(), logos, None, "local")
            log.info("logos loaded from local slots=%s", sorted(logos.keys()))
            return logos
        except Exception as e:
            _snapshot = _Snapshot(time.time(), _snapshot.logos, str(e), _snapshot.source)
            log.warning("local logos read failed (serving stale): %s", e)
            return _snapshot.logos


def cache_meta() -> dict[str, Any]:
    return {
        "fetched_at":  _snapshot.fetched_at,
        "age_seconds": int(time.time() - _snapshot.fetched_at) if _snapshot.fetched_at else None,
        "ttl_seconds": CACHE_TTL_SECONDS,
        "slots":       sorted(_snapshot.logos.keys()),
        "error":       _snapshot.error,
        "source":      _snapshot.source,
        "local_path":  str(LOCAL_STORE_PATH.resolve()),
    }


def reset_for_tests() -> None:
    """Test helper — wipe the in-memory cache."""
    _invalidate()
