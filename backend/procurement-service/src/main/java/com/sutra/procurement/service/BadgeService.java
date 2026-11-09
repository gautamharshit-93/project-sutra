package com.sutra.procurement.service;

import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.procurement.entity.PurchaseRequest;
import com.sutra.procurement.entity.Quotation;
import com.sutra.procurement.entity.enums.PurchaseRequestStatus;
import com.sutra.procurement.repository.PurchaseRequestRepository;
import com.sutra.procurement.repository.QuotationRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The small counts shown as badges next to the menu: what is waiting for the signed-in user right now.
 * Buyers: their open RFQs with quotations ready to compare. Managers: compared RFQs waiting for an award (not their
 * own). Administrators see both, for every RFQ. Vendors: open RFQs they have not quoted on yet, and decided outcomes.
 */
@Service
@Transactional(readOnly = true)
public class BadgeService {

    /** For company users; a count is 0 when it is not the user's job. */
    public record StaffBadges(long rfqsToCompare, long awaitingAward) {
    }

    /** For a vendor: open RFQs without its quotation, and its quotations that have an outcome (the UI tracks seen). */
    public record VendorBadges(long openToQuote, List<Long> decidedQuotationIds) {
    }

    private static final Set<PurchaseRequestStatus> DECIDED =
            Set.of(PurchaseRequestStatus.APPROVED, PurchaseRequestStatus.REJECTED, PurchaseRequestStatus.CANCELLED);

    private final PurchaseRequestRepository purchaseRequests;
    private final QuotationRepository quotations;
    private final CurrentUserProvider currentUser;
    private final Clock clock;

    public BadgeService(PurchaseRequestRepository purchaseRequests, QuotationRepository quotations,
                        CurrentUserProvider currentUser, Clock clock) {
        this.purchaseRequests = purchaseRequests;
        this.quotations = quotations;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    public StaffBadges staff() {
        SutraUserPrincipal me = currentUser.requirePrincipal();
        boolean admin = me.hasRole(RoleName.ADMIN);
        long toCompare = admin ? purchaseRequests.countReadyToCompare(null)
                : me.hasRole(RoleName.PROCUREMENT) ? purchaseRequests.countReadyToCompare(me.getId()) : 0;
        long toAward = admin ? purchaseRequests.countAwaitingAward(null)
                : me.hasRole(RoleName.APPROVER) ? purchaseRequests.countAwaitingAward(me.getId()) : 0;
        return new StaffBadges(toCompare, toAward);
    }

    public VendorBadges vendor() {
        Long vendorId = currentUser.requirePrincipal().getVendorId();
        if (vendorId == null) {
            return new VendorBadges(0, List.of());
        }
        LocalDate today = LocalDate.now(clock);
        List<PurchaseRequest> visible = purchaseRequests.visibleToVendor(vendorId, PageRequest.of(0, 200)).getContent();
        List<Quotation> mine = quotations.findByVendorIdOrderByIdDesc(vendorId);
        Set<Long> quoted = mine.stream().map(q -> q.getPurchaseRequest().getId()).collect(java.util.stream.Collectors.toSet());
        long openToQuote = visible.stream()
                .filter(pr -> pr.acceptsVendorQuotations(today) && !quoted.contains(pr.getId()))
                .count();
        List<Long> decided = mine.stream()
                .filter(q -> DECIDED.contains(q.getPurchaseRequest().getStatus()))
                .map(Quotation::getId)
                .toList();
        return new VendorBadges(openToQuote, decided);
    }
}
