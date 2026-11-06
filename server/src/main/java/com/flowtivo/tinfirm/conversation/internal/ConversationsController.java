package com.flowtivo.tinfirm.conversation.internal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.flowtivo.tinfirm.conversation.ConversationCommands;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.ConversationReads;
import com.flowtivo.tinfirm.conversation.ConversationView;
import com.flowtivo.tinfirm.shared.api.ConversationsApi;
import com.flowtivo.tinfirm.shared.api.model.Attachment;
import com.flowtivo.tinfirm.shared.api.model.Conversation;
import com.flowtivo.tinfirm.shared.api.model.ConversationBinding;
import com.flowtivo.tinfirm.shared.api.model.ConversationBindingChooseRequest;
import com.flowtivo.tinfirm.shared.api.model.ConversationMessageRequest;
import com.flowtivo.tinfirm.shared.api.model.ConversationPage;
import com.flowtivo.tinfirm.shared.api.model.ConversationReader;
import com.flowtivo.tinfirm.shared.api.model.ConversationStartRequest;
import com.flowtivo.tinfirm.shared.api.model.ConversationState;
import com.flowtivo.tinfirm.shared.api.model.ConversationUpdateRequest;
import com.flowtivo.tinfirm.shared.api.model.ConversationWaitingOn;
import com.flowtivo.tinfirm.shared.api.model.SubjectPage;
import com.flowtivo.tinfirm.shared.web.EventStreamSupport;

/**
 * The Conversations API (tag {@code Conversations}; the package 3 contract, §6). Thin: it reads and acts through the
 * module's public {@link ConversationReads} and {@link ConversationCommands} in the {@code APP} view, the same path
 * the MCP endpoint takes in the {@code CLIENT} view (the package 6 contract, open point 20).
 */
@RestController
class ConversationsController implements ConversationsApi {

    private static final ConversationView APP = ConversationView.APP;

    /** The response header of a replayed submission (package R, §3.5). */
    static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final ConversationReads reads;

    private final ConversationCommands commands;

    private final ConversationReaders readers;

    private final ConversationStreams streams;

    ConversationsController(ConversationReads reads, ConversationCommands commands, ConversationReaders readers,
            ConversationStreams streams) {
        this.reads = reads;
        this.commands = commands;
        this.readers = readers;
        this.streams = streams;
    }

    @Override
    public ResponseEntity<String> streamConversation(UUID companyId, UUID conversationId) {
        return EventStreamSupport.response(streams.stream(companyId, conversationId));
    }

    @Override
    public ResponseEntity<List<ConversationReader>> listConversationReads(UUID companyId, UUID conversationId) {
        return ResponseEntity.ok(readers.readers(companyId, conversationId));
    }

    @Override
    public ResponseEntity<ConversationPage> listConversations(UUID companyId, @Nullable UUID agentId,
            @Nullable UUID subjectSystemId, @Nullable String subjectKey, @Nullable ConversationState state,
            @Nullable ConversationWaitingOn waitingOn, @Nullable UUID personId, @Nullable UUID triggerId,
            Integer limit, @Nullable String cursor) {
        return ResponseEntity.ok(reads.list(companyId, APP, new ConversationReads.Filter(agentId, subjectSystemId,
                subjectKey, state != null ? ConversationInfo.State.valueOf(state.getValue()) : null,
                waitingOn != null ? ConversationInfo.WaitingOn.valueOf(waitingOn.getValue()) : null, personId,
                triggerId), limit, cursor));
    }

    @Override
    public ResponseEntity<Conversation> startConversation(UUID companyId, ConversationStartRequest request,
            @Nullable String idempotencyKey) {
        return submitted(HttpStatus.CREATED, commands.start(companyId, request, idempotencyKey));
    }

    @Override
    public ResponseEntity<SubjectPage> listSubjects(UUID companyId, @Nullable UUID systemId, Integer limit,
            @Nullable String cursor) {
        return ResponseEntity.ok(reads.subjects(companyId, APP, systemId, limit, cursor));
    }

    @Override
    public ResponseEntity<Conversation> getConversation(UUID companyId, UUID conversationId) {
        return ResponseEntity.ok(reads.get(companyId, APP, conversationId));
    }

