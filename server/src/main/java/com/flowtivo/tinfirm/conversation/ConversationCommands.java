package com.flowtivo.tinfirm.conversation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.web.multipart.MultipartFile;

import com.flowtivo.tinfirm.shared.api.model.Attachment;
import com.flowtivo.tinfirm.shared.api.model.Conversation;
import com.flowtivo.tinfirm.shared.api.model.ConversationBinding;
import com.flowtivo.tinfirm.shared.api.model.ConversationStartRequest;
import com.flowtivo.tinfirm.shared.api.model.DecideRequest;
import com.flowtivo.tinfirm.shared.api.model.Decision;

/**
 * The conversation module's public writes (the package 6 contract, §5.7, open point 20): the one path by which the
 * REST API and the MCP endpoint act on conversations, as {@link ConversationReads} is for reads. Every write is the
 * current user's, authorised for the company through the access guard, in a transaction of its own, audited by the
 * services behind it (with the bearer credential's {@code tokenId} or {@code grantId}), and answers the REST API's
 * DTOs. What the caller may not see in the {@link ConversationView} is {@code 404}; in a conversation they see but are
 * not the person of, {@code 409 CONVERSATION_BOUND}.
 * <p>
 * The submissions (a start, a message, an upload, a retry, a decision) take the caller's idempotency key (package R,
 * §3.5): the REST API's {@code Idempotency-Key}, an MCP tool's {@code requestId}; null for none. A key is claimed in the
 * transaction that makes the resource, after the caller's authorisation. The same key again, for the same operation and
 * request, answers what the first request made as it is now ({@link Submitted#replayed()}), and makes, audits and
 * charges nothing new; with another operation or request it is {@code 422 IDEMPOTENCY_KEY_REUSED}; a request that
 * failed stored no key. A key is the caller's own, kept a day; it has 1 to 200 visible ASCII characters (else
 * {@code 400}).
 */
public interface ConversationCommands {

    /**
     * A submission's answer: what it made, as it is now.
     *
     * @param replayed whether an earlier request with the same idempotency key made it, and this one made nothing
     */
    record Submitted<T>(T body, boolean replayed) {
    }

    /** {@code POST /conversations} from the app: origin {@code APP}, the caller its person and payer. */
    Submitted<Conversation> start(UUID companyId, ConversationStartRequest request, @Nullable String idempotencyKey);

    /**
     * A start through the person's AI client (open point 14): origin {@code CLIENT}, as the app's start in everything
     * else. At most {@code perHour} such starts per member in the past hour, counted from the company's conversations
     * under a lock per member, so the limit holds across replicas; beyond it {@code 429 RATE_LIMITED} with
     * {@code retryAfter}. {@code perHour} 0 turns the limit off. A replay counts no start.
     */
    Submitted<Conversation> startFromClient(UUID companyId, ConversationStartRequest request, int perHour,
            @Nullable String idempotencyKey);

    /**
     * As {@link #startFromClient(UUID, ConversationStartRequest, int, String)}, with files the caller held for it
     * ({@link StagedFiles}, ADR-0084): moved into the conversation before its first turn, as files its person added,
     * after any of {@code attachmentIds}, within a conversation's limits. A file that is not one the caller holds, or
     * that expired, is {@code 400 VALIDATION_FAILED}; two of one name {@code 409 DUPLICATE_ATTACHMENT_NAME}; too many or
     * too large {@code 409 TOO_MANY_ATTACHMENTS}. A replay moves nothing.
     */
    Submitted<Conversation> startFromClient(UUID companyId, ConversationStartRequest request, int perHour,
            @Nullable String idempotencyKey, List<UUID> stagedFileIds);

    /**
     * What the caller's start under the idempotency key made, as it is now, when a first request with it was
     * answered; empty otherwise (a key nobody claimed, one of another submission, a malformed one). A client that
     * holds files for a start ({@link StagedFiles}, ADR-0084) asks this before it holds them again, so that a start
     * sent again reads, scans and holds nothing twice; it claims and refuses nothing, and the start's own key still
     * decides.
     */
    Optional<Conversation> startedWith(UUID companyId, String idempotencyKey);

    /** {@code PATCH /conversations/{conversationId}}: its person renames it. */
    Conversation rename(UUID companyId, ConversationView view, UUID conversationId, String title);

    /** {@code POST /conversations/{conversationId}/messages}: its person's message, answering open questions or not. */
    Submitted<Conversation> post(UUID companyId, ConversationView view, UUID conversationId, String body,
            boolean answersOpenQuestions, @Nullable String idempotencyKey);

    /** {@code POST /conversations/{conversationId}/cancel}. */
    Conversation cancel(UUID companyId, ConversationView view, UUID conversationId);

    /** {@code POST /conversations/{conversationId}/retry}. */
    Submitted<Conversation> retry(UUID companyId, ConversationView view, UUID conversationId,
            @Nullable String idempotencyKey);

    /** {@code POST /conversations/{conversationId}/end}, idempotent. */
    Conversation end(UUID companyId, ConversationView view, UUID conversationId);

    /** {@code POST /conversations/{conversationId}/bindings/{systemId}/choose}. */
    ConversationBinding choose(UUID companyId, ConversationView view, UUID conversationId, UUID systemId,
            @Nullable UUID grantId, boolean reconnect);

    /** {@code POST /conversations/{conversationId}/bindings/{systemId}/continue}. */
    ConversationBinding continueBinding(UUID companyId, ConversationView view, UUID conversationId, UUID systemId);

    /** {@code POST /conversations/{conversationId}/attachments}: the app's upload; no client uploads. */
    Submitted<Attachment> upload(UUID companyId, UUID conversationId, MultipartFile file, @Nullable String note,
            @Nullable Boolean acknowledgeCredentialMatch, @Nullable String idempotencyKey);

    /** {@code DELETE /conversations/{conversationId}/attachments/{attachmentId}}. */
    void deleteAttachment(UUID companyId, UUID conversationId, UUID attachmentId);

    /**
     * {@code POST /decisions/{decisionId}/decide}: its approver decides. In {@code CLIENT}, a team's approval (an
     * approval of a trigger's conversation) is {@code 409 DECIDE_IN_APP} with its {@code conversationId} and
     * {@code decisionId}, decided only in the app and the chat (open point 15); a decision decided there is audited
     * with {@code via CLIENT}.
     */
    Submitted<Decision> decide(UUID companyId, ConversationView view, UUID decisionId, DecideRequest request,
            @Nullable String idempotencyKey);

    /**
     * {@code PUT /inbox/dismissals/{noticeKey}}: the app's; the caller dismisses a notice of their own inbox, for
     * themselves, while its key stands (CONVENTIONS §15.7). Not audited.
     */
    void dismissNotice(UUID companyId, String noticeKey);

    /** {@code DELETE /inbox/dismissals/{noticeKey}}: the caller shows a notice they dismissed again. Not audited. */
    void restoreNotice(UUID companyId, String noticeKey);
}
