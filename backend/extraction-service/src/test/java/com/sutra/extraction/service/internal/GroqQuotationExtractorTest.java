package com.sutra.extraction.service.internal;

import com.sutra.extraction.client.DocumentProcessingClient;
import com.sutra.extraction.client.GroqClient;
import com.sutra.api.extraction.ExtractedQuotation;
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

import com.sutra.extraction.config.AiProperties;
import com.sutra.api.enums.ExtractionSource;
import com.sutra.api.enums.FileFormat;
import com.sutra.extraction.exception.QuotationProcessingException;
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

/** The Groq extractor against a mock HTTP server: request shape, OCR for scans, model fallback and failure modes. */
class GroqQuotationExtractorTest {

    private static final AiProperties.Groq CONFIG =
            new AiProperties.Groq("test-key", "openai/gpt-oss-120b", "http://groq.test", 16000, 30);
    private static final String URL = "http://groq.test/chat/completions";

    private final DocumentProcessingClient documentClient = mock(DocumentProcessingClient.class);
    private final PdfOcrReader ocr = mock(PdfOcrReader.class);
    private MockRestServiceServer server;
    private GroqQuotationExtractor extractor;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CONFIG.baseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        GroqClient client = new GroqClient(CONFIG, builder.build(), Duration.ofMillis(1), List.of("openai/gpt-oss-20b"));
        extractor = new GroqQuotationExtractor(client, documentClient, ocr, JsonMapper.builder().build());
    }

    private static ExtractionInput.Document doc(String name, FileFormat format) {
        return new ExtractionInput.Document(name, format, "%PDF-1.7".getBytes());
    }

    private static ExtractionInput input(ExtractionInput.Document... documents) {
        return new ExtractionInput(List.of(documents), "Northstar", "INR",
                List.of(new ExtractionInput.RequestedItem(1, "IT-001", "Laptop", BigDecimal.valueOf(20), "Unit", "i5 16GB")));
    }

    private static DocumentProcessingClient.ParsedDocument parsed(String text, boolean scanned) {
        return new DocumentProcessingClient.ParsedDocument("pdf", text, 1, text.length(), scanned, List.of());
    }

    /** A chat completion whose message content is the given text. */
    private static String completion(String content, String finishReason) {
        String quoted = JsonMapper.builder().build().writeValueAsString(content);
        return """
                {"id":"chatcmpl-1","object":"chat.completion","model":"openai/gpt-oss-120b",
                 "choices":[{"index":0,"message":{"role":"assistant","content":%s},"finish_reason":"%s"}],
                 "usage":{"prompt_tokens":1200,"completion_tokens":300}}""".formatted(quoted, finishReason);
    }

    private static final String EXTRACTION = """
            {"vendorName":"Northstar Office Systems","quotationNumber":"NSOS/QTN/26-27/118","quotationDate":"2026-11-01",
             "validUntil":"2026-12-01","currency":"INR",
             "items":[{"itemCode":"IT-001","description":"Business Laptop","quantity":20,"unitOfMeasure":"Unit",
                       "unitPrice":62500,"discountPercent":2,"discountAmount":null,"taxPercent":18,"taxAmount":null,
                       "lineTotal":1445500,"confidence":0.95,"notes":null}],
             "subtotal":1250000,"discountTotal":25000,"taxTotal":220500,"grandTotal":1445500,
             "deliveryTerms":"12 days","paymentTerms":"30% advance","warrantyTerms":null,"notes":null,
             "overallConfidence":0.92,"warnings":[]}""";

    @Test
    void textIsSentWithTheStrictSchemaAndParsedIntoTheRecord() {
        when(documentClient.parse(eq("quote.pdf"), any())).thenReturn(parsed("Northstar quotation IT-001 Laptop 62,500", false));
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("openai/gpt-oss-120b"))
                .andExpect(jsonPath("$.temperature").value(0))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.items.type").value("array"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].content").value(Matchers.containsString("IT-001 Laptop 62,500")))
                .andExpect(jsonPath("$.messages[1].content").value(Matchers.containsString("[IT-001]: Laptop")))
                .andRespond(withSuccess(completion(EXTRACTION, "stop"), MediaType.APPLICATION_JSON));

        ExtractedQuotation result = extractor.extract(input(doc("quote.pdf", FileFormat.PDF)));

        assertThat(result.quotationNumber()).contains("NSOS/QTN/26-27/118");
        assertThat(result.items()).singleElement().satisfies(i -> {
            assertThat(i.itemCode()).contains("IT-001");
            assertThat(i.unitPrice()).hasValueSatisfying(p -> assertThat(p).isEqualByComparingTo("62500"));
        });
        assertThat(extractor.source()).isEqualTo(ExtractionSource.GROQ);
        verify(ocr, never()).read(any(), any());
        server.verify();
    }

    @Test
    void scannedPdfIsReadByOcrAndLabelledAsSuch() {
        when(documentClient.parse(eq("scan.pdf"), any())).thenReturn(parsed("", true));
        when(ocr.read(eq("scan.pdf"), any())).thenReturn("--- page 1 ---\nIT-001 Business Laptop 20 62500");
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.messages[1].content").value(Matchers.containsString("scanned - text read by OCR")))
                .andExpect(jsonPath("$.messages[1].content").value(Matchers.containsString("IT-001 Business Laptop 20 62500")))
                .andRespond(withSuccess(completion(EXTRACTION, "stop"), MediaType.APPLICATION_JSON));

        assertThat(extractor.extract(input(doc("scan.pdf", FileFormat.PDF))).items()).hasSize(1);
        server.verify();
    }

    @Test
    void severalFilesOfOneQuotationAreSentTogetherWithMergeInstructions() {
        when(documentClient.parse(any(), any())).thenReturn(parsed("price sheet", false));
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.messages[1].content").value(Matchers.containsString("File 2 of 2: \"quote.xlsx\"")))
                .andExpect(jsonPath("$.messages[1].content").value(Matchers.containsString("all belong to ONE quotation")))
                .andRespond(withSuccess(completion(EXTRACTION, "stop"), MediaType.APPLICATION_JSON));

        extractor.extract(input(doc("quote.pdf", FileFormat.PDF), doc("quote.xlsx", FileFormat.XLSX)));
        server.verify();
    }

    @Test
    void rateLimitedModelFallsBackToTheNextFreeModel() {
        when(documentClient.parse(any(), any())).thenReturn(parsed("text", false));
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(URL)).andExpect(jsonPath("$.model").value("openai/gpt-oss-20b"))
                .andRespond(withSuccess(completion(EXTRACTION, "stop"), MediaType.APPLICATION_JSON));

        assertThat(extractor.extract(input(doc("quote.pdf", FileFormat.PDF))).items()).hasSize(1);
        server.verify();
    }

    @Test
    void preferredModelIsTriedFirst() {
        when(documentClient.parse(any(), any())).thenReturn(parsed("text", false));
        server.expect(requestTo(URL)).andExpect(jsonPath("$.model").value("openai/gpt-oss-20b"))
                .andRespond(withSuccess(completion(EXTRACTION, "stop"), MediaType.APPLICATION_JSON));

        extractor.extract(input(doc("quote.pdf", FileFormat.PDF)).withPreferredModel("openai/gpt-oss-20b"));
        server.verify();
    }

    @Test
    void failuresBecomeActionableProcessingErrors() {
        when(documentClient.parse(any(), any())).thenReturn(parsed("text", false));
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> extractor.extract(input(doc("quote.pdf", FileFormat.PDF))))
                .isInstanceOf(QuotationProcessingException.class).hasMessageContaining("GROQ_API_KEY")
                .hasMessageContaining("enter the quotation manually");

        server.reset();
        server.expect(times(3), requestTo(URL)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> extractor.extract(input(doc("quote.pdf", FileFormat.PDF))))
                .hasMessageContaining("temporarily unavailable (HTTP 503) after 3 attempt(s)");

        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess(completion("{\"vendorName\":", "length"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> extractor.extract(input(doc("quote.pdf", FileFormat.PDF))))
                .hasMessageContaining("cut off");

        server.reset();
        server.expect(requestTo(URL)).andRespond(withSuccess(completion("not json", "stop"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> extractor.extract(input(doc("quote.pdf", FileFormat.PDF))))
                .hasMessageContaining("unexpected format");
    }

    @Test
    void missingKeyFailsClearlyWithoutCallingGroq() {
        GroqClient unconfigured = new GroqClient(new AiProperties.Groq("", "openai/gpt-oss-120b", "http://groq.test", 100, 5),
                RestClient.builder().build(), Duration.ofMillis(1), List.of());
        GroqQuotationExtractor noKey = new GroqQuotationExtractor(unconfigured, documentClient, ocr, JsonMapper.builder().build());
        when(documentClient.parse(any(), any())).thenReturn(parsed("text", false));

        assertThat(noKey.isAvailable()).isFalse();
        assertThatThrownBy(() -> noKey.extract(input(doc("quote.pdf", FileFormat.PDF))))
                .hasMessageContaining("GROQ_API_KEY");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaCoversExactlyTheRecordFieldsAndIsStrict() {
        Map<String, Object> props = (Map<String, Object>) ExtractionSchema.SCHEMA.get("properties");
        assertThat(props.keySet()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ExtractedQuotation.class.getRecordComponents()).map(RecordComponent::getName).toList());
        assertThat(ExtractionSchema.SCHEMA).containsEntry("additionalProperties", false);
        assertThat((List<String>) ExtractionSchema.SCHEMA.get("required")).containsExactlyInAnyOrderElementsOf(props.keySet());
    }
}
