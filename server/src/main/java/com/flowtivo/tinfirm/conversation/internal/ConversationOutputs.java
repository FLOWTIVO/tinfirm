package com.flowtivo.tinfirm.conversation.internal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.Attachment;
import com.flowtivo.tinfirm.shared.api.model.Output;

/**
 * For part 1, implemented by part 3: what a conversation produced and its files (the package 3 contract, §3.13), for
 * its detail ({@code GET /conversations/{conversationId}}: outputs and attachments) and anything else that shows them.
 * Reads only, in the caller's transaction and tenant context; who may see the conversation is the caller's to check
 * ({@code Conversations.maySee}).
 */
public interface ConversationOutputs {

    /** The conversation's outputs, oldest first. */
    List<OutputInfo> outputs(UUID companyId, UUID conversationId);

    /** The conversation's outputs as the API shows them ({@code Output}), oldest first. */
    List<Output> outputViews(UUID companyId, UUID conversationId);

    /** The conversation's files as the API shows them ({@code Attachment}), oldest first. */
    List<Attachment> attachments(UUID companyId, UUID conversationId);

    /** What kind of output. */
    enum Kind {
        /** A pull request or merge request the server opened after a turn pushed (§5.4, open point 5). */
        CHANGE_REQUEST,
        /** A file the agent saved: with {@code save_artifact}, or left in {@code /output}. */
        DOCUMENT,
        /** A link the agent saved with {@code save_artifact}. */
        LINK
    }

    /** The state of a change request as last read from its code host; {@code UNKNOWN} before it could be. */
    enum ChangeRequestState {
        OPEN, DRAFT, MERGED, CLOSED, UNKNOWN
    }

    /**
     * An output of a conversation.
     *
     * @param runId              the turn that produced it
     * @param url                a link's or a change request's
     * @param attachmentId       a document's file (an attachment of the conversation, downloaded from its files)
     * @param repositoryId       a change request's repository; null once the repository was removed
     * @param repositoryName     that repository's name, while it exists
     * @param externalNumber     a change request's number on its code host
     * @param changeRequestState a change request's state
     */
    record OutputInfo(UUID id, UUID conversationId, @Nullable UUID runId, Kind kind, String title,
            @Nullable String url, @Nullable UUID attachmentId, @Nullable UUID repositoryId,
            @Nullable String repositoryName, @Nullable String externalNumber,
            @Nullable ChangeRequestState changeRequestState, Instant createdAt, @Nullable Instant purgedAt) {

        OutputInfo(UUID id, UUID conversationId, @Nullable UUID runId, Kind kind, String title, @Nullable String url,
                @Nullable UUID attachmentId, @Nullable UUID repositoryId, @Nullable String repositoryName,
                @Nullable String externalNumber, @Nullable ChangeRequestState changeRequestState, Instant createdAt) {
            this(id, conversationId, runId, kind, title, url, attachmentId, repositoryId, repositoryName,
                    externalNumber, changeRequestState, createdAt, null);
        }

        public OutputInfo {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(title, "title");
        }
    }
}
