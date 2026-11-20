package com.anindra.path.springai;

/**
 * A failure from the gateway, kept separate from Spring AI's own exceptions so a caller can
 * see the HTTP status and the gateway's error code.
 */
public class PathException extends RuntimeException {

    private final int status;
    private final String code;

    public PathException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    /** HTTP status; 0 when the call never reached the gateway. */
    public int status() {
        return status;
    }

    /** The gateway's {@code error.code}, or null when the response carried none. */
    public String code() {
        return code;
    }

    static PathException network(Throwable cause) {
        return new PathException(0, "network_error",
                "Could not reach the Path gateway: " + cause.getMessage(), cause);
    }
}
