package com.flowtivo.tinfirm.trigger.internal;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * Tinfirm's record of what it wrote to a system ({@code system_write}, the package 4 contract, §3.12 and open point 13;
 * row 10 of the security review of the redesign), its one writer: every {@code SystemWritten} of every conversation,
 * personal ones included (a tool's comment, transition, labels or close; a change request opened; a branch pushed; part
 * c's write-back of an outbox row, with its {@code outboxId}), recorded in the transaction that published it. A poll
 * then raises no event for them ({@link EventDetector}): a comment whose id is recorded is not new; a status, a label,
 * a close or a head commit recorded since the snapshot was read is no edge; a change request or issue recorded with its
 * creating conversation's depth fires triggers only within the chain of 3 (§5.6). Recording inserts one row and locks
 * nothing, so it keeps no lock order of its own.
 */
@Component
class SystemWriteRecorder {

    private final PollStore store;

    private final Conversations conversations;

    private final TimeSource time;

    SystemWriteRecorder(PollStore store, Conversations conversations, TimeSource time) {
        this.store = store;
        this.conversations = conversations;
        this.time = time;
    }

    @EventListener
    void written(ConversationEvents.SystemWritten event) {
        Integer depth = null;
        if ("CHANGE_REQUEST".equals(event.kind()) || "ISSUE".equals(event.kind())) {
            depth = conversations.find(event.companyId(), event.conversationId())
                .map(ConversationInfo::chainDepth)
                .orElse(0);
        }
        record(event.companyId(), event.systemId(), event.subjectKey(), event.kind(), event.externalId(),
                event.values().status(), event.values().labelsAdded(), event.values().labelsRemoved(),
                event.conversationId(), depth);
    }

    /**
     * Records one write of Tinfirm's in the caller's transaction and tenant context.
     *
     * @param kind       {@code COMMENT}, {@code TRANSITION}, {@code LABEL}, {@code CLOSE}, {@code ATTACHMENT},
     *                   {@code REMOTE_LINK}, {@code CHANGE_REQUEST}, {@code PUSH}, {@code ISSUE}
     * @param externalId a comment's or attachment's id, a change request's number, a head commit
     * @param chainDepth for {@code CHANGE_REQUEST} and {@code ISSUE}: the creating conversation's depth
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void record(UUID companyId, UUID systemId, String subjectKey, String kind, @Nullable String externalId,
            @Nullable String status, List<String> labelsAdded, List<String> labelsRemoved,
            @Nullable UUID conversationId, @Nullable Integer chainDepth) {
        store.insertWrite(new PollStore.Write(companyId, systemId, subjectKey, kind, externalId, status, labelsAdded,
                labelsRemoved, conversationId, chainDepth, time.now()));
    }
}
