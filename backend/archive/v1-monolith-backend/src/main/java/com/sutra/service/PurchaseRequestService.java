package com.sutra.service;

import static com.sutra.service.AuditService.details;

import com.sutra.entity.BusinessEntity;
import com.sutra.entity.PurchaseRequest;
import com.sutra.entity.Quotation;
import com.sutra.entity.RequestItem;
import com.sutra.entity.enums.PurchaseRequestStatus;
import com.sutra.entity.enums.QuotationStatus;
import com.sutra.exception.BusinessRuleException;
import com.sutra.exception.PurchaseRequestNotFoundException;
import com.sutra.exception.ResourceNotFoundException;
import com.sutra.repository.PurchaseRequestRepository;
import com.sutra.repository.QuotationRepository;
import com.sutra.request.PurchaseRequestRequests.PurchaseRequestRequest;
import com.sutra.request.PurchaseRequestRequests.RequestItemRequest;
import com.sutra.response.DecisionResponses.AuditLogResponse;
import com.sutra.response.PageResponse;
import com.sutra.response.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.response.PurchaseRequestResponses.PurchaseRequestSummary;
import com.sutra.security.CurrentUserProvider;
import com.sutra.security.RecordAccessPolicy;
import com.sutra.util.Text;
import java.time.Clock;
import java.time.Year;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase requests and their items.
 *
 * <p>Status flow: DRAFT -> OPEN (needs at least one item) -> UNDER_REVIEW (set when a comparison is generated) ->
 * APPROVED / REJECTED (only through an approval decision). DRAFT, OPEN and UNDER_REVIEW can be CANCELLED.</p>
 */
@Service
@Transactional
public class PurchaseRequestService {

    static final String AUDIT_TYPE = "PURCHASE_REQUEST";

    /** Transitions a user may request directly. Others happen through comparisons and approvals. */
    private static final Map<PurchaseRequestStatus, Set<PurchaseRequestStatus>> MANUAL_TRANSITIONS = Map.of(
            PurchaseRequestStatus.DRAFT, EnumSet.of(PurchaseRequestStatus.OPEN, PurchaseRequestStatus.CANCELLED),
            PurchaseRequestStatus.OPEN, EnumSet.of(PurchaseRequestStatus.DRAFT, PurchaseRequestStatus.CANCELLED),
            PurchaseRequestStatus.UNDER_REVIEW, EnumSet.of(PurchaseRequestStatus.OPEN, PurchaseRequestStatus.CANCELLED));

    private static final Set<PurchaseRequestStatus> ITEM_EDITABLE =
            EnumSet.of(PurchaseRequestStatus.DRAFT, PurchaseRequestStatus.OPEN);

    private final PurchaseRequestRepository repository;
    private final QuotationRepository quotationRepository;
    private final BusinessEntityService entityService;
    private final CustomerService customerService;
    private final QuotationReviewService reviewService;
    private final CurrentUserProvider currentUser;
    private final RecordAccessPolicy accessPolicy;
    private final AuditService auditService;
    private final Clock clock;

