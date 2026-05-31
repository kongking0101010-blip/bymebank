package com.khmerbank.sdk.exception;

import lombok.Getter;

@Getter
public class KhmerBankApiException extends KhmerBankException {
    private final int statusCode;
    private final String errorCode;

    public KhmerBankApiException(int statusCode, String errorCode, String message) {
        super("[" + statusCode + " " + errorCode + "] " + message);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
    }
}
