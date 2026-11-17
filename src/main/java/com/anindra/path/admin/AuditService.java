package com.anindra.path.admin;

import com.anindra.path.domain.AdminAudit;
import com.anindra.path.repo.AdminAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Records every mutating admin action; read back through /admin/audit. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private static final int MAX_DETAILS = 4000;

    private final AdminAuditRepository audit;
    private final ObjectMapper mapper;

    public void record(String action, String target, Object details) {
        try {
            AdminAudit entry = new AdminAudit();
            entry.setActor("shared-admin");
            entry.setAction(action);
            entry.setTarget(target);
            String json = details == null ? null : mapper.writeValueAsString(details);
            entry.setDetails(json != null && json.length() > MAX_DETAILS
                    ? json.substring(0, MAX_DETAILS) : json);
            audit.save(entry);
        } catch (Exception e) {
            // Auditing must never break the operation it describes.
            log.warn("Could not record audit entry {}: {}", action, e.getMessage());
        }
    }

    public List<Map<String, Object>> list(String action, int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        List<AdminAudit> rows = action == null || action.isBlank()
                ? audit.findAllByOrderByCreatedAtDesc(PageRequest.of(0, capped))
                : audit.findByActionOrderByCreatedAtDesc(action, PageRequest.of(0, capped));

        List<Map<String, Object>> out = new ArrayList<>();
        for (AdminAudit row : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("at", row.getCreatedAt().toString());
            item.put("actor", row.getActor());
            item.put("action", row.getAction());
            item.put("target", row.getTarget());
            item.put("details", row.getDetails());
            out.add(item);
        }
        return out;
    }
}
