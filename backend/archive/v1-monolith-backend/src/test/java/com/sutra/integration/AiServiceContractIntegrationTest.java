package com.sutra.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sutra.ai.AiServiceClient;
import com.sutra.ai.DocumentProcessingClient.MatchQuotedItem;
import com.sutra.ai.DocumentProcessingClient.MatchRequestItem;
import com.sutra.ai.DocumentProcessingClient.MatchScore;
import com.sutra.exception.QuotationProcessingException;
import com.sutra.support.Fixtures;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * File-processing integration between the Java client and the real Python service (folder /ai), using the
 * sample quotations: Excel/Word/PDF to text conversion (what Gemini receives) and item matching.
 * Skipped automatically when the service is not running on localhost:8000.
 */
class AiServiceContractIntegrationTest {

    private static final Path SAMPLES = Path.of("..", "ai", "samples");
    private final AiServiceClient client = new AiServiceClient(Fixtures.properties(), RestClient.builder());

    @BeforeEach
    void requireService() {
        assumeTrue(client.isAvailable(), "Python AI service is not running on localhost:8000");
    }

    @Test
    void excelQuotationIsConvertedToTextWithItsTable() throws Exception {
        var parsed = client.parse("a.xlsx", Files.readAllBytes(SAMPLES.resolve("vendor_a_alpha_computers.xlsx")));

        assertThat(parsed.format()).isEqualTo("xlsx");
        assertThat(parsed.text())
                .contains("Quotation No: AC/Q/2026/118")
                .contains("Dell Latitude 5440 Laptop i5 16GB 512GB SSD | 10 | Nos | 68500 | 18 | 808300")
                .contains("Grand Total");
        assertThat(parsed.charCount()).isEqualTo(parsed.text().length());
    }

    @Test
    void wordAndPdfQuotationsAreConvertedToText() throws Exception {
        var docx = client.parse("b.docx", Files.readAllBytes(SAMPLES.resolve("vendor_b_beta_systems.docx")));
        assertThat(docx.text()).contains("BSS-7781").contains("Dell Latitude 5440").contains("Payment Terms");

        var pdf = client.parse("c.pdf", Files.readAllBytes(SAMPLES.resolve("vendor_c_gamma_it.pdf")));
        assertThat(pdf.text()).contains("GIT-2026-0042").contains("665000.00");
        assertThat(pdf.likelyScanned()).isFalse();
    }

    @Test
    void corruptFileProducesAProcessingException() {
        assertThatThrownBy(() -> client.parse("broken.xlsx", "not a spreadsheet".getBytes()))
                .isInstanceOf(QuotationProcessingException.class)
                .hasMessageContaining("could not read");
    }

    @Test
    void fuzzyMatcherPairsVendorWordingWithRequestedItems() {
        List<MatchScore> scores = client.match(
                List.of(new MatchRequestItem(1L, "Laptop - Dell Latitude 5440", "i5 16GB", "Nos", BigDecimal.TEN),
                        new MatchRequestItem(2L, "24-inch LED Monitor", null, "Nos", BigDecimal.TEN)),
                List.of(new MatchQuotedItem(0, "Samsung 24 inch FHD Monitor", "Nos", BigDecimal.TEN)));

        MatchScore best = scores.stream().max(Comparator.comparingDouble(MatchScore::score)).orElseThrow();
        assertThat(best.requestItemId()).isEqualTo(2L);
    }
}
