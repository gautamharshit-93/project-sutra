package com.sutra.repository;

import com.sutra.entity.PurchaseRequest;
import com.sutra.entity.enums.PurchaseRequestStatus;
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

    long countByEntityId(Long entityId);

    long countBySubEntityId(Long subEntityId);

    long countByCustomerId(Long customerId);

    long countByRequestedById(Long userId);

    @Query(value = "select nextval('purchase_request_number_seq')", nativeQuery = true)
    long nextRequestNumber();
}
