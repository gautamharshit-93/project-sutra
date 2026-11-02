package com.sutra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sutra.config.ExtractionProperties;
import com.sutra.entity.enums.ExtractionSource;
import com.sutra.entity.enums.FileFormat;
import com.sutra.exception.QuotationProcessingException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** The OpenAI-compatible extractor against a mock chat-completions server. */
class OpenAiQuotationExtractorTest {

    private static final String BASE = "http://llm.test/v1";
    private static final String URL = BASE + "/chat/completions";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DocumentProcessingClient documentClient = mock(DocumentProcessingClient.class);
    private MockRestServiceServer server;

    private OpenAiQuotationExtractor extractor(ExtractionProperties.OpenAi config) {
        RestClient.Builder builder = RestClient.builder().baseUrl(config.baseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        return new OpenAiQuotationExtractor(config, builder.build(), documentClient, JSON, Duration.ofMillis(1));
    }

    private static ExtractionProperties.OpenAi config(String key, String baseUrl) {
        return new ExtractionProperties.OpenAi(key, baseUrl, "llama-4-scout", 8000, 30);
    }

    private static final ExtractionInput INPUT = new ExtractionInput(
            List.of(new ExtractionInput.Document("quote.xlsx", FileFormat.XLSX, new byte[] {1, 2, 3})), "Northstar", "INR",
            List.of(new ExtractionInput.RequestedItem(1, "IT-001", "Laptop", BigDecimal.valueOf(20), "Unit", null)));

    private static String completion(String content, String finishReason) {
        return """
                {"id":"c1","object":"chat.completion","model":"llama-4-scout",
                 "choices":[{"index":0,"finish_reason":"%s","message":{"role":"assistant","content":%s}}]}"""
                .formatted(finishReason, JSON.writeValueAsString(content));
    }

    private static final String EXTRACTION = """
            {"vendorName":"Northstar Office Systems","quotationNumber":"NS-118","quotationDate":"2026-11-01",
             "validUntil":"2026-12-01","currency":"INR",
             "items":[{"itemCode":"IT-001","description":"Business Laptop","quantity":20,"unitOfMeasure":"Unit",
                       "unitPrice":62500,"discountPercent":null,"discountAmount":null,"taxPercent":18,"taxAmount":null,
                       "lineTotal":1475000,"confidence":0.9,"notes":null}],
             "subtotal":1250000,"discountTotal":null,"taxTotal":225000,"grandTotal":1475000,
             "deliveryTerms":"10 days","paymentTerms":"30 days","warrantyTerms":"3 years","notes":null,
             "overallConfidence":0.9,"warnings":[]}""";

    private void textOfTheFile() {
        when(documentClient.parse(eq("quote.xlsx"), any())).thenReturn(new DocumentProcessingClient.ParsedDocument(
                "xlsx", "IT-001 Business Laptop 20 x 62,500", 1, 34, false, List.of()));
    }

    @Test
    void readsTheQuotationFromTheChatCompletion() {
        OpenAiQuotationExtractor extractor = extractor(config("sk-test", BASE));
        textOfTheFile();
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andExpect(jsonPath("$.model").value("llama-4-scout"))
                .andExpect(jsonPath("$.temperature").value(0))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andExpect(jsonPath("$.messages[1].content", Matchers.containsString("IT-001 Business Laptop")))
                .andRespond(withSuccess(completion("```json\n" + EXTRACTION + "\n```", "stop"),
                        MediaType.APPLICATION_JSON));

        ExtractedQuotation result = extractor.extract(INPUT);

        assertThat(result.quotationNumber()).contains("NS-118");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).unitPrice()).contains(new BigDecimal("62500"));
        assertThat(extractor.source()).isEqualTo(ExtractionSource.OPENAI);
        server.verify();
    }

    @Test
    void localServerNeedsNoKey() {
        ExtractionProperties.OpenAi local = config("", "http://localhost:11434/v1");
        assertThat(local.isConfigured()).isTrue();
        assertThat(config("", BASE).isConfigured()).as("remote server without key").isFalse();

        OpenAiQuotationExtractor extractor = extractor(local);
        textOfTheFile();
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(completion(EXTRACTION, "stop"), MediaType.APPLICATION_JSON));

        assertThat(extractor.extract(INPUT).items()).hasSize(1);
    }

    @Test
    void notConfiguredFailsWithInstructions() {
        OpenAiQuotationExtractor extractor = extractor(new ExtractionProperties.OpenAi("", BASE, "", 8000, 30));

        assertThat(extractor.isAvailable()).isFalse();
        assertThat(extractor.model()).isEqualTo("not configured");
        assertThatThrownBy(() -> extractor.extract(INPUT)).hasMessageContaining("OPENAI_MODEL");
    }

    @Test
    void serverErrorsAreRetriedThenReported() {
        OpenAiQuotationExtractor extractor = extractor(config("sk-test", BASE));
        textOfTheFile();
        server.expect(times(OpenAiQuotationExtractor.MAX_ATTEMPTS), requestTo(URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> extractor.extract(INPUT))
                .isInstanceOf(QuotationProcessingException.class)
                .hasMessageContaining("temporarily unavailable (HTTP 503)");
        server.verify();
    }

    @Test
    void truncatedAnswerIsRejected() {
        OpenAiQuotationExtractor extractor = extractor(config("sk-test", BASE));
        textOfTheFile();
        server.expect(requestTo(URL)).andRespond(withSuccess(completion("{\"items\":[", "length"),
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> extractor.extract(INPUT)).hasMessageContaining("cut off");
    }

    @Test
    void scannedDocumentWithoutTextPointsToGemini() {
        OpenAiQuotationExtractor extractor = extractor(config("sk-test", BASE));
        when(documentClient.parse(eq("quote.xlsx"), any())).thenReturn(
                new DocumentProcessingClient.ParsedDocument("pdf", "", 1, 0, true, List.of()));

        assertThatThrownBy(() -> extractor.extract(INPUT)).hasMessageContaining("use the Gemini engine");
    }
}
