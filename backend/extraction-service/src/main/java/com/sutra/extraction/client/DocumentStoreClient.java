package com.sutra.extraction.client;

import com.sutra.api.document.DocumentContracts.InspectedFile;
import com.sutra.common.client.InternalRestClients;
import com.sutra.common.client.ServiceCall;
import com.sutra.common.client.ServiceUrls;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/** The document service: stored quotation files, and the content check of files that are not stored. */
@Component
public class DocumentStoreClient {

    private static final String SERVICE = "document service";

    private final RestClient client;

    public DocumentStoreClient(InternalRestClients clients, ServiceUrls urls) {
        this.client = clients.forBaseUrl(urls.document());
    }

    /** The bytes of one stored file. */
    public byte[] content(Long documentId) {
        return ServiceCall.call(SERVICE, () -> client.get().uri("/internal/documents/{id}/content", documentId)
                .retrieve().body(byte[].class));
    }

    /**
     * Checks a file's real content (type, magic bytes, Office structure, size) without storing it. A bad file is
     * refused with the document service's own error (415 or 400 and its message).
     */
    public InspectedFile inspect(String fileName, byte[] bytes) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        return ServiceCall.call(SERVICE, () -> client.post().uri("/internal/documents/inspect")
                .contentType(MediaType.MULTIPART_FORM_DATA).body(parts).retrieve().body(InspectedFile.class));
    }
}
