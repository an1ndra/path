package com.anindra.path.config;

import com.anindra.path.api.ApiException;
import com.anindra.path.api.GlobalExceptionHandler;
import com.anindra.path.domain.ClientKey;
import com.anindra.path.gateway.ApiKeyService;
import com.anindra.path.gateway.RateLimitStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Single entry gate:
 *   /v1/**    -> client API key (except GET /v1/models when public-models=true)
 *   /admin/** -> X-Admin-Token
 *   /actuator/** -> open
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    public static final String CLIENT_KEY_ATTR = "gateway.clientKey";
    public static final String ADMIN_PRINCIPAL_ATTR = "gateway.adminPrincipal";

    private final ApiKeyService apiKeys;
    private final RateLimitStore rateLimiter;
    private final com.anindra.path.admin.TenancyService tenancy;
    private final AdminAuthLimiter adminAuthLimiter;
    private final GatewayProperties props;
    private final ObjectMapper mapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/v1") && !path.startsWith("/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        try {
            if (path.startsWith("/admin")) {
                requireAdmin(request);
            } else if (!isPublicModelsRequest(request)) {
                ClientKey key = resolveClientKey(request);
                int retryAfter = rateLimiter.acquire(key.getId(), key.getRpmLimit());
                if (retryAfter > 0) {
                    throw ApiException.rateLimited(
                            "Rate limit exceeded (" + key.getRpmLimit() + " requests/minute). Retry in "
                                    + retryAfter + "s.", retryAfter);
                }
                request.setAttribute(CLIENT_KEY_ATTR, key);
            }
            chain.doFilter(request, response);
        } catch (ApiException e) {
            writeError(response, e);
        }
    }

    private boolean isPublicModelsRequest(HttpServletRequest request) {
        return props.isPublicModels()
                && "GET".equalsIgnoreCase(request.getMethod())
                && ("/v1/models".equals(request.getRequestURI())
                    || request.getRequestURI().startsWith("/v1/models/"));
    }

    private ClientKey resolveClientKey(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = null;
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = header.substring(7).trim();
        }
        ClientKey key = apiKeys.authenticate(token);
        apiKeys.touch(key.getId());
        return key;
    }

    private void requireAdmin(HttpServletRequest request) {
        String expected = props.getAdminToken();
        String provided = request.getHeader("X-Admin-Token");
        if (provided == null || provided.isBlank()) {
            throw ApiException.unauthorized("Invalid or missing X-Admin-Token.", "invalid_admin_token");
        }

        String address = clientAddress(request);
        long retryAfter = adminAuthLimiter.retryAfterSeconds(address);
        if (retryAfter > 0) {
            throw ApiException.rateLimited(
                    "Too many failed admin authentication attempts. Retry in " + retryAfter + "s.",
                    (int) retryAfter);
        }

        com.anindra.path.admin.TenancyService.Principal principal;
        if (expected != null && !expected.isBlank() && constantTimeEquals(expected, provided)) {
            adminAuthLimiter.onSuccess(address);
            principal = new com.anindra.path.admin.TenancyService.Principal(
                    "bootstrap", java.util.Set.of("read", "write"));
        } else {
            principal = tenancy.authenticate(provided).orElse(null);
            if (principal == null) {
                adminAuthLimiter.onFailure(address);
                throw ApiException.unauthorized("Invalid or missing X-Admin-Token.", "invalid_admin_token");
            }
            adminAuthLimiter.onSuccess(address);
        }

        request.setAttribute(ADMIN_PRINCIPAL_ATTR, principal);
        boolean mutating = !"GET".equalsIgnoreCase(request.getMethod())
                && !"HEAD".equalsIgnoreCase(request.getMethod());
        if (mutating && !principal.canWrite()) {
            throw ApiException.forbidden("This admin token has read-only scope.");
        }
    }

    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void writeError(HttpServletResponse response, ApiException e) throws IOException {
        response.setStatus(e.status());
        response.setContentType("application/json;charset=UTF-8");
        if (e.status() == 429 && e.retryAfterSeconds() > 0) {
            response.setHeader("Retry-After", String.valueOf(e.retryAfterSeconds()));
        }
        mapper.writeValue(response.getOutputStream(),
                GlobalExceptionHandler.envelope(e.code(), e.type(), e.getMessage(), e.param()));
        response.flushBuffer();
    }
}
