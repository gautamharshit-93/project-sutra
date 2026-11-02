package com.sutra.procurement.repository;

import com.sutra.procurement.entity.Approval;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRepository extends JpaRepository<Approval, Long> {
    List<Approval> findByPurchaseRequestIdOrderByDecidedAtDesc(Long purchaseRequestId);
}
