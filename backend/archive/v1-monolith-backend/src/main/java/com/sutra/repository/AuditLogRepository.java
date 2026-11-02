package com.sutra.repository;

import com.sutra.entity.AuditLog;
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

    /** Everything that happened to a purchase request, its quotations, comparison reports and approvals. */
    @Query("""
            select a from AuditLog a
            where (a.entityType = 'PURCHASE_REQUEST' and a.entityId = :prId)
               or (a.entityType = 'QUOTATION'
                   and a.entityId in (select q.id from Quotation q where q.purchaseRequest.id = :prId))
               or (a.entityType = 'COMPARISON_REPORT'
                   and a.entityId in (select r.id from ComparisonReport r where r.purchaseRequest.id = :prId))
               or (a.entityType = 'APPROVAL'
                   and a.entityId in (select p.id from Approval p where p.purchaseRequest.id = :prId))
            """)
    Page<AuditLog> purchaseRequestHistory(@Param("prId") Long prId, Pageable pageable);
}
