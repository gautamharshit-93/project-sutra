package com.sutra.audit.repository;

import com.sutra.audit.entity.AuditLog;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("""
            select a from AuditLog a
            where (:entityType is null or a.entityType = :entityType)
              and (:entityId is null or a.entityId = :entityId)
              and (:performedBy is null or a.performedBy = :performedBy)
            """)
    Page<AuditLog> search(@Param("entityType") String entityType,
                          @Param("entityId") Long entityId,
                          @Param("performedBy") String performedBy,
                          Pageable pageable);

    /** Everything that happened to a purchase request, its quotations, comparison reports and award. */
    @Query("""
            select a from AuditLog a
            where (a.entityType = 'PURCHASE_REQUEST' and a.entityId = :prId) or a.purchaseRequestId = :prId
            """)
    Page<AuditLog> purchaseRequestHistory(@Param("prId") Long prId, Pageable pageable);

    @Query("select a.eventId from AuditLog a where a.eventId in :ids")
    List<UUID> findExistingEventIds(@Param("ids") Collection<UUID> ids);

    /** Entries stored before the hash chain existed, oldest first (linked into the chain once, at start-up). */
    List<AuditLog> findTop500BySequenceNoIsNullOrderByIdAsc();

    /** The next page of the chain after {@code sequenceNo} (keyset paging: constant cost however long the log). */
    List<AuditLog> findBySequenceNoGreaterThanOrderBySequenceNoAsc(long sequenceNo, Pageable pageable);

    long countBySequenceNoIsNull();
}
