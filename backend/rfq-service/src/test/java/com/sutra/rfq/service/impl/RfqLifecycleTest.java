package com.sutra.rfq.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.internal.InternalContracts.RfqQuotationUsage;
import com.sutra.api.internal.InternalContracts.StatusChange;
import com.sutra.api.rfq.PurchaseRequestRequests.NewRfqRequest;
import com.sutra.api.rfq.PurchaseRequestRequests.PurchaseRequestRequest;
import com.sutra.api.rfq.PurchaseRequestRequests.RequestItemRequest;
import com.sutra.common.audit.AuditService;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.RoleName;
import com.sutra.rfq.client.MasterDataClient;
import com.sutra.rfq.client.QuotationClient;
import com.sutra.rfq.entity.PurchaseRequest;
import com.sutra.rfq.repository.PurchaseRequestRepository;
import com.sutra.rfq.service.internal.RecordAccessPolicy;
import com.sutra.rfq.support.Fixtures;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** One-step RFQ creation, due-date rules, and status changes made by the comparison and approval services. */
class RfqLifecycleTest {

    private final PurchaseRequestRepository repository = mock(PurchaseRequestRepository.class);
    private final MasterDataClient masterData = mock(MasterDataClient.class);
    private final QuotationClient quotations = mock(QuotationClient.class);
    private final CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PurchaseRequestServiceImpl service = new PurchaseRequestServiceImpl(repository, masterData, quotations,
            currentUser, new RecordAccessPolicy(currentUser), auditService, Fixtures.CLOCK);

    @Nested
    class CreateWithItems {

        private void buyerCreates() {
            when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(Fixtures.user("buyer", RoleName.PROCUREMENT)));
            when(masterData.entity(5L)).thenReturn(new MasterDataClient.EntitySnapshot(5L, "AXM", "Axiom", true));
            when(repository.nextRequestNumber()).thenReturn(7L);
            when(quotations.usage(any())).thenReturn(new RfqQuotationUsage(0, List.of()));
            AtomicReference<PurchaseRequest> saved = new AtomicReference<>();
            when(repository.save(any())).thenAnswer(inv -> {
                PurchaseRequest p = inv.getArgument(0);
                p.setId(70L);
                saved.set(p);
                return p;
            });
            when(repository.findById(70L)).thenAnswer(inv -> Optional.ofNullable(saved.get()));
        }

        private static PurchaseRequestRequest header(LocalDate requiredBy, LocalDate due) {
            return new PurchaseRequestRequest("Steel for Q1", null, 5L, null, null, "INR", requiredBy, due);
        }

        private static RequestItemRequest line(String code, String qty) {
            return new RequestItemRequest(code, "CRCA sheet " + code, new BigDecimal(qty), "Tonne", null);
        }

        @Test
        void headerItemsAndOpeningHappenInOneCall() {
            buyerCreates();

            var pr = service.createWithItems(new NewRfqRequest(header(LocalDate.of(2026, 12, 15), LocalDate.of(2026, 11, 30)),
                    List.of(line("MT-101", "12"), line("MT-102", "8")), true));

            assertThat(pr.requestNumber()).isEqualTo("PR-2026-00007");
            assertThat(pr.items()).hasSize(2);
            assertThat(pr.status()).isEqualTo(PurchaseRequestStatus.OPEN);
            assertThat(pr.quotationsDue()).isEqualTo(LocalDate.of(2026, 11, 30));
        }

        @Test
        void withoutOpeningItStaysADraft() {
            buyerCreates();

            var pr = service.createWithItems(new NewRfqRequest(header(null, null), List.of(line("MT-101", "1")), false));

            assertThat(pr.status()).isEqualTo(PurchaseRequestStatus.DRAFT);
        }

        @Test
        void quotationsCannotBeDueOnADateThatHasPassed() {
            buyerCreates();

            assertThatThrownBy(() -> service.create(header(null, Fixtures.TODAY.minusDays(1))))
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("code").isEqualTo("DUE_DATE_IN_PAST");
        }

