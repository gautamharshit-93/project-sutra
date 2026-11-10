package com.sutra.extraction.service.internal;

import com.sutra.api.enums.ExtractionEngine;
import com.sutra.api.extraction.ExtractedQuotation;
import com.sutra.api.extraction.ExtractionChoice;
import com.sutra.api.extraction.ExtractionEngineResponses.EngineInfo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.common.error.BusinessRuleException;
import com.sutra.extraction.config.ExtractionProperties;
import com.sutra.api.enums.ExtractionSource;
import com.sutra.api.enums.FileFormat;
import com.sutra.extraction.exception.QuotationProcessingException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Engine selection: a named engine, AUTO's fallback order, readings without line items, Groq model choice. */
class ExtractionEnginesTest {

    private final QuotationExtractor groq = extractor(ExtractionSource.GROQ, "openai/gpt-oss-120b");
    private final QuotationExtractor rules = extractor(ExtractionSource.RULES, "rules v1");
    private ExtractionEngines engines;

    private static final ExtractionInput INPUT = new ExtractionInput(
            List.of(new ExtractionInput.Document("quote.pdf", FileFormat.PDF, "%PDF-1.7".getBytes())), "Brakeline", "INR",
            List.of(new ExtractionInput.RequestedItem(1, "AP-001", "Brake pad set", BigDecimal.TEN, "Set", null)));

    private static QuotationExtractor extractor(ExtractionSource source, String model) {
        QuotationExtractor e = mock(QuotationExtractor.class);
        when(e.source()).thenReturn(source);
        when(e.model()).thenReturn(model);
        when(e.models()).thenReturn(source == ExtractionSource.GROQ
                ? List.of("openai/gpt-oss-120b", "openai/gpt-oss-20b") : List.of(model));
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
        engines = new ExtractionEngines(List.of(rules, groq), ExtractionProperties.defaults());
    }

    @Test
    void autoUsesGroqFirstThenTheRuleBasedReader() {
        when(groq.extract(any())).thenReturn(reading(2));

        ExtractionEngines.Extraction result = engines.extract(INPUT, ExtractionEngine.AUTO);

        assertThat(result.source()).isEqualTo(ExtractionSource.GROQ);
        assertThat(engines.autoOrder()).containsExactly(ExtractionEngine.GROQ, ExtractionEngine.RULES);
        verify(rules, never()).extract(any());
    }

    @Test
    void autoMovesOnWhenGroqFailsOrIsNotConfigured() {
        when(groq.extract(any())).thenThrow(new QuotationProcessingException("Groq free-tier limit reached"));
        when(rules.extract(any())).thenReturn(reading(1));

        assertThat(engines.extract(INPUT, null).source()).isEqualTo(ExtractionSource.RULES);

        when(groq.isAvailable()).thenReturn(false);
        assertThat(engines.firstAvailable()).isSameAs(rules);
    }

    @Test
    void autoMovesOnWhenAnEngineFindsNoLineItems() {
        when(groq.extract(any())).thenReturn(reading(0));
        when(rules.extract(any())).thenReturn(reading(3));

        assertThat(engines.extract(INPUT, ExtractionEngine.AUTO).source()).isEqualTo(ExtractionSource.RULES);
    }

    @Test
    void whenEveryEngineFailsTheReasonOfEachIsListed() {
        when(groq.extract(any())).thenThrow(new QuotationProcessingException("Groq is temporarily unavailable"));
        when(rules.extract(any())).thenReturn(reading(0, "Rule-based reading found no item table."));

        assertThatThrownBy(() -> engines.extract(INPUT, ExtractionEngine.AUTO))
                .isInstanceOf(QuotationProcessingException.class)
                .hasMessageContaining("Every extraction engine failed")
                .hasMessageContaining("GROQ: Groq is temporarily unavailable")
                .hasMessageContaining("RULES: No line items found (Rule-based reading found no item table)");
    }

    @Test
    void withOnlyOneEngineItsOwnMessageIsKept() {
        engines = new ExtractionEngines(List.of(groq, rules), new ExtractionProperties(
                List.of(ExtractionEngine.GROQ), 1, null));
        when(groq.extract(any())).thenThrow(new QuotationProcessingException("Groq is temporarily unavailable."));

        assertThatThrownBy(() -> engines.extract(INPUT, ExtractionEngine.AUTO))
                .hasMessage("Groq is temporarily unavailable. Retry, or enter the quotation manually.");
        verify(rules, never()).extract(any());
    }

    @Test
    void aNamedEngineIsUsedEvenWhenItIsNotFirst() {
        when(rules.extract(any())).thenReturn(reading(1));

        assertThat(engines.extract(INPUT, ExtractionEngine.RULES).source()).isEqualTo(ExtractionSource.RULES);
        verify(groq, never()).extract(any());
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
        when(groq.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> engines.extract(INPUT, ExtractionEngine.GROQ))
                .hasMessageContaining("is not configured on the server");
    }

    @Test
    void noAvailableEngineMeansManualEntry() {
        when(groq.isAvailable()).thenReturn(false);
        when(rules.isAvailable()).thenReturn(false);

        assertThat(engines.firstAvailable()).isNull();
        assertThatThrownBy(() -> engines.extract(INPUT, ExtractionEngine.AUTO))
                .hasMessageContaining("No extraction engine is configured");
    }

    @Test
    void describeListsEachEngineWithItsModels() {
        when(groq.isAvailable()).thenReturn(false);

        assertThat(engines.describe())
                .extracting(EngineInfo::engine, EngineInfo::available)
                .containsExactlyInAnyOrder(Tuple.tuple(ExtractionEngine.GROQ, false),
                        Tuple.tuple(ExtractionEngine.RULES, true));
        assertThat(engines.describe()).filteredOn(i -> i.engine() == ExtractionEngine.GROQ).first()
                .extracting(EngineInfo::models, InstanceOfAssertFactories.list(String.class))
                .containsExactly("openai/gpt-oss-120b", "openai/gpt-oss-20b");
    }

    @Test
    void onlyConfiguredGroqModelsCanBeChosen() {
        engines.validate(new ExtractionChoice(ExtractionEngine.GROQ, "openai/gpt-oss-20b"));
        engines.validate(ExtractionChoice.AUTO);

        assertThatThrownBy(() -> engines.validate(new ExtractionChoice(ExtractionEngine.GROQ, "gpt-5")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("openai/gpt-oss-120b, openai/gpt-oss-20b");
    }

    @Test
    void theChosenModelTravelsWithTheInput() {
        when(groq.extract(any())).thenReturn(reading(1));

        engines.extract(INPUT.withPreferredModel("openai/gpt-oss-20b"), ExtractionEngine.GROQ);

        verify(groq).extract(org.mockito.ArgumentMatchers.argThat(in -> "openai/gpt-oss-20b".equals(in.preferredModel())));
    }
}
