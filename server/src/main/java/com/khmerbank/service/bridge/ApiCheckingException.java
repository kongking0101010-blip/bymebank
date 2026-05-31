package com.khmerbank.service.bridge;

import lombok.Getter;

/**
 * Thrown by {@link ApiCheckingClient} when the bridge or upstream bot
 * returns a non-2xx response, or when the network call fails.
 *
 * <p>Carries the raw upstream body for 4xx errors so we can debug
 * "Bad secret" / "Invalid bank" without re-running.
 */
@Getter
public class ApiCheckingException extends RuntimeException {

    private final int statusCode;
    private final String upstreamCode;
    /** Truncated raw response body from the bridge (or null on network failure). */
    private final String responseBody;

    public ApiCheckingException(int statusCode, String upstreamCode, String message) {
        this(statusCode, upstreamCode, message, null);
    }

    public ApiCheckingException(int statusCode, String upstreamCode,
                                String message, String responseBody) {
        super(message);
        this.statusCode = statusCode;
        this.upstreamCode = upstreamCode;
        this.responseBody = responseBody;
    }

    public ApiCheckingException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.upstreamCode = "NETWORK";
        this.responseBody = null;
    }

    public boolean isUnauthorized() { return statusCode == 401; }
    public boolean isUnavailable()  { return statusCode == 503 || statusCode == 502 || statusCode == 504; }
    public boolean isClientError()  { return statusCode >= 400 && statusCode < 500; }

    @Override
    public String getMessage() {
        String base = super.getMessage();
        if (responseBody == null || responseBody.isBlank() || !isClientError()) return base;
        String b = responseBody.length() > 300
                ? responseBody.substring(0, 300) + "…"
                : responseBody;
        return base + " | body=" + b;
    }
}
