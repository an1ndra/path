package com.anindra.path.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ObjectMapper mapper;

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException e) {
        ResponseEntity.BodyBuilder b = ResponseEntity.status(e.status());
        if (e.status() == 429 && e.retryAfterSeconds() > 0) {
            b.header("Retry-After", String.valueOf(e.retryAfterSeconds()));
        }
        return b.body(envelope(e.code(), e.type(), e.getMessage(), e.param()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> badJson(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(envelope("invalid_json", "invalid_request_error", "Request body is not valid JSON.", null));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> notFound(Exception e) {
        return ResponseEntity.status(404)
                .body(envelope("unknown_endpoint", "invalid_request_error", "No such endpoint.", null));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String, Object>> badParam(Exception e) {
        return ResponseEntity.badRequest()
                .body(envelope("invalid_parameter", "invalid_request_error", e.getMessage(), null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        return ResponseEntity.internalServerError()
                .body(envelope("internal_error", "server_error",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), null));
    }

    public static Map<String, Object> envelope(String code, String type, String message, String param) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", type);
        error.put("code", code);
        if (param != null) {
            error.put("param", param);
        }
        return Map.of("error", error);
    }
}
