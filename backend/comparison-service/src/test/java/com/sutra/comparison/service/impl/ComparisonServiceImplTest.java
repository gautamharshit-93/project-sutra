package com.sutra.comparison.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.api.comparison.ComparisonResult;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.enums.QuotationStatus;
import com.sutra.common.audit.AuditService;
import com.sutra.common.client.RemoteServiceException;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.ForbiddenOperationException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.comparison.client.ProcurementClients;
import com.sutra.comparison.entity.ComparisonReport;
import com.sutra.comparison.repository.ComparisonReportRepository;
import com.sutra.comparison.service.internal.ComparisonEngine;
import com.sutra.comparison.support.Fixtures;
import com.sutra.comparison.support.TestQuotation;
import com.sutra.comparison.support.TestRfq;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/** Generating versioned comparisons from the RFQ's ready quotations, and moving the RFQ to review. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComparisonServiceImplTest {

    @Mock ComparisonReportRepository reportRepository;
    @Mock ProcurementClients procurement;
    @Mock CurrentUserProvider currentUser;
    @Mock AuditService auditService;

    private final JsonMapper json = JsonMapper.builder().build();
    private ComparisonServiceImpl service;
    private TestRfq pr;

    @BeforeEach
    void setUp() {
        service = new ComparisonServiceImpl(reportRepository, procurement, new ComparisonEngine(Fixtures.CLOCK),
                currentUser, auditService, json, Fixtures.CLOCK);
        SutraUserPrincipal buyer = Fixtures.user("buyer", RoleName.PROCUREMENT);
        when(currentUser.requirePrincipal()).thenReturn(buyer);
        when(currentUser.username()).thenReturn("buyer");
        pr = Fixtures.purchaseRequest(buyer);
        when(procurement.rfq(pr.getId())).thenAnswer(inv -> pr.toResponse());
        when(reportRepository.saveAndFlush(any())).thenAnswer(inv -> {
            ComparisonReport r = inv.getArgument(0);
            r.setId(70L);
            return r;
        });
    }

    private TestQuotation ready(String vendor, String laptopPrice, String monitorPrice) {
        TestQuotation q = Fixtures.quotation(pr, vendor);
        Fixtures.item(q, pr.getItems().get(0), "Laptop", "10", laptopPrice, "18");
        Fixtures.item(q, pr.getItems().get(1), "Monitor", "10", monitorPrice, "18");
        return q;
    }

    @Test
    void generatesNextVersionStoresDetailsAndMovesRequestToReview() {
        TestQuotation a = ready("Alpha", "68500", "11200");
        TestQuotation b = ready("Bravo", "66900", "12000");
        TestQuotation pending = Fixtures.quotation(pr, "Pending");
        pending.setStatus(QuotationStatus.NEEDS_REVIEW);
        when(procurement.quotations(pr.getId())).thenReturn(Fixtures.responses(a, b, pending));
        when(reportRepository.findMaxVersion(pr.getId())).thenReturn(2);

        var response = service.generate(pr.getId());

        assertThat(response.reportVersion()).isEqualTo(3);
        assertThat(response.lowestCompleteQuotationId()).isEqualTo(b.getId());
        assertThat(response.result().quotations()).hasSize(2);
        assertThat(response.result().warnings()).anyMatch(w -> w.contains("1 quotation(s) not ready"));
        verify(procurement).changeStatus(eq(pr.getId()), eq(PurchaseRequestStatus.OPEN),
                eq(PurchaseRequestStatus.UNDER_REVIEW), eq("buyer"), anyMap());

        ArgumentCaptor<ComparisonReport> saved = ArgumentCaptor.forClass(ComparisonReport.class);
        verify(reportRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getQuotations()).extracting(cq -> cq.getQuotationId())
                .containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(saved.getValue().getLowestCompleteQuotationId()).isEqualTo(b.getId());
        // the stored JSON round-trips back into the same result
        assertThat(json.readValue(saved.getValue().getDetails(), ComparisonResult.class).rows()).hasSize(2);
    }

    @Test
    void anRfqAlreadyUnderReviewIsNotMovedAgain() {
        pr.setStatus(PurchaseRequestStatus.UNDER_REVIEW);
        when(procurement.quotations(pr.getId())).thenReturn(Fixtures.responses(ready("Alpha", "68500", "11200")));

        service.generate(pr.getId());

        verify(procurement, never()).changeStatus(any(), any(), any(), any(), any());
    }

    @Test
    void whenTheRfqServiceRefusesTheReportIsNotKept() {
        when(procurement.quotations(pr.getId())).thenReturn(Fixtures.responses(ready("Alpha", "68500", "11200")));
        when(procurement.changeStatus(any(), any(), any(), any(), any())).thenThrow(new RemoteServiceException(
                HttpStatus.UNPROCESSABLE_CONTENT, "PR_STATUS_CHANGED", "PR-2026-00001 is CANCELLED"));

        assertThatThrownBy(() -> service.generate(pr.getId()))
                .isInstanceOf(RemoteServiceException.class).extracting("code").isEqualTo("PR_STATUS_CHANGED");
        // the exception rolls the transaction back, so the flushed report is not committed
    }

    @Test
    void requiresAtLeastOneReadyQuotation() {
        TestQuotation pending = Fixtures.quotation(pr, "Pending");
        pending.setStatus(QuotationStatus.NEEDS_REVIEW);
        when(procurement.quotations(pr.getId())).thenReturn(Fixtures.responses(pending));

        assertThatThrownBy(() -> service.generate(pr.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("NO_READY_QUOTATIONS");
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    void viewerCannotGenerateComparisons() {
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.user("manager", RoleName.APPROVER));

        assertThatThrownBy(() -> service.generate(pr.getId())).isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void aReportIsStaleWhenAComparedQuotationChanged() {
        ComparisonReport report = new ComparisonReport();
        report.setId(70L);
        var cq = new com.sutra.comparison.entity.ComparisonQuotation();
        cq.setComparisonReport(report);
        cq.setQuotationId(5L);
        cq.setQuotationVersion(3L);
        report.getQuotations().add(cq);
        when(reportRepository.findById(70L)).thenReturn(Optional.of(report));
        when(procurement.versions(any())).thenReturn(Map.of(5L, 4L));

        assertThat(service.isStale(70L)).isTrue();
    }
}
