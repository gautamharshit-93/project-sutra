package com.sutra.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.calculation.FlagCodes;
import com.sutra.calculation.QuotationCalculator;
import com.sutra.calculation.QuotationValidator;
import com.sutra.entity.AppUser;
import com.sutra.entity.PurchaseRequest;
import com.sutra.entity.Quotation;
import com.sutra.entity.QuotationFile;
import com.sutra.entity.QuotationFlag;
import com.sutra.entity.QuotationItem;
import com.sutra.entity.Vendor;
import com.sutra.entity.enums.FileFormat;
import com.sutra.entity.enums.FlagSeverity;
import com.sutra.entity.enums.PurchaseRequestStatus;
import com.sutra.entity.enums.QuotationStatus;
import com.sutra.entity.enums.RoleName;
import com.sutra.exception.BusinessRuleException;
import com.sutra.exception.DuplicateResourceException;
import com.sutra.exception.QuotationNotFoundException;
import com.sutra.exception.UnsupportedQuotationFileException;
import com.sutra.repository.QuotationFileRepository;
import com.sutra.repository.QuotationRepository;
import com.sutra.request.QuotationRequests.QuotationItemRequest;
import com.sutra.security.CurrentUserProvider;
import com.sutra.security.RecordAccessPolicy;
import com.sutra.service.QuotationProcessingEvents.ProcessQuotationRequested;
import com.sutra.support.Fixtures;
import com.sutra.validation.QuotationFileValidator;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotationServiceTest {

    private static final byte[] PDF = "%PDF-1.7 quotation".getBytes(StandardCharsets.US_ASCII);

    @Mock QuotationRepository quotationRepository;
    @Mock QuotationFileRepository fileRepository;
    @Mock PurchaseRequestService purchaseRequestService;
    @Mock VendorService vendorService;
    @Mock CurrentUserProvider currentUser;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher events;
    @Mock EntityManager entityManager;

    private QuotationService service;
    private AppUser buyer;
    private PurchaseRequest pr;
    private Vendor vendor;

    @BeforeEach
    void setUp() {
        var reviewService = new QuotationReviewService(
                new QuotationValidator(new QuotationCalculator(), Fixtures.properties(), Fixtures.CLOCK));
        service = new QuotationService(quotationRepository, fileRepository, purchaseRequestService, vendorService,
                new QuotationFileValidator(Fixtures.properties()), reviewService, new RecordAccessPolicy(currentUser),
                currentUser, auditService, events, entityManager, Fixtures.CLOCK);
        buyer = Fixtures.user("buyer", RoleName.PROCUREMENT);
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(buyer));
        when(currentUser.requireUser()).thenReturn(buyer);
        pr = Fixtures.purchaseRequest(buyer);
        vendor = Fixtures.vendor("Alpha");
        when(purchaseRequestService.findModifiable(pr.getId())).thenReturn(pr);
        when(vendorService.findActive(vendor.getId())).thenReturn(vendor);
        when(quotationRepository.save(any())).thenAnswer(inv -> {
            Quotation q = inv.getArgument(0);
            q.setId(500L);
            return q;
        });
        when(fileRepository.findMetadataByQuotationId(any())).thenReturn(List.of());
    }

    private Quotation stored(Quotation q) {
        when(quotationRepository.findById(q.getId())).thenReturn(Optional.of(q));
        return q;
    }

    @Test
    void uploadStoresOriginalFileAndQueuesExtraction() {
        var file = new MockMultipartFile("file", "Alpha Quote.pdf", "application/pdf", PDF);

        var response = service.upload(pr.getId(), vendor.getId(), List.of(file));

        assertThat(response.status()).isEqualTo(QuotationStatus.UPLOADED);
        assertThat(response.vendorName()).isEqualTo("Alpha");
        ArgumentCaptor<QuotationFile> saved = ArgumentCaptor.forClass(QuotationFile.class);
        verify(fileRepository).save(saved.capture());
        assertThat(saved.getValue().getContent()).isEqualTo(PDF);
        assertThat(saved.getValue().getFileFormat()).isEqualTo(FileFormat.PDF);
        assertThat(saved.getValue().getSizeBytes()).isEqualTo(PDF.length);
        assertThat(saved.getValue().getSha256()).hasSize(64);
        verify(events).publishEvent(new ProcessQuotationRequested(500L));
    }

    @Test
    void unsupportedFileIsRejectedBeforeAnythingIsStored() {
        var file = new MockMultipartFile("file", "quote.txt", "text/plain", "hello".getBytes());

        assertThatThrownBy(() -> service.upload(pr.getId(), vendor.getId(), List.of(file)))
                .isInstanceOf(UnsupportedQuotationFileException.class);
        verify(quotationRepository, never()).save(any());
        verify(fileRepository, never()).save(any());
    }

    @Test
    void uploadToDraftRequestIsRejected() {
        pr.setStatus(PurchaseRequestStatus.DRAFT);
        var file = new MockMultipartFile("file", "q.pdf", "application/pdf", PDF);

        assertThatThrownBy(() -> service.upload(pr.getId(), vendor.getId(), List.of(file)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("PR_NOT_OPEN");
    }

    @Test
    void sameFileTwiceForOneRequestIsRejected() {
        when(quotationRepository.existsFileInRequest(anyString(), eq(pr.getId()))).thenReturn(true);
        var file = new MockMultipartFile("file", "q.pdf", "application/pdf", PDF);

        assertThatThrownBy(() -> service.upload(pr.getId(), vendor.getId(), List.of(file)))
                .isInstanceOf(DuplicateResourceException.class);
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

        var response = service.updateItem(q.getId(), laptop.getId(), new QuotationItemRequest(pr.getItems().get(0).getId(), null,
                "Dell Latitude 5440", new BigDecimal("10"), "Nos", new BigDecimal("67000"), null, null,
                new BigDecimal("18"), null, null, null));

        assertThat(laptop.isManuallyEdited()).isTrue();
        assertThat(laptop.getLineTotal()).isEqualByComparingTo("790600.00");
        assertThat(q.getFlags()).filteredOn(f -> f.getCode().equals(FlagCodes.LOW_CONFIDENCE))
                .allMatch(QuotationFlag::isResolved);
        assertThat(response.status()).isEqualTo(QuotationStatus.READY_FOR_COMPARISON);
    }

    @Test
    void cannotMarkReadyWhileErrorsAreOpen() {
        Quotation q = stored(Fixtures.quotation(pr, vendor));
        Fixtures.item(q, pr.getItems().get(0), "Laptop", "10", null, null); // missing price -> ERROR

        assertThatThrownBy(() -> service.markReady(q.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("OPEN_ERRORS");
    }

    @Test
    void markReadyAcknowledgesWarningsInTheNameOfTheReviewer() {
        Quotation q = stored(Fixtures.quotation(pr, vendor));
        Fixtures.item(q, pr.getItems().get(0), "Laptop", "10", "68500", null); // monitors not quoted -> WARNING

        var response = service.markReady(q.getId());

        assertThat(response.status()).isEqualTo(QuotationStatus.READY_FOR_COMPARISON);
        assertThat(q.getFlags()).filteredOn(f -> f.getSeverity() == FlagSeverity.WARNING)
                .isNotEmpty()
                .allMatch(f -> f.isResolved() && f.getResolvedBy() == buyer);
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

        assertThatThrownBy(() -> service.markReady(q.getId()))
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

        assertThat(response.status()).isEqualTo(QuotationStatus.NEEDS_REVIEW); // monitors still missing
        assertThat(response.failureReason()).isNull();
        assertThat(response.extractionSource()).isEqualTo(com.sutra.entity.enums.ExtractionSource.MANUAL);
    }

    @Test
    void oneQuotationCanHoldSeveralFilesOfDifferentFormats() {
        var pdf = new MockMultipartFile("files", "Northstar.pdf", "application/pdf", PDF);
        var xlsx = new MockMultipartFile("files", "Northstar.xlsx", "application/octet-stream", officeZip("xl/"));
        var docx = new MockMultipartFile("files", "Northstar.docx", "application/octet-stream", officeZip("word/"));

        service.upload(pr.getId(), vendor.getId(), List.of(pdf, xlsx, docx));

        ArgumentCaptor<QuotationFile> saved = ArgumentCaptor.forClass(QuotationFile.class);
        verify(fileRepository, org.mockito.Mockito.times(3)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(QuotationFile::getFileFormat)
                .containsExactly(FileFormat.PDF, FileFormat.XLSX, FileFormat.DOCX);
        assertThat(saved.getAllValues()).allMatch(f -> f.getQuotation().getId().equals(500L));
        verify(quotationRepository, org.mockito.Mockito.times(1)).save(any());
        verify(events, org.mockito.Mockito.times(1)).publishEvent(new ProcessQuotationRequested(500L));
    }

    @Test
    void theSameFileSelectedTwiceIsRejected() {
        var a = new MockMultipartFile("files", "a.pdf", "application/pdf", PDF);
        var b = new MockMultipartFile("files", "copy.pdf", "application/pdf", PDF);

        assertThatThrownBy(() -> service.upload(pr.getId(), vendor.getId(), List.of(a, b)))
                .isInstanceOf(DuplicateResourceException.class).hasMessageContaining("selected twice");
        verify(fileRepository, never()).save(any());
    }

    @Test
    void tooManyFilesAreRejected() {
        List<org.springframework.web.multipart.MultipartFile> files = new java.util.ArrayList<>();
        for (int i = 0; i <= QuotationService.MAX_FILES_PER_QUOTATION; i++) {
            files.add(new MockMultipartFile("files", "q" + i + ".pdf", "application/pdf", ("%PDF-" + i).getBytes()));
        }
        assertThatThrownBy(() -> service.upload(pr.getId(), vendor.getId(), files))
                .isInstanceOf(com.sutra.exception.InvalidQuotationFileException.class).hasMessageContaining("at most");
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
    void addingFilesReRunsExtraction() {
        Quotation q = stored(Fixtures.quotation(pr, vendor));
        q.setStatus(QuotationStatus.NEEDS_REVIEW);
        when(fileRepository.countByQuotationId(q.getId())).thenReturn(1L);

        var response = service.addFiles(q.getId(), List.of(
                new MockMultipartFile("files", "annexure.xlsx", "application/octet-stream", officeZip("xl/"))));

        assertThat(response.status()).isEqualTo(QuotationStatus.UPLOADED);
        verify(events).publishEvent(new ProcessQuotationRequested(q.getId()));
    }

    private static byte[] officeZip(String folder) {
        try {
            var out = new java.io.ByteArrayOutputStream();
            try (var zip = new java.util.zip.ZipOutputStream(out)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
                zip.write("<Types/>".getBytes());
                zip.putNextEntry(new java.util.zip.ZipEntry(folder + "document.xml"));
                zip.write(folder.getBytes());
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
