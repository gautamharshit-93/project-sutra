package com.sutra.quotation.service.internal;

import com.sutra.api.enums.QuotationStatus;
import com.sutra.api.extraction.ExtractionChoice;
import com.sutra.common.audit.AuditService;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.quotation.client.DecisionClient;
import com.sutra.quotation.repository.QuotationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import static com.sutra.common.audit.AuditService.details;

/**
 * Asks the extraction service to read a quotation once the upload (or retry) has committed. If the extraction
 * service cannot take it, the quotation becomes FAILED with the reason, keeps its files, and can be read again.
 */
@Component
public class ReadingQueue {

    private static final Logger log = LoggerFactory.getLogger(ReadingQueue.class);

    private final DecisionClient decisions;
    private final QuotationRepository quotations;
    private final AuditService auditService;
    private final TransactionTemplate tx;

    public ReadingQueue(DecisionClient decisions, QuotationRepository quotations, AuditService auditService,
                        TransactionTemplate tx) {
        this.decisions = decisions;
        this.quotations = quotations;
        this.auditService = auditService;
        this.tx = tx;
    }

    public void queue(Long quotationId, ExtractionChoice choice) {
        AfterCommit.run(() -> {
            try {
                decisions.read(quotationId, choice);
            } catch (RuntimeException e) {
                log.warn("Reading of quotation {} could not be queued: {}", quotationId, e.getMessage());
                fail(quotationId, "The quotation could not be sent for reading (" + e.getMessage()
                        + "). Read it again, or enter it manually.");
            }
        });
    }

    private void fail(Long quotationId, String reason) {
        tx.executeWithoutResult(status -> quotations.findById(quotationId).ifPresent(q -> {
            q.setStatus(QuotationStatus.FAILED);
            q.setFailureReason(reason.length() > 1000 ? reason.substring(0, 1000) : reason);
            auditService.recordAs(CurrentUserProvider.SYSTEM_USER, "QUOTATION", quotationId, "EXTRACTION_FAILED",
                    details("reason", q.getFailureReason()));
        }));
    }
}
