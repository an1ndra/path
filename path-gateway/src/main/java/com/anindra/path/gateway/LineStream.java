package com.anindra.path.gateway;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Map;

/**
 * Blocking line reader over an HTTP response body with an idle timeout.
 *
 * A pump thread reads lines into a bounded queue so that a stalled upstream can be
 * abandoned after {@code idleTimeout} instead of hanging the request forever
 * (java.net.http has no read timeout for response bodies).
 */
public class LineStream implements AutoCloseable {

    private static final String END = "\u0000END";

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(512);
    private final InputStream input;
    private final Map<String, String> headers;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread pump;

    public LineStream(InputStream input, Map<String, String> headers) {
        this.input = input;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
        this.pump = Thread.ofVirtual().name("upstream-sse-reader").start(this::pump);
    }

    /** Response headers of the streaming call (request ids, rate-limit hints). */
    public Map<String, String> headers() {
        return headers;
    }

    private void pump() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (closed.get()) {
                    break;
                }
                if (!queue.offer(line, 5, TimeUnit.SECONDS)) {
                    break;
                }
            }
        } catch (Exception ignored) {
            // socket closed / stream aborted: the consumer sees end-of-stream
        } finally {
            if (!queue.offer(END)) {
                queue.poll();
                queue.offer(END);
            }
        }
    }

    /** @return the next line, or null at end of stream. */
    public String poll(Duration idleTimeout) throws InterruptedException, TimeoutException {
        String line = queue.poll(idleTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (line == null) {
            throw new TimeoutException("Upstream sent nothing for " + idleTimeout.toSeconds() + "s");
        }
        return END.equals(line) ? null : line;
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        closed.set(true);
        try {
            input.close();
        } catch (Exception ignored) {
            // nothing to do
        }
        pump.interrupt();
    }
}
