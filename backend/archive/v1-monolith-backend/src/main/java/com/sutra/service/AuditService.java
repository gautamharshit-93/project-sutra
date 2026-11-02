package com.sutra.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.sutra.entity.AuditLog;
import com.sutra.repository.AuditLogRepository;
import com.sutra.response.DecisionResponses.AuditLogResponse;
import com.sutra.response.PageResponse;
import com.sutra.security.CurrentUserProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit trail. Entries join the caller's transaction, so an action and its audit record commit or
 * roll back together. Details must never contain file content or credentials.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository repository, CurrentUserProvider currentUser, ObjectMapper objectMapper) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void record(String entityType, Long entityId, String action, Map<String, ?> details) {
        recordAs(currentUser.username(), entityType, entityId, action, details);
    }

    @Transactional
    public void recordAs(String username, String entityType, Long entityId, String action, Map<String, ?> details) {
        AuditLog log = new AuditLog();
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setAction(action);
        log.setPerformedBy(username);
        log.setDetails(toJson(details));
        repository.save(log);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> search(String entityType, Long entityId, String performedBy,
                                                 int page, int size) {
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, AuditLog::getPerformedAt, AuditLog::getId));
        return PageResponse.of(repository.search(blankToNull(entityType), entityId, blankToNull(performedBy), pageable),
                AuditLogResponse::from);
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> purchaseRequestHistory(Long purchaseRequestId, int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200),
                Sort.by(Sort.Direction.DESC, AuditLog::getPerformedAt, AuditLog::getId));
        return PageResponse.of(repository.purchaseRequestHistory(purchaseRequestId, pageable), AuditLogResponse::from);
    }

    /** Convenience for building ordered detail maps that tolerate null values. */
    public static Map<String, Object> details(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return map;
    }

    private String toJson(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JacksonException e) {
            return "{\"error\":\"details not serialisable\"}";
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
