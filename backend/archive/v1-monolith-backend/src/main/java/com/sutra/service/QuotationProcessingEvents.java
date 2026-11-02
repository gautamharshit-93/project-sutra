package com.sutra.service;

import com.sutra.ai.ExtractionEngine;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Starts extraction in the background once the upload (or retry) transaction has committed. */
@Component
public class QuotationProcessingEvents {

    /** Published when a quotation should be (re)processed with the given engine (AUTO by default). */
    public record ProcessQuotationRequested(Long quotationId, ExtractionEngine engine) {
        public ProcessQuotationRequested {
            engine = engine == null ? ExtractionEngine.AUTO : engine;
        }

        public ProcessQuotationRequested(Long quotationId) {
            this(quotationId, ExtractionEngine.AUTO);
        }
    }

    private final QuotationProcessingService processingService;

    public QuotationProcessingEvents(@Lazy QuotationProcessingService processingService) {
        this.processingService = processingService;
    }

    @Async("extractionExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProcessRequested(ProcessQuotationRequested event) {
        processingService.process(event.quotationId(), event.engine());
    }
}
