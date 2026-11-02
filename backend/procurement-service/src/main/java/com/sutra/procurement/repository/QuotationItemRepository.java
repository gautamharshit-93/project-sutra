package com.sutra.procurement.repository;

import com.sutra.procurement.entity.QuotationItem;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QuotationItemRepository extends JpaRepository<QuotationItem, Long> {
    Optional<QuotationItem> findByIdAndQuotationId(Long id, Long quotationId);
}
