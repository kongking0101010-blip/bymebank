package com.khmerbank.sdk.exception;

public class KhmerBankException extends RuntimeException {
    public KhmerBankException(String message) { super(message); }
    public KhmerBankException(String message, Throwable cause) { super(message, cause); }
}
