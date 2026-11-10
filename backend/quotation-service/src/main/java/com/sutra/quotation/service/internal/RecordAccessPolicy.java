package com.sutra.quotation.service.internal;

import com.sutra.api.rfq.PurchaseRequestResponses.PurchaseRequestResponse;
import com.sutra.common.error.ForbiddenOperationException;
import com.sutra.common.security.CurrentUserProvider;
import com.sutra.common.security.RoleName;
import com.sutra.common.security.SutraUserPrincipal;
import org.springframework.stereotype.Component;

/**
 * Record-level rule on top of the role checks: administrators may change the quotations of any RFQ; buyers only
 * those of RFQs they raised.
 */
@Component
public class RecordAccessPolicy {

    private final CurrentUserProvider currentUser;

    public RecordAccessPolicy(CurrentUserProvider currentUser) {
        this.currentUser = currentUser;
    }

    public boolean canModify(PurchaseRequestResponse rfq) {
        SutraUserPrincipal principal = currentUser.requirePrincipal();
        if (principal.hasRole(RoleName.ADMIN)) {
            return true;
        }
        return principal.hasRole(RoleName.PROCUREMENT) && rfq.requestedById().equals(principal.getId());
    }

    public void requireModify(PurchaseRequestResponse rfq) {
        if (!canModify(rfq)) {
            throw new ForbiddenOperationException("Only the creator of " + rfq.requestNumber()
                    + " or an administrator can change it or its quotations");
        }
    }
}
