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
public class AdminController {

    private final AdminService admin;
    private final AdminReportingService reporting;
    private final ModelSyncService modelSync;
    private final ProviderProbeService probes;

    // ------------------------------------------------------------- providers

    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        return admin.listProviders();
    }

    @PostMapping("/providers")
    public Map<String, Object> createProvider(@RequestBody AdminService.ProviderInput input) {
        return admin.createProvider(input);
    }

    @PatchMapping("/providers/{id}")
    public Map<String, Object> updateProvider(@PathVariable Long id,
                                              @RequestBody AdminService.ProviderInput input) {
        return admin.updateProvider(id, input);
    }

    @DeleteMapping("/providers/{id}")
    public Map<String, Object> deleteProvider(@PathVariable Long id) {
        admin.deleteProvider(id);
        return Map.of("deleted", true, "id", id);
    }

    // ---------------------------------------------------------------- routes

    @GetMapping("/routes")
    public List<Map<String, Object>> routes(@RequestParam(required = false) String model) {
        return admin.listRoutes(model);
    }

    @PostMapping("/routes")
    public Map<String, Object> createRoute(@RequestBody AdminService.RouteInput input) {
        return admin.createRoute(input);
    }

    @PatchMapping("/routes/{id}")
    public Map<String, Object> updateRoute(@PathVariable Long id,
                                           @RequestBody AdminService.RouteInput input) {
        return admin.updateRoute(id, input);
    }

    @DeleteMapping("/routes/{id}")
    public Map<String, Object> deleteRoute(@PathVariable Long id) {
        admin.deleteRoute(id);
        return Map.of("deleted", true, "id", id);
    }

    // ------------------------------------------------------------------ keys

    @GetMapping("/keys")
    public List<Map<String, Object>> keys() {
        return admin.listKeys();
    }

    @PostMapping("/keys")
    public Map<String, Object> createKey(@RequestBody AdminService.KeyInput input) {
        return admin.createKey(input);
    }

    @PatchMapping("/keys/{id}")
    public Map<String, Object> updateKey(@PathVariable Long id,
                                         @RequestBody AdminService.KeyInput input) {
        return admin.updateKey(id, input);
    }

    @PostMapping("/keys/{id}/revoke")
    public Map<String, Object> revokeKey(@PathVariable Long id) {
        return admin.revokeKey(id);
    }

    @PostMapping("/keys/{id}/topup")
    public Map<String, Object> topUp(@PathVariable Long id, @RequestBody Map<String, BigDecimal> body) {
        return admin.topUpKey(id, body.get("amount_usd"));
    }

    // ------------------------------------------------------------- reporting

    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestParam(defaultValue = "24h") String window) {
        return reporting.stats(window);
    }

    @GetMapping("/usage")
    public Map<String, Object> usage(@RequestParam(required = false) Long keyId,
                                     @RequestParam(defaultValue = "24h") String window,
                                     @RequestParam(defaultValue = "100") int limit) {
        return reporting.usage(keyId, window, limit);
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
            @RequestParam(defaultValue = "30d") String window,
            @RequestParam(defaultValue = "10000") int limit) {
        java.time.Duration parsed = com.anindra.path.api.Durations.cap(
                com.anindra.path.api.Durations.parse(window, java.time.Duration.ofDays(30)),
                java.time.Duration.ofDays(365));
        java.time.Instant to = java.time.Instant.now();
        java.time.Instant from = to.minus(parsed);
        String csv = reporting.usageCsv(keyId, from, to, limit);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"usage-" + to + ".csv\"")
                .body(csv);
    }

    @PostMapping("/models/sync")
    public Map<String, Object> syncModels() {
        return modelSync.sync();
    }
}
