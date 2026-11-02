package com.sutra.procurement.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.common.error.BusinessRuleException;
import com.sutra.procurement.config.ExtractionProperties;
import com.sutra.procurement.entity.enums.ExtractionSource;
import com.sutra.procurement.entity.enums.FileFormat;
import com.sutra.procurement.exception.QuotationProcessingException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Engine selection: a named engine, AUTO's fallback order, readings without line items, Gemini model choice. */
class ExtractionEnginesTest {

    private final QuotationExtractor gemini = extractor(ExtractionSource.GEMINI, "gemini-3.8-flash");
    private final QuotationExtractor rules = extractor(ExtractionSource.RULES, "rules v1");
    private ExtractionEngines engines;

    private static final ExtractionInput INPUT = new ExtractionInput(
            List.of(new ExtractionInput.Document("quote.pdf", FileFormat.PDF, "%PDF-1.7".getBytes())), "Brakeline", "INR",
            List.of(new ExtractionInput.RequestedItem(1, "AP-001", "Brake pad set", BigDecimal.TEN, "Set", null)));

    private static QuotationExtractor extractor(ExtractionSource source, String model) {
        QuotationExtractor e = mock(QuotationExtractor.class);
        when(e.source()).thenReturn(source);
        when(e.model()).thenReturn(model);
        when(e.models()).thenReturn(source == ExtractionSource.GEMINI
                ? List.of("gemini-3.8-flash", "gemini-3.5-flash-lite") : List.of(model));
        when(e.isAvailable()).thenReturn(true);
        return e;
    }

    private static ExtractedQuotation reading(int lines, String... warnings) {
        List<ExtractedQuotation.ExtractedItem> items = IntStream.range(0, lines)
                .mapToObj(i -> new ExtractedQuotation.ExtractedItem(Optional.empty(), "Brake pad " + i,
                        Optional.of(BigDecimal.ONE), Optional.empty(), Optional.of(BigDecimal.TEN), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0.9, Optional.empty()))
                .toList();
        return new ExtractedQuotation(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), items, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 0.9, List.of(warnings));
    }

    @BeforeEach
    void setUp() {
        engines = new ExtractionEngines(List.of(rules, gemini), ExtractionProperties.defaults());
    }

    @Test
    void autoUsesGeminiFirstThenTheRuleBasedReader() {
        when(gemini.extract(any())).thenReturn(reading(2));

        ExtractionEngines.Extraction result = engines.extract(INPUT, ExtractionEngine.AUTO);

        assertThat(result.source()).isEqualTo(ExtractionSource.GEMINI);
        assertThat(engines.autoOrder()).containsExactly(ExtractionEngine.GEMINI, ExtractionEngine.RULES);
        verify(rules, never()).extract(any());
    }

    @Test
    void autoMovesOnWhenGeminiFailsOrIsNotConfigured() {
        when(gemini.extract(any())).thenThrow(new QuotationProcessingException("Gemini free-tier limit reached"));
        when(rules.extract(any())).thenReturn(reading(1));

        assertThat(engines.extract(INPUT, null).source()).isEqualTo(ExtractionSource.RULES);

        when(gemini.isAvailable()).thenReturn(false);
        assertThat(engines.firstAvailable()).isSameAs(rules);
    }

    @Test
    void autoMovesOnWhenAnEngineFindsNoLineItems() {
        when(gemini.extract(any())).thenReturn(reading(0));
        when(rules.extract(any())).thenReturn(reading(3));

        assertThat(engines.extract(INPUT, ExtractionEngine.AUTO).source()).isEqualTo(ExtractionSource.RULES);
    }

    @Test
    void whenEveryEngineFailsTheReasonOfEachIsListed() {
        when(gemini.extract(any())).thenThrow(new QuotationProcessingException("Gemini is temporarily unavailable"));
        when(rules.extract(any())).thenReturn(reading(0, "Rule-based reading found no item table."));

        assertThatThrownBy(() -> engines.extract(INPUT, ExtractionEngine.AUTO))
                .isInstanceOf(QuotationProcessingException.class)
                .hasMessageContaining("Every extraction engine failed")
                .hasMessageContaining("GEMINI: Gemini is temporarily unavailable")
                .hasMessageContaining("RULES: No line items found (Rule-based reading found no item table)");
    }

    @Test
    void withOnlyOneEngineItsOwnMessageIsKept() {
        engines = new ExtractionEngines(List.of(gemini, rules), new ExtractionProperties(
                List.of(ExtractionEngine.GEMINI), 1, null));
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
        when(gemini.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> engines.extract(INPUT, ExtractionEngine.GEMINI))
                .hasMessageContaining("is not configured on the server");
    }

    @Test
    void noAvailableEngineMeansManualEntry() {
        when(gemini.isAvailable()).thenReturn(false);
        when(rules.isAvailable()).thenReturn(false);

        assertThat(engines.firstAvailable()).isNull();
        assertThatThrownBy(() -> engines.extract(INPUT, ExtractionEngine.AUTO))
                .hasMessageContaining("No extraction engine is configured");
    }

    @Test
    void describeListsEachEngineWithItsModels() {
        when(gemini.isAvailable()).thenReturn(false);

        assertThat(engines.describe())
                .extracting(ExtractionEngines.EngineInfo::engine, ExtractionEngines.EngineInfo::available)
                .containsExactlyInAnyOrder(Tuple.tuple(ExtractionEngine.GEMINI, false),
                        Tuple.tuple(ExtractionEngine.RULES, true));
        assertThat(engines.describe()).filteredOn(i -> i.engine() == ExtractionEngine.GEMINI).first()
                .extracting(ExtractionEngines.EngineInfo::models).asList()
                .containsExactly("gemini-3.8-flash", "gemini-3.5-flash-lite");
    }

    @Test
    void onlyConfiguredGeminiModelsCanBeChosen() {
        engines.validate(new ExtractionChoice(ExtractionEngine.GEMINI, "gemini-3.5-flash-lite"));
        engines.validate(ExtractionChoice.AUTO);

        assertThatThrownBy(() -> engines.validate(new ExtractionChoice(ExtractionEngine.GEMINI, "gpt-5")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("gemini-3.8-flash, gemini-3.5-flash-lite");
    }

    @Test
    void theChosenModelTravelsWithTheInput() {
        when(gemini.extract(any())).thenReturn(reading(1));

        engines.extract(INPUT.withGeminiModel("gemini-3.5-flash-lite"), ExtractionEngine.GEMINI);

        verify(gemini).extract(org.mockito.ArgumentMatchers.argThat(in -> "gemini-3.5-flash-lite".equals(in.geminiModel())));
    }
}
