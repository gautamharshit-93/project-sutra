package com.sutra.common.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An audit event waiting to be delivered to the audit service (transactional outbox). It is written in the same
 * transaction as the change it describes, so the two commit or roll back together.
 */
@Entity
@Table(name = "audit_outbox")
@Getter
@Setter
public class AuditOutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId = UUID.randomUUID();

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id")
    private Long entityId;

    @Column(name = "purchase_request_id")
    private Long purchaseRequestId;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "performed_by", nullable = false, length = 60)
    private String performedBy;

    @Column(name = "performed_at", nullable = false)
    private Instant performedAt = Instant.now();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    AuditEventMessage toMessage(String service) {
        return new AuditEventMessage(eventId, service, entityType, entityId, purchaseRequestId, action, performedBy,
                performedAt, details, correlationId);
    }
}
