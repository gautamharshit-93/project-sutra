package com.sutra.quotation.client;

import com.sutra.api.document.DocumentContracts.AttachRequest;
import com.sutra.api.document.DocumentContracts.DeleteRequest;
import com.sutra.api.document.DocumentContracts.StoredDocument;
import com.sutra.common.client.InternalRestClients;
import com.sutra.common.client.ServiceCall;
import com.sutra.common.client.ServiceUrls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.List;

/** The document service: stores the original files (checked by content) and serves their bytes. */
@Component
public class DocumentStoreClient {

    private static final Logger log = LoggerFactory.getLogger(DocumentStoreClient.class);
    private static final String SERVICE = "document service";

    /** A file to store: its name as uploaded and its bytes. */
    public record Upload(String fileName, byte[] bytes) {
    }

    private final RestClient client;

    public DocumentStoreClient(InternalRestClients clients, ServiceUrls urls) {
        this.client = clients.forBaseUrl(urls.document());
    }

    /**
     * Stores the files (all or none). A file that is not what it claims to be is refused with the document
     * service's own error: 415 UNSUPPORTED_QUOTATION_FILE or 400 INVALID_QUOTATION_FILE and its message.
     */
    public List<StoredDocument> store(List<Upload> files, Long userId, String username) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        for (Upload f : files) {
            parts.add("files", new ByteArrayResource(f.bytes()) {
                @Override
                public String getFilename() {
                    return f.fileName();
                }
            });
        }
        return ServiceCall.call(SERVICE, () -> client.post()
                .uri(b -> b.path("/internal/documents").queryParam("uploadedById", userId)
                        .queryParam("uploadedByName", username).build())
                .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve()
                .body(new ParameterizedTypeReference<List<StoredDocument>>() { }));
    }

    public byte[] content(Long documentId) {
        return ServiceCall.call(SERVICE, () -> client.get().uri("/internal/documents/{id}/content", documentId)
                .retrieve().body(byte[].class));
    }

    /** Confirms the files belong to the quotation (after its transaction committed). */
    public void attach(List<Long> documentIds, Long quotationId) {
        bestEffort("attach", () -> client.post().uri("/internal/documents/attach")
                .body(new AttachRequest(documentIds, "QUOTATION", quotationId)).retrieve().toBodilessEntity());
    }

    /** Deletes files the quotation no longer has (after its transaction committed). */
    public void delete(List<Long> documentIds) {
        bestEffort("delete", () -> client.post().uri("/internal/documents/delete")
                .body(new DeleteRequest(documentIds)).retrieve().toBodilessEntity());
    }

    /**
     * Attach and delete run after the quotation's own change committed; if the document service is briefly away,
     * the change stands: an unattached file stays referenced by its quotation (and is protected by the foreign key),
     * an undeleted one is only an unused file.
     */
    private void bestEffort(String what, Runnable call) {
        try {
            ServiceCall.run(SERVICE, call);
        } catch (RuntimeException e) {
            log.warn("Document {} did not complete: {}", what, e.getMessage());
        }
    }
}
