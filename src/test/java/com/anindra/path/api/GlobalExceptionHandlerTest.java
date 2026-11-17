package com.anindra.path.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(new ObjectMapper());

    @Test
    @SuppressWarnings("unchecked")
    void unexpectedErrorsDoNotLeakInternals() {
        var response = handler.unexpected(new IllegalStateException(
                "could not execute statement [select * from client_keys where id=1]"));

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        String message = (String) ((Map<String, Object>) body.get("error")).get("message");

        assertThat(message).doesNotContain("select").doesNotContain("client_keys");
        assertThat(message).contains("reference");
        assertThat(response.getStatusCode().value()).isEqualTo(500);
    }
}
