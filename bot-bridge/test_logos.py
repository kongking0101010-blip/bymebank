"""Unit tests for the /bridge/logos endpoint and logo cache."""
from __future__ import annotations

import asyncio
import os
import time

# Allow placeholder secrets at import time.
os.environ["BRIDGE_ALLOW_PLACEHOLDER"] = "1"
os.environ["EXTERNAL_ISSUE_SECRET"] = "test-secret"
os.environ["BRIDGE_AUTH_TOKEN"] = "test-bridge-token"
os.environ["BOT_ADMIN_KEY"] = "test-admin"

import pytest
from fastapi.testclient import TestClient

from app import logos as logos_module
from app.main import app


@pytest.fixture
def client():
    return TestClient(app)


@pytest.fixture(autouse=True)
def reset_cache():
    logos_module.reset_for_tests()
    yield
    logos_module.reset_for_tests()


# ── unit tests on the validator ──────────────────────────────────

def test_validate_filters_invalid_slots():
    raw = {
        "brand":   "data:image/png;base64,iVBORw0KGgo=",
        "unknown": "data:image/png;base64,xxx",
        "aba":     "https://cdn/aba.png",  # not a data URL
        "wing":    None,                    # not a string
    }
    out = logos_module._validate(raw)
    assert "brand" in out
    assert "unknown" not in out
    assert "aba" not in out
    assert "wing" not in out


def test_validate_drops_oversize():
    big = "data:image/png;base64," + ("A" * (logos_module.MAX_LOGO_BYTES + 100))
    out = logos_module._validate({"brand": big})
    assert out == {}


def test_validate_handles_garbage():
    assert logos_module._validate(None) == {}      # type: ignore[arg-type]
    assert logos_module._validate("string") == {}  # type: ignore[arg-type]
    assert logos_module._validate([]) == {}        # type: ignore[arg-type]


# ── /bridge/logos endpoint ───────────────────────────────────────

def test_logos_requires_bridge_token(client):
    r = client.get("/bridge/logos")
    assert r.status_code == 401


def test_logos_calls_bot_and_caches(client, monkeypatch):
    calls = {"n": 0}

    async def fake_fetch():
        calls["n"] += 1
        return {"brand": "data:image/png;base64,AAA"}

    monkeypatch.setattr(logos_module, "_fetch_from_bot", fake_fetch)

    headers = {"X-Bridge-Token": "test-bridge-token"}

    # First call → fetches from bot
    r1 = client.get("/bridge/logos", headers=headers)
    assert r1.status_code == 200
    assert r1.json()["ok"] is True
    assert r1.json()["logos"] == {"brand": "data:image/png;base64,AAA"}
    assert calls["n"] == 1

    # Second call within TTL → cached, no extra fetch
    r2 = client.get("/bridge/logos", headers=headers)
    assert r2.status_code == 200
    assert calls["n"] == 1

    # ?refresh=true forces a refetch
    r3 = client.get("/bridge/logos?refresh=true", headers=headers)
    assert r3.status_code == 200
    assert calls["n"] == 2


def test_logos_falls_back_to_stale_on_upstream_error(client, monkeypatch, tmp_path):
    """When the bot is configured but throws, we should fall back to the
    on-disk store (rather than fail) so the dashboard never shows broken UI.
    The local store may be empty — that's fine, the snapshot is recorded."""
    state = {"calls": 0, "fail": False}

    async def fake_fetch():
        state["calls"] += 1
        if state["fail"]:
            raise RuntimeError("upstream down")
        return {"brand": "data:image/png;base64,AAA"}

    monkeypatch.setattr(logos_module, "_fetch_from_bot", fake_fetch)
    # Empty local store
    monkeypatch.setattr(logos_module, "LOCAL_STORE_PATH", tmp_path / "logos.json")

    headers = {"X-Bridge-Token": "test-bridge-token"}

    # Prime the cache with a successful bot fetch
    r1 = client.get("/bridge/logos", headers=headers)
    assert r1.status_code == 200
    assert r1.json()["logos"] == {"brand": "data:image/png;base64,AAA"}
    assert r1.json()["cache"]["source"] == "bot"

    # Now fail upstream + force refresh — should fall back to local (empty)
    # without returning 5xx and without crashing.
    state["fail"] = True
    r2 = client.get("/bridge/logos?refresh=true", headers=headers)
    assert r2.status_code == 200
    assert r2.json()["cache"]["source"] == "local"


def test_logos_local_fallback_when_bot_unconfigured(client, monkeypatch, tmp_path):
    """When BOT_ADMIN_KEY is empty the bridge must fall back to the local
    JSON store, not error out."""
    from app import config as config_module
    blank = config_module.Settings(
        bot_url=config_module.settings.bot_url,
        issue_secret=config_module.settings.issue_secret,
        bridge_token=config_module.settings.bridge_token,
        bot_admin_key="",
        port=config_module.settings.port,
        log_level=config_module.settings.log_level,
        enforce_secrets=False,
    )
    monkeypatch.setattr(config_module, "settings", blank)
    monkeypatch.setattr(logos_module, "settings", blank)
    from app import main as main_module
    monkeypatch.setattr(main_module, "settings", blank)

    # Point the local store at a temp file
    monkeypatch.setattr(logos_module, "LOCAL_STORE_PATH", tmp_path / "logos.json")
    logos_module.reset_for_tests()

    headers = {"X-Bridge-Token": "test-bridge-token"}

    # Empty store → returns {}
    r = client.get("/bridge/logos", headers=headers)
    assert r.status_code == 200
    assert r.json()["logos"] == {}
    assert r.json()["cache"]["source"] == "local"


def test_logos_upsert_and_delete(client, monkeypatch, tmp_path):
    monkeypatch.setattr(logos_module, "LOCAL_STORE_PATH", tmp_path / "logos.json")
    logos_module.reset_for_tests()

    headers = {"X-Bridge-Token": "test-bridge-token"}

    # Upsert a brand logo
    body = {"slot": "brand", "dataUrl": "data:image/png;base64,AAA"}
    r = client.post("/bridge/logos", json=body, headers=headers)
    assert r.status_code == 200
    assert r.json()["logos"]["brand"] == "data:image/png;base64,AAA"

    # Read it back via GET (should hit local store, not bot)
    r = client.get("/bridge/logos?refresh=true", headers=headers)
    assert r.json()["logos"]["brand"] == "data:image/png;base64,AAA"

    # Reject invalid data URL
    r = client.post("/bridge/logos",
                    json={"slot": "brand", "dataUrl": "https://example/x.png"},
                    headers=headers)
    assert r.status_code == 400

    # Reject unknown slot
    r = client.post("/bridge/logos",
                    json={"slot": "unknown", "dataUrl": "data:image/png;base64,AAA"},
                    headers=headers)
    assert r.status_code == 400

    # Delete
    r = client.delete("/bridge/logos/brand", headers=headers)
    assert r.status_code == 200
    assert "brand" not in r.json()["logos"]
