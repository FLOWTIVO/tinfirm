package com.flowtivo.tinfirm.shared.retention;

import java.time.Instant;
import java.util.UUID;

/**
 * Records a module keeps for a fixed time of its own, outside the OWNER's periods (the package 6 contract, §5.9, "fixed
 * elsewhere"): the daily retention sweep asks each implementation to delete what is past its time, once per company,
 * in a transaction of its own in the company's tenant context, and counts it in its {@code content.purged} event. The
 * seam for package 5's {@code chat_event} and {@code chat_user}, the {@code conversation_read} records (400 days after
 * their last read) and package 4's {@code trigger_subject} and {@code system_write}; the governance module's own lifted
 * holds use it too. An implementation never depends on the governance module.
 */
public interface RetainedRecords {

    /** The kind, as the audit names it ({@code chatEvents}, {@code liftedHolds}, …). */
    String kind();

    /** Deletes the company's records past their time at {@code now}; answers how many. */
    long sweep(UUID companyId, Instant now);
}
