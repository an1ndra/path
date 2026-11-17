package com.anindra.path.admin;

import com.anindra.path.domain.AdminAudit;
import com.anindra.path.repo.AdminAuditRepository;
import com.anindra.path.config.AuthFilter;
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
            entry.setActor(currentActor());
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

    /**
     * The identity behind the request: the proxy-authenticated SSO user, the name of the
     * admin token, or "shared-admin" when neither is available. Attribution is the point of
     * the audit log, so this is resolved per request rather than per service instance.
     */
    private static String currentActor() {
        try {
            var attributes = org.springframework.web.context.request.RequestContextHolder
                    .getRequestAttributes();
            if (attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet) {
                Object principal = servlet.getRequest().getAttribute(AuthFilter.ADMIN_PRINCIPAL_ATTR);
                if (principal instanceof com.anindra.path.admin.TenancyService.Principal p
                        && p.name() != null && !p.name().isBlank()) {
                    return p.name();
                }
            }
        } catch (Exception ignored) {
            // outside a request (scheduled work, tests): fall through
        }
        return "shared-admin";
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
