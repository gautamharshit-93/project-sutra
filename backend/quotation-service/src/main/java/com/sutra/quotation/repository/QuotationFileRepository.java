package com.sutra.quotation.repository;

import com.sutra.quotation.entity.QuotationFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QuotationFileRepository extends JpaRepository<QuotationFile, Long> {

    /** All files of a quotation (references to the document service), in upload order. */
    List<QuotationFile> findByQuotationIdOrderByIdAsc(Long quotationId);

    Optional<QuotationFile> findByIdAndQuotationId(Long id, Long quotationId);

    long countByQuotationId(Long quotationId);
}
