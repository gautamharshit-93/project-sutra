package com.sutra.procurement.service;

import com.sutra.common.audit.AuditService;

import static com.sutra.common.audit.AuditService.details;

import com.sutra.procurement.ai.ExtractionEngine;
import com.sutra.procurement.ai.ExtractionEngines;
import com.sutra.procurement.ai.ExtractionInput;
import com.sutra.procurement.calculation.ExtractionQualityGrader;
import com.sutra.procurement.entity.Quotation;
import com.sutra.procurement.entity.QuotationFile;
import com.sutra.procurement.entity.QuotationFlag;
import com.sutra.procurement.entity.QuotationItem;
import com.sutra.procurement.entity.enums.ExtractionSource;
import com.sutra.procurement.entity.enums.QuotationStatus;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.procurement.exception.QuotationNotFoundException;
import com.sutra.procurement.exception.QuotationProcessingException;
import com.sutra.procurement.repository.QuotationFileRepository;
import com.sutra.procurement.repository.QuotationRepository;
import com.sutra.procurement.response.CrossCheckResponses.CrossCheckResponse;
import com.sutra.procurement.response.CrossCheckResponses.EngineRun;
import com.sutra.procurement.response.CrossCheckResponses.EnginesResponse;
import com.sutra.procurement.response.CrossCheckResponses.LineReading;
import com.sutra.procurement.security.RecordAccessPolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Final checking: reads one quotation with several engines (e.g. Groq and the
 * rule-based reader), then matches, recalculates and grades each reading the same way a stored extraction is
 * treated. Nothing is saved; the user can then re-run the quotation with the engine they trust.
 */
@Service
public class ExtractionCrossCheckService {

    private static final Logger log = LoggerFactory.getLogger(ExtractionCrossCheckService.class);

    private final QuotationRepository quotationRepository;
    private final QuotationFileRepository fileRepository;
    private final ExtractionEngines engines;
    private final QuotationProcessingService processingService;
    private final RecordAccessPolicy accessPolicy;
    private final AuditService auditService;
    private final TransactionTemplate readTx;
    private final TransactionTemplate writeTx;

    public ExtractionCrossCheckService(QuotationRepository quotationRepository, QuotationFileRepository fileRepository,
                                       ExtractionEngines engines, QuotationProcessingService processingService,
                                       RecordAccessPolicy accessPolicy, AuditService auditService,
                                       PlatformTransactionManager transactionManager) {
        this.quotationRepository = quotationRepository;
        this.fileRepository = fileRepository;
        this.engines = engines;
        this.processingService = processingService;
        this.accessPolicy = accessPolicy;
        this.auditService = auditService;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    public EnginesResponse engines() {
        return new EnginesResponse(engines.describe(), engines.autoOrder());
    }

    public CrossCheckResponse crossCheck(Long quotationId, List<ExtractionEngine> requested) {
        Loaded loaded = readTx.execute(status -> load(quotationId));
        List<ExtractionEngine> toRun = enginesToRun(requested);

        List<EngineRun> runs = new ArrayList<>();
        for (ExtractionEngine engine : toRun) {
            String model = engines.get(engine).model();
            long start = System.nanoTime();
            try {
                ExtractionEngines.Extraction extraction = engines.extract(loaded.input(), engine);
                long ms = (System.nanoTime() - start) / 1_000_000;
                runs.add(readTx.execute(status -> toRun(quotationId, engine, extraction, ms)));
            } catch (QuotationProcessingException e) {
                runs.add(EngineRun.failed(engine, model, (System.nanoTime() - start) / 1_000_000, e.getMessage()));
            } catch (RuntimeException e) {
                log.error("Cross-check with {} failed for quotation {}", engine, quotationId, e);
                runs.add(EngineRun.failed(engine, model, (System.nanoTime() - start) / 1_000_000,
                        "Unexpected error: " + e.getClass().getSimpleName()));
            }
        }

        List<EngineRun> ok = runs.stream().filter(EngineRun::ok).toList();
        boolean totalsAgree = ok.size() >= 2
                && ok.stream().allMatch(r -> r.calculatedGrandTotal() != null)
                && ok.stream().map(r -> r.calculatedGrandTotal().stripTrailingZeros()).distinct().count() == 1;
        ExtractionEngine best = ok.stream()
                .max(Comparator.comparingInt((EngineRun r) -> r.quality().score())
                        .thenComparing(r -> -toRun.indexOf(r.engine())))
                .map(EngineRun::engine).orElse(null);
        String summary = summary(runs, ok, totalsAgree, best);

        Map<String, Object> scores = new LinkedHashMap<>();
        for (EngineRun r : runs) {
            scores.put(r.engine().name(), r.ok() ? r.quality().score() + " (" + r.quality().grade() + ")" : "failed");
        }
        writeTx.executeWithoutResult(status -> auditService.record(QuotationService.AUDIT_TYPE, quotationId,
                "CROSS_CHECKED", details("engines", scores, "totalsAgree", totalsAgree, "best", best)));

        return new CrossCheckResponse(quotationId, loaded.currentSource(), loaded.currentGrandTotal(), runs,
                totalsAgree, best, summary);
    }

    private Loaded load(Long quotationId) {
        Quotation q = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new QuotationNotFoundException(quotationId));
        accessPolicy.requireModify(q.getPurchaseRequest());
        if (q.getStatus() == QuotationStatus.PROCESSING) {
            throw new BusinessRuleException("QUOTATION_PROCESSING",
                    "The quotation is being processed; wait until extraction finishes");
        }
        List<QuotationFile> files = fileRepository.findByQuotationIdOrderByIdAsc(quotationId);
        if (files.isEmpty()) {
            throw new BusinessRuleException("NO_FILES", "The quotation has no files to read");
        }
        return new Loaded(processingService.inputFor(q, files), q.getExtractionSource(), q.getGrandTotal());
    }

