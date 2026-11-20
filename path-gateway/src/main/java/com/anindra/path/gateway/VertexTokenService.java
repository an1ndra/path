package com.anindra.path.gateway;

import com.anindra.path.domain.Provider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth2 access tokens for Vertex AI, from a service-account JSON.
 *
 * <p>The credential is the JSON key file Google issues (client_email, private_key,
 * token_uri). It is stored in the provider's encrypted credential column like any other
 * secret, so it is AES-GCM at rest and never shown by the admin API.
 *
 * <p>The exchange is the standard JWT-bearer flow: sign a short assertion with the
 * service-account private key, POST it to the token endpoint, keep the access token until
 * shortly before it expires. Tokens are cached per provider, so a request does not pay for
 * an OAuth round-trip.
 */
@Component
@Slf4j
public class VertexTokenService {

    private static final String SCOPE = "https://www.googleapis.com/auth/cloud-platform";

    private record Cached(String token, long expiresAtMs) {
    }

    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newHttpClient();

    public VertexTokenService(com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param serviceAccountJson the decrypted provider credential
     * @return an access token, from the cache when it is still valid
     */
    public String accessToken(Provider provider, String serviceAccountJson) {
        Cached cached = cache.get(provider.getId());
        if (cached != null && cached.expiresAtMs() > System.currentTimeMillis() + 60_000) {
            return cached.token();
        }
        Cached fresh = exchange(serviceAccountJson);
        cache.put(provider.getId(), fresh);
        return fresh.token();
    }

    private Cached exchange(String serviceAccountJson) {
        com.fasterxml.jackson.databind.JsonNode account;
        try {
            account = mapper.readTree(serviceAccountJson);
        } catch (Exception e) {
            throw new UpstreamException(400, "vertex_credentials_invalid",
                    "The Vertex credential is not a service-account JSON key.");
        }
        String email = account.path("client_email").asText(null);
        String privateKeyPem = account.path("private_key").asText(null);
        String tokenUri = account.path("token_uri").asText("https://oauth2.googleapis.com/token");
        if (email == null || privateKeyPem == null) {
            throw new UpstreamException(400, "vertex_credentials_invalid",
                    "The service-account JSON needs client_email and private_key.");
        }

        String assertion = jwt(email, privateKeyPem, tokenUri);
        String form = "grant_type=" + URLEncoder.encode(
                "urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUri))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new UpstreamException(response.statusCode(), "vertex_auth_failed",
                        "Google rejected the service-account assertion: " + response.body());
            }
            com.fasterxml.jackson.databind.JsonNode body = mapper.readTree(response.body());
            String token = body.path("access_token").asText(null);
            if (token == null) {
                throw new UpstreamException(502, "vertex_auth_failed",
                        "The token endpoint returned no access_token.");
            }
            long ttl = body.path("expires_in").asLong(3600);
            return new Cached(token, System.currentTimeMillis() + ttl * 1000);
        } catch (java.io.IOException e) {
            throw new UpstreamException(502, "vertex_auth_failed",
                    "Could not reach the Google token endpoint: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(502, "vertex_auth_failed", "Token exchange interrupted.");
        }
    }

    /** RS256 JWT with the claims Google's JWT-bearer flow expects. */
    String jwt(String email, String privateKeyPem, String audience) {
        long now = System.currentTimeMillis() / 1000;
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = base64Url(("{\"iss\":\"" + email + "\",\"scope\":\"" + SCOPE
                + "\",\"aud\":\"" + audience + "\",\"iat\":" + now + ",\"exp\":" + (now + 3600)
                + "}").getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + claims;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey(privateKeyPem));
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + base64Url(signature.sign());
        } catch (Exception e) {
            throw new UpstreamException(400, "vertex_credentials_invalid",
                    "Could not sign the service-account assertion: " + e.getMessage());
        }
    }

    private static PrivateKey privateKey(String pem) throws Exception {
        String body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
