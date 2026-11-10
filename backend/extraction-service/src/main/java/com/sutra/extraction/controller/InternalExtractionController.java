package com.sutra.extraction.controller;

import com.sutra.api.internal.InternalContracts.AiCompletion;
import com.sutra.api.internal.InternalContracts.AiCompletionRequest;
import com.sutra.api.internal.InternalContracts.AiStatus;
import com.sutra.api.internal.InternalContracts.ExtractionTask;
import com.sutra.extraction.client.DocumentProcessingClient;
import com.sutra.extraction.client.GroqClient;
import com.sutra.extraction.dto.response.ExtractionJobResponse;
import com.sutra.extraction.service.ExtractionService;
import com.sutra.extraction.service.internal.ExtractionEngines;
import com.sutra.extraction.service.internal.QuotationExtractor;
import com.sutra.api.extraction.ExtractionChoice;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * For the other services (service tokens only): queue a reading of a quotation, and the AI (Groq) chat completion
 * and status used by the reporting service's assistant and dashboard. This service is the only one that holds the
 * Groq key and talks to the AI.
 */
@RestController
@RequestMapping("/internal")
public class InternalExtractionController {

    private final ExtractionService extractions;
    private final GroqClient groq;
    private final ExtractionEngines engines;
    private final DocumentProcessingClient documentReader;
    private final com.sutra.extraction.service.RfqDraftService drafts;

    public InternalExtractionController(ExtractionService extractions, GroqClient groq, ExtractionEngines engines,
                                        DocumentProcessingClient documentReader,
                                        com.sutra.extraction.service.RfqDraftService drafts) {
        this.extractions = extractions;
        this.groq = groq;
        this.engines = engines;
        this.documentReader = documentReader;
        this.drafts = drafts;
    }

    /** Queues a reading; answers at once with the job and its status URL. */
    @PostMapping("/extractions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExtractionJobResponse submit(@RequestBody ExtractionTask task) {
        return extractions.submit(task);
    }

    /** Refuses an engine/model that cannot be used right now (e.g. GROQ without a key); nothing is queued. */
    @PostMapping("/extractions/check")
    public ResponseEntity<Void> check(@RequestBody ExtractionTask task) {
        engines.validate(new ExtractionChoice(task.engine(), task.model()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ai/complete")
    public AiCompletion complete(@RequestBody AiCompletionRequest request) {
        GroqClient.Completion c = groq.complete(request.messages(), request.responseFormat(),
                request.maxOutputTokens(), null);
        return new AiCompletion(c.content(), c.model(), c.inputTokens(), c.outputTokens());
    }

    @GetMapping("/ai/status")
    public AiStatus status() {
        QuotationExtractor first = engines.firstAvailable(); // the rule-based reader when Groq is not configured
        return new AiStatus(groq.isConfigured(), groq.model(),
                first == null ? "NONE" : String.valueOf(first.source()), first == null ? "none" : first.model(),
                first != null, documentReader.isAvailable());
    }

    /** An RFQ pasted into the assistant, read into a draft (nothing saved). */
    @PostMapping("/rfq-drafts/from-text")
    public com.sutra.api.rfq.RfqDraftResponses.RfqDraft draftFromText(
            @RequestBody com.sutra.api.rfq.PurchaseRequestRequests.RfqTextRequest request) {
        return drafts.fromText(request.text());
    }
}
