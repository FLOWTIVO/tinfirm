package com.flowtivo.tinfirm.conversation.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.retention.RetainedRecords;

/**
 * A member's dismissal of an inbox notice goes 400 days after it was made (CONVENTIONS §15.7): by then its notice has
 * almost always left the inbox, since each key names the condition that raised it. The daily retention sweep (package
 * 6) deletes them and counts them
 * as {@code inboxDismissals}; a notice whose condition held that long shows again.
 */
@Component
class InboxDismissalRetention implements RetainedRecords {

    static final Duration KEPT = Duration.ofDays(400);

    private final InboxDismissalStore store;

    InboxDismissalRetention(InboxDismissalStore store) {
        this.store = store;
    }

    @Override
    public String kind() {
        return "inboxDismissals";
    }

    @Override
    public long sweep(UUID companyId, Instant now) {
        return store.deleteBefore(companyId, now.minus(KEPT));
    }
}
