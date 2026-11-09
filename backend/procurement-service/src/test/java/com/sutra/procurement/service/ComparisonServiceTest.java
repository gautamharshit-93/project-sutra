package com.sutra.procurement.service;

import com.sutra.common.audit.AuditService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.procurement.calculation.ComparisonEngine;
import com.sutra.procurement.calculation.QuotationCalculator;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.procurement.entity.ComparisonReport;
import com.sutra.procurement.entity.PurchaseRequest;
import com.sutra.procurement.entity.Quotation;
import com.sutra.procurement.entity.enums.PurchaseRequestStatus;
import com.sutra.procurement.entity.enums.QuotationStatus;
import com.sutra.common.security.RoleName;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.error.ForbiddenOperationException;
import com.sutra.procurement.repository.ComparisonReportRepository;
import com.sutra.procurement.repository.QuotationRepository;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.procurement.security.RecordAccessPolicy;
import com.sutra.procurement.support.Fixtures;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComparisonServiceTest {

    @Mock ComparisonReportRepository reportRepository;
    @Mock QuotationRepository quotationRepository;
    @Mock PurchaseRequestService purchaseRequestService;
    @Mock CurrentUserProvider currentUser;
    @Mock AuditService auditService;

    private ComparisonService service;
    private SutraUserPrincipal buyer;
    private PurchaseRequest pr;

    @BeforeEach
    void setUp() {
        service = new ComparisonService(reportRepository, quotationRepository, purchaseRequestService,
                new ComparisonEngine(Fixtures.CLOCK), new RecordAccessPolicy(currentUser), currentUser, auditService,
                JsonMapper.builder().build(), Fixtures.CLOCK);
        buyer = Fixtures.user("buyer", RoleName.PROCUREMENT);
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(buyer));
        pr = Fixtures.purchaseRequest(buyer);
        when(purchaseRequestService.find(pr.getId())).thenReturn(pr);
        when(reportRepository.save(any())).thenAnswer(inv -> {
            ComparisonReport r = inv.getArgument(0);
            r.setId(70L);
            return r;
        });
    }

    private Quotation ready(String vendor, String laptopPrice, String monitorPrice) {
        Quotation q = Fixtures.quotation(pr, Fixtures.vendor(vendor));
        Fixtures.item(q, pr.getItems().get(0), "Laptop", "10", laptopPrice, "18");
        Fixtures.item(q, pr.getItems().get(1), "Monitor", "10", monitorPrice, "18");
        new QuotationCalculator().recalculate(q);
        q.setStatus(QuotationStatus.READY_FOR_COMPARISON);
        return q;
    }

    @Test
    void generatesNextVersionStoresDetailsAndMovesRequestToReview() {
        Quotation a = ready("Alpha", "68500", "11200");
        Quotation b = ready("Bravo", "66900", "12000");
        Quotation pending = Fixtures.quotation(pr, Fixtures.vendor("Pending"));
        when(quotationRepository.findByPurchaseRequestIdOrderByIdAsc(pr.getId())).thenReturn(List.of(a, b, pending));
        when(reportRepository.findMaxVersion(pr.getId())).thenReturn(2);

        var response = service.generate(pr.getId());

        assertThat(response.reportVersion()).isEqualTo(3);
        assertThat(response.lowestCompleteQuotationId()).isEqualTo(b.getId());
        assertThat(response.result().quotations()).hasSize(2);
        assertThat(response.result().warnings()).anyMatch(w -> w.contains("1 quotation(s) not ready"));
        assertThat(pr.getStatus()).isEqualTo(PurchaseRequestStatus.UNDER_REVIEW);

        ArgumentCaptor<ComparisonReport> saved = ArgumentCaptor.forClass(ComparisonReport.class);
        verify(reportRepository).save(saved.capture());
        assertThat(saved.getValue().getQuotations()).extracting(cq -> cq.getQuotationVersion()).containsOnly(0L);
        assertThat(saved.getValue().getDetails()).contains("\"lowestCompleteQuotationId\":" + b.getId());
        // the stored JSON round-trips back into the same result
        assertThat(service.fromJson(saved.getValue().getDetails()).rows()).hasSize(2);
    }

    @Test
    void requiresAtLeastOneReadyQuotation() {
        Quotation pending = Fixtures.quotation(pr, Fixtures.vendor("Pending"));
        when(quotationRepository.findByPurchaseRequestIdOrderByIdAsc(pr.getId())).thenReturn(List.of(pending));

        assertThatThrownBy(() -> service.generate(pr.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("NO_READY_QUOTATIONS");
        verify(reportRepository, never()).save(any());
    }

    @Test
    void viewerCannotGenerateComparisons() {
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(99L, RoleName.APPROVER));

        assertThatThrownBy(() -> service.generate(pr.getId())).isInstanceOf(ForbiddenOperationException.class);
    }
}
