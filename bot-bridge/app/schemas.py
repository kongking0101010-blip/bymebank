"""Request / response schemas for the bridge."""
from __future__ import annotations

from typing import Any, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field

Bank = Literal["aba", "wing", "acleda", "bakong"]


class BankInput(BaseModel):
    """One bank entry passed to /bridge/issue_key."""

    model_config = ConfigDict(extra="ignore")

    bank: Bank
    qr_string: Optional[str] = None
    merchant_link: Optional[str] = None
    merchant_id: Optional[str] = None
    phone: Optional[str] = None


class IssueKeyRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    external_id: str = Field(..., min_length=1, max_length=128)
    telegram_id: Optional[int] = None
    days: int = Field(30, ge=1, le=3650)
    merchant_name: str = Field(..., min_length=2, max_length=64)
    banks: list[BankInput] = Field(..., min_length=1)
    deliver_to_telegram: bool = False

    # Buyer-paid context — forwarded straight through to the upstream
    # /api/external/issue_key call so the admin Telegram DM shows the
    # real numbers (Amount, Method, MD5, Plan) instead of the
    # "$0.00 / EXTERNAL / (empty)" defaults the bot falls back to when
    # these fields are absent. All optional so legacy callers that only
    # send the original five fields keep working.
    amount_paid: Optional[float] = Field(None, ge=0)
    payment_md5: Optional[str] = Field(None, min_length=8, max_length=128)
    payment_method: Optional[str] = Field(None, max_length=64)
    plan_id: Optional[str] = Field(None, max_length=32)


class GenerateQrRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    api_key: str = Field(..., min_length=8, max_length=128)
    bank: Bank
    amount: float = Field(..., gt=0)
    currency: Literal["USD", "KHR"] = "USD"
    merchant_name: Optional[str] = None


class CheckPaymentParams(BaseModel):
    md5: str = Field(..., min_length=8, max_length=128)
    key: str = Field(..., min_length=8, max_length=128)


class BotResponse(BaseModel):
    """Generic envelope returned upstream — we forward it verbatim."""

    model_config = ConfigDict(extra="allow")

    ok: Optional[bool] = None
    success: Optional[bool] = None

    @property
    def succeeded(self) -> bool:
        if self.ok is True or self.success is True:
            return True
        if self.ok is False or self.success is False:
            return False
        return True


class ErrorEnvelope(BaseModel):
    ok: bool = False
    error: str
    detail: Any = None
