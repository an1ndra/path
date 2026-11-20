package com.anindra.path.gateway;

import com.anindra.path.config.GatewayProperties;
import com.anindra.path.domain.ClientKey;
import com.anindra.path.domain.ResponseCacheEntry;
import com.anindra.path.repo.ResponseCacheEntryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Second cache tier: a near-duplicate prompt may reuse a stored completion.
 *
 * <p>The safety of the whole thing rests on staying inside the exact cache's rules — the
 * route opted into caching, the request is {@code temperature: 0} and tool-free, and the
 * entry is scoped to one client key. On top of that this tier is explicitly opt-in per
 * route, because a hit here answers a <em>different</em> prompt.
 *
 * <p>Cost: the prompt has to be embedded, which is an upstream call. That is why the tier
 * is off by default and why it is only consulted after the exact lookup misses.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticCacheService {

    /** @param similarity the cosine distance the match was accepted at, for the response header */
    public record Hit(ResponseCacheEntry entry, double similarity) {
    }

    private final ResponseCacheEntryRepository entries;
    private final EmbeddingsService embeddings;
    private final GatewayProperties props;
    private final ObjectMapper mapper;

    public boolean enabled() {
        String model = props.getCache().getSemanticModel();
        return model != null && !model.isBlank();
    }

    /**
     * @return the prompt vector, or null when the tier is off or embedding failed. A
     *         failure here must never fail the request: the gateway simply behaves as if
     *         the tier were absent.
     */
    public double[] embed(String text, ClientKey key) {
        if (!enabled()) {
            return null;
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.getCache().getSemanticModel());
        body.put("input", text);
        try {
            EmbeddingsService.Result result = embeddings.embed(body, key);
            JsonNode data = result.body().path("data").path(0).path("embedding");
            if (!data.isArray() || data.isEmpty()) {
                return null;
            }
            double[] vector = new double[data.size()];
            for (int i = 0; i < data.size(); i++) {
                vector[i] = data.get(i).asDouble();
            }
            return vector;
        } catch (Exception e) {
            log.debug("Semantic cache skipped: could not embed the prompt ({})", e.getMessage());
            return null;
        }
    }

    /**
     * Scans the candidate entries for this key and model. The candidate set is bounded by
     * {@code semantic-max-entries}, so this stays a small comparison rather than a table
     * scan; a larger deployment wants an ANN index, which is called out in docs/LIMITS.md.
     */
    public Optional<Hit> lookup(Long clientKeyId, String publicModel, double[] vector, double threshold) {
        if (vector == null || vector.length == 0) {
            return Optional.empty();
        }
        List<ResponseCacheEntry> candidates = entries.findByClientKeyIdAndPublicModelIdAndEmbeddingIsNotNull(
                clientKeyId, publicModel,
                PageRequest.of(0, Math.max(1, props.getCache().getSemanticMaxEntries())));

        Instant now = Instant.now();
        double best = -1;
        ResponseCacheEntry bestEntry = null;
        for (ResponseCacheEntry candidate : candidates) {
            if (candidate.getExpiresAt().isBefore(now)) {
                continue;
            }
            double[] other = parse(candidate.getEmbedding());
            if (other == null) {
                continue;
            }
            double similarity = cosine(vector, other);
            if (similarity >= threshold && similarity > best) {
                best = similarity;
                bestEntry = candidate;
            }
        }
        if (bestEntry == null) {
            return Optional.empty();
        }
        return Optional.of(new Hit(bestEntry, best));
    }

    public String encode(double[] vector) {
        if (vector == null || vector.length == 0) {
            return null;
        }
        List<String> parts = new ArrayList<>(vector.length);
        for (double value : vector) {
            parts.add(String.valueOf(value));
        }
        return String.join(",", parts);
    }

    static double cosine(double[] a, double[] b) {
        if (a.length != b.length || a.length == 0) {
            return -1;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return -1;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static double[] parse(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        String[] parts = encoded.split(",");
        double[] vector = new double[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                vector[i] = Double.parseDouble(parts[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return vector;
    }

    public String model() {
        return props.getCache().getSemanticModel();
    }
}
