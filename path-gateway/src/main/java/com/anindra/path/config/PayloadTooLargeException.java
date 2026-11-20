package com.anindra.path.config;

import java.io.IOException;

/** Signals that a request body crossed the configured byte limit. */
public class PayloadTooLargeException extends IOException {
    public PayloadTooLargeException(String message) {
        super(message);
    }
}
