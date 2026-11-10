package com.sutra.comparison.repository;

import com.sutra.comparison.entity.ComparisonReport;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComparisonReportRepository extends JpaRepository<ComparisonReport, Long> {
    List<ComparisonReport> findByPurchaseRequestIdOrderByReportVersionDesc(Long purchaseRequestId);

    Optional<ComparisonReport> findFirstByPurchaseRequestIdOrderByReportVersionDesc(Long purchaseRequestId);

    @Query("select coalesce(max(r.reportVersion), 0) from ComparisonReport r where r.purchaseRequestId = :prId")
    int findMaxVersion(@Param("prId") Long purchaseRequestId);

    @Query("select count(c) from ComparisonQuotation c where c.id.quotationId = :quotationId")
    long countQuotationReferences(@Param("quotationId") Long quotationId);
}
