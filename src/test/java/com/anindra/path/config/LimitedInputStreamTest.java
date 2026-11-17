package com.anindra.path.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitedInputStreamTest {

    private ServletInputStream stream(byte[] data, long limit) {
        ByteArrayInputStream source = new ByteArrayInputStream(data);
        return new LimitedInputStream(new ServletInputStream() {
            @Override
            public int read() {
                return source.read();
            }

            @Override
            public boolean isFinished() {
                return source.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                // no async reads in this test
            }
        }, limit);
    }

    @Test
    void passesBodiesUnderTheLimit() throws Exception {
        byte[] body = "small body".getBytes();
        assertThat(stream(body, 1024).readAllBytes()).isEqualTo(body);
    }

    @Test
    void abortsOnceTheLimitIsCrossed() {
        // A chunked request has no Content-Length; this is the only enforcement point.
        assertThatThrownBy(() -> stream(new byte[2048], 1024).readAllBytes())
                .isInstanceOf(PayloadTooLargeException.class);
    }
}
