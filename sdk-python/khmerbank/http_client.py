"""Internal HTTP wrapper around `requests` with retries and error handling."""

from __future__ import annotations

import json
import logging
from decimal import Decimal
from datetime import datetime
from typing import Any, Optional
from uuid import UUID

import requests
from requests.adapters import HTTPAdapter
from urllib3.util import Retry

from .exceptions import KhmerBankAPIError, KhmerBankError

logger = logging.getLogger("khmerbank")


def _json_default(obj: Any) -> Any:
    if isinstance(obj, Decimal):
        return str(obj)
    if isinstance(obj, datetime):
        return obj.isoformat()
    if isinstance(obj, UUID):
        return str(obj)
    raise TypeError(f"Cannot serialise {type(obj).__name__}")


class HttpClient:
    """Thin wrapper that:
    - injects the `X-API-Key` header
    - retries on transient errors
    - unwraps `ApiResponse<T>` envelopes
    - converts errors into `KhmerBankAPIError`
    """

    def __init__(self, base_url: str, api_key: str, timeout: int = 30) -> None:
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout

        self._session = requests.Session()
        retry = Retry(
            total=3,
            backoff_factor=0.5,
            status_forcelist=(500, 502, 503, 504),
            allowed_methods=("GET", "POST", "DELETE"),
        )
        self._session.mount("http://", HTTPAdapter(max_retries=retry))
        self._session.mount("https://", HTTPAdapter(max_retries=retry))
        self._session.headers.update({
            "X-API-Key": api_key,
            "User-Agent": "khmerbank-python-sdk/1.0.0",
            "Accept": "application/json",
        })

    def get(self, path: str) -> Any:
        return self._send("GET", path)

    def post(self, path: str, body: Optional[dict] = None) -> Any:
        return self._send("POST", path, body)

    def delete(self, path: str) -> Any:
        return self._send("DELETE", path)

    def _send(self, method: str, path: str, body: Optional[dict] = None) -> Any:
        url = f"{self.base_url}{path}"
        data = json.dumps(body, default=_json_default) if body is not None else None

        try:
            r = self._session.request(
                method,
                url,
                data=data,
                headers={"Content-Type": "application/json"} if data else None,
                timeout=self.timeout,
            )
        except requests.RequestException as e:
            raise KhmerBankError(f"Network error: {e}") from e

        try:
            payload = r.json()
        except ValueError:
            payload = {"message": r.text}

        if 200 <= r.status_code < 300:
            return payload.get("data") if isinstance(payload, dict) else payload

        code = payload.get("code", "UNKNOWN") if isinstance(payload, dict) else "UNKNOWN"
        message = payload.get("message", r.reason) if isinstance(payload, dict) else r.reason
        raise KhmerBankAPIError(r.status_code, code, message)
