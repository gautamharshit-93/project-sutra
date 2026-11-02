package com.sutra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.config.ExtractionProperties;
import com.sutra.entity.enums.ExtractionSource;
import com.sutra.entity.enums.FileFormat;
import com.sutra.exception.QuotationProcessingException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Engine selection: a named engine, AUTO's fallback order, and readings without any line item. */
class ExtractionEnginesTest {

    private final QuotationExtractor gemini = extractor(ExtractionSource.GEMINI, "gemini-3.8-flash");
    private final QuotationExtractor openai = extractor(ExtractionSource.OPENAI, "llama-4");
    private final QuotationExtractor rules = extractor(ExtractionSource.RULES, "rules v1");
    private ExtractionEngines engines;

    private static final ExtractionInput INPUT = new ExtractionInput(
            List.of(new ExtractionInput.Document("quote.pdf", FileFormat.PDF, "%PDF-1.7".getBytes())), "Northstar", "INR",
            List.of(new ExtractionInput.RequestedItem(1, "IT-001", "Laptop", BigDecimal.TEN, "Unit", null)));

    private static QuotationExtractor extractor(ExtractionSource source, String model) {
        QuotationExtractor e = mock(QuotationExtractor.class);
        when(e.source()).thenReturn(source);
        when(e.model()).thenReturn(model);
        when(e.isAvailable()).thenReturn(true);
        return e;
    }

    private static ExtractedQuotation reading(int lines, String... warnings) {
        List<ExtractedQuotation.ExtractedItem> items = java.util.stream.IntStream.range(0, lines)
                .mapToObj(i -> new ExtractedQuotation.ExtractedItem(Optional.empty(), "Laptop " + i,
                        Optional.of(BigDecimal.ONE), Optional.empty(), Optional.of(BigDecimal.TEN), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0.9, Optional.empty()))
                .toList();
        return new ExtractedQuotation(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), items, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0.9, List.of(warnings));
    }

    @BeforeEach
    void setUp() {
        engines = new ExtractionEngines(List.of(rules, openai, gemini), ExtractionProperties.defaults());
    }

    @Test
    void autoUsesTheFirstEngineInTheConfiguredOrder() {
        when(gemini.extract(any())).thenReturn(reading(2));

        ExtractionEngines.Extraction result = engines.extract(INPUT, ExtractionEngine.AUTO);

        assertThat(result.source()).isEqualTo(ExtractionSource.GEMINI);
        assertThat(result.model()).isEqualTo("gemini-3.8-flash");
        assertThat(engines.autoOrder()).containsExactly(ExtractionEngine.GEMINI, ExtractionEngine.OPENAI,
                ExtractionEngine.RULES);
        verify(openai, never()).extract(any());
    }

    @Test
    void autoSkipsUnavailableEnginesAndMovesOnAfterAFailure() {
        when(gemini.extract(any())).thenThrow(new QuotationProcessingException("Gemini free-tier limit reached"));
        when(openai.isAvailable()).thenReturn(false);
        when(rules.extract(any())).thenReturn(reading(1));

        ExtractionEngines.Extraction result = engines.extract(INPUT, null);

        assertThat(result.source()).isEqualTo(ExtractionSource.RULES);
        verify(openai, never()).extract(any());
        assertThat(engines.firstAvailable()).isSameAs(gemini);
    }

    @Test
    void autoMovesOnWhenAnEngineFindsNoLineItems() {
        when(gemini.extract(any())).thenReturn(reading(0));
        when(openai.extract(any())).thenReturn(reading(3));

        assertThat(engines.extract(INPUT, ExtractionEngine.AUTO).source()).isEqualTo(ExtractionSource.OPENAI);
    }

    @Test
    void whenEveryEngineFailsTheReasonOfEachIsListed() {
        when(gemini.extract(any())).thenThrow(new QuotationProcessingException("Gemini is temporarily unavailable"));
        when(openai.extract(any())).thenReturn(reading(0));
        when(rules.extract(any())).thenReturn(reading(0, "Rule-based reading found no item table."));

        assertThatThrownBy(() -> engines.extract(INPUT, ExtractionEngine.AUTO))
                .isInstanceOf(QuotationProcessingException.class)
                .hasMessageContaining("Every extraction engine failed")
                .hasMessageContaining("GEMINI: Gemini is temporarily unavailable")
                .hasMessageContaining("OPENAI: No line items found")
                .hasMessageContaining("RULES: No line items found (Rule-based reading found no item table)");
    }

    @Test
    void withOnlyOneEngineItsOwnMessageIsKept() {
        engines = new ExtractionEngines(List.of(gemini, rules), new ExtractionProperties(
                List.of(ExtractionEngine.GEMINI), 1, null, null));
        when(gemini.extract(any())).thenThrow(new QuotationProcessingException("Gemini is temporarily unavailable."));

        assertThatThrownBy(() -> engines.extract(INPUT, ExtractionEngine.AUTO))
                .hasMessage("Gemini is temporarily unavailable. Retry, or enter the quotation manually.");
        verify(rules, never()).extract(any());
    }

    @Test
    void aNamedEngineIsUsedEvenWhenItIsNotFirst() {
        when(rules.extract(any())).thenReturn(reading(1));

        assertThat(engines.extract(INPUT, ExtractionEngine.RULES).source()).isEqualTo(ExtractionSource.RULES);
        verify(gemini, never()).extract(any());
    }

    @Test
    void aNamedEngineThatFindsNoLineItemsFails() {
        when(rules.extract(any())).thenReturn(reading(0, "The document is blank"));

        assertThatThrownBy(() -> engines.extract(INPUT, ExtractionEngine.RULES))
                .isInstanceOf(QuotationProcessingException.class)
                .hasMessageContaining("No line items found (The document is blank)")
                .hasMessageContaining("another engine");
    }

    @Test
    void aNamedEngineThatIsNotConfiguredIsRefusedClearly() {
        when(openai.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> engines.extract(INPUT, ExtractionEngine.OPENAI))
                .hasMessageContaining("is not configured on the server");
    }

    @Test
    void noAvailableEngineMeansManualEntry() {
        when(gemini.isAvailable()).thenReturn(false);
        when(openai.isAvailable()).thenReturn(false);
        when(rules.isAvailable()).thenReturn(false);

        assertThat(engines.firstAvailable()).isNull();
        assertThatThrownBy(() -> engines.extract(INPUT, ExtractionEngine.AUTO))
                .hasMessageContaining("No extraction engine is configured");
    }

    @Test
    void describeReportsEveryEngineWithItsAvailability() {
        when(openai.isAvailable()).thenReturn(false);

        assertThat(engines.describe())
                .extracting(ExtractionEngines.EngineInfo::engine, ExtractionEngines.EngineInfo::available)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(ExtractionEngine.GEMINI, true),
                        org.assertj.core.groups.Tuple.tuple(ExtractionEngine.OPENAI, false),
                        org.assertj.core.groups.Tuple.tuple(ExtractionEngine.RULES, true));
    }
}
