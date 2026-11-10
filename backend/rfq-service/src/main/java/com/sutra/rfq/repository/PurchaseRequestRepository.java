package com.sutra.rfq.repository;

import java.util.Collection;
import java.util.List;
import com.sutra.rfq.entity.PurchaseRequest;
import com.sutra.api.enums.PurchaseRequestStatus;
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
    /** Open RFQs raised by {@code userId} (all buyers when null). */
    @Query("""
            select p.id from PurchaseRequest p
            where p.status = com.sutra.api.enums.PurchaseRequestStatus.OPEN
              and (:userId is null or p.requestedBy.id = :userId)
            """)
    List<Long> findOpenIds(@Param("userId") Long userId);

    /** Compared RFQs waiting for a manager's award, leaving out the ones excludeUserId raised (null = all). */
    @Query("""
            select count(p) from PurchaseRequest p
            where p.status = com.sutra.api.enums.PurchaseRequestStatus.UNDER_REVIEW
              and (:excludeUserId is null or p.requestedBy.id <> :excludeUserId)
            """)
    long countAwaitingAward(@Param("excludeUserId") Long excludeUserId);

    /** What a vendor sees: RFQs open for quotations, plus the ones it has quoted on (to follow the outcome). */
    /** What a vendor may see: every open RFQ, plus the ones it quoted on (whatever their status). */
    @Query("""
            select p from PurchaseRequest p
            where p.status = com.sutra.api.enums.PurchaseRequestStatus.OPEN or p.id in :quotedIds
            """)
    Page<PurchaseRequest> visibleToVendor(@Param("quotedIds") Collection<Long> quotedIds, Pageable pageable);

    List<PurchaseRequest> findByIdIn(Collection<Long> ids);

    long countByEntityId(Long entityId);

    long countBySubEntityId(Long subEntityId);

    long countByCustomerId(Long customerId);

    long countByRequestedById(Long userId);

    @Query(value = "select nextval('purchase_request_number_seq')", nativeQuery = true)
    long nextRequestNumber();
}
