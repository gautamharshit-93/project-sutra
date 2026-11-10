package com.sutra.extraction.service.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sutra.api.extraction.ExtractedQuotation.ExtractedItem;
import com.sutra.api.internal.InternalContracts.LineMatch;
import com.sutra.api.internal.InternalContracts.RequestedLine;
import com.sutra.extraction.client.DocumentProcessingClient;
import com.sutra.extraction.client.DocumentProcessingClient.MatchScore;
import com.sutra.extraction.exception.QuotationProcessingException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Matching quoted lines to the RFQ's lines: codes first, then the best scores, each requested line once. */
class HybridItemMatcherTest {

    private final DocumentProcessingClient client = mock(DocumentProcessingClient.class);
    private final HybridItemMatcher matcher = new HybridItemMatcher(client);

    private static RequestedLine laptops(String code) {
        return new RequestedLine(11L, 1, code, "Laptop Dell Latitude 5440", new BigDecimal("10"), "Nos", null);
    }

    private static RequestedLine monitors(String code) {
        return new RequestedLine(12L, 2, code, "24 inch LED Monitor", new BigDecimal("10"), "Nos", null);
    }

    private static ExtractedItem quoted(String code, String description) {
        return new ExtractedItem(Optional.ofNullable(code), description, Optional.of(BigDecimal.TEN), Optional.of("Nos"),
                Optional.of(BigDecimal.ONE), null, null, null, null, null, 0.9, null);
    }

    @Test
    void assignsEachRequestLineAtMostOnceHighestScoreFirst() {
        when(client.match(any(), any())).thenReturn(List.of(
                new MatchScore(0, 11L, 0.70),
                new MatchScore(1, 11L, 0.90),
                new MatchScore(0, 12L, 0.20),
                new MatchScore(1, 12L, 0.10)));

        List<LineMatch> matches = matcher.match(List.of(laptops(null), monitors(null)),
                List.of(quoted(null, "Laptop basic"), quoted(null, "Laptop premium")));

        assertThat(matches).singleElement().satisfies(m -> {
            assertThat(m.index()).as("the premium laptop line scored higher").isEqualTo(1);
            assertThat(m.requestItemId()).isEqualTo(11L);
            assertThat(m.score()).isEqualByComparingTo("0.900");
        });
    }

    @Test
    void fallsBackToLocalSimilarityWhenPythonServiceIsDown() {
        when(client.match(any(), any())).thenThrow(new QuotationProcessingException("down"));

        List<LineMatch> matches = matcher.match(List.of(laptops(null), monitors(null)),
                List.of(quoted(null, "Dell 24 inch LED Monitor P2422H"), quoted(null, "Dell Latitude 5440 Laptop i5")));

        assertThat(matches).extracting(LineMatch::index, LineMatch::requestItemId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, 12L), org.assertj.core.groups.Tuple.tuple(1, 11L));
    }

    @Test
    void identicalItemCodesMatchExactlyBeforeAnyFuzzyScoring() {
        List<LineMatch> matches = matcher.match(List.of(laptops("IT-001"), monitors("IT-002")),
                List.of(quoted("it 002", "Some display"), quoted("IT001", "Notebook computer")));

        assertThat(matches).extracting(LineMatch::index, LineMatch::requestItemId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(0, 12L), org.assertj.core.groups.Tuple.tuple(1, 11L));
        assertThat(matches.get(1).score()).isEqualByComparingTo("1.000");
        verifyNoInteractions(client); // nothing left for the fuzzy matcher
    }

    @Test
    void linesWithoutADescriptionAreNeverMatched() {
        when(client.match(any(), any())).thenReturn(List.of());

        assertThat(matcher.match(List.of(laptops("IT-001")), List.of(quoted("IT-001", " ")))).isEmpty();
    }

    @Test
    void localSimilarityToleratesWordOrderAndExtraWords() {
        assertThat(TextSimilarity.score("Dell Latitude 5440 laptop", "Laptop - Dell Latitude 5440, 16GB RAM"))
                .isGreaterThan(0.7);
        assertThat(TextSimilarity.score("Office chair", "Laptop Dell Latitude")).isLessThan(0.2);
    }
}
