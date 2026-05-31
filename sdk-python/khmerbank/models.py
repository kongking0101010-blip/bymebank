"""Pydantic models for the KhmerBank SDK."""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from enum import Enum
from typing import Optional
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field


class BankType(str, Enum):
    ABA = "ABA"
    ACLEDA = "ACLEDA"
    WING = "WING"
    BAKONG = "BAKONG"


class Currency(str, Enum):
    USD = "USD"
    KHR = "KHR"


class PaymentStatus(str, Enum):
    PENDING = "PENDING"
    PROCESSING = "PROCESSING"
    PAID = "PAID"
    FAILED = "FAILED"
    EXPIRED = "EXPIRED"
    REFUNDED = "REFUNDED"
    CANCELLED = "CANCELLED"


class _Base(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="ignore")


class GenerateQrRequest(_Base):
    bank_type: BankType = Field(alias="bankType")
    amount: Decimal
    currency: Currency
    merchant_id: Optional[UUID] = Field(default=None, alias="merchantId")
    description: Optional[str] = None
    reference: Optional[str] = None
    expires_in: Optional[int] = Field(default=None, alias="expiresIn")


class QrCodeResponse(_Base):
    transaction_id: str = Field(alias="transactionId")
    bank_type: BankType = Field(alias="bankType")
    amount: Decimal
    currency: Currency
    description: Optional[str] = None
    reference: Optional[str] = None
    qr_payload: str = Field(alias="qrPayload")
    qr_image: Optional[str] = Field(default=None, alias="qrImage")
    check_url: Optional[str] = Field(default=None, alias="checkUrl")
    status: PaymentStatus
    expires_at: Optional[datetime] = Field(default=None, alias="expiresAt")
    created_at: Optional[datetime] = Field(default=None, alias="createdAt")


class PaymentStatusResponse(_Base):
    transaction_id: str = Field(alias="transactionId")
    status: PaymentStatus
    bank_type: BankType = Field(alias="bankType")
    amount: Decimal
    currency: Currency
    bank_reference: Optional[str] = Field(default=None, alias="bankReference")
    paid_at: Optional[datetime] = Field(default=None, alias="paidAt")
    expires_at: Optional[datetime] = Field(default=None, alias="expiresAt")
    paid: bool = False


class LinkMerchantRequest(_Base):
    bank_type: BankType = Field(alias="bankType")
    merchant_name: str = Field(alias="merchantName")
    merchant_id: str = Field(alias="merchantId")
    merchant_city: Optional[str] = Field(default=None, alias="merchantCity")
    merchant_link: Optional[str] = Field(default=None, alias="merchantLink")
    account_number: Optional[str] = Field(default=None, alias="accountNumber")
    secret: Optional[str] = None


class MerchantResponse(_Base):
    id: UUID
    bank_type: BankType = Field(alias="bankType")
    merchant_name: str = Field(alias="merchantName")
    merchant_city: Optional[str] = Field(default=None, alias="merchantCity")
    merchant_id: str = Field(alias="merchantId")
    merchant_link: Optional[str] = Field(default=None, alias="merchantLink")
    account_number: Optional[str] = Field(default=None, alias="accountNumber")
    verified: bool = False
    active: bool = True
    created_at: Optional[datetime] = Field(default=None, alias="createdAt")
