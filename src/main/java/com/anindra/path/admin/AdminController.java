package com.anindra.path.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Admin API, guarded by the X-Admin-Token filter. */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@io.swagger.v3.oas.annotations.tags.Tag(name = "Admin", description = "Configuration, keys, operations and reporting")
public class AdminController {

    private final AdminService admin;
    private final AdminReportingService reporting;
    private final ModelSyncService modelSync;
    private final ProviderProbeService probes;
    private final AuditService audit;
    private final PromptLogService promptLogs;
    private final TenancyService tenancy;

    // ------------------------------------------------------------- providers

    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        return admin.listProviders();
    }

    @PostMapping("/providers")
    public Map<String, Object> createProvider(@RequestBody AdminService.ProviderInput input) {
        var result = admin.createProvider(input);
        audit.record("provider.create", String.valueOf(result.get("id")), input);
        return result;
    }

    @PatchMapping("/providers/{id}")
    public Map<String, Object> updateProvider(@PathVariable Long id,
                                              @RequestBody AdminService.ProviderInput input) {
        var result = admin.updateProvider(id, input);
        audit.record("provider.update", String.valueOf(result.get("id")), input);
        return result;
    }

    @DeleteMapping("/providers/{id}")
    public Map<String, Object> deleteProvider(@PathVariable Long id) {
        admin.deleteProvider(id);
        audit.record("provider.delete", "provider:" + id, null);
        return Map.of("deleted", true, "id", id);
    }

    // ---------------------------------------------------------------- routes

    @GetMapping("/routes")
    public List<Map<String, Object>> routes(@RequestParam(required = false) String model) {
        return admin.listRoutes(model);
    }

    @PostMapping("/routes")
    public Map<String, Object> createRoute(@RequestBody AdminService.RouteInput input) {
        var result = admin.createRoute(input);
        audit.record("route.create", String.valueOf(result.get("id")), input);
        return result;
    }

    @PatchMapping("/routes/{id}")
    public Map<String, Object> updateRoute(@PathVariable Long id,
                                           @RequestBody AdminService.RouteInput input) {
        var result = admin.updateRoute(id, input);
        audit.record("route.update", String.valueOf(result.get("id")), input);
        return result;
    }

    @DeleteMapping("/routes/{id}")
    public Map<String, Object> deleteRoute(@PathVariable Long id) {
        admin.deleteRoute(id);
        audit.record("route.delete", "route:" + id, null);
        return Map.of("deleted", true, "id", id);
    }

    // ------------------------------------------------------------------ keys

    @GetMapping("/keys")
    public List<Map<String, Object>> keys(@RequestParam(required = false) Long projectId) {
        return admin.listKeys(projectId);
    }

    @PostMapping("/keys")
    public Map<String, Object> createKey(@RequestBody AdminService.KeyInput input) {
        var result = admin.createKey(input);
        audit.record("key.create", String.valueOf(result.get("id")), input);
        return result;
    }

    @PatchMapping("/keys/{id}")
    public Map<String, Object> updateKey(@PathVariable Long id,
                                         @RequestBody AdminService.KeyInput input) {
        var result = admin.updateKey(id, input);
        audit.record("key.update", String.valueOf(result.get("id")), input);
        return result;
    }

    @PostMapping("/keys/{id}/revoke")
    public Map<String, Object> revokeKey(@PathVariable Long id) {
        var result = admin.revokeKey(id);
        audit.record("key.revoke", String.valueOf(result.get("id")), null);
        return result;
    }

    @PostMapping("/keys/{id}/topup")
    public Map<String, Object> topUp(@PathVariable Long id, @RequestBody Map<String, BigDecimal> body) {
        return admin.topUpKey(id, body.get("amount_usd"));
    }

    @PostMapping("/rotate-master-key")
    public Map<String, Object> rotateMasterKey(@RequestBody Map<String, String> body) {
        var result = admin.rotateMasterKey(body.get("new_key"));
        audit.record("master-key.rotate", "master-key", null);
        return result;
    }

    // -------------------------------------------------------------- tenancy

    @com.fasterxml.jackson.databind.annotation.JsonNaming(
            com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ProjectInput(String slug, String name,
                               @com.fasterxml.jackson.annotation.JsonAlias("creditLimitUsd") java.math.BigDecimal creditLimitUsd,
                               @com.fasterxml.jackson.annotation.JsonAlias("dailyLimitUsd") java.math.BigDecimal dailyLimitUsd,
                               @com.fasterxml.jackson.annotation.JsonAlias("monthlyLimitUsd") java.math.BigDecimal monthlyLimitUsd,
                               @com.fasterxml.jackson.annotation.JsonAlias("rpmLimit") Integer rpmLimit,
                               @com.fasterxml.jackson.annotation.JsonAlias("maxConcurrentRequests")
                               Integer maxConcurrentRequests) {
    }

    public record TokenInput(String name, String scopes) {
    }

    @GetMapping("/projects")
    public List<Map<String, Object>> projects() {
        return tenancy.listProjects();
    }

    @PostMapping("/projects")
    public Map<String, Object> createProject(@RequestBody ProjectInput input) {
        var created = tenancy.createProject(input.slug(), input.name(),
                input.creditLimitUsd(), input.dailyLimitUsd(), input.monthlyLimitUsd(),
                input.rpmLimit(), input.maxConcurrentRequests());
        audit.record("project.create", "project:" + created.get("id"), input);
        return created;
    }

    @PatchMapping("/projects/{id}")
    public Map<String, Object> updateProject(@PathVariable Long id, @RequestBody ProjectInput input) {
        var updated = tenancy.updateProjectLimits(id, input.creditLimitUsd(),
                input.dailyLimitUsd(), input.monthlyLimitUsd(),
                input.rpmLimit(), input.maxConcurrentRequests());
        audit.record("project.update", "project:" + id, input);
        return updated;
    }

    @GetMapping("/projects/{id}/usage")
    public Map<String, Object> projectUsage(@PathVariable Long id) {
        return tenancy.projectSummary(id);
    }

    @DeleteMapping("/projects/{id}")
    public Map<String, Object> deleteProject(@PathVariable Long id) {
        tenancy.deleteProject(id);
        audit.record("project.delete", "project:" + id, null);
        return Map.of("deleted", true, "id", id);
    }

    @GetMapping("/tokens")
    public List<Map<String, Object>> tokens() {
        return tenancy.listTokens();
    }

    @PostMapping("/tokens")
    public Map<String, Object> createToken(@RequestBody TokenInput input) {
        var created = tenancy.createToken(input.name(), input.scopes());
        audit.record("token.create", "token:" + created.token().getId(), input);
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", created.token().getId());
        view.put("name", created.token().getName());
        view.put("scopes", created.token().getScopes());
        view.put("token", created.plaintext());
        view.put("warning", "Store this token now. It is not shown again.");
        return view;
    }

    @PostMapping("/tokens/{id}/revoke")
    public Map<String, Object> revokeToken(@PathVariable Long id) {
        var revoked = tenancy.revokeToken(id);
        audit.record("token.revoke", "token:" + id, null);
        return revoked;
    }

    // ------------------------------------------------------------- reporting

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "24h") String window,
                                     @RequestParam(required = false) Long projectId) {
        return reporting.stats(window, projectId);
    }

    @GetMapping("/usage")
    public Map<String, Object> usage(@RequestParam(required = false) Long keyId,
                                     @RequestParam(required = false) Long projectId,
                                     @RequestParam(defaultValue = "24h") String window,
                                     @RequestParam(defaultValue = "100") int limit) {
        return reporting.usage(keyId, projectId, window, limit);
    }

    @PostMapping("/providers/{id}/probe")
    public Map<String, Object> probe(@PathVariable Long id) {
        return probes.probe(id);
    }

    @GetMapping("/providers/health")
    public List<Map<String, Object>> providerHealth() {
        return probes.summary();
    }

    @GetMapping(value = "/usage/export", produces = "text/csv")
    public org.springframework.http.ResponseEntity<String> exportUsage(
            @RequestParam(required = false) Long keyId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "30d") String window,
            @RequestParam(defaultValue = "10000") int limit) {
        java.time.Duration parsed = com.anindra.path.api.Durations.cap(
                com.anindra.path.api.Durations.parse(window, java.time.Duration.ofDays(30)),
                java.time.Duration.ofDays(365));
        java.time.Instant to = java.time.Instant.now();
        java.time.Instant from = to.minus(parsed);
        String csv = reporting.usageCsv(keyId, projectId, from, to, limit);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"usage-" + to + ".csv\"")
                .body(csv);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> auditLog(@RequestParam(required = false) String action,
                                              @RequestParam(defaultValue = "100") int limit) {
        return audit.list(action, limit);
    }

    @GetMapping("/prompt-logs")
    public List<Map<String, Object>> promptLogs(@RequestParam(required = false) Long keyId,
                                                @RequestParam(defaultValue = "50") int limit) {
        return promptLogs.list(keyId, limit);
    }

    @PostMapping("/models/sync")
    public Map<String, Object> syncModels() {
        return modelSync.sync();
    }
}
