package com.anindra.path.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import java.io.IOException;

/** Counts bytes as they are read, so a chunked body cannot bypass the limit. */
class LimitedInputStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final long limit;
    private long read;

    LimitedInputStream(ServletInputStream delegate, long limit) {
        this.delegate = delegate;
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int value = delegate.read();
        if (value >= 0 && ++read > limit) {
            throw new PayloadTooLargeException("Request body exceeded " + limit + " bytes");
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int count = delegate.read(buffer, offset, length);
        if (count > 0) {
            read += count;
            if (read > limit) {
                throw new PayloadTooLargeException("Request body exceeded " + limit + " bytes");
            }
        }
        return count;
    }

    @Override
    public boolean isFinished() {
        return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
        return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener listener) {
        delegate.setReadListener(listener);
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
