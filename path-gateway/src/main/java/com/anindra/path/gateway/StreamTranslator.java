package com.anindra.path.gateway;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Stateful converter from upstream stream lines to OpenAI-shaped chunks.
 */
public interface StreamTranslator {

    /** Chunks to emit for this line. Empty list means "nothing to emit yet". */
    List<JsonNode> translate(String line);

    /** Upstream signalled the end of the stream. */
    boolean done();

    /** Token usage seen so far, or {@link TokenUsage#EMPTY} when the upstream never reported it. */
    TokenUsage usage();
}
