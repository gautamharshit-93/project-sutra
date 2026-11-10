package com.sutra.quotation.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.api.decision.DecisionResponses.ApprovalResponse;
import com.sutra.api.enums.ApprovalDecision;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.enums.QuotationStatus;
import com.sutra.api.quotation.VendorPortalResponses.Outcome;
import com.sutra.api.quotation.VendorPortalResponses.Stage;
import com.sutra.common.audit.AuditService;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.DuplicateResourceException;
import com.sutra.common.error.ForbiddenOperationException;
import com.sutra.common.error.ResourceNotFoundException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.quotation.client.DecisionClient;
import com.sutra.quotation.client.DocumentStoreClient;
import com.sutra.quotation.client.MasterDataClient;
import com.sutra.quotation.client.RfqClient;
import com.sutra.quotation.entity.NamedRef;
import com.sutra.quotation.entity.Quotation;
import com.sutra.quotation.exception.QuotationNotFoundException;
import com.sutra.quotation.repository.QuotationFileRepository;
import com.sutra.quotation.repository.QuotationRepository;
import com.sutra.quotation.service.QuotationService;
import com.sutra.quotation.support.Fixtures;
import com.sutra.quotation.support.TestRfq;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

/** A vendor login only ever sees and changes its own vendor's quotations. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VendorPortalServiceImplTest {

    @Mock QuotationRepository quotationRepository;
    @Mock QuotationFileRepository fileRepository;
    @Mock QuotationService quotationService;
    @Mock RfqClient rfqs;
    @Mock DecisionClient decisions;
    @Mock DocumentStoreClient documents;
    @Mock MasterDataClient masterData;
    @Mock CurrentUserProvider currentUser;
    @Mock AuditService auditService;

    private VendorPortalServiceImpl portal;
    private final NamedRef brakeline = Fixtures.vendor("Brakeline Components");
    private final NamedRef rival = Fixtures.vendor("Rival Castings");
    private TestRfq rfq;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-09T06:00:00Z"), ZoneId.of("Asia/Kolkata"));
        portal = new VendorPortalServiceImpl(quotationRepository, fileRepository, quotationService, rfqs, decisions,
                documents, masterData, currentUser, auditService, clock);
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.vendorUser("brakeline", brakeline));
        rfq = Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT));
        when(rfqs.get(anyLong())).thenAnswer(inv -> rfq.toResponse());
        when(rfqs.getMany(any())).thenAnswer(inv -> Map.of(rfq.getId(), rfq.toResponse()));
        when(masterData.activeVendor(brakeline.getId()))
                .thenReturn(new MasterDataClient.VendorSnapshot(brakeline.getId(), "BRK", brakeline.getName(), true));
        when(fileRepository.findByQuotationIdOrderByIdAsc(any())).thenReturn(List.of());
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
        when(quotationService.submitForVendor(any(), eq(brakeline), any())).thenReturn(created);
        var file = new MockMultipartFile("files", "q.pdf", "application/pdf", "%PDF-1.7".getBytes());

        var result = portal.submit(rfq.getId(), List.of(file));

        assertThat(result.stage()).isEqualTo(Stage.RECEIVED);
        assertThat(result.grandTotal()).as("no total before the reading is accepted").isNull();
        verify(quotationService).submitForVendor(eq(rfq.toResponse()), eq(brakeline), any());
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
    void afterTheDueDateTheRfqAcceptsNoMoreQuotations() {
        rfq.setQuotationsDue(LocalDate.of(2026, 11, 8)); // the test clock says 9 Nov 2026

        assertThatThrownBy(() -> portal.submit(rfq.getId(), List.of()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("RFQ_DUE_DATE_PASSED");
        verify(quotationService, never()).submitForVendor(any(), any(), any());
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

        assertThatThrownBy(() -> portal.rfq(rfq.getId()))
                .isInstanceOf(ResourceNotFoundException.class)
                .extracting("code").isEqualTo("PURCHASE_REQUEST_NOT_FOUND");
    }

    @Test
    void ownQuotationCanBeWithdrawnWhileTheRfqIsOpenAndNotCompared() {
        Quotation mine = quotationOf(brakeline);

        portal.withdraw(mine.getId());

        verify(auditService).record(eq(QuotationServiceImpl.AUDIT_TYPE), eq(mine.getId()), eq("WITHDRAWN_BY_VENDOR"), anyMap());
        verify(quotationRepository).delete(mine);
    }

    @Test
    void comparedQuotationCannotBeWithdrawn() {
        Quotation mine = quotationOf(brakeline);
        when(decisions.comparisonReferences(mine.getId())).thenReturn(1L);

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
        when(decisions.latestDecisions(Set.of(rfq.getId()))).thenReturn(Map.of(rfq.getId(), new ApprovalResponse(1L,
                rfq.getId(), 2L, 1, mine.getId(), brakeline.getName(), ApprovalDecision.APPROVED, null, "manager",
                Instant.now())));
        when(quotationRepository.findByVendorIdOrderByIdDesc(brakeline.getId())).thenReturn(List.of(mine));

        var mineSeen = portal.myQuotations().get(0);

        assertThat(mineSeen.outcome()).isEqualTo(Outcome.AWARDED);
        assertThat(mineSeen.grandTotal()).isEqualByComparingTo("118000.00");
        assertThat(mineSeen.canWithdraw()).isFalse();
    }

    @Test
    void loginWithoutAVendorLinkIsRefused() {
        SutraUserPrincipal unlinked = new SutraUserPrincipal(5L, "odd", null, true, Set.of(RoleName.VENDOR));
        when(currentUser.requirePrincipal()).thenReturn(unlinked);

        assertThatThrownBy(() -> portal.myQuotations()).isInstanceOf(ForbiddenOperationException.class);
    }
}
