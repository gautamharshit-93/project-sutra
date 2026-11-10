package com.sutra.extraction.dto.response;

import com.sutra.api.enums.ExtractionEngine;
import com.sutra.api.enums.ExtractionSource;
import com.sutra.extraction.entity.ExtractionJob;

import java.time.Instant;

/** One reading of a quotation, as shown on its status URL ({@code /api/extractions/{id}}). */
public record ExtractionJobResponse(Long id, Long quotationId, ExtractionEngine engine, String preferredModel,
                                    ExtractionJob.Status status, ExtractionSource source, String modelUsed,
                                    Integer itemsRead, String failureReason, Instant requestedAt, Instant startedAt,
                                    Instant finishedAt, String statusUrl) {

    public static ExtractionJobResponse from(ExtractionJob j) {
        return new ExtractionJobResponse(j.getId(), j.getQuotationId(), j.getEngine(), j.getPreferredModel(),
                j.getStatus(), j.getSource(), j.getModelUsed(), j.getItemsRead(), j.getFailureReason(),
                j.getRequestedAt(), j.getStartedAt(), j.getFinishedAt(), "/api/extractions/" + j.getId());
    }
}
