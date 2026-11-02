package com.sutra.procurement.repository;

import com.sutra.procurement.entity.RequestItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestItemRepository extends JpaRepository<RequestItem, Long> {
    List<RequestItem> findByPurchaseRequestIdOrderByLineNumber(Long purchaseRequestId);
}
