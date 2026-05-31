"""Runtime configuration loaded from env vars."""
from __future__ import annotations

import logging
import os
import sys
from dataclasses import dataclass

from dotenv import load_dotenv

load_dotenv()

# Placeholder values that should NEVER make it into prod.
_PLACEHOLDER_VALUES = frozenset({
    "",
    "change-me",
    "change-me-in-prod",
    "replace-me",
    "replace-me-with-real-secret",
    "replace-me-with-random-40-char-string",
})


@dataclass(frozen=True)
class Settings:
    bot_url: str
    issue_secret: str
    bridge_token: str
    bot_admin_key: str
    port: int
    log_level: str
    enforce_secrets: bool

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            bot_url=os.getenv(
                "APICHECKING_BOT_URL",
                "https://apicheckpayment.onrender.com",
            ).rstrip("/"),
            issue_secret=os.getenv("EXTERNAL_ISSUE_SECRET", ""),
            bridge_token=os.getenv("BRIDGE_AUTH_TOKEN", ""),
            bot_admin_key=os.getenv("BOT_ADMIN_KEY", ""),
            port=int(os.getenv("PORT", "8090")),
            log_level=os.getenv("LOG_LEVEL", "INFO").upper(),
            # In prod we refuse to start with placeholder secrets.
            # Set BRIDGE_ALLOW_PLACEHOLDER=1 only for local dev.
            enforce_secrets=os.getenv("BRIDGE_ALLOW_PLACEHOLDER", "").lower()
                            not in {"1", "true", "yes"},
        )

    @property
    def configured(self) -> bool:
        return bool(self.issue_secret and self.bridge_token)

    def assert_production_ready(self) -> None:
        """Raise SystemExit if running with placeholder / empty secrets in
        enforce mode. Call once at startup."""
        if not self.enforce_secrets:
            return
        problems = []
        if self.issue_secret in _PLACEHOLDER_VALUES:
            problems.append("EXTERNAL_ISSUE_SECRET is empty or a placeholder")
        if self.bridge_token in _PLACEHOLDER_VALUES:
            problems.append("BRIDGE_AUTH_TOKEN is empty or a placeholder")
        if problems:
            sys.stderr.write(
                "\n[bot-bridge] Refusing to start:\n"
                + "\n".join(f"  • {p}" for p in problems)
                + "\nSet real values in .env, or export "
                  "BRIDGE_ALLOW_PLACEHOLDER=1 for local dev.\n"
            )
            raise SystemExit(2)


settings = Settings.from_env()


def configure_logging() -> None:
    logging.basicConfig(
        level=settings.log_level,
        format="%(asctime)s [%(levelname)s] %(name)s :: %(message)s",
    )