    @Override
    public ResponseEntity<Conversation> renameConversation(UUID companyId, UUID conversationId,
            ConversationUpdateRequest request) {
        return ResponseEntity.ok(commands.rename(companyId, APP, conversationId, request.getTitle()));
    }

    @Override
    public ResponseEntity<Conversation> postConversationMessage(UUID companyId, UUID conversationId,
            ConversationMessageRequest request, @Nullable String idempotencyKey) {
        return submitted(HttpStatus.CREATED, commands.post(companyId, APP, conversationId, request.getBody(),
                Boolean.TRUE.equals(request.getAnswersOpenQuestions()), idempotencyKey));
    }

    @Override
    public ResponseEntity<Conversation> cancelConversation(UUID companyId, UUID conversationId) {
        return ResponseEntity.ok(commands.cancel(companyId, APP, conversationId));
    }

    @Override
    public ResponseEntity<Conversation> retryConversation(UUID companyId, UUID conversationId,
            @Nullable String idempotencyKey) {
        return submitted(HttpStatus.OK, commands.retry(companyId, APP, conversationId, idempotencyKey));
    }

    @Override
    public ResponseEntity<Conversation> endConversation(UUID companyId, UUID conversationId) {
        return ResponseEntity.ok(commands.end(companyId, APP, conversationId));
    }

    @Override
    public ResponseEntity<ConversationBinding> chooseConversationBinding(UUID companyId, UUID conversationId,
            UUID systemId, ConversationBindingChooseRequest request) {
        return ResponseEntity.ok(commands.choose(companyId, APP, conversationId, systemId, request.getGrantId(),
                Boolean.TRUE.equals(request.getReconnect())));
    }

    @Override
    public ResponseEntity<ConversationBinding> continueConversationBinding(UUID companyId, UUID conversationId,
            UUID systemId) {
        return ResponseEntity.ok(commands.continueBinding(companyId, APP, conversationId, systemId));
    }

    // ---- The conversation's files (part 3) -------------------------------------------------------------------------

    @Override
    public ResponseEntity<List<Attachment>> listAttachments(UUID companyId, UUID conversationId) {
        return ResponseEntity.ok(reads.attachments(companyId, APP, conversationId));
    }

    @Override
    public ResponseEntity<Attachment> uploadAttachment(UUID companyId, UUID conversationId, MultipartFile file,
            @Nullable String idempotencyKey, @Nullable String note, @Nullable Boolean acknowledgeCredentialMatch) {
        return submitted(HttpStatus.CREATED, commands.upload(companyId, conversationId, file, note,
                acknowledgeCredentialMatch, idempotencyKey));
    }

    /**
     * The file as an attachment of its stored content type, never rendered inline: {@code Content-Disposition:
     * attachment} with its name (the last segment of a path), and {@code nosniff}.
     */
    @Override
    public ResponseEntity<Resource> downloadAttachment(UUID companyId, UUID conversationId, UUID attachmentId) {
        ConversationReads.ConversationFile download = reads.download(companyId, APP, conversationId, attachmentId);
        String name = download.attachment().getFileName();
        name = name.substring(name.lastIndexOf('/') + 1);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(download.attachment().getContentType()))
            .contentLength(download.content().length)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .cacheControl(CacheControl.noStore())
            .body(new ByteArrayResource(download.content()));
    }

    /**
     * A submission's answer (package R, §3.5): the first request's status, and {@code Idempotent-Replayed: true} when
     * an earlier request with the same {@code Idempotency-Key} made what it shows.
     */
    static <T> ResponseEntity<T> submitted(HttpStatus status, ConversationCommands.Submitted<T> submitted) {
        ResponseEntity.BodyBuilder answer = ResponseEntity.status(status);
        if (submitted.replayed()) {
            answer.header(IDEMPOTENT_REPLAYED, "true");
        }
        return answer.body(submitted.body());
    }

    @Override
    public ResponseEntity<Void> deleteAttachment(UUID companyId, UUID conversationId, UUID attachmentId) {
        commands.deleteAttachment(companyId, conversationId, attachmentId);
        return ResponseEntity.noContent().build();
    }
}
