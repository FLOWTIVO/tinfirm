package com.flowtivo.tinfirm.conversation.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.run.TurnEnded;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.api.model.Attachment;
import com.flowtivo.tinfirm.shared.api.model.Output;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.ChangeRequests;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.Systems;

import tools.jackson.databind.JsonNode;

/**
 * What a conversation produced (the package 3 contract, §3.13, §5.5): a {@code LINK} the agent saved with
 * {@code save_artifact} ({@code run.request} kind {@code ARTIFACT}, {@link ArtifactRequests}), a {@code DOCUMENT} it
 * saved with {@code save_artifact} (a file its host uploaded) or left in {@code /output} (recorded when its turn ends,
 * from {@code TurnEnd.outputFiles}), and a {@code CHANGE_REQUEST} the server opened after a push. Each is audited as
 * {@code output.created}: actor AGENT for what the agent saved, SYSTEM for a change request. It is also the reader of a
 * conversation's outputs and files for part 1 ({@link ConversationOutputs}).
 */
@Service
class OutputService implements ArtifactRequests, ConversationOutputs {

    private static final Logger log = LoggerFactory.getLogger(OutputService.class);

    static final String TARGET = "output";

    /** The longest link and title (V005, the protocol's {@code ArtifactRequest}). */
    static final int MAX_URL_LENGTH = 2000;

    static final int MAX_TITLE_LENGTH = 200;

    /** An http(s) link without user info or whitespace, as {@code output_url_ck} takes it. */
    private static final Pattern LINK = Pattern.compile("^https?://[^/?#@\\s]+([/?#]\\S*)?$");

    private final OutputStore outputs;

    private final AttachmentStore attachments;

    private final AttachmentService attachmentService;

    private final Conversations conversations;

    private final Systems systems;

    private final AuditLog auditLog;

    private final TimeSource time;

    OutputService(OutputStore outputs, AttachmentStore attachments, AttachmentService attachmentService,
            Conversations conversations, Systems systems, AuditLog auditLog, TimeSource time) {
        this.outputs = outputs;
        this.attachments = attachments;
        this.attachmentService = attachmentService;
        this.conversations = conversations;
        this.systems = systems;
        this.auditLog = auditLog;
        this.time = time;
    }

    // ---- save_artifact: a link -------------------------------------------------------------------------------------

