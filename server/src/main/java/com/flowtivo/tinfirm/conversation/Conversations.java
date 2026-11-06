package com.flowtivo.tinfirm.conversation;

import java.util.Optional;
import java.util.UUID;

import com.flowtivo.tinfirm.shared.access.CompanyAccess;

/**
 * Conversations for other modules and for the conversation module's parts (the package 3 contract, §3.8, §5.10). Runs
 * in the caller's transaction and tenant context: row-level security limits every read to the company of that
 * context.
 */
public interface Conversations {

    /** The conversation, whoever may see it. */
    Optional<ConversationInfo> find(UUID companyId, UUID conversationId);

    /**
     * The id of the conversation with this key ({@code shop-123-dev-1}), whoever may see it: for a hold's lookup
     * (package 6), which shows nothing of the conversation but its id and key. Who may know it is the caller's to check.
     */
    Optional<UUID> idOfKey(UUID companyId, String key);

    /**
     * Whether the caller sees the conversation, with its messages, runs, decisions, outputs, attachments and bindings
     * (§5.10, I17): its person, and OWNER in full. An ADMIN who is not OWNER does not, nor does any other member, nor an
     * instance admin who is not a member, whose break-glass gives ADMIN's view (the package 6 contract, §5.10): what
     * they may not see is {@code 404}.
     */
    boolean maySee(CompanyAccess access, UUID userId, ConversationInfo conversation);

    /**
     * Whether the caller sees the conversation through the view: in {@link ConversationView#APP} as
     * {@link #maySee(CompanyAccess, UUID, ConversationInfo)}; in {@link ConversationView#CLIENT} only as its person,
     * OWNER included (the package 6 contract, G9, open point 11).
     */
    boolean maySee(CompanyAccess access, UUID userId, ConversationInfo conversation, ConversationView view);

    /**
     * Whether the caller acts as the conversation's person: replies, answers, approves, renames, chooses and continues
     * a binding (I15). OWNER sees it but is not its person ({@code CONVERSATION_BOUND}).
     */
    boolean isPerson(CompanyAccess access, UUID userId, ConversationInfo conversation);
}
