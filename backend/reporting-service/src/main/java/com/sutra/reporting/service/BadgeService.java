package com.sutra.reporting.service;

import com.sutra.reporting.dto.response.Badges.StaffBadges;
import com.sutra.reporting.dto.response.Badges.VendorBadges;

/**
 * Menu badges. Buyers: their open RFQs with quotations ready to compare. Managers: compared RFQs waiting for an
 * award (not their own). Administrators see both, for every RFQ. Vendors: open RFQs they have not quoted on yet,
 * and their quotations that have an outcome.
 */
public interface BadgeService {

    StaffBadges staff();

    VendorBadges vendor();
}
