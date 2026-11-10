package com.sutra.api.document;

import com.sutra.api.enums.FileFormat;

import java.time.Instant;
import java.util.List;

/**
 * Internal API of the document service. Files are stored once, checked by content, and served only to the
 * service that owns them; users download through the owning service, which applies its own access rules.
 */
public final class DocumentContracts {

    private DocumentContracts() {
    }

    /** A stored file without its bytes. */
    public record StoredDocument(Long id, String fileName, String contentType, FileFormat fileFormat, long sizeBytes,
                                 String sha256, Instant uploadedAt) {
    }

    /** What the content check found for a file that is not stored (e.g. an RFQ document read into a draft). */
    public record InspectedFile(String fileName, FileFormat fileFormat, long sizeBytes, String sha256) {
    }

    /**
     * Confirms that stored documents now belong to a record ({@code ownerType} e.g. QUOTATION). Uploads that are
     * never attached (the owning transaction failed) are removed by the document service after a while.
     */
    public record AttachRequest(List<Long> documentIds, String ownerType, Long ownerId) {
    }

    /** Documents to delete because their owner deleted them. */
    public record DeleteRequest(List<Long> documentIds) {
    }
}
