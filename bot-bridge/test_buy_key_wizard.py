"""End-to-end smoke test for the KhmerBank ↔ apicheckpayment bridge.

Exercises the three bridge endpoints in order:
    1. POST /bridge/issue_key   → mint a fresh sk_ key
    2. POST /bridge/generate_qr → mint a 0.01 KHR Bakong test QR
    3. GET  /bridge/check_payment → poll briefly to confirm "UNPAID"

Run with:
    python test_buy_key_wizard.py
    python test_buy_key_wizard.py --bridge http://localhost:8090
    python test_buy_key_wizard.py --merchant "Demo Cafe" --bakong-id alice@aclb --phone 0123456789
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

import httpx

try:
    from dotenv import load_dotenv  # type: ignore
    load_dotenv(Path(__file__).parent / ".env")
except ImportError:
    pass


def mask(value: str | None) -> str:
    if not value:
        return "—"
    if len(value) <= 12:
        return value[0] + "•" * 8
    return f"{value[:4]}…{value[-4:]}"


def banner(text: str) -> None:
    print()
    print("─" * 64)
    print(text)
    print("─" * 64)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--bridge", default=os.getenv("BRIDGE_URL", "http://localhost:8090"))
    p.add_argument("--token", default=os.getenv("BRIDGE_AUTH_TOKEN", ""))
    p.add_argument("--external-id", default=f"ci_test_{int(time.time())}")
    p.add_argument("--merchant", default="CI Test Merchant")
    p.add_argument("--bakong-id", default="ci_test@aclb")
    p.add_argument("--phone", default="0123456789")
    p.add_argument("--days", type=int, default=30)
    p.add_argument("--poll", type=int, default=2,
                   help="how many times to poll check_payment (default 2)")
    args = p.parse_args()

    if not args.token:
        print("ERROR: set BRIDGE_AUTH_TOKEN in env or .env, or pass --token")
        return 2

    headers = {"X-Bridge-Token": args.token, "Content-Type": "application/json"}
    client = httpx.Client(base_url=args.bridge, timeout=httpx.Timeout(30.0, connect=15.0))

    # ── 0. health ──────────────────────────────────────────────
    banner(f"0. GET {args.bridge}/health")
    try:
        h = client.get("/health")
        h.raise_for_status()
        print(json.dumps(h.json(), indent=2))
    except Exception as e:
        print(f"FAIL: bridge unreachable → {e}")
        return 1

    # ── 1. issue_key ───────────────────────────────────────────
    banner("1. POST /bridge/issue_key")
    issue_body = {
        "external_id": args.external_id,
        "days": args.days,
        "merchant_name": args.merchant,
        "banks": [{
            "bank": "bakong",
            "merchant_id": args.bakong_id,
            "phone": args.phone,
        }],
    }
    print("body:", json.dumps(issue_body, indent=2))

    r = client.post("/bridge/issue_key", headers=headers, json=issue_body)
    print(f"status: {r.status_code}")
    try:
        body = r.json()
    except Exception:
        body = {"raw": r.text[:200]}
    print(json.dumps(body, indent=2))

    if r.status_code != 200 or not body.get("ok"):
        print("FAIL: could not issue key — "
              "check that EXTERNAL_ISSUE_SECRET on the bridge matches the upstream bot")
        return 1

    api_key: str = body["key"]
    print(f"OK — key={mask(api_key)} expires_in_days={body.get('expires_in_days')}")

    # ── 2. generate_qr ─────────────────────────────────────────
    banner("2. POST /bridge/generate_qr (0.01 KHR Bakong)")
    qr_body = {
        "api_key":  api_key,
        "bank":     "bakong",
        "amount":   0.01,
        "currency": "KHR",
    }
    r = client.post("/bridge/generate_qr", headers=headers, json=qr_body)
    print(f"status: {r.status_code}")
    try:
        qr = r.json()
    except Exception:
        qr = {"raw": r.text[:200]}

    if not qr.get("success"):
        print(json.dumps(qr, indent=2))
        print("FAIL: generate_qr did not succeed")
        return 1

    md5 = qr["md5"]
    print(f"OK — md5={md5}  bank={qr.get('bank')}  merchant={qr.get('merchant_name')}")
    print(f"qr_string head: {qr.get('qr_string','')[:64]}…")
    print(f"qr_image head:  {qr.get('qr_image','')[:48]}…")

    # ── 3. check_payment ───────────────────────────────────────
    banner(f"3. GET /bridge/check_payment (×{args.poll}, expect UNPAID until you pay)")
    final_status = None
    for i in range(args.poll):
        r = client.get(
            "/bridge/check_payment",
            headers={"X-Bridge-Token": args.token},
            params={"md5": md5, "key": api_key},
        )
        try:
            cp = r.json()
        except Exception:
            cp = {"raw": r.text[:200]}
        final_status = cp.get("status")
        print(f"  poll {i+1}/{args.poll}: status={final_status} success={cp.get('success')}")
        if final_status == "PAID":
            break
        time.sleep(3)

    banner("DONE")
    print(f"key       : {mask(api_key)}")
    print(f"md5       : {md5}")
    print(f"final     : {final_status}")
    print()
    print("Open this in a browser to see the QR you just generated:")
    print(f"  {qr.get('qr_image','')[:80]}…")
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