    /**
     * {@code save_artifact} with a link: a {@code LINK} output of the conversation, answered {@code DONE} with its id.
     * A request id the conversation saw already gets the same output again, without a second one (the host sends a
     * request again after a reconnect). In the transaction that checked the run, as SYSTEM in the run's company.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RunnerMessages.RunRequestAck request(ActiveRun run, UUID requestId, JsonNode payload) {
        Optional<OutputStore.OutputRow> saved = outputs.ofRequest(run.conversationId(), requestId);
        if (saved.isPresent()) {
            return done(run, requestId, saved.get());
        }
        ConversationInfo conversation = conversations.find(run.companyId(), run.conversationId()).orElse(null);
        if (conversation == null || !conversation.open()) {
            return RunnerMessages.RunRequestAck.rejected(run.runId(), requestId, ProblemCode.CONVERSATION_ENDED.name(),
                    "The conversation has ended, so the link was not saved.");
        }
        JsonNode urlNode = payload.get("url");
        String url = urlNode != null && urlNode.isString() ? urlNode.asString().strip() : null;
        String problem = url == null ? "`url` is missing" : linkProblem(url);
        if (problem != null) {
            return RunnerMessages.RunRequestAck.rejected(run.runId(), requestId, AttachmentService.INVALID_REQUEST,
                    problem + ": it must be an http(s) link of at most " + MAX_URL_LENGTH + " characters, without a "
                            + "user name or password. Nothing was saved.");
        }
        JsonNode titleNode = payload.get("title");
        String title = title(titleNode != null && titleNode.isString() ? titleNode.asString() : null, url);
        OutputStore.OutputRow row = new OutputStore.OutputRow(Ids.newId(), run.companyId(), run.conversationId(),
                run.runId(), Kind.LINK, title, url, null, null, null, null, requestId, time.now());
        if (!outputs.insert(row)) {
            // Saved meanwhile under the same request id.
            return outputs.ofRequest(run.conversationId(), requestId)
                .map(existing -> done(run, requestId, existing))
                .orElseThrow(() -> new IllegalStateException("The link of request " + requestId + " was not saved"));
        }
        audit(row, Actor.agent(run.agentId()));
        return done(run, requestId, row);
    }

    private static RunnerMessages.RunRequestAck done(ActiveRun run, UUID requestId, OutputStore.OutputRow row) {
        return RunnerMessages.RunRequestAck.done(run.runId(), requestId,
                new RunnerMessages.ArtifactPayload(row.id()), "Saved the link `" + row.title() + "` as an output of "
                        + "the conversation, where the person sees it.");
    }

    /** Why a link cannot be saved, or null: http(s), a host, no user info, no whitespace, at most 2000 characters. */
    static @Nullable String linkProblem(String url) {
        if (url.isEmpty()) {
            return "`url` is empty";
        }
        if (url.length() > MAX_URL_LENGTH) {
            return "`url` is too long";
        }
        if (!LINK.matcher(url).matches() || url.codePoints().anyMatch(Character::isISOControl)) {
            return "`url` is not a plain http(s) link";
        }
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawUserInfo() != null) {
                return "`url` has no host, or has a user name";
            }
        }
        catch (URISyntaxException ex) {
            return "`url` is not a valid link";
        }
        return null;
    }

    /** The title as given, trimmed and cut to 200 characters; the fallback when none was given. */
    static String title(@Nullable String title, String fallback) {
        String text = title != null && !title.isBlank() ? title.strip() : fallback.strip();
        if (text.codePointCount(0, text.length()) > MAX_TITLE_LENGTH) {
            text = text.substring(0, text.offsetByCodePoints(0, MAX_TITLE_LENGTH - 1)).strip() + "…";
        }
        return text;
    }

    /** A document's title: its name, or the end of a long path. */
    static String documentTitle(String fileName) {
        if (fileName.codePointCount(0, fileName.length()) <= MAX_TITLE_LENGTH) {
            return fileName;
        }
        int keep = MAX_TITLE_LENGTH - 1;
        return "…" + fileName.substring(fileName.offsetByCodePoints(fileName.length(), -keep));
    }

    // ---- Documents --------------------------------------------------------------------------------------------------

    /**
     * {@code save_artifact} with a file: the file the host uploaded for the active run, stored as the agent's file of
     * the conversation, becomes a {@code DOCUMENT} output at once. In the upload's transaction.
     *
     * @return the output
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID savedDocument(ActiveRun run, UUID attachmentId, String fileName) {
        OutputStore.OutputRow row = new OutputStore.OutputRow(Ids.newId(), run.companyId(), run.conversationId(),
                run.runId(), Kind.DOCUMENT, documentTitle(fileName), null, attachmentId, null, null, null, null,
                time.now());
        if (!outputs.insert(row)) {
            return outputs.ofAttachment(attachmentId).map(OutputStore.OutputRow::id).orElseThrow();
        }
        audit(row, Actor.agent(run.agentId()));
        return row.id();
    }

    /**
     * The files the turn's agent left in {@code /output}, which its host uploaded and listed in {@code run.finished}:
     * each becomes a {@code DOCUMENT} output, when it is the agent's file of this run in this conversation and no
     * output's document yet. A file the host names that is not such is skipped (logged). In the transaction that ended
     * the run.
     *
     * @return how many documents were recorded
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int turnDocuments(ConversationInfo conversation, TurnEnded.TurnEnd end) {
        int recorded = 0;
        for (TurnEnded.OutputFile file : end.outputFiles()) {
            AttachmentStore.AttachmentRow attachment = attachments.find(conversation.id(), file.attachmentId())
                .filter(found -> AttachmentService.ofRun(found, conversation.id(), end.runId()))
                .orElse(null);
            if (attachment == null) {
                log.info("Run {} listed the file {} of /output, which is not its own in conversation {}", end.runId(),
                        file.attachmentId(), conversation.id());
                continue;
            }
            if (attachment.outputId() != null) {
                continue;
            }
            OutputStore.OutputRow row = new OutputStore.OutputRow(Ids.newId(), end.companyId(), conversation.id(),
                    end.runId(), Kind.DOCUMENT, documentTitle(attachment.fileName()), null, attachment.id(), null,
                    null, null, null, time.now());
            if (outputs.insert(row)) {
                audit(row, Actor.agent(end.agentId()));
                recorded++;
            }
        }
        return recorded;
    }

    // ---- Change requests --------------------------------------------------------------------------------------------

    /**
     * A change request the server opened, or found open for the branch: a {@code CHANGE_REQUEST} output with its
     * number and state. In the caller's transaction, which holds the conversation's lock.
     *
     * @param prefix {@code #} for a pull request, {@code !} for a merge request
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutputStore.OutputRow changeRequest(UUID companyId, UUID conversationId, UUID runId,
            RepositoryInfo repository, ChangeRequests.Opened opened, String prefix) {
        ChangeRequestState state = opened.state() != null ? ChangeRequestState.valueOf(opened.state().name())
                : ChangeRequestState.OPEN;
        OutputStore.OutputRow row = new OutputStore.OutputRow(Ids.newId(), companyId, conversationId, runId,
                Kind.CHANGE_REQUEST, title(repository.name() + " " + prefix + opened.number(), opened.url()),
                opened.url(), null, repository.id(), Integer.toString(opened.number()), state, null, time.now());
        if (!outputs.insert(row)) {
            throw new IllegalStateException("The change request's output was not stored");
        }
        audit(row, Actor.SYSTEM);
        return row;
    }

    private void audit(OutputStore.OutputRow row, Actor actor) {
        auditLog.record(AuditRecord.company(row.companyId(), AuditAction.OUTPUT_CREATED, actor)
            .target(TARGET, row.id())
            .detail("conversationId", row.conversationId().toString())
            .detail("runId", row.runId() != null ? row.runId().toString() : null)
            .detail("kind", row.kind().name())
            .detail("attachmentId", row.attachmentId() != null ? row.attachmentId().toString() : null)
            .detail("url", row.url())
            .detail("repositoryId", row.repositoryId() != null ? row.repositoryId().toString() : null)
            .detail("number", row.externalNumber())
            .build());
    }

    // ---- The reader for part 1 --------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<OutputInfo> outputs(UUID companyId, UUID conversationId) {
        List<OutputStore.OutputRow> rows = outputs.ofConversation(conversationId);
        Map<UUID, Optional<RepositoryInfo>> repositories = new HashMap<>();
        return rows.stream().map(row -> {
            String repositoryName = row.repositoryId() == null ? null
                    : repositories.computeIfAbsent(row.repositoryId(), id -> systems.repository(companyId, id))
                        .map(RepositoryInfo::name)
                        .orElse(null);
            return new OutputInfo(row.id(), row.conversationId(), row.runId(), row.kind(), row.title(), row.url(),
                    row.attachmentId(), row.repositoryId(), repositoryName, row.externalNumber(),
                    row.changeRequestState(), row.createdAt(), row.purgedAt());
        }).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Output> outputViews(UUID companyId, UUID conversationId) {
        return outputs(companyId, conversationId).stream()
            .map(output -> new Output(output.id(), output.conversationId(), Output.KindEnum.fromValue(
                    output.kind().name()), output.title(), AttachmentStore.ts(output.createdAt()))
                .runId(output.runId())
                .purgedAt(output.purgedAt() != null ? AttachmentStore.ts(output.purgedAt()) : null)
                .url(output.url())
                .attachmentId(output.attachmentId())
                .repositoryId(output.repositoryId())
                .repositoryName(output.repositoryName())
                .externalNumber(output.externalNumber())
                .changeRequestState(output.changeRequestState() != null
                        ? Output.ChangeRequestStateEnum.fromValue(output.changeRequestState().name()) : null))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Attachment> attachments(UUID companyId, UUID conversationId) {
        return attachmentService.attachments(companyId, conversationId);
    }

    /** The output, if the tenant context sees it. */
    @Transactional(readOnly = true)
    public Optional<OutputStore.OutputRow> find(UUID outputId) {
        return outputs.find(outputId);
    }

    /** A change request's state as last read, on its output. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void changeRequestState(UUID outputId, ChangeRequests.State state) {
        outputs.changeRequestState(outputId, ChangeRequestState.valueOf(Objects.requireNonNull(state).name()));
    }
}
