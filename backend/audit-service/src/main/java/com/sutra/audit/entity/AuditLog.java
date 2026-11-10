package com.sutra.audit.entity;

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
 * One entry of the audit trail, as sent by the service where the action happened. Never changed or deleted (a
 * database trigger refuses both). {@code hash} = SHA-256 over this entry's content and {@code previousHash}, so a
 * changed, removed or re-ordered entry breaks the chain (see {@code AuditHashCalculator}).
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Column(nullable = false, length = 40, updatable = false)
    private String service;

    @Column(name = "entity_type", nullable = false, length = 50, updatable = false)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private Long entityId;

    /** The RFQ this entry belongs to (for quotations, comparisons and awards too), when there is one. */
    @Column(name = "purchase_request_id", updatable = false)
    private Long purchaseRequestId;

    @Column(nullable = false, length = 50, updatable = false)
    private String action;

    @Column(name = "performed_by", nullable = false, length = 60, updatable = false)
    private String performedBy;

    @Column(name = "performed_at", nullable = false, updatable = false)
    private Instant performedAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", updatable = false)
    private String details;

    /** Follows one user action through every service (X-Correlation-Id). */
    @Column(name = "correlation_id", length = 64, updatable = false)
    private String correlationId;

    /** Position in the hash chain: 1, 2, 3 ... in the order entries were stored. */
    @Column(name = "sequence_no", unique = true)
    private Long sequenceNo;

    @Column(name = "previous_hash", length = 64)
    private String previousHash;

    @Column(length = 64)
    private String hash;
}
