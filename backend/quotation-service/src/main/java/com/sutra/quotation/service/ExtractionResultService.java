package com.sutra.quotation.service;

import com.sutra.api.internal.InternalContracts.ExtractionOutcome;
import com.sutra.api.internal.InternalContracts.ExtractionWork;

/** The quotation side of reading: hands a quotation to the extraction service and stores what it read. */
public interface ExtractionResultService {

    /** Marks the quotation PROCESSING and returns its files and requested lines; 409 if it is already being read. */
    ExtractionWork claim(Long quotationId);

    /** Stores a reading (lines, matches, recalculated totals, flags) or the reason it failed. */
    void receive(Long quotationId, ExtractionOutcome outcome);
}
