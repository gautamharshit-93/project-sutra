package com.sutra.repository;

import com.sutra.entity.enums.FileFormat;
import java.time.Instant;

/** Projection of {@link com.sutra.entity.QuotationFile} without the file bytes. */
public record QuotationFileMetadata(
        Long id,
        Long quotationId,
        String fileName,
        String contentType,
        FileFormat fileFormat,
        long sizeBytes,
        String sha256,
        Instant uploadedAt) {
}
