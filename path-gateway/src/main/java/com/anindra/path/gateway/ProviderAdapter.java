package com.anindra.path.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;

import java.math.BigDecimal;

/** Translates between the gateway's OpenAI-shaped contract and one provider wire format. */
public interface ProviderAdapter {

    ProviderType type();

    UpstreamRequest build(ChatRequestSpec spec, ModelRoute route, Provider provider,
                          String apiKey, boolean stream);

    /** Normalise a non-streaming upstream response into an OpenAI chat.completion object. */
    JsonNode parseFullResponse(JsonNode upstream, ChatRequestSpec spec, ModelRoute route);

    /** Token accounting for a non-streaming response; {@link TokenUsage#EMPTY} when absent. */
    TokenUsage parseUsage(JsonNode upstream);

    StreamTranslator newStreamTranslator(ChatRequestSpec spec, ModelRoute route);

    BigDecimal priceInput(ModelRoute route);

    BigDecimal priceOutput(ModelRoute route);
}
