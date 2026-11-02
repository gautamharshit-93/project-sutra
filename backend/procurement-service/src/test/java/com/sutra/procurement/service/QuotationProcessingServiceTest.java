package com.sutra.procurement.service;

import com.sutra.common.audit.AuditService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.procurement.ai.ExtractedQuotation;
import com.sutra.procurement.ai.ExtractedQuotation.ExtractedItem;
import com.sutra.procurement.ai.ExtractionEngines;
import com.sutra.procurement.ai.ItemMatcher;
import com.sutra.procurement.ai.QuotationExtractor;
import com.sutra.procurement.calculation.FlagCodes;
import com.sutra.procurement.calculation.QuotationCalculator;
import com.sutra.procurement.calculation.QuotationValidator;
import com.sutra.procurement.config.ExtractionProperties;
import com.sutra.procurement.entity.PurchaseRequest;
import com.sutra.procurement.entity.Quotation;
import com.sutra.procurement.entity.QuotationFile;
import com.sutra.procurement.entity.enums.ExtractionSource;
import com.sutra.procurement.entity.enums.FileFormat;
import com.sutra.procurement.entity.enums.QuotationStatus;
import com.sutra.common.security.RoleName;
import com.sutra.procurement.exception.QuotationProcessingException;
import com.sutra.procurement.repository.QuotationFileRepository;
import com.sutra.procurement.repository.QuotationRepository;
import com.sutra.procurement.support.Fixtures;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuotationProcessingServiceTest {

    @Mock QuotationRepository quotationRepository;
    @Mock QuotationFileRepository fileRepository;
    @Mock QuotationExtractor extractor;
    @Mock ItemMatcher itemMatcher;
    @Mock AuditService auditService;
    @Mock PlatformTransactionManager transactionManager;

    private QuotationProcessingService service;
    private PurchaseRequest pr;
    private Quotation quotation;
    private QuotationFile file;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var review = new QuotationReviewService(
                new QuotationValidator(new QuotationCalculator(), Fixtures.properties(), Fixtures.CLOCK));
        when(extractor.source()).thenReturn(ExtractionSource.GEMINI);
        when(extractor.isAvailable()).thenReturn(true);
        var engines = new ExtractionEngines(List.of(extractor), ExtractionProperties.defaults());
        service = new QuotationProcessingService(quotationRepository, fileRepository, engines, itemMatcher,
                review, auditService, transactionManager, Fixtures.properties(), Fixtures.CLOCK);

        pr = Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT));
        quotation = Fixtures.quotation(pr, Fixtures.vendor("Alpha Computers"));
        quotation.setStatus(QuotationStatus.UPLOADED);
        file = new QuotationFile();
        file.setFileName("alpha.pdf");
        file.setFileFormat(FileFormat.PDF);
        file.setContent("%PDF-".getBytes());
        when(quotationRepository.findById(quotation.getId())).thenReturn(Optional.of(quotation));
        when(fileRepository.findByQuotationIdOrderByIdAsc(quotation.getId())).thenReturn(List.of(file));
        // the mocked matcher links quoted lines to request lines in order
        org.mockito.Mockito.doAnswer(inv -> {
            var quoted = quotation.getItems();
            for (int i = 0; i < quoted.size() && i < pr.getItems().size(); i++) {
                quoted.get(i).setRequestItem(pr.getItems().get(i));
                quoted.get(i).setMatchConfidence(new BigDecimal("0.900"));
            }
            return null;
        }).when(itemMatcher).assign(any(), any());
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

    @Test
    void successfulExtractionIsStoredMatchedAndValidated() {
        when(extractor.extract(any())).thenReturn(extraction(List.of(
                line("Dell Latitude 5440", "10", "68500", "18", "808300", 0.95),
                line("Dell 24in Monitor", "10", "11200", "18", "132160", 0.95)), "940460", List.of()));

        service.process(quotation.getId());

        assertThat(quotation.getItems()).hasSize(2);
        assertThat(quotation.getCurrency()).isEqualTo("INR");
        assertThat(quotation.getQuotationNumber()).isEqualTo("AQ-118");
        assertThat(quotation.getGrandTotal()).as("backend-calculated").isEqualByComparingTo("940460.00");
        assertThat(quotation.getExtractionSource()).isEqualTo(ExtractionSource.GEMINI);
        assertThat(quotation.getProcessingAttempts()).isEqualTo(1);
        assertThat(quotation.getStatus()).isEqualTo(QuotationStatus.READY_FOR_COMPARISON);
        verify(auditService).recordAs(eq("system"), eq("QUOTATION"), eq(quotation.getId()), eq("EXTRACTED"), anyMap());
    }

    @Test
    void aiTotalsThatDisagreeWithBackendArithmeticAreFlaggedForReview() {
        when(extractor.extract(any())).thenReturn(extraction(List.of(
                line("Dell Latitude 5440", "10", "68500", "18", "808300", 0.95),
                line("Dell 24in Monitor", "10", "11200", "18", "132160", 0.6)), "999999", List.of("Stamp covers total")));

        service.process(quotation.getId());

        assertThat(quotation.getGrandTotal()).isEqualByComparingTo("940460.00");
        assertThat(quotation.getStatus()).isEqualTo(QuotationStatus.NEEDS_REVIEW);
        assertThat(quotation.getFlags()).extracting(f -> f.getCode()).contains(
                FlagCodes.GRAND_TOTAL_MISMATCH, FlagCodes.LOW_CONFIDENCE, FlagCodes.EXTRACTOR_NOTE);
    }

    @Test
    void failedExtractionKeepsTheFileAndRecordsTheReason() {
        when(extractor.extract(any())).thenThrow(new QuotationProcessingException("Gemini free-tier limit reached."));

        service.process(quotation.getId());

        assertThat(quotation.getStatus()).isEqualTo(QuotationStatus.FAILED);
        assertThat(quotation.getFailureReason()).contains("free-tier limit");
        assertThat(quotation.getItems()).isEmpty();
        verify(fileRepository, never()).delete(any());
        verify(auditService).recordAs(eq("system"), eq("QUOTATION"), eq(quotation.getId()), eq("EXTRACTION_FAILED"), anyMap());
    }

    @Test
    void vendorNameOnDocumentThatDiffersFromSelectedVendorIsFlagged() {
        quotation.setVendor(Fixtures.vendor("Gamma Traders"));
        when(extractor.extract(any())).thenReturn(extraction(List.of(
                line("Laptop", "10", "68500", "18", "808300", 0.95)), "808300", List.of()));

        service.process(quotation.getId());

        assertThat(quotation.getFlags()).extracting(f -> f.getCode()).contains(FlagCodes.VENDOR_NAME_MISMATCH);
    }

    @Test
    void quotationAlreadyBeingProcessedIsSkipped() {
        quotation.setStatus(QuotationStatus.PROCESSING);

        service.process(quotation.getId());

        verify(extractor, never()).extract(any());
    }

    @Test
    void invalidExtractedValuesAreDroppedNotStored() {
        ExtractedItem bad = new ExtractedItem(Optional.empty(), "Laptop", Optional.of(new BigDecimal("-2")), Optional.empty(),
                Optional.of(new BigDecimal("100")), Optional.of(new BigDecimal("250")), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), 0.9, Optional.empty());
        when(extractor.extract(any())).thenReturn(extraction(List.of(bad), "100", List.of()));

        service.process(quotation.getId());

        var item = quotation.getItems().get(0);
        assertThat(item.getQuantity()).as("negative quantity rejected").isNull();
        assertThat(item.getDiscountPercent()).as("percent above 100 rejected").isNull();
        assertThat(quotation.getFlags()).extracting(f -> f.getCode()).contains(FlagCodes.MISSING_QUANTITY);
    }

    @Test
    void documentWithoutAnyLineItemFailsWithAClearReason() {
        when(extractor.extract(any())).thenReturn(new ExtractedQuotation(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), 0.2, List.of("The document is blank")));

        service.process(quotation.getId());

        assertThat(quotation.getStatus()).isEqualTo(QuotationStatus.FAILED);
        assertThat(quotation.getFailureReason()).contains("No line items found", "blank", "manually");
    }
}
