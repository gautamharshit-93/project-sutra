package com.sutra.quotation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.api.document.DocumentContracts.StoredDocument;
import com.sutra.api.enums.ExtractionSource;
import com.sutra.api.enums.FileFormat;
import com.sutra.api.enums.FlagSeverity;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.enums.QuotationStatus;
import com.sutra.api.extraction.ExtractionChoice;
import com.sutra.api.quotation.QuotationRequests.QuotationItemRequest;
import com.sutra.api.rules.FlagCodes;
import com.sutra.common.audit.AuditService;
import com.sutra.common.client.RemoteServiceException;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.quotation.client.DecisionClient;
import com.sutra.quotation.client.DocumentStoreClient;
import com.sutra.quotation.client.MasterDataClient;
import com.sutra.quotation.client.RfqClient;
import com.sutra.quotation.entity.NamedRef;
import com.sutra.quotation.entity.Quotation;
import com.sutra.quotation.entity.QuotationFile;
import com.sutra.quotation.entity.QuotationFlag;
import com.sutra.quotation.entity.QuotationItem;
import com.sutra.quotation.exception.InvalidQuotationFileException;
import com.sutra.quotation.exception.QuotationNotFoundException;
import com.sutra.quotation.repository.QuotationFileRepository;
import com.sutra.quotation.repository.QuotationRepository;
import com.sutra.quotation.service.internal.QuotationCalculator;
import com.sutra.quotation.service.internal.QuotationReviewService;
import com.sutra.quotation.service.internal.QuotationValidator;
import com.sutra.quotation.service.internal.ReadingQueue;
import com.sutra.quotation.service.internal.RecordAccessPolicy;
import com.sutra.quotation.support.Fixtures;
import com.sutra.quotation.support.TestRfq;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

