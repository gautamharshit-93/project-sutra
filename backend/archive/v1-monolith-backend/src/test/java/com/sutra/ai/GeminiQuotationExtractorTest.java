package com.sutra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sutra.config.SutraProperties;
import com.sutra.entity.enums.ExtractionSource;
import com.sutra.entity.enums.FileFormat;
import com.sutra.exception.QuotationProcessingException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** The Gemini extractor against a mock HTTP server: request shape, response parsing and every failure mode. */
class GeminiQuotationExtractorTest {

    private static final SutraProperties.Gemini CONFIG =
            new SutraProperties.Gemini("test-key", "gemini-3.8-flash", "http://gemini.test", 16000, 30);
    private static final String URL = "http://gemini.test/v1beta/interactions";

    private final DocumentProcessingClient documentClient = mock(DocumentProcessingClient.class);
    private MockRestServiceServer server;
    private GeminiQuotationExtractor extractor;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CONFIG.baseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        extractor = new GeminiQuotationExtractor(new GeminiClient(CONFIG, builder.build(), Duration.ofMillis(1)), documentClient,
                JsonMapper.builder().build());
    }

    private static ExtractionInput.Document doc(String name, FileFormat format) {
        return new ExtractionInput.Document(name, format, "%PDF-1.7".getBytes());
    }

    private static ExtractionInput input(ExtractionInput.Document... documents) {
        return new ExtractionInput(List.of(documents), "Northstar", "INR",
                List.of(new ExtractionInput.RequestedItem(1, "IT-001", "Laptop", BigDecimal.valueOf(20), "Unit", "i5 16GB")));
    }

    private static ExtractionInput pdf() {
        return input(doc("quote.pdf", FileFormat.PDF));
    }

    private static DocumentProcessingClient.ParsedDocument parsed(String text) {
        return new DocumentProcessingClient.ParsedDocument("xlsx", text, 1, text.length(), false, List.of());
    }

    /** An Interactions API response whose model output is the given JSON text. */
    private static String interaction(String status, String outputJson) {
        String quoted = JsonMapper.builder().build().writeValueAsString(outputJson); // JSON-escaped string literal
        return """
                {"id":"int-1","object":"interaction","status":"%s","model":"gemini-3.8-flash",
                 "steps":[{"type":"thought","content":[]},
                          {"type":"model_output","content":[{"type":"text","text":%s}]}],
                 "usage":{"total_input_tokens":1200,"total_output_tokens":300}}""".formatted(status, quoted);
    }

    private static final String EXTRACTION = """
            {"vendorName":"Northstar Office Systems","quotationNumber":"NSOS/QTN/26-27/118","quotationDate":"2026-11-01",
             "validUntil":"2026-12-01","currency":"INR",
             "items":[{"itemCode":"IT-001","description":"Business Laptop","quantity":20,"unitOfMeasure":"Unit",
                       "unitPrice":62500,"discountPercent":2,"discountAmount":null,"taxPercent":18,"taxAmount":null,
                       "lineTotal":1445500,"confidence":0.95,"notes":null}],
             "subtotal":1250000,"discountTotal":25000,"taxTotal":220500,"grandTotal":1445500,
             "deliveryTerms":"12 days","paymentTerms":"30% advance","warrantyTerms":null,"notes":null,
             "overallConfidence":0.92,"warnings":["The Word file shows GST 18% while the PDF shows 18%"]}""";

    @Test
    void pdfIsSentDirectlyWithSchemaAndParsedIntoTheRecord() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("x-goog-api-key", "test-key"))
                .andExpect(jsonPath("$.model").value("gemini-3.8-flash"))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.input[1].text", Matchers.containsString("File 1 of 1: \"quote.pdf\"")))
                .andExpect(jsonPath("$.input[2].type").value("document"))
                .andExpect(jsonPath("$.input[2].mime_type").value("application/pdf"))
                .andExpect(jsonPath("$.response_format.mime_type").value("application/json"))
                .andExpect(jsonPath("$.response_format.schema.properties.items.items.properties.unitPrice.type",
                        Matchers.contains("number", "null")))
                .andExpect(jsonPath("$.input[3].text", Matchers.containsString("line 1 [IT-001]: Laptop")))
                .andRespond(withSuccess(interaction("completed", EXTRACTION), MediaType.APPLICATION_JSON));

        ExtractedQuotation q = extractor.extract(pdf());

        server.verify();
        assertThat(q.quotationNumber()).contains("NSOS/QTN/26-27/118");
        assertThat(q.items()).singleElement().satisfies(item -> {
            assertThat(item.itemCode()).contains("IT-001");
            assertThat(item.unitPrice()).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("62500"));
            assertThat(item.discountAmount()).isEmpty();
        });
        assertThat(q.warrantyTerms()).isEmpty();
        verify(documentClient, never()).parse(any(), any());
    }

    @Test
    void severalFilesOfOneQuotationAreSentTogetherWithMergeInstructions() {
        when(documentClient.parse(eq("quote.xlsx"), any())).thenReturn(parsed("Item Code | Unit Price\nIT-001 | 62500"));
        when(documentClient.parse(eq("quote.docx"), any())).thenReturn(parsed("Quotation No. NSOS/QTN/26-27/118"));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.input[1].text", Matchers.containsString("File 1 of 3: \"quote.pdf\" (PDF)")))
                .andExpect(jsonPath("$.input[2].type").value("document"))
                .andExpect(jsonPath("$.input[3].text", Matchers.containsString("File 2 of 3: \"quote.xlsx\" (XLSX)")))
                .andExpect(jsonPath("$.input[3].text", Matchers.containsString("IT-001 | 62500")))
                .andExpect(jsonPath("$.input[4].text", Matchers.containsString("File 3 of 3: \"quote.docx\" (DOCX)")))
                .andExpect(jsonPath("$.input[5].text", Matchers.containsString("all belong to ONE quotation")))
                .andExpect(jsonPath("$.input[5].text", Matchers.containsString("never add duplicates together")))
                .andRespond(withSuccess(interaction("completed", EXTRACTION), MediaType.APPLICATION_JSON));

        ExtractedQuotation q = extractor.extract(input(doc("quote.pdf", FileFormat.PDF),
                doc("quote.xlsx", FileFormat.XLSX), doc("quote.docx", FileFormat.DOCX)));

        server.verify();
        assertThat(q.items()).hasSize(1);
        assertThat(q.warnings()).anyMatch(w -> w.contains("Word file"));
    }

    @Test
    void temporaryOverloadIsRetriedAutomatically() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(URL)).andRespond(withSuccess(interaction("completed", EXTRACTION), MediaType.APPLICATION_JSON));

        ExtractedQuotation q = extractor.extract(pdf());

        server.verify();
        assertThat(q.items()).hasSize(1);
    }

    @Test
    void freeTierLimitGivesAnActionableMessage() {
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> extractor.extract(pdf()))
                .isInstanceOf(QuotationProcessingException.class)
                .hasMessageContaining("free-tier limit")
                .hasMessageContaining("enter the quotation manually");
    }

    @Test
    void invalidKeyAndServerErrorsAreExplained() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> extractor.extract(pdf())).hasMessageContaining("GEMINI_API_KEY");

        server.reset();
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> extractor.extract(pdf()))
                .hasMessageContaining("temporarily unavailable").hasMessageContaining("after 3 attempt(s)");
        server.verify();
    }

    @Test
    void truncatedFailedOrMalformedResponsesBecomeProcessingErrors() {
        server.expect(requestTo(URL)).andRespond(withSuccess(interaction("incomplete", "{\"vendorName\":"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> extractor.extract(pdf())).isInstanceOf(QuotationProcessingException.class)
                .hasMessageContaining("cut off");

        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"status\":\"failed\",\"errors\":[{\"code\":\"x\",\"message\":\"document unreadable\"}]}",
                MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> extractor.extract(pdf())).hasMessageContaining("document unreadable");

        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess(interaction("completed", "not json"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> extractor.extract(pdf())).hasMessageContaining("unexpected format");
    }

    @Test
    void unreadableOrOversizedTextIsNeverSentOrTruncated() {
        when(documentClient.parse(any(), any())).thenReturn(parsed(" "));
        assertThatThrownBy(() -> extractor.extract(input(doc("q.docx", FileFormat.DOCX))))
                .isInstanceOf(QuotationProcessingException.class).hasMessageContaining("No readable text");

        String huge = "x".repeat(GeminiClient.MAX_TEXT_CHARS + 1);
        when(documentClient.parse(any(), any())).thenReturn(parsed(huge));
        assertThatThrownBy(() -> extractor.extract(input(doc("q.docx", FileFormat.DOCX)))).hasMessageContaining("too large");
        server.verify(); // no request was made
    }

    @Test
    void missingKeyFailsClearlyWithoutCallingGemini() {
        var unconfigured = new GeminiQuotationExtractor(
                new GeminiClient(new SutraProperties.Gemini("", "gemini-3.8-flash", "http://gemini.test", 1000, 5),
                        RestClient.builder().build(), Duration.ofMillis(1)),
                documentClient, JsonMapper.builder().build());

        assertThat(unconfigured.isAvailable()).isFalse();
        assertThat(unconfigured.source()).isEqualTo(ExtractionSource.GEMINI);
        assertThatThrownBy(() -> unconfigured.extract(pdf()))
                .isInstanceOf(QuotationProcessingException.class).hasMessageContaining("GEMINI_API_KEY");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaCoversExactlyTheRecordFields() {
        Map<String, Object> properties = (Map<String, Object>) ExtractionSchema.SCHEMA.get("properties");
        assertThat(properties.keySet()).containsExactlyElementsOf(names(ExtractedQuotation.class));

        Map<String, Object> items = (Map<String, Object>) properties.get("items");
        Map<String, Object> itemProperties = (Map<String, Object>) ((Map<String, Object>) items.get("items")).get("properties");
        assertThat(itemProperties.keySet()).containsExactlyElementsOf(names(ExtractedQuotation.ExtractedItem.class));
        assertThat((List<String>) ExtractionSchema.SCHEMA.get("required")).containsExactlyElementsOf(properties.keySet());
    }

    private static List<String> names(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
