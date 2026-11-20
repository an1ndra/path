package com.anindra.path.api;

/**
 * A gateway error rendered in the OpenAI error envelope:
 * <pre>{"error":{"message":...,"type":...,"code":...}}</pre>
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String type;
    private final String code;
    private final String param;

    public ApiException(int status, String type, String code, String message) {
        this(status, type, code, message, null);
    }

    public ApiException(int status, String type, String code, String message, String param) {
        super(message);
        this.status = status;
        this.type = type;
        this.code = code;
        this.param = param;
    }

    public int status() { return status; }
    public String type() { return type; }
    public String code() { return code; }
    public String param() { return param; }

    public static ApiException badRequest(String message, String param) {
        return new ApiException(400, "invalid_request_error", "invalid_request", message, param);
    }

    public static ApiException unauthorized(String message, String code) {
        return new ApiException(401, "authentication_error", code, message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, "permission_error", "forbidden", message);
    }

    public static ApiException notFound(String message, String code) {
        return new ApiException(404, "invalid_request_error", code, message);
    }

    public static ApiException rateLimited(String message, int retryAfterSeconds) {
        ApiException e = new ApiException(429, "rate_limit_error", "rate_limit_exceeded", message);
        e.retryAfterSeconds = retryAfterSeconds;
        return e;
    }

    public static ApiException insufficientCredits(String message) {
        return new ApiException(402, "billing_error", "insufficient_credits", message);
    }

    public static ApiException tooManyConcurrent(int limit) {
        ApiException e = new ApiException(429, "rate_limit_error", "concurrency_limit_exceeded",
                "Too many concurrent requests for this key (limit " + limit + ").");
        e.retryAfterSeconds = 1;
        return e;
    }

    public static ApiException upstream(int status, String message, String code) {
        return new ApiException(status >= 400 ? status : 502, "upstream_error", code, message);
    }

    private int retryAfterSeconds;

    public int retryAfterSeconds() { return retryAfterSeconds; }
}
