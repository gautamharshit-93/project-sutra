package com.sutra.repository;

import com.sutra.entity.Quotation;
import com.sutra.entity.enums.QuotationStatus;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuotationRepository extends JpaRepository<Quotation, Long> {
    List<Quotation> findByPurchaseRequestIdOrderByIdAsc(Long purchaseRequestId);

    List<Quotation> findByPurchaseRequestIdAndStatusIn(Long purchaseRequestId, Collection<QuotationStatus> statuses);

    long countByStatus(QuotationStatus status);

    long countByVendorId(Long vendorId);

    long countByUploadedById(Long userId);

    @Query("select count(c) from ComparisonQuotation c where c.quotation.id = :quotationId")
    long countComparisonReferences(@Param("quotationId") Long quotationId);

    @Query("""
            select count(f) > 0 from QuotationFile f
            where f.sha256 = :sha256 and f.quotation.purchaseRequest.id = :purchaseRequestId
            """)
    boolean existsFileInRequest(@Param("sha256") String sha256, @Param("purchaseRequestId") Long purchaseRequestId);
}
