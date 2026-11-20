package com.anindra.path.api;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Tees a streamed response into memory so it can be replayed for an Idempotency-Key.
 * Stops capturing past {@code maxBytes} and reports overflow, in which case the response
 * is not stored and a retry will call the provider again.
 */
public class CapturingResponseWrapper extends HttpServletResponseWrapper {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final int maxBytes;
    private boolean overflow;
    private ServletOutputStream output;

    public CapturingResponseWrapper(HttpServletResponse response, int maxBytes) {
        super(response);
        this.maxBytes = maxBytes;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (output == null) {
            output = new Tee(super.getOutputStream());
        }
        return output;
    }

    public boolean overflowed() {
        return overflow;
    }

    public String body() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private final class Tee extends ServletOutputStream {

        private final ServletOutputStream delegate;

        private Tee(ServletOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            if (captured.size() < maxBytes) {
                captured.write(b);
            } else {
                overflow = true;
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            int room = maxBytes - captured.size();
            if (room > 0) {
                captured.write(b, off, Math.min(room, len));
            }
            if (len > room) {
                overflow = true;
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            delegate.setWriteListener(listener);
        }
    }
}
