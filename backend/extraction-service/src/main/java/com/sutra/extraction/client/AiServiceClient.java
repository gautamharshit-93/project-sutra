package com.sutra.extraction.client;

import com.sutra.extraction.service.internal.ExtractionInput;
import com.sutra.api.extraction.ExtractedQuotation;
import com.sutra.extraction.config.AiProperties;
import com.sutra.extraction.exception.QuotationProcessingException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** HTTP client for the Python service. Only file names and sizes are ever logged. */
@Component
public class AiServiceClient implements DocumentProcessingClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private final RestClient restClient;

    public AiServiceClient(AiProperties properties, RestClient.Builder builder) {
        Duration timeout = Duration.ofSeconds(properties.ai().serviceTimeoutSeconds());
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(timeout);
        this.restClient = builder.baseUrl(properties.ai().serviceUrl()).requestFactory(factory).build();
    }

    @Override
    public ParsedDocument parse(String fileName, byte[] content) {
        return post("/parse", fileBody(fileName, content), ParsedDocument.class, fileName);
    }

    @Override
    public List<MatchScore> match(List<MatchRequestItem> requestItems, List<MatchQuotedItem> quotedItems) {
        try {
            MatchResponse response = restClient.post().uri("/match")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("requestItems", requestItems, "quotedItems", quotedItems))
                    .retrieve()
                    .body(MatchResponse.class);
            return response == null ? List.of() : response.scores();
        } catch (RestClientException e) {
            throw new QuotationProcessingException("Item matching service failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ExtractedQuotation extractWithRules(List<ExtractionInput.Document> documents) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        for (ExtractionInput.Document doc : documents) {
            body.add("files", fileResource(doc.fileName(), doc.content()));
        }
        String names = String.join(", ", documents.stream().map(ExtractionInput.Document::fileName).toList());
        return post("/extract", body, ExtractedQuotation.class, names);
    }

    @Override
    public boolean isAvailable() {
        try {
            restClient.get().uri("/health").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }

    private <T> T post(String path, MultiValueMap<String, Object> body, Class<T> type, String fileName) {
        try {
            T result = restClient.post().uri(path)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(type);
            if (result == null) {
                throw new QuotationProcessingException("Document service returned an empty response for " + path);
            }
            return result;
        } catch (RestClientResponseException e) {
            log.warn("Document service {} failed for '{}' with HTTP {}", path, fileName, e.getStatusCode().value());
            throw new QuotationProcessingException(
                    "Document service could not read the file: " + summarise(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            throw new QuotationProcessingException(
                    "Document processing service is unavailable. Start the AI service and retry.", e);
        }
    }

    private static MultiValueMap<String, Object> fileBody(String fileName, byte[] content) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", fileResource(fileName, content));
        return body;
    }

    private static ByteArrayResource fileResource(String fileName, byte[] content) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private static String summarise(String body) {
        if (body == null || body.isBlank()) {
            return "no details";
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    record MatchResponse(List<MatchScore> scores) {
    }
}
