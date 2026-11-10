package com.sutra.audit.dto.response;

import java.time.Instant;

/**
 * Result of recomputing the whole audit hash chain.
 *
 * @param intact              true when every entry's hash and link to the previous entry match
 * @param checked             how many entries were checked
 * @param firstBrokenSequence sequence number of the first entry whose hash or link does not match (null if intact)
 * @param reason              what did not match (null if intact)
 * @param lastHash            hash of the newest entry (the chain head)
 * @param verifiedAt          when the check ran
 */
public record ChainVerificationResponse(boolean intact, long checked, Long firstBrokenSequence, String reason,
                                        String lastHash, Instant verifiedAt) {
}
