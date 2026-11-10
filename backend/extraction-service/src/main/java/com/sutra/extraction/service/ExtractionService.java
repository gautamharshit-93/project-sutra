package com.sutra.extraction.service;

import com.sutra.api.internal.InternalContracts.ExtractionTask;
import com.sutra.extraction.dto.response.ExtractionJobResponse;

import java.util.List;

/**
 * Reading quotations, as jobs: a job is queued (the caller gets 202 and the job's status URL), runs in the
 * background, and hands its result to the quotation service.
 */
public interface ExtractionService {

    /** Queues a reading. The engine/model choice is checked first (e.g. GROQ without a key is refused). */
    ExtractionJobResponse submit(ExtractionTask task);

    /** Runs one queued job (called on the extraction thread pool). */
    void run(Long jobId);

    ExtractionJobResponse get(Long jobId);

    List<ExtractionJobResponse> history(Long quotationId);
}
