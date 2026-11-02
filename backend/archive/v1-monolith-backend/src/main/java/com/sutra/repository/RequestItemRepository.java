package com.sutra.repository;

import com.sutra.entity.RequestItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RequestItemRepository extends JpaRepository<RequestItem, Long> {
    List<RequestItem> findByPurchaseRequestIdOrderByLineNumber(Long purchaseRequestId);
}
