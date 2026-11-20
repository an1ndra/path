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
 *   /actuator/health -> open (status only); every other actuator endpoint needs the admin token
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class AuthFilter extends OncePerRequestFilter {

    public static final String CLIENT_KEY_ATTR = "gateway.clientKey";
    /** Project concurrency cap for the resolved key, or absent when unlimited. */
    public static final String PROJECT_CONCURRENCY_ATTR = "gateway.projectConcurrency";
    public static final String ADMIN_PRINCIPAL_ATTR = "gateway.adminPrincipal";

    private final ApiKeyService apiKeys;
    private final RateLimitStore rateLimiter;
    private final com.anindra.path.admin.TenancyService tenancy;
    private final AdminAuthLimiter adminAuthLimiter;
    private final ClientAuthLimiter clientAuthLimiter;
    private final GatewayProperties props;
    private final com.anindra.path.repo.ProjectRepository projects;
    private final ObjectMapper mapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/v1") && !path.startsWith("/admin") && !path.startsWith("/actuator");
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
            } else if (path.startsWith("/actuator")) {
                requireActuatorAccess(request, path);
            } else if (!isPublicModelsRequest(request)) {
                ClientKey key = authenticateClientKey(request);
                int retryAfter = rateLimiter.acquire(key.getId(), key.getRpmLimit());
                if (retryAfter > 0) {
                    throw ApiException.rateLimited(
                            "Rate limit exceeded (" + key.getRpmLimit() + " requests/minute). Retry in "
                                    + retryAfter + "s.", retryAfter);
                }
                enforceProjectLimit(request, key);
                request.setAttribute(CLIENT_KEY_ATTR, key);
            }
            chain.doFilter(request, response);
        } catch (ApiException e) {
            writeError(response, e);
        }
    }

    /**
     * A project cap sits above the per-key one: forty keys at 120 rpm should still be
     * bounded by whatever the project allows. The concurrency cap is stashed on the
     * request because only the controller can hold a permit for the life of a stream.
     */
    private void enforceProjectLimit(HttpServletRequest request, ClientKey key) {
        if (key.getProjectId() == null) {
            return;
        }
        com.anindra.path.domain.Project project = projects.findById(key.getProjectId()).orElse(null);
        if (project == null) {
            return;
        }
        Integer projectRpm = project.getRpmLimit();
        if (projectRpm != null && projectRpm > 0) {
            int retryAfter = rateLimiter.acquire("project:" + project.getId(), projectRpm);
            if (retryAfter > 0) {
                throw ApiException.rateLimited(
                        "Project '" + project.getSlug() + "' rate limit exceeded (" + projectRpm
                                + " requests/minute). Retry in " + retryAfter + "s.", retryAfter);
            }
        }
        if (project.getMaxConcurrentRequests() != null && project.getMaxConcurrentRequests() > 0) {
            request.setAttribute(PROJECT_CONCURRENCY_ATTR, project.getMaxConcurrentRequests());
        }
    }

    /** Fails closed: no identity header means no admin access, whatever else is sent. */
    private void requireProxyIdentity(HttpServletRequest request) {
        String header = props.getProxyAuth().getHeader();
        String identity = request.getHeader(header);
        if (identity == null || identity.isBlank()) {
            throw ApiException.unauthorized(
                    "Admin access requires a signed-in session; the proxy did not send " + header + ".",
                    "proxy_identity_missing");
        }
        java.util.Set<String> scopes = java.util.Arrays.stream(
                        props.getProxyAuth().getScopes().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        request.setAttribute(ADMIN_PRINCIPAL_ATTR,
                new com.anindra.path.admin.TenancyService.Principal(identity.trim(), scopes));
        boolean mutating = !"GET".equalsIgnoreCase(request.getMethod())
                && !"HEAD".equalsIgnoreCase(request.getMethod());
        if (mutating && !scopes.contains("write")) {
            throw ApiException.forbidden("This identity has read-only scope.");
        }
    }

    private boolean isPublicModelsRequest(HttpServletRequest request) {
        return props.isPublicModels()
                && "GET".equalsIgnoreCase(request.getMethod())
                && ("/v1/models".equals(request.getRequestURI())
                    || request.getRequestURI().startsWith("/v1/models/"));
    }

    /**
     * Only the liveness probe is public, and it reports status alone. /actuator/health/providers,
     * /actuator/metrics and /actuator/prometheus expose the framework version, datasource
     * internals, repository names and provider topology, so they are gated like /admin.
     */
    private void requireActuatorAccess(HttpServletRequest request, String path) {
        boolean liveness = "GET".equalsIgnoreCase(request.getMethod())
                && "/actuator/health".equals(path);
        if (!liveness) {
            requireAdmin(request);
        }
    }

    /**
     * Resolves the client key, metering failures per address first.
     *
     * The 429 is checked before the key lookup so a guessing campaign never reaches the
     * database, and it is returned for a wrong key exactly as for a flood of them — the
     * response must not reveal whether a guess was at least well-formed.
     */
    private ClientKey authenticateClientKey(HttpServletRequest request) {
        String address = clientAddress(request);
        long retryAfter = clientAuthLimiter.retryAfterSeconds(address);
        if (retryAfter > 0) {
            throw ApiException.rateLimited(
                    "Too many rejected API keys from this address. Retry in " + retryAfter + "s.",
                    (int) retryAfter);
        }
        try {
            ClientKey key = resolveClientKey(request);
            clientAuthLimiter.onSuccess(address);
            return key;
        } catch (ApiException e) {
            clientAuthLimiter.onFailure(address);
            throw e;
        }
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
        // SSO at the edge: an authenticating proxy (Authentik, Keycloak, oauth2-proxy, Cloudflare
        // Access) resolves the human and injects an identity header. The gateway then trusts it
        // instead of a pasted token. This is only safe when the gateway cannot be reached except
        // through that proxy — docs/SSO.md says so in bold, and the startup log repeats it.
        if (props.getProxyAuth().isEnabled()) {
            requireProxyIdentity(request);
            return;
        }

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

    /**
     * The address the limiter counts against.
     *
     * Deliberately does NOT read X-Forwarded-For: a client can prepend any value, and the
     * first hop was being trusted, so rotating it reset the counter on every request. When
     * a trusted edge is in front (Cloudflare), configure client-ip-header and it is used.
     */
    private String clientAddress(HttpServletRequest request) {
        String header = props.getClientIpHeader();
        if (header != null && !header.isBlank()) {
            String value = request.getHeader(header.trim());
            if (value != null && !value.isBlank()) {
                return value.split(",")[0].trim();
            }
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
