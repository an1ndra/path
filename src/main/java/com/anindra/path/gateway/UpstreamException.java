package com.anindra.path.gateway;

/** Non-2xx answer from an upstream provider. */
public class UpstreamException extends RuntimeException {

    private final int status;
    private final String providerCode;

    public UpstreamException(int status, String providerCode, String message) {
        super(message);
        this.status = status;
        this.providerCode = providerCode;
    }

    public int status() { return status; }
    public String providerCode() { return providerCode; }

    /** 429 and 5xx are worth trying on another route; 400/401/404 are not. */
    public boolean retryable() {
        return status == 429 || status == 408 || status >= 500;
    }
}
