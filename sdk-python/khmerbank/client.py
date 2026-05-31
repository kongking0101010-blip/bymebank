"""Main client class for the KhmerBank Python SDK."""

from __future__ import annotations

import logging
import time
from decimal import Decimal
from typing import List, Optional, Union
from uuid import UUID

from .http_client import HttpClient
from .models import (
    BankType,
    Currency,
    GenerateQrRequest,
    LinkMerchantRequest,
    MerchantResponse,
    PaymentStatus,
    PaymentStatusResponse,
    QrCodeResponse,
)

logger = logging.getLogger("khmerbank")

DEFAULT_BASE_URL = "https://api.khmerbank.dev"
TERMINAL_STATUSES = {
    PaymentStatus.PAID,
    PaymentStatus.FAILED,
    PaymentStatus.EXPIRED,
    PaymentStatus.CANCELLED,
}


class KhmerBank:
    """High-level client for the KhmerBank gateway.

    Example:
        >>> from khmerbank import KhmerBank, BankType, Currency
        >>> client = KhmerBank(api_key="kb_xxx", base_url="http://localhost:8080")
        >>> qr = client.generate_qr(
        ...     bank=BankType.BAKONG,
        ...     amount="12.50",
        ...     currency=Currency.USD,
        ...     description="Order #1234",
        ... )
        >>> print(qr.transaction_id, qr.qr_payload)
        >>> status = client.wait_for_payment(qr.transaction_id, timeout=900)
        >>> print(status.paid)
    """

    def __init__(
        self,
        api_key: str,
        base_url: str = DEFAULT_BASE_URL,
        timeout: int = 30,
    ) -> None:
        if not api_key:
            raise ValueError("api_key is required")
        self._http = HttpClient(base_url, api_key, timeout)

    # ----------------------------------------------------------------- #
    # Payments
    # ----------------------------------------------------------------- #

    def generate_qr(
        self,
        *,
        bank: BankType,
        amount: Union[str, int, float, Decimal],
        currency: Currency,
        description: Optional[str] = None,
        reference: Optional[str] = None,
        merchant_id: Optional[UUID] = None,
        expires_in: Optional[int] = None,
    ) -> QrCodeResponse:
        """Generate a payment QR code."""
        req = GenerateQrRequest(
            bankType=bank,
            amount=Decimal(str(amount)),
            currency=currency,
            description=description,
            reference=reference,
            merchantId=merchant_id,
            expiresIn=expires_in,
        )
        body = req.model_dump(by_alias=True, exclude_none=True)
        # Ensure Decimal stays as string
        body["amount"] = str(req.amount)
        data = self._http.post("/api/v1/payments/qr", body)
        return QrCodeResponse.model_validate(data)

    def check_status(self, transaction_id: str) -> PaymentStatusResponse:
        """Look up the current status of a payment."""
        data = self._http.get(f"/api/v1/payments/{transaction_id}/status")
        return PaymentStatusResponse.model_validate(data)

    def wait_for_payment(
        self,
        transaction_id: str,
        timeout: int = 900,
        poll_interval: int = 3,
    ) -> PaymentStatusResponse:
        """Block until the payment reaches a terminal status, or `timeout` seconds elapse."""
        deadline = time.monotonic() + timeout
        last: Optional[PaymentStatusResponse] = None
        while time.monotonic() < deadline:
            last = self.check_status(transaction_id)
            if last.status in TERMINAL_STATUSES:
                return last
            time.sleep(poll_interval)
        return last or self.check_status(transaction_id)

    # ----------------------------------------------------------------- #
    # Merchants
    # ----------------------------------------------------------------- #

    def link_merchant(
        self,
        *,
        bank: BankType,
        merchant_name: str,
        merchant_id: str,
        merchant_city: Optional[str] = None,
        merchant_link: Optional[str] = None,
        account_number: Optional[str] = None,
        secret: Optional[str] = None,
    ) -> MerchantResponse:
        req = LinkMerchantRequest(
            bankType=bank,
            merchantName=merchant_name,
            merchantId=merchant_id,
            merchantCity=merchant_city,
            merchantLink=merchant_link,
            accountNumber=account_number,
            secret=secret,
        )
        body = req.model_dump(by_alias=True, exclude_none=True)
        data = self._http.post("/api/v1/merchants", body)
        return MerchantResponse.model_validate(data)

    def list_merchants(self) -> List[MerchantResponse]:
        data = self._http.get("/api/v1/merchants") or []
        return [MerchantResponse.model_validate(m) for m in data]

    def delete_merchant(self, merchant_id: UUID) -> None:
        self._http.delete(f"/api/v1/merchants/{merchant_id}")
