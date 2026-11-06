package com.flowtivo.tinfirm.conversation.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.flowtivo.tinfirm.conversation.ConversationCommands;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.ConversationReads;
import com.flowtivo.tinfirm.conversation.ConversationView;
import com.flowtivo.tinfirm.shared.api.model.Attachment;
import com.flowtivo.tinfirm.shared.api.model.Conversation;
import com.flowtivo.tinfirm.shared.api.model.ConversationBinding;
import com.flowtivo.tinfirm.shared.api.model.ConversationPage;
import com.flowtivo.tinfirm.shared.api.model.ConversationStartRequest;
import com.flowtivo.tinfirm.shared.api.model.DecideRequest;
import com.flowtivo.tinfirm.shared.api.model.Decision;
import com.flowtivo.tinfirm.shared.api.model.DecisionPage;
import com.flowtivo.tinfirm.shared.api.model.Inbox;
import com.flowtivo.tinfirm.shared.api.model.SubjectPage;

/**
 * {@link ConversationReads} and {@link ConversationCommands} over the module's services (the package 6 contract, §5.7,
 * open point 20): the REST controllers ({@code APP}) and the MCP endpoint ({@code CLIENT}) both come through here, and
 * the services apply the view (who is seen, the origin of a start, a team's approval) and the submissions' idempotency
 * keys ({@link SubmissionKeys}, package R), once for both. Each service method runs in its own transaction and
 * authorises the caller first.
 */
@Component
class ConversationApi implements ConversationReads, ConversationCommands {

    private final ConversationService conversations;

    private final DecisionService decisions;

    private final InboxService inbox;

    private final AttachmentService attachments;

    ConversationApi(ConversationService conversations, DecisionService decisions, InboxService inbox,
            AttachmentService attachments) {
        this.conversations = conversations;
        this.decisions = decisions;
        this.inbox = inbox;
        this.attachments = attachments;
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    @Override
    public ConversationPage list(UUID companyId, ConversationView view, Filter filter, @Nullable Integer limit,
            @Nullable String cursor) {
        return conversations.list(companyId, view, filter.agentId(), filter.subjectSystemId(), filter.subjectKey(),
                filter.state(), filter.waitingOn(), filter.personId(), filter.triggerId(), limit, cursor);
    }

    @Override
    public SubjectPage subjects(UUID companyId, ConversationView view, @Nullable UUID systemId,
            @Nullable Integer limit, @Nullable String cursor) {
        return conversations.subjects(companyId, view, systemId, limit, cursor);
    }

    @Override
    public Conversation get(UUID companyId, ConversationView view, UUID conversationId) {
        return conversations.get(companyId, view, conversationId);
    }

    @Override
    public Conversation getByKey(UUID companyId, ConversationView view, String key) {
        return conversations.getByKey(companyId, view, key);
    }

    @Override
    public List<Attachment> attachments(UUID companyId, ConversationView view, UUID conversationId) {
        return attachments.list(companyId, view, conversationId);
    }

    @Override
    public ConversationFile download(UUID companyId, ConversationView view, UUID conversationId,
            UUID attachmentId) {
        AttachmentService.Download download = attachments.download(companyId, view, conversationId, attachmentId);
        return new ConversationFile(attachments.toDto(download.attachment()), download.content());
    }

    @Override
    public DecisionPage decisions(UUID companyId, ConversationView view, boolean open, boolean mine,
            @Nullable Integer limit, @Nullable String cursor) {
        return decisions.list(companyId, view, open, mine, limit, cursor);
    }

    @Override
    public Decision decision(UUID companyId, ConversationView view, UUID decisionId) {
        return decisions.get(companyId, view, decisionId);
    }

    @Override
    public Inbox inbox(UUID companyId, ConversationView view) {
        return inbox.inbox(companyId);
    }

    // ---- commands --------------------------------------------------------------------------------------------------

    @Override
    public Submitted<Conversation> start(UUID companyId, ConversationStartRequest request,
            @Nullable String idempotencyKey) {
        return conversations.start(companyId, request, ConversationInfo.Origin.APP, 0, idempotencyKey);
    }

    @Override
    public Submitted<Conversation> startFromClient(UUID companyId, ConversationStartRequest request, int perHour,
            @Nullable String idempotencyKey) {
        return conversations.start(companyId, request, ConversationInfo.Origin.CLIENT, perHour, idempotencyKey);
    }

    @Override
    public Submitted<Conversation> startFromClient(UUID companyId, ConversationStartRequest request, int perHour,
            @Nullable String idempotencyKey, List<UUID> stagedFileIds) {
        return conversations.start(companyId, request, ConversationInfo.Origin.CLIENT, perHour, idempotencyKey,
                stagedFileIds);
    }

    @Override
    public Optional<Conversation> startedWith(UUID companyId, String idempotencyKey) {
        return conversations.started(companyId, idempotencyKey);
    }

    @Override
    public Conversation rename(UUID companyId, ConversationView view, UUID conversationId, String title) {
        return conversations.rename(companyId, view, conversationId, title);
    }

    @Override
    public Submitted<Conversation> post(UUID companyId, ConversationView view, UUID conversationId, String body,
            boolean answersOpenQuestions, @Nullable String idempotencyKey) {
        return conversations.message(companyId, view, conversationId, body, answersOpenQuestions, idempotencyKey);
    }

    @Override
    public Conversation cancel(UUID companyId, ConversationView view, UUID conversationId) {
        return conversations.cancel(companyId, view, conversationId);
    }

    @Override
    public Submitted<Conversation> retry(UUID companyId, ConversationView view, UUID conversationId,
            @Nullable String idempotencyKey) {
        return conversations.retry(companyId, view, conversationId, idempotencyKey);
    }

    @Override
    public Conversation end(UUID companyId, ConversationView view, UUID conversationId) {
        return conversations.end(companyId, view, conversationId);
    }

    @Override
    public ConversationBinding choose(UUID companyId, ConversationView view, UUID conversationId, UUID systemId,
            @Nullable UUID grantId, boolean reconnect) {
        return conversations.choose(companyId, view, conversationId, systemId, grantId, reconnect);
    }

    @Override
    public ConversationBinding continueBinding(UUID companyId, ConversationView view, UUID conversationId,
            UUID systemId) {
        return conversations.continueBinding(companyId, view, conversationId, systemId);
    }

    @Override
    public Submitted<Attachment> upload(UUID companyId, UUID conversationId, MultipartFile file, @Nullable String note,
            @Nullable Boolean acknowledgeCredentialMatch, @Nullable String idempotencyKey) {
        return attachments.upload(companyId, conversationId, file, note, acknowledgeCredentialMatch, idempotencyKey);
    }

    @Override
    public void deleteAttachment(UUID companyId, UUID conversationId, UUID attachmentId) {
        attachments.delete(companyId, conversationId, attachmentId);
    }

    @Override
    public Submitted<Decision> decide(UUID companyId, ConversationView view, UUID decisionId, DecideRequest request,
            @Nullable String idempotencyKey) {
        return decisions.decide(companyId, view, decisionId, request, idempotencyKey);
    }

    @Override
    public void dismissNotice(UUID companyId, String noticeKey) {
        inbox.dismiss(companyId, noticeKey);
    }

    @Override
    public void restoreNotice(UUID companyId, String noticeKey) {
        inbox.restore(companyId, noticeKey);
    }
}
