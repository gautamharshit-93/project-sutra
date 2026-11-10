package com.sutra.rfq.service.impl;

import java.util.Collection;
import com.sutra.rfq.service.PurchaseRequestService;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;
import com.sutra.api.internal.InternalContracts.StatusChange;
import com.sutra.rfq.client.QuotationClient;
import com.sutra.rfq.mapper.PurchaseRequestMapper;
import com.sutra.common.audit.AuditService;

import static com.sutra.common.audit.AuditService.details;

import com.sutra.rfq.client.MasterDataClient;
import com.sutra.rfq.entity.NamedRef;
import com.sutra.rfq.entity.UserRef;
import com.sutra.rfq.entity.PurchaseRequest;
import com.sutra.rfq.entity.RequestItem;
import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.common.error.BusinessRuleException;
import com.sutra.rfq.exception.PurchaseRequestNotFoundException;
import com.sutra.common.error.ResourceNotFoundException;
import com.sutra.rfq.repository.PurchaseRequestRepository;
import com.sutra.api.rfq.PurchaseRequestRequests.NewRfqRequest;
import com.sutra.api.rfq.PurchaseRequestRequests.PurchaseRequestRequest;
import com.sutra.api.rfq.PurchaseRequestRequests.RequestItemRequest;
import com.sutra.common.web.PageResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestSummary;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.rfq.service.internal.RecordAccessPolicy;
import com.sutra.common.util.Text;
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
public class PurchaseRequestServiceImpl implements PurchaseRequestService {

    public static final String AUDIT_TYPE = "PURCHASE_REQUEST";

    /** Transitions a user may request directly. Others happen through comparisons and approvals. */
    private static final Map<PurchaseRequestStatus, Set<PurchaseRequestStatus>> MANUAL_TRANSITIONS = Map.of(
            PurchaseRequestStatus.DRAFT, EnumSet.of(PurchaseRequestStatus.OPEN, PurchaseRequestStatus.CANCELLED),
            PurchaseRequestStatus.OPEN, EnumSet.of(PurchaseRequestStatus.DRAFT, PurchaseRequestStatus.CANCELLED),
            PurchaseRequestStatus.UNDER_REVIEW, EnumSet.of(PurchaseRequestStatus.OPEN, PurchaseRequestStatus.CANCELLED));

    private static final Set<PurchaseRequestStatus> ITEM_EDITABLE =
            EnumSet.of(PurchaseRequestStatus.DRAFT, PurchaseRequestStatus.OPEN);

    private final PurchaseRequestRepository repository;
    private final MasterDataClient masterData;
    private final QuotationClient quotations;
    private final CurrentUserProvider currentUser;
    private final RecordAccessPolicy accessPolicy;
    private final AuditService auditService;
    private final Clock clock;

