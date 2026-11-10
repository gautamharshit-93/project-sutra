package com.sutra.quotation.repository;

import com.sutra.api.enums.QuotationStatus;
import com.sutra.quotation.entity.Quotation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface QuotationRepository extends JpaRepository<Quotation, Long> {

    List<Quotation> findByPurchaseRequestIdOrderByIdAsc(Long purchaseRequestId);

    long countByPurchaseRequestId(Long purchaseRequestId);

    long countByStatus(QuotationStatus status);

    long countByVendorId(Long vendorId);

    long countByUploadedById(Long userId);

    /** A vendor's own quotations, newest first (vendor portal). */
    List<Quotation> findByVendorIdOrderByIdDesc(Long vendorId);

    List<Quotation> findByPurchaseRequestIdAndVendorId(Long purchaseRequestId, Long vendorId);


    /** Of the given RFQs, those that have at least one quotation ready to compare. */
    @Query("""
            select distinct q.purchaseRequestId from Quotation q
            where q.purchaseRequestId in :rfqIds
              and q.status = com.sutra.api.enums.QuotationStatus.READY_FOR_COMPARISON
            """)
    List<Long> findRfqIdsWithReadyQuotations(@Param("rfqIds") Collection<Long> rfqIds);

    @Query("""
            select distinct i.requestItemId from QuotationItem i
            where i.quotation.purchaseRequestId = :rfqId and i.requestItemId is not null
            """)
    List<Long> findMatchedRequestItemIds(@Param("rfqId") Long rfqId);

    @Query("""
            select count(f) > 0 from QuotationFile f
            where f.sha256 = :sha256 and f.quotation.purchaseRequestId = :purchaseRequestId
            """)
    boolean existsFileInRequest(@Param("sha256") String sha256, @Param("purchaseRequestId") Long purchaseRequestId);
}
