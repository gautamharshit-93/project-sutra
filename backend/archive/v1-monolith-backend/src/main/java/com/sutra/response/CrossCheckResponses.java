package com.sutra.response;

import com.sutra.ai.ExtractionEngine;
import com.sutra.ai.ExtractionEngines;
import com.sutra.calculation.GradeReport;
import com.sutra.entity.enums.ExtractionSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Results of reading one quotation with several engines side by side, without changing the stored data. */
public final class CrossCheckResponses {

    private CrossCheckResponses() {
    }

    public record CrossCheckResponse(Long quotationId, ExtractionSource currentSource, BigDecimal currentGrandTotal,
                                     List<EngineRun> runs, boolean totalsAgree, ExtractionEngine bestEngine,
                                     String summary) {
    }

    /** One engine's reading, matched, recalculated and graded exactly like a stored extraction. */
    public record EngineRun(ExtractionEngine engine, String model, boolean ok, String error, long durationMs,
                            int itemCount, int matchedItems, int requestedItems, String quotationNumber,
                            String currency, LocalDate validUntil, BigDecimal statedGrandTotal,
                            BigDecimal calculatedGrandTotal, long openFlags, List<String> flags,
                            List<LineReading> lines, GradeReport quality) {

        public static EngineRun failed(ExtractionEngine engine, String model, long durationMs, String error) {
            return new EngineRun(engine, model, false, error, durationMs, 0, 0, 0, null, null, null, null, null, 0,
                    List.of(), List.of(), null);
        }
    }

    public record LineReading(String itemCode, Integer requestLine, String description, BigDecimal quantity,
                              BigDecimal unitPrice, BigDecimal lineTotal) {
    }

    /** Which engines exist and whether they can be used now. */
    public record EnginesResponse(List<ExtractionEngines.EngineInfo> engines, List<ExtractionEngine> autoOrder) {
    }
}