    public PurchaseRequestServiceImpl(PurchaseRequestRepository repository, MasterDataClient masterData,
                                      QuotationClient quotations, CurrentUserProvider currentUser,
                                  RecordAccessPolicy accessPolicy, AuditService auditService, Clock clock) {
        this.repository = repository;
        this.masterData = masterData;
        this.quotations = quotations;
        this.currentUser = currentUser;
        this.accessPolicy = accessPolicy;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseRequestSummary> search(String q, PurchaseRequestStatus status, int page, int size) {
        var pageable = PageRequest.of(page, Math.min(size, 200), Sort.by(Sort.Direction.DESC, PurchaseRequest::getCreatedAt, PurchaseRequest::getId));
        return PageResponse.of(repository.search(Text.blankToNull(q), status, pageable), PurchaseRequestMapper::purchaseRequestSummary);
    }

    @Transactional(readOnly = true)
    public PurchaseRequestResponse get(Long id) {
        return PurchaseRequestMapper.purchaseRequestResponse(find(id));
    }

    public PurchaseRequestResponse create(PurchaseRequestRequest request) {
        PurchaseRequest pr = new PurchaseRequest();
        pr.setRequestNumber(String.format("PR-%d-%05d", Year.now(clock).getValue(), repository.nextRequestNumber()));
        pr.setRequestedBy(UserRef.of(currentUser.requirePrincipal()));
        apply(pr, request);
        pr = repository.save(pr);
        auditService.record(AUDIT_TYPE, pr.getId(), "CREATED", details("requestNumber", pr.getRequestNumber(),
                "title", pr.getTitle()));
        return PurchaseRequestMapper.purchaseRequestResponse(pr);
    }

    /** Header, items and (optionally) opening to vendors in one transaction: all of it is saved, or none. */
    public PurchaseRequestResponse createWithItems(NewRfqRequest request) {
        PurchaseRequestResponse result = create(request.request());
        Long id = result.id();
        for (RequestItemRequest item : request.items() == null ? List.<RequestItemRequest>of() : request.items()) {
            result = addItem(id, item);
        }
        if (request.open()) {
            result = changeStatus(id, PurchaseRequestStatus.OPEN);
        }
        return result;
    }

    public PurchaseRequestResponse update(Long id, PurchaseRequestRequest request) {
        PurchaseRequest pr = findModifiable(id);
        boolean currencyChanged = request.currency() != null && !request.currency().equals(pr.getCurrency());
        apply(pr, request);
        if (currencyChanged) {
            revalidateQuotations(pr);
        }
        auditService.record(AUDIT_TYPE, id, "UPDATED", details("title", pr.getTitle(), "currency", pr.getCurrency()));
        return PurchaseRequestMapper.purchaseRequestResponse(pr);
    }

    public void delete(Long id) {
        PurchaseRequest pr = find(id);
        accessPolicy.requireModify(pr);
        if (pr.getStatus() != PurchaseRequestStatus.DRAFT) {
            throw new BusinessRuleException("PR_NOT_DRAFT", "Only draft requests can be deleted; cancel it instead");
        }
        if (quotations.usage(id).quotations() > 0) {
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
        if (target == PurchaseRequestStatus.DRAFT && quotations.usage(id).quotations() > 0) {
            throw new BusinessRuleException("PR_HAS_QUOTATIONS", "A request with quotations cannot go back to draft");
        }
        pr.setStatus(target);
        auditService.record(AUDIT_TYPE, id, "STATUS_CHANGED", details("from", from, "to", target));
        return PurchaseRequestMapper.purchaseRequestResponse(pr);
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
        return PurchaseRequestMapper.purchaseRequestResponse(pr);
    }

    public PurchaseRequestResponse updateItem(Long id, Long itemId, RequestItemRequest request) {
        PurchaseRequest pr = findItemEditable(id);
        RequestItem item = findItem(pr, itemId);
        applyItem(item, request);
        revalidateQuotations(pr);
        auditService.record(AUDIT_TYPE, id, "ITEM_UPDATED", details("line", item.getLineNumber(),
                "description", item.getDescription(), "quantity", item.getQuantity()));
        return PurchaseRequestMapper.purchaseRequestResponse(pr);
    }

    public PurchaseRequestResponse deleteItem(Long id, Long itemId) {
        PurchaseRequest pr = findItemEditable(id);
        RequestItem item = findItem(pr, itemId);
        boolean quoted = quotations.usage(id).matchedRequestItemIds().contains(itemId);
        if (quoted) {
            throw new BusinessRuleException("REQUEST_ITEM_QUOTED",
                    "Line " + item.getLineNumber() + " is matched to quotation lines; unmatch them first");
        }
        pr.getItems().remove(item);
        repository.flush();
        revalidateQuotations(pr);
        auditService.record(AUDIT_TYPE, id, "ITEM_DELETED", details("line", item.getLineNumber(),
                "description", item.getDescription()));
        return PurchaseRequestMapper.purchaseRequestResponse(pr);
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

    /**
     * The RFQ's quotations are re-checked against the changed RFQ once this change has committed (the quotation
     * service reads the RFQ back, so it must see the new data).
     */
    private void revalidateQuotations(PurchaseRequest pr) {
        Long id = pr.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    quotations.revalidate(id);
                }
            });
        } else {
            quotations.revalidate(id);
        }
    }

    // ---------- status changes made by other services ----------

    /** Comparison (OPEN to UNDER_REVIEW) and approval (UNDER_REVIEW to APPROVED/REJECTED) move the RFQ here. */
    @Override
    public PurchaseRequestResponse applyStatusChange(Long id, StatusChange change) {
        PurchaseRequest pr = find(id);
        if (pr.getStatus() == change.to()) {
            return PurchaseRequestMapper.purchaseRequestResponse(pr); // repeated call: already done
        }
        if (pr.getStatus() != change.from()) {
            throw new BusinessRuleException("PR_STATUS_CHANGED", pr.getRequestNumber() + " is " + pr.getStatus()
                    + ", not " + change.from() + "; reload and try again");
        }
        pr.setStatus(change.to());
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("from", change.from());
        details.put("to", change.to());
        if (change.details() != null) {
            details.putAll(change.details());
        }
        auditService.recordAs(change.actor() == null ? CurrentUserProvider.SYSTEM_USER : change.actor(), AUDIT_TYPE,
                id, "STATUS_CHANGED", details);
        return PurchaseRequestMapper.purchaseRequestResponse(pr);
    }


    private void apply(PurchaseRequest pr, PurchaseRequestRequest request) {
        MasterDataClient.EntitySnapshot entity = masterData.entity(request.entityId());
        if (!entity.active()) {
            throw new BusinessRuleException("ENTITY_INACTIVE", entity.name() + " is inactive");
        }
        pr.setEntity(new NamedRef(entity.id(), entity.name()));
        if (request.subEntityId() == null) {
            pr.setSubEntity(null);
        } else {
            MasterDataClient.SubEntitySnapshot sub = masterData.subEntity(entity.id(), request.subEntityId());
            pr.setSubEntity(new NamedRef(sub.id(), sub.name()));
        }
        if (request.customerId() == null) {
            pr.setCustomer(null);
        } else {
            MasterDataClient.CustomerSnapshot customer = masterData.customer(request.customerId());
            pr.setCustomer(new NamedRef(customer.id(), customer.name()));
        }
        pr.setTitle(request.title().trim());
        pr.setDescription(Text.blankToNull(request.description()));
        if (request.currency() != null) {
            pr.setCurrency(request.currency());
        }
        if (request.quotationsDue() != null && !request.quotationsDue().equals(pr.getQuotationsDue())
                && request.quotationsDue().isBefore(java.time.LocalDate.now(clock))) {
            throw new BusinessRuleException("DUE_DATE_IN_PAST", "The quotations-due date cannot be in the past");
        }
        if (request.quotationsDue() != null && request.requiredBy() != null
                && request.quotationsDue().isAfter(request.requiredBy())) {
            throw new BusinessRuleException("DUE_AFTER_REQUIRED_BY",
                    "Quotations must be due on or before the required-by date");
        }
        pr.setRequiredBy(request.requiredBy());
        pr.setQuotationsDue(request.quotationsDue());
    }

    private static void applyItem(RequestItem item, RequestItemRequest request) {
        item.setItemCode(Text.blankToNull(request.itemCode()));
        item.setDescription(request.description().trim());
        item.setQuantity(request.quantity());
        item.setUnitOfMeasure(request.unitOfMeasure().trim());
        item.setSpecifications(Text.blankToNull(request.specifications()));
    }

    // ---------- queries for the other services ----------

    @Override
    @Transactional(readOnly = true)
    public List<PurchaseRequestResponse> getMany(Collection<Long> ids) {
        return ids == null || ids.isEmpty() ? List.of()
                : repository.findByIdIn(ids).stream().map(PurchaseRequestMapper::purchaseRequestResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PurchaseRequestResponse> vendorView(Collection<Long> quotedIds, int page, int size) {
        // an empty IN () is not valid SQL: -1 never matches
        Collection<Long> ids = quotedIds == null || quotedIds.isEmpty() ? List.of(-1L) : quotedIds;
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, PurchaseRequest::getCreatedAt, PurchaseRequest::getId));
        return PageResponse.of(repository.visibleToVendor(ids, pageable), PurchaseRequestMapper::purchaseRequestResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Long> openIds(Long requestedById) {
        return repository.findOpenIds(requestedById);
    }

    @Override
    @Transactional(readOnly = true)
    public long countAwaitingAward(Long excludeRequestedById) {
        return repository.countAwaitingAward(excludeRequestedById);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (PurchaseRequestStatus status : PurchaseRequestStatus.values()) {
            counts.put(status.name(), repository.countByStatus(status));
        }
        return counts;
    }

    @Override
    @Transactional(readOnly = true)
    public long usage(String kind, Long id) {
        return switch (kind) {
            case "entities" -> repository.countByEntityId(id);
            case "sub-entities" -> repository.countBySubEntityId(id);
            case "customers" -> repository.countByCustomerId(id);
            case "users" -> repository.countByRequestedById(id);
            default -> -1;
        };
    }
}
