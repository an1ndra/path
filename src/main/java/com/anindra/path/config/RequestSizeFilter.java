package com.anindra.path.config;

import com.anindra.path.api.ApiException;
import com.anindra.path.api.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects oversized request bodies before they are read into memory.
 *
 * Tomcat's {@code maxPostSize} only covers form posts, so JSON bodies were previously
 * unbounded. Requests without a Content-Length (chunked) are left to the container limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@RequiredArgsConstructor
public class RequestSizeFilter extends OncePerRequestFilter {

    private final GatewayProperties props;
    private final ObjectMapper mapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        long length = request.getContentLengthLong();
        if (length > props.getMaxRequestBytes()) {
            response.setStatus(413);
            response.setContentType("application/json;charset=UTF-8");
            mapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.envelope(
                    "payload_too_large", "invalid_request_error",
                    "Request body is " + length + " bytes; the limit is "
                            + props.getMaxRequestBytes() + ".", null));
            response.flushBuffer();
            return;
        }
        chain.doFilter(request, response);
    }
}
