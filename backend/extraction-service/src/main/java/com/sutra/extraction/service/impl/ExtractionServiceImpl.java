package com.sutra.extraction.service.impl;

import com.sutra.api.extraction.ExtractionChoice;
import com.sutra.api.internal.InternalContracts.ExtractionOutcome;
import com.sutra.api.internal.InternalContracts.ExtractionTask;
import com.sutra.api.internal.InternalContracts.ExtractionWork;
import com.sutra.api.internal.InternalContracts.LineMatch;
import com.sutra.api.internal.InternalContracts.RequestedLine;
import com.sutra.common.error.ResourceNotFoundException;
import com.sutra.extraction.client.DocumentStoreClient;
import com.sutra.extraction.client.QuotationClient;
import com.sutra.extraction.dto.response.ExtractionJobResponse;
import com.sutra.extraction.entity.ExtractionJob;
import com.sutra.extraction.exception.QuotationProcessingException;
import com.sutra.extraction.repository.ExtractionJobRepository;
import com.sutra.extraction.service.ExtractionService;
import com.sutra.extraction.service.internal.ExtractionEngines;
import com.sutra.extraction.service.internal.ExtractionInput;
import com.sutra.extraction.service.internal.HybridItemMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Runs readings as jobs:
 * <ol>
 *   <li>{@link #submit} stores a QUEUED job and, after it commits, hands it to the extraction pool.</li>
 *   <li>{@link #run} claims the quotation (the quotation service marks it PROCESSING and returns its files and
 *       requested lines), fetches the files from the document service, reads them (Groq first, the rule-based
 *       reader as backup, or the engine asked for), matches the lines, and hands the result back.</li>
 *   <li>On failure the quotation service is told why; the quotation becomes FAILED and keeps its files.</li>
 * </ol>
 * No database transaction is open while the engines run: reading can take minutes.
 */
@Service
public class ExtractionServiceImpl implements ExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionServiceImpl.class);

    private final ExtractionJobRepository jobs;
    private final QuotationClient quotations;
    private final DocumentStoreClient documents;
    private final ExtractionEngines engines;
    private final HybridItemMatcher matcher;
    private final TransactionTemplate tx;
    private final Executor executor;
    private final ExtractionService self;
    private final Clock clock;

    public ExtractionServiceImpl(ExtractionJobRepository jobs, QuotationClient quotations,
                                 DocumentStoreClient documents, ExtractionEngines engines, HybridItemMatcher matcher,
                                 TransactionTemplate tx, @Qualifier("extractionExecutor") Executor executor,
                                 @Lazy ExtractionService self, Clock clock) {
        this.jobs = jobs;
        this.quotations = quotations;
        this.documents = documents;
        this.engines = engines;
        this.matcher = matcher;
        this.tx = tx;
        this.executor = executor;
        this.self = self;
        this.clock = clock;
    }

    @Override
    public ExtractionJobResponse submit(ExtractionTask task) {
        engines.validate(new ExtractionChoice(task.engine(), task.model()));
        ExtractionJob job = tx.execute(status -> {
            ExtractionJob j = new ExtractionJob();
            j.setQuotationId(task.quotationId());
            j.setEngine(new ExtractionChoice(task.engine(), task.model()).engine());
            j.setPreferredModel(new ExtractionChoice(task.engine(), task.model()).model());
            j.setRequestedAt(Instant.now(clock));
            return jobs.save(j);
        });
        dispatch(job.getId());
        return ExtractionJobResponse.from(job);
    }

    private void dispatch(Long jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(() -> self.run(jobId));
                }
            });
        } else {
            executor.execute(() -> self.run(jobId));
        }
    }

    @Override
    public void run(Long jobId) {
        ExtractionJob job = jobs.findById(jobId).orElse(null);
        if (job == null || job.getStatus() != ExtractionJob.Status.QUEUED) {
            return;
        }
        update(jobId, j -> {
            j.setStatus(ExtractionJob.Status.RUNNING);
            j.setStartedAt(Instant.now(clock));
        });
        Long quotationId = job.getQuotationId();
        Optional<ExtractionWork> claimed;
        try {
            claimed = quotations.claim(quotationId);
        } catch (RuntimeException e) {
            finish(jobId, ExtractionJob.Status.FAILED, null, null, null, "Quotation service unavailable: " + e.getMessage());
            return;
        }
        if (claimed.isEmpty()) {
            finish(jobId, ExtractionJob.Status.SKIPPED, null, null, null, "The quotation is already being read, or was deleted");
            return;
        }
        ExtractionWork work = claimed.get();
        ExtractionOutcome outcome;
        try {
            ExtractionInput input = input(work).withPreferredModel(job.getPreferredModel());
            ExtractionEngines.Extraction extracted = engines.extract(input, job.getEngine());
            List<LineMatch> matches = matcher.match(work.requestedLines(), extracted.data().items());
            outcome = new ExtractionOutcome(extracted.data(), extracted.source(), extracted.model(), matches, null);
        } catch (QuotationProcessingException e) {
            outcome = ExtractionOutcome.failed(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unexpected extraction error for quotation {}", quotationId, e);
            outcome = ExtractionOutcome.failed("Unexpected extraction error. Retry, or enter the quotation manually.");
        }
        try {
            quotations.report(quotationId, outcome);
        } catch (RuntimeException e) {
            log.error("Could not hand the reading of quotation {} to the quotation service", quotationId, e);
            finish(jobId, ExtractionJob.Status.FAILED, null, null, null,
                    "The result could not be handed to the quotation service: " + e.getMessage());
            return;
        }
        if (outcome.succeeded()) {
            finish(jobId, ExtractionJob.Status.SUCCEEDED, outcome, outcome.model(), outcome.data().items().size(), null);
        } else {
            finish(jobId, ExtractionJob.Status.FAILED, null, null, null, outcome.failureReason());
        }
    }

    /** Files from the document service, requested lines as context. */
    private ExtractionInput input(ExtractionWork work) {
        if (work.documents() == null || work.documents().isEmpty()) {
            throw new QuotationProcessingException("The original file is missing");
        }
        List<ExtractionInput.Document> files = work.documents().stream()
                .map(d -> new ExtractionInput.Document(d.fileName(), d.format(), documents.content(d.documentId())))
                .toList();
        List<ExtractionInput.RequestedItem> requested = work.requestedLines().stream()
                .map(ExtractionServiceImpl::requestedItem).toList();
        return new ExtractionInput(files, work.vendorName(), work.requestCurrency(), requested);
    }

    private static ExtractionInput.RequestedItem requestedItem(RequestedLine r) {
        return new ExtractionInput.RequestedItem(r.lineNumber(), r.itemCode(), r.description(), r.quantity(),
                r.unitOfMeasure(), r.specifications());
    }

    private void finish(Long jobId, ExtractionJob.Status status, ExtractionOutcome outcome, String model,
                        Integer items, String reason) {
        update(jobId, j -> {
            j.setStatus(status);
            j.setSource(outcome == null ? null : outcome.source());
            j.setModelUsed(model);
            j.setItemsRead(items);
            j.setFailureReason(reason == null ? null : reason.length() > 1000 ? reason.substring(0, 1000) : reason);
            j.setFinishedAt(Instant.now(clock));
        });
    }

    private void update(Long jobId, java.util.function.Consumer<ExtractionJob> change) {
        tx.executeWithoutResult(status -> jobs.findById(jobId).ifPresent(change));
    }

    @Override
    public ExtractionJobResponse get(Long jobId) {
        return jobs.findById(jobId).map(ExtractionJobResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Extraction", jobId));
    }

    @Override
    public List<ExtractionJobResponse> history(Long quotationId) {
        return jobs.findTop20ByQuotationIdOrderByIdDesc(quotationId).stream().map(ExtractionJobResponse::from).toList();
    }

    /**
     * After a restart: queued jobs run again; a job that was reading when the service stopped is reported as
     * failed, so its quotation leaves PROCESSING and can be read again.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeAfterRestart() {
        for (ExtractionJob job : jobs.findByStatusInOrderByIdAsc(EnumSet.of(ExtractionJob.Status.QUEUED,
                ExtractionJob.Status.RUNNING))) {
            if (job.getStatus() == ExtractionJob.Status.QUEUED) {
                executor.execute(() -> self.run(job.getId()));
                continue;
            }
            String reason = "Reading was interrupted by a restart. Read the quotation again.";
            try {
                quotations.report(job.getQuotationId(), ExtractionOutcome.failed(reason));
            } catch (RuntimeException e) {
                log.warn("Could not report the interrupted reading of quotation {}: {}", job.getQuotationId(), e.getMessage());
            }
            finish(job.getId(), ExtractionJob.Status.FAILED, null, null, null, reason);
        }
    }
}
