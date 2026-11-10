package com.sutra.extraction.repository;

import com.sutra.extraction.entity.ExtractionJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ExtractionJobRepository extends JpaRepository<ExtractionJob, Long> {

    List<ExtractionJob> findByStatusInOrderByIdAsc(Collection<ExtractionJob.Status> statuses);

    List<ExtractionJob> findTop20ByQuotationIdOrderByIdDesc(Long quotationId);
}
