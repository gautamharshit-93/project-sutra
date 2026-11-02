package com.sutra.repository;

import com.sutra.entity.QuotationFile;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuotationFileRepository extends JpaRepository<QuotationFile, Long> {

    /** All files of a quotation, including their bytes, in upload order. */
    List<QuotationFile> findByQuotationIdOrderByIdAsc(Long quotationId);

    Optional<QuotationFile> findByIdAndQuotationId(Long id, Long quotationId);

    long countByQuotationId(Long quotationId);

    /** Metadata only - avoids pulling the BYTEA content into memory. */
    @Query("""
            select new com.sutra.repository.QuotationFileMetadata(
                f.id, f.quotation.id, f.fileName, f.contentType, f.fileFormat, f.sizeBytes, f.sha256, f.uploadedAt)
            from QuotationFile f where f.quotation.id = :quotationId order by f.id
            """)
    List<QuotationFileMetadata> findMetadataByQuotationId(@Param("quotationId") Long quotationId);
}
