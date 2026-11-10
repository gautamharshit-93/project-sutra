package com.sutra.approval.repository;

import com.sutra.approval.entity.Approval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ApprovalRepository extends JpaRepository<Approval, Long> {

    List<Approval> findByPurchaseRequestIdOrderByDecidedAtDesc(Long purchaseRequestId);

    /** The newest decision of each of the RFQs. */
    @Query("""
            select a from Approval a
            where a.purchaseRequestId in :rfqIds
              and a.decidedAt = (select max(b.decidedAt) from Approval b where b.purchaseRequestId = a.purchaseRequestId)
            """)
    List<Approval> findLatestFor(@Param("rfqIds") Collection<Long> rfqIds);

    long countByDecidedById(Long userId);
}