    private List<ExtractionEngine> enginesToRun(List<ExtractionEngine> requested) {
        List<ExtractionEngine> list = requested == null ? List.of() : requested.stream()
                .filter(e -> e != null && e != ExtractionEngine.AUTO).distinct().toList();
        if (list.isEmpty()) {
            list = engines.describe().stream().filter(ExtractionEngines.EngineInfo::available)
                    .map(ExtractionEngines.EngineInfo::engine).toList();
        }
        if (list.isEmpty()) {
            throw new BusinessRuleException("NO_ENGINE", "No extraction engine is configured");
        }
        return list;
    }

    private EngineRun toRun(Long quotationId, ExtractionEngine engine, ExtractionEngines.Extraction extraction, long ms) {
        Quotation original = quotationRepository.findById(quotationId)
                .orElseThrow(() -> new QuotationNotFoundException(quotationId));
        Quotation preview = processingService.preview(original, extraction.data(), extraction.source());
        int matched = (int) preview.getItems().stream().map(QuotationItem::getRequestItem).filter(Objects::nonNull)
                .distinct().count();
        List<String> flags = preview.getFlags().stream().map(QuotationFlag::getMessage).limit(15).toList();
        List<LineReading> lines = preview.getItems().stream()
                .map(i -> new LineReading(i.getItemCode(),
                        i.getRequestItem() == null ? null : i.getRequestItem().getLineNumber(), i.getDescription(),
                        i.getQuantity(), i.getUnitPrice(), i.getLineTotal()))
                .toList();
        BigDecimal stated = preview.getStatedGrandTotal();
        return new EngineRun(engine, extraction.model(), true, null, ms, preview.getItems().size(), matched,
                original.getPurchaseRequest().getItems().size(), preview.getQuotationNumber(), preview.getCurrency(),
                preview.getValidUntil(), stated, preview.getGrandTotal(), preview.openFlagCount(), flags, lines,
                ExtractionQualityGrader.grade(preview));
    }

    private static String summary(List<EngineRun> runs, List<EngineRun> ok, boolean totalsAgree, ExtractionEngine best) {
        if (ok.isEmpty()) {
            return "No engine could read this quotation. Enter it manually.";
        }
        if (ok.size() == 1) {
            return "Only " + ok.get(0).engine() + " produced a reading (" + (runs.size() - 1)
                    + " failed); there is nothing to cross-check it against.";
        }
        return (totalsAgree
                ? "All " + ok.size() + " engines calculate the same grand total."
                : "The engines disagree on the grand total: check the lines that differ against the original file.")
                + " Highest quality score: " + best + ".";
    }

    private record Loaded(ExtractionInput input, ExtractionSource currentSource, BigDecimal currentGrandTotal) {
    }
}
