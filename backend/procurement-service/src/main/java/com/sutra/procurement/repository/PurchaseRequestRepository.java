package com.sutra.procurement.repository;

import com.sutra.procurement.entity.PurchaseRequest;
import com.sutra.procurement.entity.enums.PurchaseRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseRequestRepository extends JpaRepository<PurchaseRequest, Long> {

    @Query("""
            select p from PurchaseRequest p
            where (:status is null or p.status = :status)
              and (:q is null
                   or lower(p.title) like lower(concat('%', cast(:q as string), '%'))
                   or lower(p.requestNumber) like lower(concat('%', cast(:q as string), '%')))
            """)
    Page<PurchaseRequest> search(@Param("q") String q,
                                 @Param("status") PurchaseRequestStatus status,
                                 Pageable pageable);

    long countByStatus(PurchaseRequestStatus status);

    /** Open RFQs with at least one quotation ready to compare (for one buyer, or all when userId is null). */
    @Query("""
            select count(p) from PurchaseRequest p
            where p.status = com.sutra.procurement.entity.enums.PurchaseRequestStatus.OPEN
              and (:userId is null or p.requestedBy.id = :userId)
              and exists (select q.id from Quotation q where q.purchaseRequest = p
                          and q.status = com.sutra.procurement.entity.enums.QuotationStatus.READY_FOR_COMPARISON)
            """)
    long countReadyToCompare(@Param("userId") Long userId);

    /** Compared RFQs waiting for a manager's award, leaving out the ones excludeUserId raised (null = all). */
    @Query("""
            select count(p) from PurchaseRequest p
            where p.status = com.sutra.procurement.entity.enums.PurchaseRequestStatus.UNDER_REVIEW
              and (:excludeUserId is null or p.requestedBy.id <> :excludeUserId)
            """)
    long countAwaitingAward(@Param("excludeUserId") Long excludeUserId);

    /** What a vendor sees: RFQs open for quotations, plus the ones it has quoted on (to follow the outcome). */
    @Query("""
            select p from PurchaseRequest p
            where p.status = com.sutra.procurement.entity.enums.PurchaseRequestStatus.OPEN
               or exists (select 1 from Quotation q where q.purchaseRequest = p and q.vendor.id = :vendorId)
            """)
    Page<PurchaseRequest> visibleToVendor(@Param("vendorId") Long vendorId, Pageable pageable);

    long countByEntityId(Long entityId);

    long countBySubEntityId(Long subEntityId);

    long countByCustomerId(Long customerId);

    long countByRequestedById(Long userId);

    @Query(value = "select nextval('purchase_request_number_seq')", nativeQuery = true)
    long nextRequestNumber();
}
