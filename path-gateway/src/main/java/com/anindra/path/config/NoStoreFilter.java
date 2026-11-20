package com.anindra.path.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Marks every API response as uncacheable.
 *
 * A CDN decides what to cache largely by URL, not by what the endpoint is: append a static
 * extension to an authenticated URL and Cloudflare will happily store the 200 for hours and
 * serve it to anonymous clients. That is how a pentest kept reading pre-hardening Actuator
 * data ("cache ghosts") long after the origin started returning 401. Explicit
 * {@code no-store} removes the ambiguity at the source, so the edge has nothing to keep
 * whatever the path looks like.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class NoStoreFilter extends OncePerRequestFilter {

    private static final String[] PROTECTED_PREFIXES = {"/admin", "/v1", "/actuator"};

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String prefix : PROTECTED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Set before the chain runs so it survives error and 401/429 paths too.
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, private");
        response.setHeader("Pragma", "no-cache");
        chain.doFilter(request, response);
    }
}
