package com.sutra.quotation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.api.enums.ExtractionSource;
import com.sutra.api.enums.FileFormat;
import com.sutra.api.enums.QuotationStatus;
import com.sutra.api.extraction.ExtractedQuotation;
import com.sutra.api.extraction.ExtractedQuotation.ExtractedItem;
import com.sutra.api.internal.InternalContracts.ExtractionOutcome;
import com.sutra.api.internal.InternalContracts.ExtractionWork;
import com.sutra.api.internal.InternalContracts.LineMatch;
import com.sutra.api.rules.FlagCodes;
import com.sutra.common.audit.AuditService;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.common.security.RoleName;
import com.sutra.quotation.client.RfqClient;
import com.sutra.quotation.entity.Quotation;
import com.sutra.quotation.entity.QuotationFile;
import com.sutra.quotation.repository.QuotationFileRepository;
import com.sutra.quotation.repository.QuotationRepository;
import com.sutra.quotation.service.internal.QuotationCalculator;
import com.sutra.quotation.service.internal.QuotationReviewService;
import com.sutra.quotation.service.internal.QuotationValidator;
import com.sutra.quotation.support.Fixtures;
import com.sutra.quotation.support.TestRfq;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** The quotation side of reading: claiming a quotation and storing what the extraction service read. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExtractionResultServiceImplTest {

    @Mock QuotationRepository quotationRepository;
    @Mock QuotationFileRepository fileRepository;
    @Mock RfqClient rfqs;
    @Mock AuditService auditService;

    private ExtractionResultServiceImpl service;
    private TestRfq pr;
    private Quotation quotation;

    @BeforeEach
    void setUp() {
        var review = new QuotationReviewService(
                new QuotationValidator(new QuotationCalculator(), Fixtures.properties(), Fixtures.CLOCK));
        service = new ExtractionResultServiceImpl(quotationRepository, fileRepository, rfqs, review, auditService,
                Fixtures.properties(), Fixtures.CLOCK);
        pr = Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT));
        quotation = Fixtures.quotation(pr, Fixtures.vendor("Alpha Computers"));
        quotation.setStatus(QuotationStatus.UPLOADED);
        QuotationFile file = new QuotationFile();
        file.setDocumentId(901L);
        file.setFileName("alpha.pdf");
        file.setFileFormat(FileFormat.PDF);
        when(rfqs.get(anyLong())).thenAnswer(inv -> pr.toResponse());
        when(quotationRepository.findById(quotation.getId())).thenReturn(Optional.of(quotation));
        when(fileRepository.findByQuotationIdOrderByIdAsc(quotation.getId())).thenReturn(List.of(file));
    }

    private static ExtractedItem line(String description, String qty, String price, String tax, String total,
                                      double confidence) {
        return new ExtractedItem(Optional.empty(), description, Optional.of(new BigDecimal(qty)), Optional.of("Nos"),
                Optional.of(new BigDecimal(price)), Optional.empty(), Optional.empty(),
                Optional.of(new BigDecimal(tax)), Optional.empty(), Optional.of(new BigDecimal(total)), confidence,
                Optional.empty());
    }

    private static ExtractedQuotation extraction(List<ExtractedItem> items, String grandTotal, List<String> warnings) {
        return new ExtractedQuotation(Optional.of("Alpha Computers Pvt Ltd"), Optional.of("AQ-118"),
                Optional.of("2026-10-30"), Optional.of("2026-11-30"), Optional.of("inr"), items, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(new BigDecimal(grandTotal)), Optional.of("2 weeks"),
                Optional.of("30 days"), Optional.empty(), Optional.empty(), 0.93, warnings);
    }

    /** The extraction service matched quoted line i to requested line i. */
    private ExtractionOutcome read(ExtractedQuotation data) {
        List<LineMatch> matches = new java.util.ArrayList<>();
        for (int i = 0; i < data.items().size() && i < pr.getItems().size(); i++) {
            matches.add(new LineMatch(i, pr.getItems().get(i).getId(), new BigDecimal("0.900")));
        }
        return new ExtractionOutcome(data, ExtractionSource.GROQ, "openai/gpt-oss-120b", matches, null);
    }

    @Test
    void claimingMarksTheQuotationProcessingAndReturnsItsFilesAndRequestedLines() {
        ExtractionWork work = service.claim(quotation.getId());

        assertThat(quotation.getStatus()).isEqualTo(QuotationStatus.PROCESSING);
        assertThat(quotation.getProcessingAttempts()).isEqualTo(1);
        assertThat(work.documents()).singleElement().satisfies(d -> assertThat(d.documentId()).isEqualTo(901L));
        assertThat(work.requestedLines()).hasSize(2);
        assertThat(work.vendorName()).isEqualTo("Alpha Computers");
        assertThat(work.requestCurrency()).isEqualTo("INR");
    }

    @Test
    void aQuotationAlreadyBeingReadIsNotClaimedTwice() {
        quotation.setStatus(QuotationStatus.PROCESSING);

        assertThatThrownBy(() -> service.claim(quotation.getId())).isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void successfulReadingIsStoredMatchedAndValidated() {
        service.claim(quotation.getId());
        service.receive(quotation.getId(), read(extraction(List.of(
                line("Dell Latitude 5440", "10", "68500", "18", "808300", 0.95),
                line("Dell 24in Monitor", "10", "11200", "18", "132160", 0.95)), "940460", List.of())));

        assertThat(quotation.getItems()).hasSize(2);
        assertThat(quotation.getItems().get(0).getRequestItemId()).isEqualTo(pr.getItems().get(0).getId());
        assertThat(quotation.getCurrency()).isEqualTo("INR");
        assertThat(quotation.getQuotationNumber()).isEqualTo("AQ-118");
        assertThat(quotation.getGrandTotal()).as("backend-calculated").isEqualByComparingTo("940460.00");
        assertThat(quotation.getExtractionSource()).isEqualTo(ExtractionSource.GROQ);
        assertThat(quotation.getStatus()).isEqualTo(QuotationStatus.READY_FOR_COMPARISON);
        verify(auditService).recordAs(eq("system"), eq("QUOTATION"), eq(quotation.getId()), eq("EXTRACTED"), anyMap());
    }

    @Test
    void aiTotalsThatDisagreeWithBackendArithmeticAreFlaggedButComparable() {
        service.receive(quotation.getId(), read(extraction(List.of(
                line("Dell Latitude 5440", "10", "68500", "18", "808300", 0.95),
                line("Dell 24in Monitor", "10", "11200", "18", "132160", 0.6)), "999999", List.of("Stamp covers total"))));

        assertThat(quotation.getGrandTotal()).isEqualByComparingTo("940460.00"); // the backend's own total is used
        assertThat(quotation.getStatus()).isEqualTo(QuotationStatus.READY_FOR_COMPARISON);
        assertThat(quotation.getFlags()).extracting(f -> f.getCode()).contains(
                FlagCodes.GRAND_TOTAL_MISMATCH, FlagCodes.LOW_CONFIDENCE, FlagCodes.EXTRACTOR_NOTE);
    }

    @Test
    void aFailedReadingKeepsTheFilesAndRecordsTheReason() {
        service.receive(quotation.getId(), ExtractionOutcome.failed("Groq free-tier limit reached."));

        assertThat(quotation.getStatus()).isEqualTo(QuotationStatus.FAILED);
        assertThat(quotation.getFailureReason()).contains("free-tier limit");
        assertThat(quotation.getItems()).isEmpty();
        verify(auditService).recordAs(eq("system"), eq("QUOTATION"), eq(quotation.getId()), eq("EXTRACTION_FAILED"), anyMap());
    }

    @Test
    void vendorNameOnDocumentThatDiffersFromSelectedVendorIsFlagged() {
        quotation.setVendor(Fixtures.vendor("Gamma Traders"));

        service.receive(quotation.getId(), read(extraction(List.of(
                line("Laptop", "10", "68500", "18", "808300", 0.95)), "808300", List.of())));

        assertThat(quotation.getFlags()).extracting(f -> f.getCode()).contains(FlagCodes.VENDOR_NAME_MISMATCH);
    }

    @Test
    void invalidExtractedValuesAreDroppedNotStored() {
        ExtractedItem bad = new ExtractedItem(Optional.empty(), "Laptop", Optional.of(new BigDecimal("-2")), Optional.empty(),
                Optional.of(new BigDecimal("100")), Optional.of(new BigDecimal("250")), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), 0.9, Optional.empty());

        service.receive(quotation.getId(), read(extraction(List.of(bad), "100", List.of())));

        var item = quotation.getItems().get(0);
        assertThat(item.getQuantity()).as("negative quantity rejected").isNull();
        assertThat(item.getDiscountPercent()).as("percent above 100 rejected").isNull();
        assertThat(quotation.getFlags()).extracting(f -> f.getCode()).contains(FlagCodes.MISSING_QUANTITY);
    }

    @Test
    void aMatchToALineThatIsNotOnTheRfqIsIgnored() {
        var data = extraction(List.of(line("Laptop", "10", "68500", "18", "808300", 0.95)), "808300", List.of());

        service.receive(quotation.getId(), new ExtractionOutcome(data, ExtractionSource.RULES, "rules v1",
                List.of(new LineMatch(0, -1L, BigDecimal.ONE)), null));

        assertThat(quotation.getItems().get(0).getRequestItemId()).isNull();
        assertThat(quotation.getFlags()).extracting(f -> f.getCode()).contains(FlagCodes.UNMATCHED_ITEM);
    }
}
