package com.sutra.procurement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.common.audit.AuditService;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.common.error.ForbiddenOperationException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.procurement.client.MasterDataClient;
import com.sutra.procurement.entity.Approval;
import com.sutra.procurement.entity.NamedRef;
import com.sutra.procurement.entity.PurchaseRequest;
import com.sutra.procurement.entity.Quotation;
import com.sutra.procurement.entity.enums.ApprovalDecision;
import com.sutra.procurement.entity.enums.PurchaseRequestStatus;
import com.sutra.procurement.entity.enums.QuotationStatus;
import com.sutra.procurement.exception.PurchaseRequestNotFoundException;
import com.sutra.procurement.exception.QuotationNotFoundException;
import com.sutra.procurement.repository.ApprovalRepository;
import com.sutra.procurement.repository.PurchaseRequestRepository;
import com.sutra.procurement.repository.QuotationFileRepository;
import com.sutra.procurement.repository.QuotationRepository;
import com.sutra.procurement.response.VendorPortalResponses.Outcome;
import com.sutra.procurement.response.VendorPortalResponses.Stage;
import com.sutra.procurement.support.Fixtures;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

/** A vendor login only ever sees and changes its own vendor's quotations. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VendorPortalServiceTest {

    @Mock PurchaseRequestRepository purchaseRequestRepository;
    @Mock QuotationRepository quotationRepository;
    @Mock QuotationFileRepository fileRepository;
    @Mock ApprovalRepository approvalRepository;
    @Mock QuotationService quotationService;
    @Mock MasterDataClient masterData;
    @Mock CurrentUserProvider currentUser;
    @Mock AuditService auditService;
    @InjectMocks VendorPortalService portal;

    private final NamedRef brakeline = Fixtures.vendor("Brakeline Components");
    private final NamedRef rival = Fixtures.vendor("Rival Castings");
    private PurchaseRequest rfq;

    @BeforeEach
    void setUp() {
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.vendorUser("brakeline", brakeline));
        rfq = Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT));
        when(purchaseRequestRepository.findById(rfq.getId())).thenReturn(Optional.of(rfq));
        when(masterData.activeVendor(brakeline.getId()))
                .thenReturn(new MasterDataClient.VendorSnapshot(brakeline.getId(), "BRK", brakeline.getName(), true));
        when(fileRepository.findMetadataByQuotationId(any())).thenReturn(List.of());
    }

    private Quotation quotationOf(NamedRef vendor) {
        Quotation q = Fixtures.quotation(rfq, vendor);
        when(quotationRepository.findById(q.getId())).thenReturn(Optional.of(q));
        return q;
    }

    @Test
    void vendorSubmitsItsOwnQuotationForAnOpenRfq() {
        Quotation created = Fixtures.quotation(rfq, brakeline);
        created.setStatus(QuotationStatus.UPLOADED);
        when(quotationService.submitForVendor(eq(rfq), eq(brakeline), any())).thenReturn(created);
        var file = new MockMultipartFile("files", "q.pdf", "application/pdf", "%PDF-1.7".getBytes());

        var result = portal.submit(rfq.getId(), List.of(file));

        assertThat(result.stage()).isEqualTo(Stage.RECEIVED);
        assertThat(result.grandTotal()).as("no total before a buyer confirms the reading").isNull();
        verify(quotationService).submitForVendor(eq(rfq), eq(brakeline), any());
    }

    @Test
    void oneQuotationPerVendorAndRfq() {
        when(quotationRepository.findByPurchaseRequestIdAndVendorId(rfq.getId(), brakeline.getId()))
                .thenReturn(List.of(Fixtures.quotation(rfq, brakeline)));

        assertThatThrownBy(() -> portal.submit(rfq.getId(), List.of())).isInstanceOf(DuplicateResourceException.class);
        verify(quotationService, never()).submitForVendor(any(), any(), any());
    }

    @Test
    void closedRfqAcceptsNoQuotations() {
        rfq.setStatus(PurchaseRequestStatus.UNDER_REVIEW);

        assertThatThrownBy(() -> portal.submit(rfq.getId(), List.of()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("RFQ_CLOSED");
    }

    @Test
    void anotherVendorsQuotationLooksLikeItDoesNotExist() {
        Quotation theirs = quotationOf(rival);

        assertThatThrownBy(() -> portal.withdraw(theirs.getId())).isInstanceOf(QuotationNotFoundException.class);
        assertThatThrownBy(() -> portal.download(theirs.getId(), 1L)).isInstanceOf(QuotationNotFoundException.class);
        verify(quotationRepository, never()).delete(any());
    }

    @Test
    void closedRfqTheVendorDidNotQuoteOnIsHidden() {
        rfq.setStatus(PurchaseRequestStatus.APPROVED);

        assertThatThrownBy(() -> portal.rfq(rfq.getId())).isInstanceOf(PurchaseRequestNotFoundException.class);
    }

    @Test
    void ownQuotationCanBeWithdrawnWhileTheRfqIsOpenAndNotCompared() {
        Quotation mine = quotationOf(brakeline);

        portal.withdraw(mine.getId());

        verify(auditService).record(eq(QuotationService.AUDIT_TYPE), eq(mine.getId()), eq("WITHDRAWN_BY_VENDOR"), anyMap());
        verify(quotationRepository).delete(mine);
    }

    @Test
    void comparedQuotationCannotBeWithdrawn() {
        Quotation mine = quotationOf(brakeline);
        when(quotationRepository.countComparisonReferences(mine.getId())).thenReturn(1L);

        assertThatThrownBy(() -> portal.withdraw(mine.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("CANNOT_WITHDRAW");
    }

    @Test
    void vendorLearnsWhetherItWonTheAward() {
        Quotation mine = Fixtures.quotation(rfq, brakeline);
        mine.setStatus(QuotationStatus.READY_FOR_COMPARISON);
        mine.setGrandTotal(new BigDecimal("118000.00"));
        rfq.setStatus(PurchaseRequestStatus.APPROVED);
        Approval award = new Approval();
        award.setDecision(ApprovalDecision.APPROVED);
        award.setSelectedQuotation(mine);
        when(approvalRepository.findByPurchaseRequestIdOrderByDecidedAtDesc(rfq.getId())).thenReturn(List.of(award));
        when(quotationRepository.findByVendorIdOrderByIdDesc(brakeline.getId())).thenReturn(List.of(mine));

        var mineSeen = portal.myQuotations().get(0);

        assertThat(mineSeen.outcome()).isEqualTo(Outcome.AWARDED);
        assertThat(mineSeen.grandTotal()).isEqualByComparingTo("118000.00");
        assertThat(mineSeen.canWithdraw()).isFalse();
    }

    @Test
    void loginWithoutAVendorLinkIsRefused() {
        SutraUserPrincipal unlinked = new SutraUserPrincipal(5L, "odd", null, true, java.util.Set.of(RoleName.VENDOR));
        when(currentUser.requirePrincipal()).thenReturn(unlinked);

        assertThatThrownBy(() -> portal.myQuotations()).isInstanceOf(ForbiddenOperationException.class);
    }
}
