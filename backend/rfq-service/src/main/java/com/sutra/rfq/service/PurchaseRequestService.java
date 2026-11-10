package com.sutra.rfq.service;

import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.internal.InternalContracts.StatusChange;
import com.sutra.api.rfq.PurchaseRequestRequests.NewRfqRequest;
import com.sutra.api.rfq.PurchaseRequestRequests.PurchaseRequestRequest;
import com.sutra.api.rfq.PurchaseRequestRequests.RequestItemRequest;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestSummary;
import com.sutra.common.web.PageResponse;
import com.sutra.rfq.entity.PurchaseRequest;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Purchase requests (RFQs) and their items.
 *
 * <p>Status flow: DRAFT -> OPEN (needs at least one item) -> UNDER_REVIEW (set by the comparison service) ->
 * APPROVED / REJECTED (set by the approval service). DRAFT, OPEN and UNDER_REVIEW can be CANCELLED.</p>
 */
public interface PurchaseRequestService {

    PageResponse<PurchaseRequestSummary> search(String q, PurchaseRequestStatus status, int page, int size);

    PurchaseRequestResponse get(Long id);

    PurchaseRequestResponse create(PurchaseRequestRequest request);

    /** Header, items and (optionally) opening to vendors in one transaction: all of it is saved, or none. */
    PurchaseRequestResponse createWithItems(NewRfqRequest request);

    PurchaseRequestResponse update(Long id, PurchaseRequestRequest request);

    void delete(Long id);

    PurchaseRequestResponse changeStatus(Long id, PurchaseRequestStatus target);

    PurchaseRequestResponse addItem(Long id, RequestItemRequest request);

    PurchaseRequestResponse updateItem(Long id, Long itemId, RequestItemRequest request);

    PurchaseRequestResponse deleteItem(Long id, Long itemId);

    PurchaseRequest find(Long id);

    /** Loads a request the current user may change and that is not closed. */
    PurchaseRequest findModifiable(Long id);

    /** A status change made by another service (compare-and-set: refused unless the RFQ is still {@code from}). */
    PurchaseRequestResponse applyStatusChange(Long id, StatusChange change);

    List<PurchaseRequestResponse> getMany(Collection<Long> ids);

    /** Every open RFQ, plus the ones the vendor quoted on. */
    PageResponse<PurchaseRequestResponse> vendorView(Collection<Long> quotedIds, int page, int size);

    /** Ids of open RFQs raised by this user (every buyer when null). */
    List<Long> openIds(Long requestedById);

    long countAwaitingAward(Long excludeRequestedById);

    Map<String, Long> countByStatus();

    /** How many RFQs refer to an entity, sub-entity, customer or user; -1 for an unknown kind. */
    long usage(String kind, Long id);
}