/** Upload, files, correction and deletion of quotations; the RFQ, files and reading are other services. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotationServiceImplTest {

    private static final byte[] PDF = "%PDF-1.7 quotation".getBytes(StandardCharsets.US_ASCII);

    @Mock QuotationRepository quotationRepository;
    @Mock QuotationFileRepository fileRepository;
    @Mock RfqClient rfqs;
    @Mock MasterDataClient masterData;
    @Mock DocumentStoreClient documents;
    @Mock DecisionClient decisions;
    @Mock ReadingQueue readingQueue;
    @Mock CurrentUserProvider currentUser;
    @Mock AuditService auditService;
    @Mock EntityManager entityManager;

    private QuotationServiceImpl service;
    private SutraUserPrincipal buyer;
    private TestRfq pr;
    private NamedRef vendor;
    private final AtomicLong documentIds = new AtomicLong(900);

    @BeforeEach
    void setUp() {
        var reviewService = new QuotationReviewService(
                new QuotationValidator(new QuotationCalculator(), Fixtures.properties(), Fixtures.CLOCK));
        service = new QuotationServiceImpl(quotationRepository, fileRepository, rfqs, masterData, documents, decisions,
                readingQueue, reviewService, new RecordAccessPolicy(currentUser), currentUser, auditService,
                entityManager, Fixtures.CLOCK);
        buyer = Fixtures.user("buyer", RoleName.PROCUREMENT);
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(buyer));
        pr = Fixtures.purchaseRequest(buyer);
        vendor = Fixtures.vendor("Alpha");
        when(rfqs.get(anyLong())).thenAnswer(inv -> pr.toResponse());
        when(masterData.activeVendor(vendor.getId()))
                .thenReturn(new MasterDataClient.VendorSnapshot(vendor.getId(), "ALPHA", vendor.getName(), true));
        when(quotationRepository.save(any())).thenAnswer(inv -> {
            Quotation q = inv.getArgument(0);
            q.setId(500L);
            return q;
        });
        when(fileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fileRepository.findByQuotationIdOrderByIdAsc(any())).thenReturn(List.of());
        // The document service checks each file's content and returns what it stored.
        when(documents.store(any(), any(), any())).thenAnswer(inv -> {
            List<DocumentStoreClient.Upload> uploads = inv.getArgument(0);
            List<StoredDocument> stored = new ArrayList<>();
            for (DocumentStoreClient.Upload u : uploads) {
                FileFormat format = FileFormat.fromFileName(u.fileName()).orElseThrow();
                stored.add(new StoredDocument(documentIds.incrementAndGet(), u.fileName(), format.mimeType(), format,
                        u.bytes().length, "a".repeat(64), Instant.now()));
            }
            return stored;
        });
    }

    private Quotation stored(Quotation q) {
        when(quotationRepository.findById(q.getId())).thenReturn(Optional.of(q));
        return q;
    }

    @Test
    void uploadKeepsAReferenceToTheStoredFileAndQueuesReading() {
        var file = new MockMultipartFile("file", "Alpha Quote.pdf", "application/pdf", PDF);

        var response = service.upload(pr.getId(), vendor.getId(), List.of(file), ExtractionChoice.AUTO);

        assertThat(response.status()).isEqualTo(QuotationStatus.UPLOADED);
        assertThat(response.vendorName()).isEqualTo("Alpha");
        assertThat(response.requestNumber()).isEqualTo(pr.getRequestNumber());
        ArgumentCaptor<QuotationFile> saved = ArgumentCaptor.forClass(QuotationFile.class);
        verify(fileRepository).save(saved.capture());
        assertThat(saved.getValue().getDocumentId()).isEqualTo(901L);
        assertThat(saved.getValue().getFileFormat()).isEqualTo(FileFormat.PDF);
        assertThat(saved.getValue().getSizeBytes()).isEqualTo(PDF.length);
        verify(decisions).checkEngine(ExtractionChoice.AUTO);
        verify(readingQueue).queue(500L, ExtractionChoice.AUTO);
    }

    @Test
    void aFileTheDocumentServiceRefusesStopsTheUploadWithItsError() {
        org.mockito.Mockito.doThrow(new RemoteServiceException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_QUOTATION_FILE", "Unsupported file type for 'quote.txt'."))
                .when(documents).store(any(), any(), any());
        var file = new MockMultipartFile("file", "quote.txt", "text/plain", "hello".getBytes());

        assertThatThrownBy(() -> service.upload(pr.getId(), vendor.getId(), List.of(file), ExtractionChoice.AUTO))
                .isInstanceOf(RemoteServiceException.class)
                .extracting("code").isEqualTo("UNSUPPORTED_QUOTATION_FILE");
        verify(fileRepository, never()).save(any());
        verify(readingQueue, never()).queue(anyLong(), any());
    }

    @Test
    void uploadToDraftRequestIsRejected() {
        pr.setStatus(PurchaseRequestStatus.DRAFT);
        var file = new MockMultipartFile("file", "q.pdf", "application/pdf", PDF);

        assertThatThrownBy(() -> service.upload(pr.getId(), vendor.getId(), List.of(file), ExtractionChoice.AUTO))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("PR_NOT_OPEN");
    }

    @Test
    void onlyTheBuyerWhoRaisedTheRfqMayUpload() {
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.user("other.buyer", RoleName.PROCUREMENT));
        var file = new MockMultipartFile("file", "q.pdf", "application/pdf", PDF);

        assertThatThrownBy(() -> service.upload(pr.getId(), vendor.getId(), List.of(file), ExtractionChoice.AUTO))
                .isInstanceOf(com.sutra.common.error.ForbiddenOperationException.class);
    }

    @Test
    void sameFileTwiceForOneRequestIsRejected() {
        when(quotationRepository.existsFileInRequest(anyString(), eq(pr.getId()))).thenReturn(true);
        var file = new MockMultipartFile("file", "q.pdf", "application/pdf", PDF);

        assertThatThrownBy(() -> service.upload(pr.getId(), vendor.getId(), List.of(file), ExtractionChoice.AUTO))
                .isInstanceOf(DuplicateResourceException.class);
        verify(documents, never()).store(any(), any(), any());
    }

    @Test
    void missingQuotationThrowsQuotationNotFound() {
        when(quotationRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(1L)).isInstanceOf(QuotationNotFoundException.class);
    }

    @Test
    void manualCorrectionRecalculatesAndSettlesConfidenceFlag() {
        Quotation q = stored(Fixtures.quotation(pr, vendor));
        QuotationItem laptop = Fixtures.item(q, pr.getItems().get(0), "Laptop", "10", "68500", "18");
        Fixtures.item(q, pr.getItems().get(1), "Monitor", "10", "11200", "18");
        q.addFlag(new QuotationFlag(laptop, "item", FlagSeverity.WARNING, FlagCodes.LOW_CONFIDENCE, "low"));

        var response = service.updateItem(q.getId(), laptop.getId(), new QuotationItemRequest(pr.getItems().get(0).getId(),
                null, "Dell Latitude 5440", new BigDecimal("10"), "Nos", new BigDecimal("67000"), null, null,
                new BigDecimal("18"), null, null, null));

        assertThat(laptop.isManuallyEdited()).isTrue();
        assertThat(laptop.getLineTotal()).isEqualByComparingTo("790600.00");
        assertThat(q.getFlags()).filteredOn(f -> f.getCode().equals(FlagCodes.LOW_CONFIDENCE))
                .allMatch(QuotationFlag::isResolved);
        assertThat(response.status()).isEqualTo(QuotationStatus.READY_FOR_COMPARISON);
        assertThat(response.items().get(0).requestItemLineNumber()).as("line number from the RFQ service").isEqualTo(1);
    }

    @Test
    void aMatchToALineOfAnotherRfqIsRefused() {
        Quotation q = stored(Fixtures.quotation(pr, vendor));

        assertThatThrownBy(() -> service.addItem(q.getId(), new QuotationItemRequest(-5L, null, "Laptop",
                new BigDecimal("10"), "Nos", new BigDecimal("68500"), null, null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("REQUEST_ITEM_MISMATCH");
    }

    @Test
    void aSecondQuotationFromTheSameVendorIsRefused() {
        when(quotationRepository.findByPurchaseRequestIdAndVendorId(pr.getId(), vendor.getId()))
                .thenReturn(List.of(Fixtures.quotation(pr, vendor)));
        var second = new MockMultipartFile("files", "second.pdf", "application/pdf", PDF);

        assertThatThrownBy(() -> service.upload(pr.getId(), vendor.getId(), List.of(second), ExtractionChoice.AUTO))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already has a quotation");
    }

    @Test
    void errorFlagsCannotBeDismissed() {
        Quotation q = stored(Fixtures.quotation(pr, vendor));
        QuotationFlag error = new QuotationFlag(null, "items", FlagSeverity.ERROR, FlagCodes.NO_ITEMS_EXTRACTED, "none");
        error.setId(9L);
        q.addFlag(error);

        assertThatThrownBy(() -> service.resolveFlag(q.getId(), 9L, "looks fine"))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("ERROR_FLAG_NEEDS_FIX");
    }

    @Test
    void quotationBeingProcessedCannotBeEdited() {
        Quotation q = stored(Fixtures.quotation(pr, vendor));
        q.setStatus(QuotationStatus.PROCESSING);

        assertThatThrownBy(() -> service.addItem(q.getId(), new QuotationItemRequest(pr.getItems().get(0).getId(), null,
                "Laptop", new BigDecimal("10"), "Nos", new BigDecimal("68500"), null, null, null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("QUOTATION_PROCESSING");
    }

    @Test
    void manualEntryRevivesAFailedQuotation() {
        Quotation q = stored(Fixtures.quotation(pr, vendor));
        q.setStatus(QuotationStatus.FAILED);
        q.setFailureReason("Document processing service is unavailable");

        var response = service.addItem(q.getId(), new QuotationItemRequest(pr.getItems().get(0).getId(), null, "Laptop",
                new BigDecimal("10"), "Nos", new BigDecimal("68500"), null, null, null, null, null, null));

        assertThat(response.status()).isEqualTo(QuotationStatus.READY_FOR_COMPARISON); // missing monitors are a note
        assertThat(response.failureReason()).isNull();
        assertThat(response.extractionSource()).isEqualTo(ExtractionSource.MANUAL);
    }

    @Test
    void oneQuotationCanHoldSeveralFilesOfDifferentFormats() {
        var pdf = new MockMultipartFile("files", "Northstar.pdf", "application/pdf", PDF);
        var xlsx = new MockMultipartFile("files", "Northstar.xlsx", "application/octet-stream", "xlsx".getBytes());
        var docx = new MockMultipartFile("files", "Northstar.docx", "application/octet-stream", "docx".getBytes());

        service.upload(pr.getId(), vendor.getId(), List.of(pdf, xlsx, docx), ExtractionChoice.AUTO);

        ArgumentCaptor<QuotationFile> saved = ArgumentCaptor.forClass(QuotationFile.class);
        verify(fileRepository, times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(QuotationFile::getFileFormat)
                .containsExactly(FileFormat.PDF, FileFormat.XLSX, FileFormat.DOCX);
        assertThat(saved.getAllValues()).allMatch(f -> f.getQuotation().getId().equals(500L));
        verify(quotationRepository, times(1)).save(any());
        verify(readingQueue, times(1)).queue(500L, ExtractionChoice.AUTO);
    }

    @Test
    void theSameFileSelectedTwiceIsRejected() {
        var a = new MockMultipartFile("files", "a.pdf", "application/pdf", PDF);
        var b = new MockMultipartFile("files", "copy.pdf", "application/pdf", PDF);

        assertThatThrownBy(() -> service.upload(pr.getId(), vendor.getId(), List.of(a, b), ExtractionChoice.AUTO))
                .isInstanceOf(DuplicateResourceException.class).hasMessageContaining("selected twice");
        verify(fileRepository, never()).save(any());
    }

    @Test
    void tooManyFilesAreRejected() {
        List<org.springframework.web.multipart.MultipartFile> files = new ArrayList<>();
        for (int i = 0; i <= QuotationServiceImpl.MAX_FILES_PER_QUOTATION; i++) {
            files.add(new MockMultipartFile("files", "q" + i + ".pdf", "application/pdf", ("%PDF-" + i).getBytes()));
        }
        assertThatThrownBy(() -> service.upload(pr.getId(), vendor.getId(), files, ExtractionChoice.AUTO))
                .isInstanceOf(InvalidQuotationFileException.class).hasMessageContaining("at most");
    }

    @Test
    void lastFileCannotBeRemoved() {
        Quotation q = stored(Fixtures.quotation(pr, vendor));
        QuotationFile only = new QuotationFile();
        only.setId(77L);
        when(fileRepository.findByIdAndQuotationId(77L, q.getId())).thenReturn(Optional.of(only));
        when(fileRepository.countByQuotationId(q.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.removeFile(q.getId(), 77L))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("LAST_FILE");
    }

    @Test
    void addingFilesReadsTheQuotationAgain() {
        Quotation q = stored(Fixtures.quotation(pr, vendor));
        q.setStatus(QuotationStatus.NEEDS_REVIEW);
        when(fileRepository.countByQuotationId(q.getId())).thenReturn(1L);

        var response = service.addFiles(q.getId(), List.of(
                new MockMultipartFile("files", "annexure.xlsx", "application/octet-stream", "xlsx".getBytes())));

        assertThat(response.status()).isEqualTo(QuotationStatus.UPLOADED);
        verify(readingQueue).queue(q.getId(), ExtractionChoice.AUTO);
    }

    @Test
    void aQuotationInASavedComparisonIsKept() {
        Quotation q = stored(Fixtures.quotation(pr, vendor));
        when(decisions.comparisonReferences(q.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(q.getId()))
                .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("QUOTATION_IN_COMPARISON");
        verify(quotationRepository, never()).delete(any());
    }

    @Test
    void rfqChangesReCheckItsReadQuotations() {
        Quotation read = Fixtures.quotation(pr, vendor);
        Fixtures.item(read, pr.getItems().get(0), "Laptop", "10", "68500", "18");
        Quotation processing = Fixtures.quotation(pr, Fixtures.vendor("Beta"));
        processing.setStatus(QuotationStatus.PROCESSING);
        when(quotationRepository.findByPurchaseRequestIdOrderByIdAsc(pr.getId())).thenReturn(List.of(read, processing));
        pr.setCurrency("USD");

        service.revalidateForRfq(pr.getId());

        assertThat(read.getFlags()).extracting(QuotationFlag::getCode).contains(FlagCodes.CURRENCY_MISMATCH);
        assertThat(processing.getFlags()).as("a quotation being read is left alone").isEmpty();
    }
}
