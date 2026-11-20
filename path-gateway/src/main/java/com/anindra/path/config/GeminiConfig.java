package com.anindra.path.config;

import com.anindra.path.domain.ProviderType;
import com.anindra.path.gateway.GeminiAdapter;
import com.anindra.path.gateway.ProviderAdapter;
import com.anindra.path.gateway.VertexTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Two adapters over one implementation: AI Studio and Vertex differ only in URL and auth,
 * and the provider type is what selects one at request time.
 */
@Configuration
public class GeminiConfig {

    @Bean
    public ProviderAdapter geminiAdapter(ObjectMapper mapper, VertexTokenService tokens) {
        return new GeminiAdapter(mapper, ProviderType.GEMINI, tokens);
    }

    @Bean
    public ProviderAdapter vertexAdapter(ObjectMapper mapper, VertexTokenService tokens) {
        return new GeminiAdapter(mapper, ProviderType.VERTEX, tokens);
    }
}
