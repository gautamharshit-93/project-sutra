package com.sutra.document.service.internal;

import com.sutra.document.service.DocumentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * An upload is stored before the owning record (e.g. the quotation) is saved. If that save fails, the stored file is
 * never attached; this job removes such uploads once they are older than {@code sutra.files.orphan-after-minutes}.
 * (Attached files are never touched; a cross-schema foreign key from the owner also protects them.)
 */
@Component
@ConditionalOnProperty(name = "sutra.files.orphan-cleanup", havingValue = "true", matchIfMissing = true)
public class OrphanDocumentCleanupJob {

    private final DocumentService documents;

    public OrphanDocumentCleanupJob(DocumentService documents) {
        this.documents = documents;
    }

    @Scheduled(fixedDelayString = "${sutra.files.orphan-check-interval-ms:600000}", initialDelay = 60_000)
    public void run() {
        documents.removeOrphans();
    }
}