    public PurchaseRequestService(PurchaseRequestRepository repository, QuotationRepository quotationRepository,
                                  BusinessEntityService entityService, CustomerService customerService,
                                  QuotationReviewService reviewService, CurrentUserProvider currentUser,
                                  RecordAccessPolicy accessPolicy, AuditService auditService, Clock clock) {
        this.repository = repository;
        this.quotationRepository = quotationRepository;
        this.entityService = entityService;
        this.customerService = customerService;
        this.reviewService = reviewService;
        this.currentUser = currentUser;
        this.accessPolicy = accessPolicy;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseRequestSummary> search(String q, PurchaseRequestStatus status, int page, int size) {
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, PurchaseRequest::getCreatedAt, PurchaseRequest::getId));
        return PageResponse.of(repository.search(Text.blankToNull(q), status, pageable), PurchaseRequestSummary::from);
    }

    @Transactional(readOnly = true)
    public PurchaseRequestResponse get(Long id) {
        return PurchaseRequestResponse.from(find(id));
    }

    /** Change history of the request and everything attached to it, newest first. */
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> history(Long id, int page, int size) {
        find(id);
        return auditService.purchaseRequestHistory(id, page, size);
    }

    public PurchaseRequestResponse create(PurchaseRequestRequest request) {
        PurchaseRequest pr = new PurchaseRequest();
        pr.setRequestNumber(String.format("PR-%d-%05d", Year.now(clock).getValue(), repository.nextRequestNumber()));
        pr.setRequestedBy(currentUser.requireUser());
        apply(pr, request);
        pr = repository.save(pr);
        auditService.record(AUDIT_TYPE, pr.getId(), "CREATED", details("requestNumber", pr.getRequestNumber(),
                "title", pr.getTitle()));
        return PurchaseRequestResponse.from(pr);
    }

    public PurchaseRequestResponse update(Long id, PurchaseRequestRequest request) {
        PurchaseRequest pr = findModifiable(id);
        boolean currencyChanged = request.currency() != null && !request.currency().equals(pr.getCurrency());
        apply(pr, request);
        if (currencyChanged) {
            revalidateQuotations(pr);
        }
        auditService.record(AUDIT_TYPE, id, "UPDATED", details("title", pr.getTitle(), "currency", pr.getCurrency()));
        return PurchaseRequestResponse.from(pr);
    }

    public void delete(Long id) {
        PurchaseRequest pr = find(id);
        accessPolicy.requireModify(pr);
        if (pr.getStatus() != PurchaseRequestStatus.DRAFT) {
            throw new BusinessRuleException("PR_NOT_DRAFT", "Only draft requests can be deleted; cancel it instead");
        }
        if (!quotationRepository.findByPurchaseRequestIdOrderByIdAsc(id).isEmpty()) {
            throw new BusinessRuleException("PR_HAS_QUOTATIONS", "Delete the request's quotations first");
        }
        repository.delete(pr);
        auditService.record(AUDIT_TYPE, id, "DELETED", details("requestNumber", pr.getRequestNumber()));
    }

    public PurchaseRequestResponse changeStatus(Long id, PurchaseRequestStatus target) {
        PurchaseRequest pr = find(id);
        accessPolicy.requireModify(pr);
        PurchaseRequestStatus from = pr.getStatus();
        if (!MANUAL_TRANSITIONS.getOrDefault(from, Set.of()).contains(target)) {
            throw new BusinessRuleException("INVALID_STATUS_TRANSITION", "Cannot change a request from " + from
                    + " to " + target + (target == PurchaseRequestStatus.APPROVED || target == PurchaseRequestStatus.REJECTED
                    ? "; record an approval decision instead" : ""));
        }
        if (target == PurchaseRequestStatus.OPEN && pr.getItems().isEmpty()) {
            throw new BusinessRuleException("PR_HAS_NO_ITEMS", "Add at least one item before opening the request");
        }
        if (target == PurchaseRequestStatus.DRAFT
                && !quotationRepository.findByPurchaseRequestIdOrderByIdAsc(id).isEmpty()) {
            throw new BusinessRuleException("PR_HAS_QUOTATIONS", "A request with quotations cannot go back to draft");
        }
        pr.setStatus(target);
        auditService.record(AUDIT_TYPE, id, "STATUS_CHANGED", details("from", from, "to", target));
        return PurchaseRequestResponse.from(pr);
    }

    // ---------- items ----------

    public PurchaseRequestResponse addItem(Long id, RequestItemRequest request) {
        PurchaseRequest pr = findItemEditable(id);
        RequestItem item = new RequestItem();
        item.setLineNumber(pr.nextLineNumber());
        applyItem(item, request);
        pr.addItem(item);
        repository.flush();
        revalidateQuotations(pr);
        auditService.record(AUDIT_TYPE, id, "ITEM_ADDED", details("line", item.getLineNumber(),
                "description", item.getDescription(), "quantity", item.getQuantity()));
        return PurchaseRequestResponse.from(pr);
    }

    public PurchaseRequestResponse updateItem(Long id, Long itemId, RequestItemRequest request) {
        PurchaseRequest pr = findItemEditable(id);
        RequestItem item = findItem(pr, itemId);
        applyItem(item, request);
        revalidateQuotations(pr);
        auditService.record(AUDIT_TYPE, id, "ITEM_UPDATED", details("line", item.getLineNumber(),
                "description", item.getDescription(), "quantity", item.getQuantity()));
        return PurchaseRequestResponse.from(pr);
    }

    public PurchaseRequestResponse deleteItem(Long id, Long itemId) {
        PurchaseRequest pr = findItemEditable(id);
        RequestItem item = findItem(pr, itemId);
        boolean quoted = quotationRepository.findByPurchaseRequestIdOrderByIdAsc(id).stream()
                .flatMap(q -> q.getItems().stream())
                .anyMatch(qi -> qi.getRequestItem() != null && qi.getRequestItem().getId().equals(itemId));
        if (quoted) {
            throw new BusinessRuleException("REQUEST_ITEM_QUOTED",
                    "Line " + item.getLineNumber() + " is matched to quotation lines; unmatch them first");
        }
        pr.getItems().remove(item);
        repository.flush();
        revalidateQuotations(pr);
        auditService.record(AUDIT_TYPE, id, "ITEM_DELETED", details("line", item.getLineNumber(),
                "description", item.getDescription()));
        return PurchaseRequestResponse.from(pr);
    }

    // ---------- helpers ----------

    public PurchaseRequest find(Long id) {
        return repository.findById(id).orElseThrow(() -> new PurchaseRequestNotFoundException(id));
    }

    /** Loads a request the current user may change and that is not closed. */
    public PurchaseRequest findModifiable(Long id) {
        PurchaseRequest pr = find(id);
        accessPolicy.requireModify(pr);
        if (!pr.isEditable()) {
            throw new BusinessRuleException("PR_CLOSED", pr.getRequestNumber() + " is " + pr.getStatus()
                    + " and can no longer be changed");
        }
        return pr;
    }

    private PurchaseRequest findItemEditable(Long id) {
        PurchaseRequest pr = findModifiable(id);
        if (!ITEM_EDITABLE.contains(pr.getStatus())) {
            throw new BusinessRuleException("PR_ITEMS_LOCKED",
                    "Items cannot change while the request is " + pr.getStatus() + "; reopen it first");
        }
        return pr;
    }

    private static RequestItem findItem(PurchaseRequest pr, Long itemId) {
        return pr.getItems().stream().filter(i -> i.getId().equals(itemId)).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Request item", itemId));
    }

    private void revalidateQuotations(PurchaseRequest pr) {
        List<Quotation> quotations = quotationRepository.findByPurchaseRequestIdOrderByIdAsc(pr.getId());
        quotations.stream()
                .filter(q -> q.getStatus() != QuotationStatus.PROCESSING && q.getStatus() != QuotationStatus.UPLOADED)
                .forEach(reviewService::revalidate);
    }

    private void apply(PurchaseRequest pr, PurchaseRequestRequest request) {
        BusinessEntity entity = entityService.find(request.entityId());
        if (!entity.isActive()) {
            throw new BusinessRuleException("ENTITY_INACTIVE", entity.getName() + " is inactive");
        }
        pr.setEntity(entity);
        pr.setSubEntity(request.subEntityId() == null ? null : entityService.findSub(entity.getId(), request.subEntityId()));
        pr.setCustomer(request.customerId() == null ? null : customerService.find(request.customerId()));
        pr.setTitle(request.title().trim());
        pr.setDescription(Text.blankToNull(request.description()));
        if (request.currency() != null) {
            pr.setCurrency(request.currency());
        }
        pr.setRequiredBy(request.requiredBy());
    }

    private static void applyItem(RequestItem item, RequestItemRequest request) {
        item.setItemCode(Text.blankToNull(request.itemCode()));
        item.setDescription(request.description().trim());
        item.setQuantity(request.quantity());
        item.setUnitOfMeasure(request.unitOfMeasure().trim());
        item.setSpecifications(Text.blankToNull(request.specifications()));
    }
}
