package com.flowtivo.tinfirm.chat.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.retention.RetainedRecords;

/**
 * A chat user is deleted 90 days after they were last seen (the package 5 contract, §3.3 and §5.13, open point 10): a
 * user who writes again is read from the platform anew. Counted by the daily retention sweep (package 6) as
 * {@code chatUsers}. A link is an account, which this does not touch.
 */
@Component
class ChatUserRetention implements RetainedRecords {

    static final Duration KEPT = Duration.ofDays(90);

    private final ChatStore store;

    ChatUserRetention(ChatStore store) {
        this.store = store;
    }

    @Override
    public String kind() {
        return "chatUsers";
    }

    @Override
    public long sweep(UUID companyId, Instant now) {
        return store.deleteUsersSeenBefore(companyId, now.minus(KEPT));
    }
}
