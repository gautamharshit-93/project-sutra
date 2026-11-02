package com.sutra.procurement.repository;

import com.sutra.procurement.entity.ComparisonReport;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComparisonReportRepository extends JpaRepository<ComparisonReport, Long> {
    List<ComparisonReport> findByPurchaseRequestIdOrderByReportVersionDesc(Long purchaseRequestId);

    Optional<ComparisonReport> findFirstByPurchaseRequestIdOrderByReportVersionDesc(Long purchaseRequestId);

    @Query("select coalesce(max(r.reportVersion), 0) from ComparisonReport r where r.purchaseRequest.id = :prId")
    int findMaxVersion(@Param("prId") Long purchaseRequestId);
}
