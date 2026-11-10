package com.sutra.document.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.api.document.DocumentContracts.StoredDocument;
import com.sutra.api.enums.FileFormat;
import com.sutra.document.config.DocumentProperties;
import com.sutra.document.entity.Document;
import com.sutra.document.exception.InvalidQuotationFileException;
import com.sutra.document.repository.DocumentRepository;
import com.sutra.document.service.internal.FileContentValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionTemplate;

/** Storing files: content checked first, all-or-nothing, orphans removed. */
@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n".getBytes();
    private static final Instant NOW = Instant.parse("2026-11-10T10:00:00Z");

    @Mock DocumentRepository repository;
    private DocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        DocumentProperties properties = new DocumentProperties(1024 * 1024, 60);
        TransactionTemplate tx = new TransactionTemplate() {
            @Override
            public void executeWithoutResult(java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action) {
                action.accept(null);
            }
        };
        service = new DocumentServiceImpl(repository, new FileContentValidator(properties), properties,
                Clock.fixed(NOW, ZoneOffset.UTC), tx);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validFilesAreStoredPendingWithHashAndFormat() {
        AtomicLong ids = new AtomicLong(40);
        when(repository.saveAll(anyList())).thenAnswer(inv -> {
            List<Document> docs = inv.getArgument(0);
            docs.forEach(d -> d.setId(ids.incrementAndGet()));
            return docs;
        });

        List<StoredDocument> stored = service.store(List.of(new MockMultipartFile("files", "C:\\fake\\quote.pdf",
                "application/pdf", PDF)), 7L, "asha.buyer");

        assertThat(stored).singleElement().satisfies(d -> {
            assertThat(d.id()).isEqualTo(41L);
            assertThat(d.fileName()).isEqualTo("quote.pdf");
            assertThat(d.fileFormat()).isEqualTo(FileFormat.PDF);
            assertThat(d.sha256()).hasSize(64);
            assertThat(d.uploadedAt()).isEqualTo(NOW);
        });
        ArgumentCaptor<List<Document>> saved = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue().get(0).getStatus()).isEqualTo(Document.Status.PENDING);
        assertThat(saved.getValue().get(0).getContent()).isEqualTo(PDF);
    }

    @Test
    void oneBadFileMeansNothingIsStored() {
        var good = new MockMultipartFile("files", "quote.pdf", "application/pdf", PDF);
        var renamed = new MockMultipartFile("files", "list.pdf", "application/pdf", "just text".getBytes());

        assertThatThrownBy(() -> service.store(List.of(good, renamed), 7L, "asha.buyer"))
                .isInstanceOf(InvalidQuotationFileException.class)
                .hasMessageContaining("not a valid PDF");
        verify(repository, never()).saveAll(any());
    }

    @Test
    void anEmptyUploadIsRefused() {
        assertThatThrownBy(() -> service.store(List.of(), 7L, "asha.buyer"))
                .isInstanceOf(InvalidQuotationFileException.class);
    }

    @Test
    void uploadsNeverAttachedAreRemovedAfterTheGracePeriod() {
        when(repository.findPendingBefore(NOW.minusSeconds(3600))).thenReturn(List.of(3L, 4L));

        org.mockito.Mockito.doNothing().doThrow(new org.springframework.dao.DataIntegrityViolationException("referenced"))
                .when(repository).flush();

        assertThat(service.removeOrphans()).as("the referenced one is kept").isEqualTo(1);
        verify(repository).deleteById(3L);
        verify(repository).deleteById(4L);
    }
}
