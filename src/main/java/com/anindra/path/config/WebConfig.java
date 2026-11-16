package com.anindra.path.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final GatewayProperties props;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = props.getCorsOrigins() == null || props.getCorsOrigins().isBlank()
                ? new String[]{"*"}
                : props.getCorsOrigins().split("\\s*,\\s*");
        registry.addMapping("/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Gateway-Provider", "X-Gateway-Upstream-Model",
                        "X-Gateway-Cost-Usd", "X-Gateway-Latency-Ms", "Retry-After")
                .maxAge(3600);
    }
}
