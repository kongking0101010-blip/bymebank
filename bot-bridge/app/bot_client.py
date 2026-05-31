"""HTTP client to the upstream apicheckpayment bot.

Owns httpx connection pool, retries on 5xx, and a 30s read timeout.
"""
from __future__ import annotations

import asyncio
import logging
import time
from contextlib import asynccontextmanager
from typing import Any

import httpx

from .config import settings

log = logging.getLogger("bot-bridge.client")

_RETRY_5XX = 2
_BACKOFF = 0.6

_client: httpx.AsyncClient | None = None


def get_client() -> httpx.AsyncClient:
    global _client
    if _client is None:
        _client = httpx.AsyncClient(
            base_url=settings.bot_url,
            timeout=httpx.Timeout(connect=15.0, read=30.0,
                                  write=30.0, pool=30.0),
            headers={"User-Agent": "khmerbank-bridge/1.0"},
        )
    return _client


@asynccontextmanager
async def lifespan(_app):
    yield
    if _client is not None:
        await _client.aclose()


async def _request(method: str, path: str, **kwargs: Any) -> httpx.Response:
    client = get_client()
    last_exc: Exception | None = None
    for attempt in range(_RETRY_5XX + 1):
        started = time.perf_counter()
        try:
            r = await client.request(method, path, **kwargs)
        except httpx.HTTPError as e:
            last_exc = e
            log.warning(
                "bot %s %s attempt=%d network_error=%s",
                method, path, attempt, e,
            )
            if attempt == _RETRY_5XX:
                raise
            await asyncio.sleep(_BACKOFF * (attempt + 1))
            continue
        elapsed_ms = int((time.perf_counter() - started) * 1000)
        log.info(
            "bot %s %s status=%d latency_ms=%d attempt=%d",
            method, path, r.status_code, elapsed_ms, attempt,
        )
        if r.status_code >= 500 and attempt < _RETRY_5XX:
            await asyncio.sleep(_BACKOFF * (attempt + 1))
            continue
        return r
    if last_exc:
        raise last_exc
    raise RuntimeError("unreachable")


async def issue_key(payload: dict[str, Any]) -> httpx.Response:
    body = {**payload, "secret": settings.issue_secret}
    return await _request("POST", "/api/external/issue_key", json=body)


async def generate_qr(payload: dict[str, Any]) -> httpx.Response:
    return await _request("POST", "/generate_qr", json=payload)


async def check_payment(md5: str, key: str) -> httpx.Response:
    return await _request("GET", "/api/check_payment",
                          params={"md5": md5, "key": key})


async def key_info(key: str) -> httpx.Response:
    """Look up the canonical bank list registered against an sk_ key.

    The bot returns:
      { "success": true, "registered": true, "total": 3,
        "banks": [{"bank":"aba","account_name":"...","has_qr":true}, ...] }
    """
    return await _request("GET", "/key_info", params={"key": key})
