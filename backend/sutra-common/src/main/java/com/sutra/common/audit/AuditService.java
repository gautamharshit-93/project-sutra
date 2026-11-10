package com.sutra.common.audit;

import com.sutra.common.web.CorrelationIdFilter;
import com.sutra.common.security.CurrentUserProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Records audit events (transactional outbox). Each event is written to this service's outbox in the caller's
 * transaction - {@code Propagation.MANDATORY} refuses a call outside one - so an action and its audit record commit
 * or roll back together; {@link AuditOutboxRelay} then delivers it to the audit
 * service. Details must never contain file content or credentials.
 */
@Service
public class AuditService {

    private final AuditOutboxRepository outbox;
    private final CurrentUserProvider currentUser;
    private final ObjectMapper objectMapper;
    private final AuditScopeResolver scopeResolver;

    public AuditService(AuditOutboxRepository outbox, CurrentUserProvider currentUser, ObjectMapper objectMapper,
                        AuditScopeResolver scopeResolver) {
        this.outbox = outbox;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
        this.scopeResolver = scopeResolver;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String entityType, Long entityId, String action, Map<String, ?> details) {
        recordAs(currentUser.username(), entityType, entityId, action, details);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAs(String username, String entityType, Long entityId, String action, Map<String, ?> details) {
        AuditOutboxEvent event = new AuditOutboxEvent();
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setPurchaseRequestId(scopeResolver.purchaseRequestIdOf(entityType, entityId));
        event.setAction(action);
        event.setPerformedBy(username);
        event.setDetails(toJson(details));
        event.setCorrelationId(CorrelationIdFilter.currentOrNew());
        outbox.save(event);
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
}