        @Test
        void quotationsCannotBeDueAfterTheGoodsAreNeeded() {
            buyerCreates();

            assertThatThrownBy(() -> service.create(header(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 25))))
                    .isInstanceOf(BusinessRuleException.class)
                    .extracting("code").isEqualTo("DUE_AFTER_REQUIRED_BY");
        }
    }

    @Test
    void vendorsMayQuoteUpToAndIncludingTheDueDate() {
        PurchaseRequest pr = Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT));
        pr.setQuotationsDue(LocalDate.of(2026, 11, 30));

        assertThat(pr.acceptsVendorQuotations(LocalDate.of(2026, 11, 30))).isTrue();
        assertThat(pr.acceptsVendorQuotations(LocalDate.of(2026, 12, 1))).isFalse();
        pr.setQuotationsDue(null);
        assertThat(pr.acceptsVendorQuotations(LocalDate.of(2030, 1, 1))).as("no due date: open until compared").isTrue();
        pr.setStatus(PurchaseRequestStatus.UNDER_REVIEW);
        assertThat(pr.acceptsVendorQuotations(LocalDate.of(2026, 11, 1))).isFalse();
    }

    @Nested
    class StatusChangesFromOtherServices {

        private PurchaseRequest stored(PurchaseRequestStatus status) {
            PurchaseRequest pr = Fixtures.purchaseRequest(Fixtures.user("buyer", RoleName.PROCUREMENT));
            pr.setStatus(status);
            when(repository.findById(pr.getId())).thenReturn(Optional.of(pr));
            return pr;
        }

        @Test
        void anAwardMovesTheRfqAndRecordsWhoDecided() {
            PurchaseRequest pr = stored(PurchaseRequestStatus.UNDER_REVIEW);

            var result = service.applyStatusChange(pr.getId(), new StatusChange(PurchaseRequestStatus.UNDER_REVIEW,
                    PurchaseRequestStatus.APPROVED, "kailash-manager", Map.of("approvalId", 5L)));

            assertThat(result.status()).isEqualTo(PurchaseRequestStatus.APPROVED);
            verify(auditService).recordAs(eq("kailash-manager"), eq("PURCHASE_REQUEST"), eq(pr.getId()),
                    eq("STATUS_CHANGED"), anyMap());
        }

        @Test
        void aChangeFromAStatusTheRfqNoLongerHasIsRefused() {
            PurchaseRequest pr = stored(PurchaseRequestStatus.CANCELLED);

            assertThatThrownBy(() -> service.applyStatusChange(pr.getId(), new StatusChange(
                    PurchaseRequestStatus.UNDER_REVIEW, PurchaseRequestStatus.APPROVED, "manager", null)))
                    .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("PR_STATUS_CHANGED");
            assertThat(pr.getStatus()).isEqualTo(PurchaseRequestStatus.CANCELLED);
        }

        @Test
        void aRepeatedChangeIsHarmless() {
            PurchaseRequest pr = stored(PurchaseRequestStatus.UNDER_REVIEW);

            service.applyStatusChange(pr.getId(), new StatusChange(PurchaseRequestStatus.OPEN,
                    PurchaseRequestStatus.UNDER_REVIEW, "buyer", null));

            assertThat(pr.getStatus()).isEqualTo(PurchaseRequestStatus.UNDER_REVIEW);
            verify(auditService, never()).recordAs(any(), any(), any(), any(), any());
        }

        @Test
        void anRfqWithQuotationsCannotGoBackToDraft() {
            when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(1L, RoleName.ADMIN));
            PurchaseRequest pr = stored(PurchaseRequestStatus.OPEN);
            when(quotations.usage(pr.getId())).thenReturn(new RfqQuotationUsage(2, List.of()));

            assertThatThrownBy(() -> service.changeStatus(pr.getId(), PurchaseRequestStatus.DRAFT))
                    .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("PR_HAS_QUOTATIONS");
        }

        @Test
        void aMatchedRequestLineCannotBeDeleted() {
            when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(1L, RoleName.ADMIN));
            PurchaseRequest pr = stored(PurchaseRequestStatus.OPEN);
            Long laptops = pr.getItems().get(0).getId();
            when(quotations.usage(pr.getId())).thenReturn(new RfqQuotationUsage(1, List.of(laptops)));

            assertThatThrownBy(() -> service.deleteItem(pr.getId(), laptops))
                    .isInstanceOf(BusinessRuleException.class).extracting("code").isEqualTo("REQUEST_ITEM_QUOTED");
        }
    }
}
