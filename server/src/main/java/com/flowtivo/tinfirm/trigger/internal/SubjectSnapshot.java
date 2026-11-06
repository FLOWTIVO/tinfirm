package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.SystemPolls;

/**
 * A poll's snapshot of a subject ({@code trigger_subject}, the package 4 contract, §3.10): the baseline of its edges,
 * as the poll last read it.
 *
 * @param assignees             the assignees' identities at the system
 * @param textHash              {@link EventDetector#textHash}
 * @param createdByTinfirmDepth the chain depth of the conversation that created the subject (§5.6); null: not Tinfirm's
 * @param lastCommentId         the newest comment read
 * @param sourceUpdatedAt       the system's last change of the subject as read
 * @param seenAt                when the poll read it (the page's read, before the pass handled it)
 */
record SubjectSnapshot(String subjectId, String key, String kind, boolean open, @Nullable String status,
        @Nullable String type, List<String> labels, List<SystemPolls.User> assignees, @Nullable String textHash,
        @Nullable String headSha, @Nullable Integer createdByTinfirmDepth, @Nullable String lastCommentId,
        @Nullable Instant lastCommentAt, @Nullable Instant sourceUpdatedAt, Instant seenAt) {

    /** How far before its read a snapshot without comments counts comments as new: the systems' clocks differ. */
    static final Duration CLOCK_SLACK = Duration.ofMinutes(2);

    SubjectSnapshot {
        labels = List.copyOf(labels);
        assignees = List.copyOf(assignees);
    }

    /** The newest comment's number, or null. */
    @Nullable Long lastCommentOrder() {
        if (lastCommentId == null || !lastCommentId.matches("[0-9]{1,18}")) {
            return null;
        }
        return Long.parseLong(lastCommentId);
    }

    /** Comments written from then on are new, for a snapshot that read none. */
    Instant commentsFrom() {
        return lastCommentAt != null ? lastCommentAt : seenAt.minus(CLOCK_SLACK);
    }

    /** The snapshot of a read. */
    static SubjectSnapshot of(SystemPolls.Subject subject, @Nullable SubjectSnapshot previous,
            @Nullable Integer createdByTinfirmDepth, Instant seenAt) {
        String lastId = previous != null ? previous.lastCommentId() : null;
        Instant lastAt = previous != null ? previous.lastCommentAt() : null;
        Long last = previous != null ? previous.lastCommentOrder() : null;
        for (SystemPolls.Comment comment : subject.comments()) {
            if (last == null || comment.order() > last) {
                last = comment.order();
                lastId = comment.id();
                lastAt = comment.created();
            }
        }
        return new SubjectSnapshot(subject.id(), subject.key(), subject.kind().name(), subject.open(),
                subject.status(), subject.type(), EventDetector.distinct(subject.labels()), subject.assignees(),
                EventDetector.textHash(subject), subject.headSha() != null ? subject.headSha()
                        : previous != null ? previous.headSha() : null,
                createdByTinfirmDepth != null ? createdByTinfirmDepth
                        : previous != null ? previous.createdByTinfirmDepth() : null,
                lastId, lastAt, subject.updated(), seenAt);
    }
}
