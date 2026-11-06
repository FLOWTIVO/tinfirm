package com.flowtivo.tinfirm.chat.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.retention.RetainedRecords;

/**
 * A chat event goes a day after it arrived, whatever became of it (the package 5 contract, §3.7 and §5.13): its
 * payload is cleared once handled, and nothing reads the row later. The daily retention sweep (package 6) deletes what
 * the chat's own pass ({@code ChatInbox.expire}) left, and counts it as {@code chatEvents}.
 */
@Component
class ChatEventRetention implements RetainedRecords {

    static final Duration KEPT = Duration.ofDays(1);

    private final ChatStore store;

    ChatEventRetention(ChatStore store) {
        this.store = store;
    }

    @Override
    public String kind() {
        return "chatEvents";
    }

    @Override
    public long sweep(UUID companyId, Instant now) {
        return store.deleteEventsBefore(companyId, now.minus(KEPT));
    }
}
