package com.flowtivo.tinfirm.conversation;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.Attachment;
import com.flowtivo.tinfirm.shared.api.model.Conversation;
import com.flowtivo.tinfirm.shared.api.model.ConversationPage;
import com.flowtivo.tinfirm.shared.api.model.Decision;
import com.flowtivo.tinfirm.shared.api.model.DecisionPage;
import com.flowtivo.tinfirm.shared.api.model.Inbox;
import com.flowtivo.tinfirm.shared.api.model.SubjectPage;

/**
 * The conversation module's public reads (the package 6 contract, §5.7, open point 20): the one path by which the
 * REST API and the MCP endpoint read conversations, their files, decisions and the inbox, so that the two cannot
 * drift. Every read is the current user's (the authenticated actor of the request or of the tool call), first
 * authorised for the company through the access guard, in a transaction of its own, and answers the REST API's DTOs.
 * What the caller may not see in the {@link ConversationView} is {@code 404}.
 * <p>
 * Every read of a conversation, its files and its decisions passes here, so that a read of another person's personal
 * conversation by OWNER is recorded at this one place (security review, row 18; {@code conversation.read}).
 */
public interface ConversationReads {

    /**
     * {@code GET /conversations}: newest activity first. In {@code APP}, OWNER may name {@code personId} (null: every
     * person's); anyone else sees their own. In {@code CLIENT}, the caller's own whatever {@code personId} says.
     */
    ConversationPage list(UUID companyId, ConversationView view, Filter filter, @Nullable Integer limit,
            @Nullable String cursor);

    /**
     * {@code GET /subjects}: the subjects of the conversations the caller sees in the view; with {@code systemId}, only
     * those on the system.
     */
    SubjectPage subjects(UUID companyId, ConversationView view, @Nullable UUID systemId, @Nullable Integer limit,
            @Nullable String cursor);

    /** {@code GET /conversations/{conversationId}}. */
    Conversation get(UUID companyId, ConversationView view, UUID conversationId);

    /** A conversation by its key ({@code shop-123-dev-1}), as {@link #get} reads it by id. */
    Conversation getByKey(UUID companyId, ConversationView view, String key);

    /** {@code GET /conversations/{conversationId}/attachments}. */
    List<Attachment> attachments(UUID companyId, ConversationView view, UUID conversationId);

    /** {@code GET /conversations/{conversationId}/attachments/{attachmentId}/content}. */
    ConversationFile download(UUID companyId, ConversationView view, UUID conversationId, UUID attachmentId);

    /**
     * {@code GET /decisions}: newest first; {@code open}, {@code mine}. In {@code CLIENT}, only the decisions of the
     * conversations the caller sees there; a team's approval comes with {@code mayDecide} as the app says, and
     * {@code decide} answers it {@code DECIDE_IN_APP}.
     */
    DecisionPage decisions(UUID companyId, ConversationView view, boolean open, boolean mine, @Nullable Integer limit,
            @Nullable String cursor);

    /** {@code GET /decisions/{decisionId}}. */
    Decision decision(UUID companyId, ConversationView view, UUID decisionId);

    /** {@code GET /inbox}: what waits for the caller; it holds only their own conversations in either view. */
    Inbox inbox(UUID companyId, ConversationView view);

    /**
     * The filters of {@link #list}; each null for none.
     *
     * @param triggerId a trigger's conversations (the package 4 contract, §5.13), of a trigger the caller sees
     */
    record Filter(@Nullable UUID agentId, @Nullable UUID subjectSystemId, @Nullable String subjectKey,
            ConversationInfo.@Nullable State state, ConversationInfo.@Nullable WaitingOn waitingOn,
            @Nullable UUID personId, @Nullable UUID triggerId) {

        public static final Filter NONE = new Filter(null, null, null, null, null, null, null);

        /** The filters without a trigger's. */
        public Filter(@Nullable UUID agentId, @Nullable UUID subjectSystemId, @Nullable String subjectKey,
                ConversationInfo.@Nullable State state, ConversationInfo.@Nullable WaitingOn waitingOn,
                @Nullable UUID personId) {
            this(agentId, subjectSystemId, subjectKey, state, waitingOn, personId, null);
        }
    }

    /** A conversation's file and its content. */
    record ConversationFile(Attachment attachment, byte[] content) {
    }
}
