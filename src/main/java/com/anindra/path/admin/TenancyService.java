package com.anindra.path.admin;

import com.anindra.path.api.ApiException;
import com.anindra.path.domain.AdminToken;
import com.anindra.path.domain.Project;
import com.anindra.path.gateway.ApiKeyService;
import com.anindra.path.repo.AdminTokenRepository;
import com.anindra.path.repo.ClientKeyRepository;
import com.anindra.path.repo.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Projects group keys and usage; admin tokens carry read or read+write scopes. */
@Service
@RequiredArgsConstructor
public class TenancyService {

    public record Principal(String name, Set<String> scopes) {
        public boolean canWrite() {
            return scopes.contains("write");
        }
    }

    public record CreatedToken(AdminToken token, String plaintext) {
    }

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ProjectRepository projects;
    private final AdminTokenRepository tokens;
    private final ClientKeyRepository keys;

    // ------------------------------------------------------------- projects

    public List<Map<String, Object>> listProjects() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Project project : projects.findAllByOrderBySlugAsc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", project.getId());
            row.put("slug", project.getSlug());
            row.put("name", project.getName());
            row.put("created_at", project.getCreatedAt().toString());
            row.put("key_count", keys.findByProjectIdOrderByCreatedAtDesc(project.getId()).size());
            out.add(row);
        }
        return out;
    }

    @Transactional
    public Map<String, Object> createProject(String slug, String name) {
        if (slug == null || slug.isBlank()) {
            throw ApiException.badRequest("slug is required", "slug");
        }
        if (projects.findBySlug(slug.trim()).isPresent()) {
            throw new ApiException(409, "invalid_request_error", "project_exists",
                    "A project with slug '" + slug + "' already exists.");
        }
        Project project = new Project(slug.trim().toLowerCase(),
                name == null || name.isBlank() ? slug.trim() : name.trim());
        projects.save(project);
        return listProjects().stream()
                .filter(p -> project.getId().equals(p.get("id")))
                .findFirst().orElseThrow();
    }

    @Transactional
    public void deleteProject(Long id) {
        Project project = projects.findById(id)
                .orElseThrow(() -> ApiException.notFound("No project with id " + id, "project_not_found"));
        if (!keys.findByProjectIdOrderByCreatedAtDesc(id).isEmpty()) {
            throw new ApiException(409, "invalid_request_error", "project_not_empty",
                    "Move or delete the project's keys before deleting it.");
        }
        projects.delete(project);
    }

    // --------------------------------------------------------------- tokens

    public List<Map<String, Object>> listTokens() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AdminToken token : tokens.findAllByOrderByCreatedAtDesc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", token.getId());
            row.put("name", token.getName());
            row.put("masked_token", token.getTokenPrefix() + "..." + token.getTokenSuffix());
            row.put("scopes", token.getScopes());
            row.put("enabled", token.isEnabled());
            row.put("created_at", token.getCreatedAt().toString());
            out.add(row);
        }
        return out;
    }

    @Transactional
    public CreatedToken createToken(String name, String scopes) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("name is required", "name");
        }
        String normalized = normalizeScopes(scopes);
        String secret = "adm_" + random(40);
        AdminToken token = new AdminToken();
        token.setName(name.trim());
        token.setTokenHash(ApiKeyService.hash(secret));
        token.setTokenPrefix(secret.substring(0, 12));
        token.setTokenSuffix(secret.substring(secret.length() - 4));
        token.setScopes(normalized);
        tokens.save(token);
        return new CreatedToken(token, secret);
    }

    @Transactional
    public Map<String, Object> revokeToken(Long id) {
        AdminToken token = tokens.findById(id)
                .orElseThrow(() -> ApiException.notFound("No token with id " + id, "token_not_found"));
        token.setEnabled(false);
        tokens.save(token);
        return listTokens().stream().filter(t -> id.equals(t.get("id"))).findFirst().orElseThrow();
    }

    public Optional<Principal> authenticate(String rawToken) {
        return tokens.findByTokenHash(ApiKeyService.hash(rawToken))
                .filter(AdminToken::isEnabled)
                .map(token -> new Principal(token.getName(), scopesOf(token.getScopes())));
    }

    private static String normalizeScopes(String scopes) {
        Set<String> parsed = scopesOf(scopes);
        if (parsed.isEmpty()) {
            throw ApiException.badRequest("scopes must contain 'read' and/or 'write'", "scopes");
        }
        return parsed.stream().sorted().collect(Collectors.joining(","));
    }

    private static Set<String> scopesOf(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return Set.of("read");
        }
        return java.util.Arrays.stream(scopes.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> s.equals("read") || s.equals("write"))
                .collect(Collectors.toSet());
    }

    private static String random(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
