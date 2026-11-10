package com.sutra.reporting.service.impl;

import com.sutra.api.enums.PurchaseRequestStatus;
import com.sutra.api.internal.InternalContracts.QuotationRef;
import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import com.sutra.reporting.client.PlatformClient;
import com.sutra.reporting.dto.response.Badges.StaffBadges;
import com.sutra.reporting.dto.response.Badges.VendorBadges;
import com.sutra.reporting.service.BadgeService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class BadgeServiceImpl implements BadgeService {

    private static final Set<PurchaseRequestStatus> DECIDED =
            Set.of(PurchaseRequestStatus.APPROVED, PurchaseRequestStatus.REJECTED, PurchaseRequestStatus.CANCELLED);

    private final PlatformClient platform;
    private final CurrentUserProvider currentUser;
    private final Clock clock;

    public BadgeServiceImpl(PlatformClient platform, CurrentUserProvider currentUser, Clock clock) {
        this.platform = platform;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Override
    public StaffBadges staff() {
        SutraUserPrincipal me = currentUser.requirePrincipal();
        boolean admin = me.hasRole(RoleName.ADMIN);
        long toCompare = admin ? readyToCompare(null) : me.hasRole(RoleName.PROCUREMENT) ? readyToCompare(me.getId()) : 0;
        long toAward = admin ? platform.awaitingAward(null)
                : me.hasRole(RoleName.APPROVER) ? platform.awaitingAward(me.getId()) : 0;
        return new StaffBadges(toCompare, toAward);
    }

    /** Open RFQs (of this buyer, or every buyer) with at least one quotation ready to compare. */
    private long readyToCompare(Long buyerId) {
        return platform.rfqsWithReadyQuotations(platform.openRfqIds(buyerId)).size();
    }

    @Override
    public VendorBadges vendor() {
        Long vendorId = currentUser.requirePrincipal().getVendorId();
        if (vendorId == null) {
            return new VendorBadges(0, List.of());
        }
        LocalDate today = LocalDate.now(clock);
        List<QuotationRef> mine = platform.quotationsOf(vendorId);
        Set<Long> quoted = new HashSet<>(mine.stream().map(QuotationRef::rfqId).toList());
        List<PurchaseRequestResponse> visible = platform.vendorView(quoted, 0, 100).content();
        long openToQuote = visible.stream()
                .filter(pr -> pr.status() == PurchaseRequestStatus.OPEN
                        && (pr.quotationsDue() == null || !today.isAfter(pr.quotationsDue()))
                        && !quoted.contains(pr.id()))
                .count();
        Set<Long> decidedRfqs = new HashSet<>(platform.rfqs(quoted).stream()
                .filter(pr -> DECIDED.contains(pr.status())).map(PurchaseRequestResponse::id).toList());
        List<Long> decided = mine.stream().filter(r -> decidedRfqs.contains(r.rfqId())).map(QuotationRef::quotationId).toList();
        return new VendorBadges(openToQuote, decided);
    }
}
