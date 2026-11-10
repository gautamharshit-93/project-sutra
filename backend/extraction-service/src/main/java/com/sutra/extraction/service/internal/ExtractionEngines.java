package com.sutra.extraction.service.internal;

import com.sutra.api.enums.ExtractionEngine;
import com.sutra.api.extraction.ExtractedQuotation;
import com.sutra.api.extraction.ExtractionChoice;
import com.sutra.api.extraction.ExtractionEngineResponses.EngineInfo;

import com.sutra.extraction.config.ExtractionProperties;
import com.sutra.api.enums.ExtractionSource;
import com.sutra.extraction.exception.QuotationProcessingException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * All extraction engines, and the choice between them. A user may ask for one engine (GROQ or RULES) to
 * switch between them and cross-check, or for AUTO: the engines in {@code sutra.extraction.engine-order} are tried
 * one after another, skipping those that are not configured and moving on when one fails (for example a free
 * tier that is rate-limited).
 */
@Component
public class ExtractionEngines {

    private static final Logger log = LoggerFactory.getLogger(ExtractionEngines.class);

    private final Map<ExtractionEngine, QuotationExtractor> byEngine = new EnumMap<>(ExtractionEngine.class);
    private final List<ExtractionEngine> order;

    public ExtractionEngines(List<QuotationExtractor> extractors, ExtractionProperties properties) {
        for (QuotationExtractor extractor : extractors) {
            for (ExtractionEngine engine : ExtractionEngine.values()) {
                if (engine.source() == extractor.source()) {
                    byEngine.put(engine, extractor);
                }
            }
        }
        this.order = properties.engineOrder().stream().filter(byEngine::containsKey).toList();
    }

    /** The result of one extraction and the engine that produced it. */
    public record Extraction(ExtractedQuotation data, ExtractionSource source, String model) {
    }

    /** Availability of one engine, for the engine picker and the dashboard. */

    public List<EngineInfo> describe() {
        List<EngineInfo> infos = new ArrayList<>();
        for (Map.Entry<ExtractionEngine, QuotationExtractor> e : byEngine.entrySet()) {
            infos.add(new EngineInfo(e.getKey(), e.getKey().label(), e.getValue().model(), e.getValue().models(),
                    e.getValue().isAvailable(), order.contains(e.getKey())));
        }
        return infos;
    }

    /** The order AUTO tries, e.g. [GROQ, RULES]. */
    public List<ExtractionEngine> autoOrder() {
        return order;
    }

    /** The first engine AUTO would use right now, or null when none is available. */
    public QuotationExtractor firstAvailable() {
        return order.stream().map(byEngine::get).filter(QuotationExtractor::isAvailable).findFirst().orElse(null);
    }

    /**
     * Checks a user's choice before it is queued: a chosen Groq model must be one of the configured free models.
     */
    public void validate(ExtractionChoice choice) {
        if (choice.model() == null) {
            return;
        }
        QuotationExtractor groq = byEngine.get(ExtractionEngine.GROQ);
        if (groq == null || !groq.models().contains(choice.model())) {
            throw new com.sutra.common.error.BusinessRuleException("UNKNOWN_MODEL", "Groq model '"
                    + choice.model() + "' is not available. Choose one of: "
                    + (groq == null ? "none" : String.join(", ", groq.models())));
        }
    }

    /** One specific engine (not AUTO). */
    public QuotationExtractor get(ExtractionEngine engine) {
        QuotationExtractor extractor = engine == null ? null : byEngine.get(engine);
        if (extractor == null) {
            throw new QuotationProcessingException("Unknown extraction engine " + engine);
        }
        return extractor;
    }

    /**
     * Extracts with the requested engine, or with AUTO's first engine that succeeds. A reading without any line
     * item counts as a failure (e.g. a blank or unreadable document). When every engine fails, the exception lists
     * each engine's reason; when only one engine was tried, its own message is kept.
     */
    public Extraction extract(ExtractionInput input, ExtractionEngine requested) {
        if (requested != null && requested != ExtractionEngine.AUTO) {
            QuotationExtractor extractor = get(requested);
            if (!extractor.isAvailable()) {
                throw new QuotationProcessingException(requested.label() + " is not configured on the server. "
                        + "Choose another engine, or enter the quotation manually.");
            }
            ExtractedQuotation data = extractor.extract(input);
            if (data.items().isEmpty()) {
                throw new QuotationProcessingException(noItems(data)
                        + ". Retry with another engine, or enter the quotation manually.");
            }
            return new Extraction(data, extractor.source(), extractor.model());
        }

        List<String> failures = new ArrayList<>();
        String onlyMessage = null;
        for (ExtractionEngine engine : order) {
            QuotationExtractor extractor = byEngine.get(engine);
            if (!extractor.isAvailable()) {
                continue;
            }
            String message;
            try {
                ExtractedQuotation data = extractor.extract(input);
                if (!data.items().isEmpty()) {
                    return new Extraction(data, extractor.source(), extractor.model());
                }
                message = noItems(data);
                log.info("Engine {} found no line items in [{}]; trying the next engine", engine, input.fileName());
            } catch (QuotationProcessingException e) {
                message = e.getMessage();
                log.info("Engine {} failed for [{}]; trying the next engine", engine, input.fileName());
            }
            failures.add(engine + ": " + message);
            onlyMessage = message;
        }
        if (failures.isEmpty()) {
            throw new QuotationProcessingException("No extraction engine is configured. Enter the quotation manually.");
        }
        if (failures.size() == 1) {
            String sentence = onlyMessage.endsWith(".") ? onlyMessage : onlyMessage + ".";
            throw new QuotationProcessingException(sentence.toLowerCase(Locale.ROOT).contains("manual")
                    ? sentence : sentence + " Retry, or enter the quotation manually.");
        }
        throw new QuotationProcessingException("Every extraction engine failed. " + String.join(" | ", failures));
    }

    /** "No line items found", with the engine's first warning as the likely reason. */
    private static String noItems(ExtractedQuotation data) {
        return data.warnings().stream().filter(w -> w != null && !w.isBlank()).findFirst()
                .map(w -> "No line items found (" + w.trim().replaceAll("\\.$", "") + ")")
                .orElse("No line items found");
    }
}
