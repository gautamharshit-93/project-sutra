package com.sutra.security;

import com.sutra.entity.PurchaseRequest;
import com.sutra.entity.enums.RoleName;
import com.sutra.exception.ForbiddenOperationException;
import org.springframework.stereotype.Component;

/**
 * Record-level rules on top of the role checks in the controllers:
 * administrators may change any purchase request; procurement users only the ones they created.
 */
@Component
public class RecordAccessPolicy {

    private final CurrentUserProvider currentUser;

    public RecordAccessPolicy(CurrentUserProvider currentUser) {
        this.currentUser = currentUser;
    }

    public boolean canModify(PurchaseRequest request) {
        SutraUserPrincipal principal = currentUser.requirePrincipal();
        if (principal.hasRole(RoleName.ADMIN)) {
            return true;
        }
        return principal.hasRole(RoleName.PROCUREMENT)
                && request.getRequestedBy().getId().equals(principal.getId());
    }

    public void requireModify(PurchaseRequest request) {
        if (!canModify(request)) {
            throw new ForbiddenOperationException("Only the creator of " + request.getRequestNumber()
                    + " or an administrator can change it or its quotations");
        }
    }
}
