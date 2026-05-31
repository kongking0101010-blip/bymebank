"""KhmerBank SDK exceptions."""


class KhmerBankError(Exception):
    """Base exception for the KhmerBank SDK."""


class KhmerBankAPIError(KhmerBankError):
    """Raised when the API returns a non-2xx response."""

    def __init__(self, status_code: int, code: str, message: str) -> None:
        super().__init__(f"[{status_code} {code}] {message}")
        self.status_code = status_code
        self.code = code
        self.message = message
