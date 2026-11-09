package com.sutra.procurement.service;

import com.sutra.procurement.ai.ExtractionChoice;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Starts extraction in the background once the upload (or retry) transaction has committed. */
@Component
public class QuotationProcessingEvents {

    /** Published when a quotation should be (re)processed with the given engine and preferred Groq model. */
    public record ProcessQuotationRequested(Long quotationId, ExtractionChoice choice) {
        public ProcessQuotationRequested {
            choice = choice == null ? ExtractionChoice.AUTO : choice;
        }

        public ProcessQuotationRequested(Long quotationId) {
            this(quotationId, ExtractionChoice.AUTO);
        }
    }

    private final QuotationProcessingService processingService;

    public QuotationProcessingEvents(@Lazy QuotationProcessingService processingService) {
        this.processingService = processingService;
    }

    @Async("extractionExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onProcessRequested(ProcessQuotationRequested event) {
        processingService.process(event.quotationId(), event.choice());
    }
}
