package com.sutra.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sutra.entity.AppUser;
import com.sutra.entity.BusinessEntity;
import com.sutra.entity.PurchaseRequest;
import com.sutra.entity.enums.PurchaseRequestStatus;
import com.sutra.entity.enums.RoleName;
import com.sutra.exception.BusinessRuleException;
import com.sutra.exception.ForbiddenOperationException;
import com.sutra.exception.PurchaseRequestNotFoundException;
import com.sutra.repository.PurchaseRequestRepository;
import com.sutra.repository.QuotationRepository;
import com.sutra.request.PurchaseRequestRequests.PurchaseRequestRequest;
import com.sutra.request.PurchaseRequestRequests.RequestItemRequest;
import com.sutra.security.CurrentUserProvider;
import com.sutra.security.RecordAccessPolicy;
import com.sutra.support.Fixtures;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PurchaseRequestServiceTest {

    @Mock PurchaseRequestRepository repository;
    @Mock QuotationRepository quotationRepository;
    @Mock BusinessEntityService entityService;
    @Mock CustomerService customerService;
    @Mock QuotationReviewService reviewService;
    @Mock CurrentUserProvider currentUser;
    @Mock AuditService auditService;

    private PurchaseRequestService service;
    private AppUser owner;

    @BeforeEach
    void setUp() {
        service = new PurchaseRequestService(repository, quotationRepository, entityService, customerService,
                reviewService, currentUser, new RecordAccessPolicy(currentUser), auditService, Fixtures.CLOCK);
        owner = Fixtures.user("buyer", RoleName.PROCUREMENT);
        actAs(owner);
    }

    private void actAs(AppUser user) {
        when(currentUser.requirePrincipal()).thenReturn(Fixtures.principal(user));
        when(currentUser.requireUser()).thenReturn(user);
    }

    private PurchaseRequest stored(PurchaseRequest pr) {
        when(repository.findById(pr.getId())).thenReturn(Optional.of(pr));
        return pr;
    }

    @Test
    void createAssignsNumberRequesterAndDraftStatus() {
        BusinessEntity entity = Fixtures.entity();
        when(entityService.find(entity.getId())).thenReturn(entity);
        when(repository.nextRequestNumber()).thenReturn(42L);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.create(new PurchaseRequestRequest("Laptops", null, entity.getId(), null, null, "INR", null));

        assertThat(response.requestNumber()).isEqualTo("PR-2026-00042");
        assertThat(response.status()).isEqualTo(PurchaseRequestStatus.DRAFT);
        assertThat(response.requestedBy()).isEqualTo("buyer");
    }

    @Test
    void inactiveEntityCannotBeUsed() {
        BusinessEntity entity = Fixtures.entity();
        entity.setActive(false);
        when(entityService.find(entity.getId())).thenReturn(entity);
        when(repository.nextRequestNumber()).thenReturn(1L);

        assertThatThrownBy(() -> service.create(new PurchaseRequestRequest("X", null, entity.getId(), null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void cannotOpenRequestWithoutItems() {
        PurchaseRequest pr = Fixtures.purchaseRequest(owner);
        pr.getItems().clear();
        pr.setStatus(PurchaseRequestStatus.DRAFT);
        stored(pr);

        assertThatThrownBy(() -> service.changeStatus(pr.getId(), PurchaseRequestStatus.OPEN))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("PR_HAS_NO_ITEMS");
    }

    @Test
    void approvalStatusCannotBeSetManually() {
        PurchaseRequest pr = stored(Fixtures.purchaseRequest(owner));
        pr.setStatus(PurchaseRequestStatus.UNDER_REVIEW);

        assertThatThrownBy(() -> service.changeStatus(pr.getId(), PurchaseRequestStatus.APPROVED))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("record an approval decision");
    }

    @Test
    void addingItemNumbersLinesSequentially() {
        PurchaseRequest pr = stored(Fixtures.purchaseRequest(owner));
        when(quotationRepository.findByPurchaseRequestIdOrderByIdAsc(pr.getId())).thenReturn(List.of());

        var response = service.addItem(pr.getId(),
                new RequestItemRequest(null, "Keyboard", new BigDecimal("10"), "Nos", "Wireless"));

        assertThat(response.items()).hasSize(3);
        assertThat(response.items().get(2).lineNumber()).isEqualTo(3);
        assertThat(response.items().get(2).specifications()).isEqualTo("Wireless");
    }

    @Test
    void anotherProcurementUserCannotChangeTheRequest() {
        PurchaseRequest pr = stored(Fixtures.purchaseRequest(owner));
        actAs(Fixtures.user("other", RoleName.PROCUREMENT));

        assertThatThrownBy(() -> service.addItem(pr.getId(), new RequestItemRequest(null, "X", BigDecimal.ONE, "Nos", null)))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void administratorMayChangeAnyRequest() {
        PurchaseRequest pr = stored(Fixtures.purchaseRequest(owner));
        actAs(Fixtures.user("admin", RoleName.ADMIN));

        var response = service.changeStatus(pr.getId(), PurchaseRequestStatus.CANCELLED);

        assertThat(response.status()).isEqualTo(PurchaseRequestStatus.CANCELLED);
    }

    @Test
    void closedRequestCannotBeEdited() {
        PurchaseRequest pr = stored(Fixtures.purchaseRequest(owner));
        pr.setStatus(PurchaseRequestStatus.APPROVED);

        assertThatThrownBy(() -> service.addItem(pr.getId(), new RequestItemRequest(null, "X", BigDecimal.ONE, "Nos", null)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("PR_CLOSED");
    }

    @Test
    void missingRequestThrowsNotFound() {
        when(repository.findById(5L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(5L)).isInstanceOf(PurchaseRequestNotFoundException.class);
    }
}
