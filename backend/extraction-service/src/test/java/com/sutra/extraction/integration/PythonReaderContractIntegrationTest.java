package com.sutra.extraction.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sutra.extraction.client.AiServiceClient;
import com.sutra.extraction.client.DocumentProcessingClient.MatchQuotedItem;
import com.sutra.extraction.client.DocumentProcessingClient.MatchRequestItem;
import com.sutra.extraction.client.DocumentProcessingClient.MatchScore;
import com.sutra.api.extraction.ExtractedQuotation;
import com.sutra.extraction.service.internal.ExtractionInput;
import com.sutra.api.enums.FileFormat;
import com.sutra.extraction.exception.QuotationProcessingException;
import com.sutra.extraction.support.Fixtures;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * File processing between this service and the real Python document service (folder /ai), with the automotive
 * test pack in /data: file-to-text conversion (what Groq receives), the rule-based reader and item matching.
 * Skipped automatically when the document service is not running on localhost:8000.
 */
class PythonReaderContractIntegrationTest {

    private static final Path DATA = Path.of("..", "..", "ai", "test-data");
    private final AiServiceClient client = new AiServiceClient(Fixtures.properties(), RestClient.builder());

    @BeforeEach
    void requireService() {
        assumeTrue(client.isAvailable(), "Python document service is not running on localhost:8000");
    }

    private static ExtractionInput.Document doc(String name, FileFormat format) throws Exception {
        return new ExtractionInput.Document(name, format, Files.readAllBytes(DATA.resolve(name)));
    }

    @Test
    void excelAndPdfQuotationsAreConvertedToText() throws Exception {
        var xlsx = client.parse("q.xlsx", Files.readAllBytes(DATA.resolve("Vendor_2_Pioneer_Industrial_Material_List.xlsx")));
        assertThat(xlsx.format()).isEqualTo("xlsx");
        assertThat(xlsx.text()).contains("PIS-2026-1187").contains("Aluminium alloy ingot ADC12").contains("Grand Total");

        var pdf = client.parse("q.pdf", Files.readAllBytes(DATA.resolve("Vendor_2_Pioneer_Industrial_Quotation.pdf")));
        assertThat(pdf.text()).contains("PIS-2026-1187").contains("MT-104");
        assertThat(pdf.likelyScanned()).isFalse();
    }

    @Test
    void ruleBasedReaderReadsAPdfAndExcelQuotationTogether() throws Exception {
        ExtractedQuotation result = client.extractWithRules(List.of(
                doc("Vendor_3_Vertex_Auto_Quotation.pdf", FileFormat.PDF),
                doc("Vendor_3_Vertex_Auto_Material_List.xlsx", FileFormat.XLSX)));

        assertThat(result.items()).hasSize(5);
        assertThat(result.items()).extracting(i -> i.itemCode().orElse(null))
                .containsExactly("MT-101", "MT-102", "MT-103", "MT-104", "MT-105");
        assertThat(result.grandTotal()).hasValueSatisfying(t -> assertThat(t).isEqualByComparingTo("3622788.80"));
        assertThat(result.warrantyTerms()).hasValue("Bearing warranty to be confirmed");
    }

    @Test
    void corruptFileProducesAProcessingException() {
        assertThatThrownBy(() -> client.parse("broken.xlsx", "not a spreadsheet".getBytes()))
                .isInstanceOf(QuotationProcessingException.class)
                .hasMessageContaining("could not read");
    }

    @Test
    void fuzzyMatcherPairsVendorWordingWithRequestedMaterials() {
        List<MatchScore> scores = client.match(
                List.of(new MatchRequestItem(1L, "Cold-rolled steel sheet CRCA 1.2 mm", "IS 513 Grade D", "Tonne", BigDecimal.TEN),
                        new MatchRequestItem(2L, "Deep-groove ball bearing 6204-2RS", null, "Nos", BigDecimal.TEN)),
                List.of(new MatchQuotedItem(0, "Ball bearing 6204 2RS sealed", "Nos", BigDecimal.TEN)));

        MatchScore best = scores.stream().max(Comparator.comparingDouble(MatchScore::score)).orElseThrow();
        assertThat(best.requestItemId()).isEqualTo(2L);
    }
}
