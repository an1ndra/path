package com.anindra.path.gateway;

/** Non-2xx answer from an upstream provider. */
public class UpstreamException extends RuntimeException {

    private final int status;
    private final String providerCode;
    private final int retryAfterSeconds;

    public UpstreamException(int status, String providerCode, String message) {
        this(status, providerCode, message, 0);
    }

    public UpstreamException(int status, String providerCode, String message, int retryAfterSeconds) {
        super(message);
        this.status = status;
        this.providerCode = providerCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() { return status; }
    public String providerCode() { return providerCode; }

    /** Upstream's Retry-After hint in seconds, or 0 when absent. */
    public int retryAfterSeconds() { return retryAfterSeconds; }

    /** 429 and 5xx are worth trying on another route; 400/401/404 are not. */
    public boolean retryable() {
        return status == 429 || status == 408 || status >= 500;
    }
}
