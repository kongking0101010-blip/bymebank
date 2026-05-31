package com.khmerbank.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String errorCode;

    public ApiException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public static ApiException badRequest(String code, String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, msg);
    }
    public static ApiException notFound(String code, String msg) {
        return new ApiException(HttpStatus.NOT_FOUND, code, msg);
    }
    public static ApiException unauthorized(String code, String msg) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, msg);
    }
    public static ApiException forbidden(String code, String msg) {
        return new ApiException(HttpStatus.FORBIDDEN, code, msg);
    }
    public static ApiException conflict(String code, String msg) {
        return new ApiException(HttpStatus.CONFLICT, code, msg);
    }
    public static ApiException tooManyRequests(String code, String msg) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, msg);
    }
    public static ApiException internal(String code, String msg) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, code, msg);
    }
}
