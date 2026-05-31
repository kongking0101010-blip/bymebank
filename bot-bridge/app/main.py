"""FastAPI bridge between Spring Boot and the apicheckpayment Telegram bot.

Routes:
    GET  /health
    POST /bridge/issue_key
    POST /bridge/generate_qr
    GET  /bridge/check_payment

Every /bridge/* route requires header X-Bridge-Token == BRIDGE_AUTH_TOKEN.
The upstream EXTERNAL_ISSUE_SECRET never leaves this process.
"""
from __future__ import annotations

import logging
import time
from typing import Any
 
from fastapi import Depends, FastAPI, HTTPException, Request, status
from fastapi.responses import JSONResponse

from .bot_client import (
    check_payment as bot_check_payment,
    generate_qr as bot_generate_qr,
    issue_key as bot_issue_key,
    key_info as bot_key_info,
    lifespan,
)
from .config import configure_logging, settings
from .logos import (
    cache_meta as logos_cache_meta,
    get_logos,
    upsert_local,
    remove_local,
)
from .schemas import (
    CheckPaymentParams,
    GenerateQrRequest,
    IssueKeyRequest,
)
from .security import mask_key, require_bridge_token

configure_logging()
log = logging.getLogger("bot-bridge")

# Production-readiness gate: refuse to start with placeholder secrets.
# Bypass for local dev by setting BRIDGE_ALLOW_PLACEHOLDER=1.
settings.assert_production_ready()

app = FastAPI(
    title="KhmerBank ↔ apicheckpayment bridge",
    version="1.0.0",
    description=(
        "Stateless proxy between the KhmerBank Spring Boot backend and "
        "the apicheckpayment Telegram bot."
    ),
    lifespan=lifespan,
)


# ── access log middleware ────────────────────────────────────────
@app.middleware("http")
async def access_log(request: Request, call_next):
    started = time.perf_counter()
    response = await call_next(request)
    elapsed_ms = int((time.perf_counter() - started) * 1000)
    log.info(
        "%s %s status=%d latency_ms=%d client=%s",
        request.method,
        request.url.path,
        response.status_code,
        elapsed_ms,
        request.client.host if request.client else "-",
    )
    return response


@app.get("/health")
@app.get("/healthz")
async def health() -> dict[str, Any]:
    return {
        "ok": True,
        "configured": settings.configured,
        "bot_url": settings.bot_url,
    }


# ── 1. Issue key ─────────────────────────────────────────────────
@app.post("/bridge/issue_key", dependencies=[Depends(require_bridge_token)])
async def issue_key_route(req: IssueKeyRequest) -> JSONResponse:
    if not settings.issue_secret:
        return JSONResponse(
            status_code=503,
            content={"ok": False, "error": "EXTERNAL_ISSUE_SECRET not set"},
        )

    payload = req.model_dump(exclude_none=True)
    log.info(
        "issue_key external_id=%s days=%d banks=%s amount=%s method=%s md5=%s plan=%s",
        req.external_id,
        req.days,
        [b.bank for b in req.banks],
        req.amount_paid,
        req.payment_method,
        (req.payment_md5[:12] + "...") if req.payment_md5 else None,
        req.plan_id,
    )

    try:
        response = await bot_issue_key(payload)
    except Exception as e:
        log.exception("issue_key network failure")
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Bot unreachable: {e}",
        )

    return _passthrough(response)


# ── 2. Generate QR ───────────────────────────────────────────────
@app.post("/bridge/generate_qr", dependencies=[Depends(require_bridge_token)])
async def generate_qr_route(req: GenerateQrRequest) -> JSONResponse:
    payload = req.model_dump(exclude_none=True)

    log.info(
        "generate_qr key=%s bank=%s amount=%s currency=%s",
        mask_key(req.api_key),
        req.bank,
        req.amount,
        req.currency,
    )

    try:
        response = await bot_generate_qr(payload)
    except Exception as e:
        log.exception("generate_qr network failure")
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Bot unreachable: {e}",
        )

    return _passthrough(response)


# ── 3. Check payment ─────────────────────────────────────────────
@app.get("/bridge/check_payment", dependencies=[Depends(require_bridge_token)])
async def check_payment_route(md5: str, key: str) -> JSONResponse:
    params = CheckPaymentParams(md5=md5, key=key)
    log.info(
        "check_payment md5=%s… key=%s",
        params.md5[:8],
        mask_key(params.key),
    )

    try:
        response = await bot_check_payment(params.md5, params.key)
    except Exception as e:
        log.exception("check_payment network failure")
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Bot unreachable: {e}",
        )

    return _passthrough(response)


# ── 3b. Key info — canonical bank list per sk_ key ───────────────
@app.get("/bridge/key_info", dependencies=[Depends(require_bridge_token)])
async def key_info_route(key: str) -> JSONResponse:
    """Returns the upstream's authoritative bank list for the given key.

    Pass-through of the bot's /key_info endpoint:
      { "success": true, "registered": true, "total": 3,
        "banks": [{"bank":"aba","account_name":"…","has_qr":true}, ...] }
    """
    log.info("key_info key=%s", mask_key(key))
    try:
        response = await bot_key_info(key)
    except Exception as e:
        log.exception("key_info network failure")
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Bot unreachable: {e}",
        )
    return _passthrough(response)


# ── 4. Logos (admin-uploaded branding) ───────────────────────────
@app.get("/bridge/logos", dependencies=[Depends(require_bridge_token)])
async def logos_route(refresh: bool = False) -> JSONResponse:
    """Return all admin-uploaded logos as a slot → ``data:`` URL map.

    Cached in-process for 60 s. Pass ``?refresh=true`` to force a refetch
    (Spring will use this when an admin clicks "Refresh from bot").
    """
    try:
        logos = await get_logos(force_refresh=refresh)
    except Exception as e:
        log.exception("logos fetch failed")
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=f"Bot unreachable: {e}",
        )
    return JSONResponse(
        status_code=200,
        content={"ok": True, "logos": logos, "cache": logos_cache_meta()},
    )


@app.post("/bridge/logos", dependencies=[Depends(require_bridge_token)])
async def logos_upsert(payload: dict) -> JSONResponse:
    """Upsert a single logo into the local store (used when the upstream bot
    doesn't expose /r-admin/logos). Body: ``{"slot": "brand", "dataUrl": "data:image/…"}``."""
    slot = (payload or {}).get("slot", "")
    data_url = (payload or {}).get("dataUrl", "")
    if not slot or not data_url:
        raise HTTPException(status_code=400, detail="slot and dataUrl required")
    try:
        logos = upsert_local(slot, data_url)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    return JSONResponse(
        status_code=200,
        content={"ok": True, "logos": logos, "cache": logos_cache_meta()},
    )


@app.delete("/bridge/logos/{slot}", dependencies=[Depends(require_bridge_token)])
async def logos_delete(slot: str) -> JSONResponse:
    try:
        logos = remove_local(slot)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    return JSONResponse(
        status_code=200,
        content={"ok": True, "logos": logos, "cache": logos_cache_meta()},
    )


# ── helpers ──────────────────────────────────────────────────────
def _passthrough(r) -> JSONResponse:
    """Forward the upstream JSON body as-is (preserving status code)."""
    try:
        body = r.json()
    except Exception:
        body = {"ok": False, "error": "Upstream returned non-JSON",
                "raw": r.text[:200]}
    return JSONResponse(status_code=r.status_code, content=body)
