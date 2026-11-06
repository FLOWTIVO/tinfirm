package com.flowtivo.tinfirm.conversation.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.ConversationCommands.Submitted;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.ConversationView;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.conversation.StagedFiles;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.Attachment;
import com.flowtivo.tinfirm.shared.api.model.CredentialMatch;
import com.flowtivo.tinfirm.shared.directory.AgentDisplayNames;
import com.flowtivo.tinfirm.shared.directory.UserDisplayNames;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.vault.ScanReport;
import com.flowtivo.tinfirm.vault.VaultService;

/**
 * A conversation's files (ADR-0014, ADR-0021; the package 3 contract, §3.13, §5.10, §6): those its person adds, and the
 * documents its agent saves. Every file is scanned once, here, before it is stored:
 * <ul>
 * <li>the value of a vault secret of the company: 422 {@code CREDENTIAL_FOUND}, never overridable;</li>
 * <li>content that cannot be scanned completely: 422 {@code ATTACHMENT_NOT_SCANNABLE};</li>
 * <li>a known credential format: 422 {@code CREDENTIAL_FOUND}, unless the person is an OWNER or ADMIN (who manages the
 * company's secrets) and acknowledges it, recorded in the audit event; an agent never acknowledges one.</li>
 * </ul>
 * Whoever sees the conversation ({@code Conversations.maySee}: its person, OWNER) reads its files; only its person adds
 * and deletes them (OWNER gets {@code CONVERSATION_BOUND}); anyone else gets 404, as for another company's id. OWNER's
 * list and download of another person's files are audited ({@link OwnerReads}). An upload is checked, then scanned,
 * then locks the conversation's row and checks again, so that the limits ({@link AttachmentProperties}) hold under
 * concurrent uploads and a scan holds up no other request of the conversation. A document of an output is not deleted
 * while the output is.
 */
@Service
class AttachmentService {

    static final String TARGET = "attachment";

    static final String DEFAULT_CONTENT_TYPE = MediaType.APPLICATION_OCTET_STREAM_VALUE;

    static final int MAX_FILE_NAME_LENGTH = 255;

    /**
     * The longest relative path a document from {@code /output} keeps as its name (V010): runner protocol v2's
     * {@code ArtifactResult.name}, in characters (code points), as the protocol and the database count them.
     */
    static final int MAX_PATH_NAME_LENGTH = 1024;

    static final int MAX_NOTE_LENGTH = 2000;

    /** The protocol's code of a request the server cannot take as it is. */
    static final String INVALID_REQUEST = "INVALID_REQUEST";

    private final AttachmentStore attachments;

    private final Conversations conversations;

    private final CompanyAccessGuard companyAccess;

    private final VaultService vault;

    private final Agents agents;

    private final UserDisplayNames userNames;

    private final AgentDisplayNames agentNames;

    private final AuditLog auditLog;

    private final AttachmentProperties properties;

    private final TimeSource time;

    private final OwnerReads ownerReads;

    private final SubmissionKeys keys;

    private final Principals principals;

    private final StagedFileStore staged;

    AttachmentService(AttachmentStore attachments, Conversations conversations, CompanyAccessGuard companyAccess,
            VaultService vault, Agents agents, UserDisplayNames userNames, AgentDisplayNames agentNames,
            AuditLog auditLog, AttachmentProperties properties, TimeSource time, OwnerReads ownerReads,
            SubmissionKeys keys, Principals principals, StagedFileStore staged) {
        this.attachments = attachments;
        this.conversations = conversations;
        this.companyAccess = companyAccess;
        this.vault = vault;
        this.agents = agents;
        this.userNames = userNames;
        this.agentNames = agentNames;
        this.auditLog = auditLog;
        this.properties = properties;
        this.time = time;
        this.ownerReads = ownerReads;
        this.keys = keys;
        this.principals = principals;
        this.staged = staged;
    }

    /** A file's content with its row, for a download. */
    record Download(AttachmentStore.AttachmentRow attachment, byte[] content) {
    }

    /**
     * A file a runner host uploads for the agent of an active run.
     *
     * @param relativePath whether the name may be a path relative to {@code /output} ({@code reports/q3.md})
     */
    record AgentUpload(String fileName, @Nullable String contentType, byte[] content, @Nullable String note,
            boolean relativePath) {
    }

    /**
     * The answer to an agent's upload, written for the agent: stored, or refused with a code and a message that says
     * why and what to do.
     */
    record AgentUploadResult(boolean stored, @Nullable UUID attachmentId, @Nullable String fileName,
            @Nullable String code, String message) {

        static AgentUploadResult stored(UUID attachmentId, String fileName, String message) {
            return new AgentUploadResult(true, attachmentId, fileName, null, message);
        }

        static AgentUploadResult rejected(String code, String message) {
            return new AgentUploadResult(false, null, null, code, message);
        }
    }

    // ---- The API --------------------------------------------------------------------------------------------------

    /** {@code GET /conversations/{id}/attachments}: who sees the conversation. */
    @Transactional(readOnly = true)
    public List<Attachment> list(UUID companyId, ConversationView view, UUID conversationId) {
        Seen seen = seen(companyId, conversationId, view);
        ownerReads.read(seen.access(), seen.userId(), seen.conversation(), OwnerReads.What.ATTACHMENTS);
        return toDtos(attachments.list(conversationId));
    }

    /**
     * {@code POST /conversations/{id}/attachments}: its person. With an idempotency key (package R, §3.5), the same
     * upload again (the file's hash, its name, its note) answers the file it stored, as it is now, and stores nothing.
     */
    @Transactional
    public Submitted<Attachment> upload(UUID companyId, UUID conversationId, MultipartFile file,
            @Nullable String note, @Nullable Boolean acknowledgeCredentialMatch, @Nullable String idempotencyKey) {
        Seen seen = seen(companyId, conversationId);
        requirePerson(seen);
        String fileName = fileName(file.getOriginalFilename());
        String contentType = contentType(file.getContentType());
        long maxFileSize = properties.maxFileSize().toBytes();
        if (file.getSize() > maxFileSize) {
            throw tooLarge();
        }
        byte[] content = read(file);
        if (content.length > maxFileSize) {
            throw tooLarge();
        }
        // The key before anything is checked against the conversation or stored: a replay finds its file there. It is
        // the caller's: a personal conversation's person, a member of a trigger's audience.
        UUID caller = principals.ofMember(companyId, seen.userId()).map(PrincipalInfo::id).orElse(null);
        SubmissionKeys.Claim claim = keys.claim(companyId, caller, idempotencyKey,
                SubmissionKeys.Operation.uploadAttachment, SubmissionKeys.uploadSha256("/api/companies/" + companyId
                        + "/conversations/" + conversationId + "/attachments", sha256(content), fileName, note));
        if (claim instanceof SubmissionKeys.Claim.Replay replay) {
            return new Submitted<>(attachments.find(conversationId, replay.resourceId()).map(this::toDto)
                .orElseThrow(AttachmentService::notFound), true);
        }
        // What the lock checks again, before the scan: a file that cannot be stored is not scanned.
        requireOpen(seen.conversation().state().name());
        if (attachments.exists(conversationId, fileName)) {
            throw duplicateName();
        }
        requireRoom(conversationId, content.length);
        // The scan before the lock: however long it takes, it holds up no other request of the conversation.
        ScanReport scan = vault.scan(companyId, content);
        List<String> acknowledged = checkScan(scan, seen.access(),
                Boolean.TRUE.equals(acknowledgeCredentialMatch));
        lockOpen(conversationId);
        if (attachments.exists(conversationId, fileName)) {
            throw duplicateName();
        }
        requireRoom(conversationId, content.length);
        AttachmentStore.AttachmentRow row = new AttachmentStore.AttachmentRow(Ids.newId(), companyId, conversationId,
                fileName, contentType, content.length, sha256(content), note(note), seen.userId(), null, null,
                time.now(), null);
        try {
            attachments.insert(row, content);
        }
        catch (DuplicateKeyException ex) {
            throw duplicateName();
        }
        auditLog.record(AuditRecord.company(companyId, AuditAction.ATTACHMENT_UPLOADED, CurrentActor.actor())
            .target(TARGET, row.id())
            .detail("conversationId", conversationId.toString())
            .detail("sha256", row.sha256())
            .detail("sizeBytes", row.sizeBytes())
            .detail("contentType", row.contentType())
            .detail("scanned", true)
            .detail("acknowledgedCredentialFormats", acknowledged.isEmpty() ? null : acknowledged)
            .build());
        keys.made(claim, SubmissionKeys.ATTACHMENT, row.id());
        return new Submitted<>(toDtos(List.of(row)).getFirst(), false);
    }

    /** {@code GET /conversations/{id}/attachments/{attachmentId}}: who sees the conversation. */
    @Transactional(readOnly = true)
    public Download download(UUID companyId, ConversationView view, UUID conversationId, UUID attachmentId) {
        Seen seen = seen(companyId, conversationId, view);
        AttachmentStore.AttachmentRow row = attachments.find(conversationId, attachmentId)
            .orElseThrow(AttachmentService::notFound);
        if (row.contentPurgedAt() != null) {
            throw new ApiException(HttpStatus.GONE, ProblemCode.CONTENT_PURGED, "The file's content was removed on "
                    + row.contentPurgedAt().atOffset(ZoneOffset.UTC).toLocalDate() + "; its name, size and hash stay");
        }
        byte[] content = attachments.content(conversationId, attachmentId).orElseThrow(AttachmentService::notFound);
        ownerReads.read(seen.access(), seen.userId(), seen.conversation(), OwnerReads.What.ATTACHMENT);
        return new Download(row, content);
    }

    /** {@code DELETE /conversations/{id}/attachments/{attachmentId}}: its person; never an output's document. */
    @Transactional
    public void delete(UUID companyId, UUID conversationId, UUID attachmentId) {
        Seen seen = seen(companyId, conversationId);
        AttachmentStore.AttachmentRow row = attachments.find(conversationId, attachmentId)
            .orElseThrow(AttachmentService::notFound);
        requirePerson(seen);
        attachments.lockConversation(conversationId).orElseThrow(AttachmentService::conversationNotFound);
        if (row.outputId() != null || !attachments.delete(conversationId, attachmentId)) {
            if (attachments.find(conversationId, attachmentId).isEmpty()) {
                throw notFound();
            }
            throw ApiException.conflict(ProblemCode.ATTACHMENT_IS_OUTPUT,
                    "The file is the document of an output of the conversation, which keeps it");
        }
        auditLog.record(AuditRecord.company(companyId, AuditAction.ATTACHMENT_DELETED, CurrentActor.actor())
            .target(TARGET, attachmentId)
            .detail("conversationId", conversationId.toString())
            .detail("sha256", row.sha256())
            .build());
    }

    /**
     * {@code POST /conversations} with {@code attachmentIds} (the package 3 contract, §6): files of the person's other
     * conversations, copied into the new one before its first turn, as files the person added, with their names, notes
     * and content (scanned when they were first stored); the conversation's limits hold. A file that is not one of
     * the person's conversations is {@code 400 VALIDATION_FAILED}; two of one name {@code 409
     * DUPLICATE_ATTACHMENT_NAME}. In the starting transaction, after the conversation's row; audited as
     * {@code attachment.uploaded} with {@code copiedFromAttachmentId}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void carryInto(UUID companyId, UUID conversationId, @Nullable List<UUID> attachmentIds,
            UUID personPrincipalId, UUID userId) {
        if (attachmentIds == null || attachmentIds.isEmpty()) {
            return;
        }
        List<UUID> ids = attachmentIds.stream().distinct().toList();
        if (ids.size() > properties.maxFilesPerConversation()) {
            throw ApiException.conflict(ProblemCode.TOO_MANY_ATTACHMENTS,
                    "A conversation can have at most " + properties.maxFilesPerConversation() + " files");
        }
        List<AttachmentStore.AttachmentRow> files = attachments.ofPerson(personPrincipalId, ids);
        if (files.size() != ids.size()) {
            throw ApiException.validation("attachmentIds names a file that is not one of your conversations'");
        }
        Set<String> names = new HashSet<>();
        long bytes = 0;
        for (AttachmentStore.AttachmentRow file : files) {
            if (file.contentPurgedAt() != null) {
                throw ApiException.validation("attachmentIds names a file whose content was removed");
            }
            if (!names.add(file.fileName())) {
                throw ApiException.conflict(ProblemCode.DUPLICATE_ATTACHMENT_NAME, "Two of the files are named `"
                        + file.fileName() + "`: a conversation's files have names of their own");
            }
            bytes += file.sizeBytes();
        }
        if (bytes > properties.maxConversationSize().toBytes()) {
            throw ApiException.conflict(ProblemCode.TOO_MANY_ATTACHMENTS, "The files of a conversation can have at "
                    + "most " + size(properties.maxConversationSize().toBytes()) + " in all");
        }
        for (AttachmentStore.AttachmentRow file : files) {
            AttachmentStore.AttachmentRow copy = new AttachmentStore.AttachmentRow(Ids.newId(), companyId,
                    conversationId, file.fileName(), file.contentType(), file.sizeBytes(), file.sha256(), file.note(),
                    userId, null, null, time.now(), null);
            attachments.copy(file.id(), copy);
            auditLog.record(AuditRecord.company(companyId, AuditAction.ATTACHMENT_UPLOADED, CurrentActor.actor())
                .target(TARGET, copy.id())
                .detail("conversationId", conversationId.toString())
                .detail("sha256", copy.sha256())
                .detail("sizeBytes", copy.sizeBytes())
                .detail("contentType", copy.contentType())
                .detail("scanned", true)
                .detail("copiedFromAttachmentId", file.id().toString())
                .build());
        }
    }

    // ---- Files a client holds for a start (ADR-0084) ---------------------------------------------------------------

    /** How long a held file waits for its start. */
    static final Duration STAGED_LIFETIME = Duration.ofHours(1);

    /**
     * {@link StagedFiles#stage}: the caller, who may start conversations, holds a file
     * for a start, checked as an upload is and scanned; nobody acknowledges a credential format here. Their expired
     * files go first; their unexpired ones and this one stay within a conversation's limits.
     */
    @Transactional
    public StagedFiles.StagedFile stage(UUID companyId, String originalName,
            @Nullable String declaredType, byte[] content) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.WORK);
        UUID userId = CurrentActor.requireUser().id();
        UUID person = principals.ofMember(companyId, userId).map(PrincipalInfo::id)
            .orElseThrow(AttachmentService::notFound);
        String fileName = fileName(originalName);
        String contentType = contentType(declaredType);
        if (content.length > properties.maxFileSize().toBytes()) {
            throw tooLarge();
        }
        // The person's lock before the count: parallel holds one short of the limit hold one.
        staged.lockHolds(person);
        Instant now = time.now();
        staged.deleteExpired(person, now);
        StagedFileStore.Totals held = staged.totals(person, now);
        if (held.count() >= properties.maxFilesPerConversation()) {
            throw ApiException.conflict(ProblemCode.TOO_MANY_ATTACHMENTS, "You hold "
                    + properties.maxFilesPerConversation() + " files for a start already, a conversation's most: start "
                    + "the conversation, or wait an hour for them to expire");
        }
        if (held.bytes() + content.length > properties.maxConversationSize().toBytes()) {
            throw ApiException.conflict(ProblemCode.TOO_MANY_ATTACHMENTS, "The files you hold for a start can have at "
                    + "most " + size(properties.maxConversationSize().toBytes()) + " in all, as a conversation's");
        }
        checkScan(vault.scan(companyId, content), access, false);
        StagedFileStore.StagedRow row = new StagedFileStore.StagedRow(Ids.newId(), companyId, person, userId, fileName,
                contentType, content.length, sha256(content), now, now.plus(STAGED_LIFETIME));
        staged.insert(row, content);
        return new StagedFiles.StagedFile(row.id(), row.fileName(),
                row.contentType(), row.sizeBytes(), row.expiresAt());
    }

    /**
     * {@link StagedFiles#release}: the caller lets go of the files among {@code ids} they still hold, a start that was
     * answered as a replay or refused not having taken them. Deleted within the caller's own principal: a file a start
     * moved is gone already, and another person's ids delete nothing. No lock: a hold's count under the person's lock
     * only shrinks by it. Not audited, as a hold is not.
     */
    @Transactional
    public void release(UUID companyId, List<UUID> ids) {
        companyAccess.require(companyId, CompanyPermission.WORK);
        UUID userId = CurrentActor.requireUser().id();
        UUID person = principals.ofMember(companyId, userId).map(PrincipalInfo::id)
            .orElseThrow(AttachmentService::notFound);
        staged.delete(person, ids.stream().distinct().toList());
    }

    /**
     * A client's start with the files the person held for it (ADR-0084): moved into the new conversation before its
     * first turn, as files the person added, after the files of {@code attachmentIds}, within the conversation's limits;
     * scanned when they were held. A file that is not one the person holds, or that expired, is {@code 400
     * VALIDATION_FAILED}; two of one name {@code 409 DUPLICATE_ATTACHMENT_NAME}. In the starting transaction, after the
     * conversation's row; audited as {@code attachment.uploaded} with {@code stagedFileId}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void carryStaged(UUID companyId, UUID conversationId, List<UUID> stagedFileIds, UUID personPrincipalId,
            UUID userId) {
        if (stagedFileIds.isEmpty()) {
            return;
        }
        List<UUID> ids = stagedFileIds.stream().distinct().toList();
        List<StagedFileStore.StagedRow> files = staged.ofPerson(personPrincipalId, ids, time.now());
        if (files.size() != ids.size()) {
            throw ApiException.validation("fileIds names a file you do not hold, or one that expired: a file is held "
                    + "for an hour");
        }
        AttachmentStore.Totals totals = attachments.totals(conversationId);
        long bytes = totals.bytes();
        Set<String> names = new HashSet<>();
        for (StagedFileStore.StagedRow file : files) {
            if (!names.add(file.fileName()) || attachments.exists(conversationId, file.fileName())) {
                throw duplicateName();
            }
            bytes += file.sizeBytes();
        }
        if (totals.count() + files.size() > properties.maxFilesPerConversation()) {
            throw ApiException.conflict(ProblemCode.TOO_MANY_ATTACHMENTS,
                    "A conversation can have at most " + properties.maxFilesPerConversation() + " files");
        }
        if (bytes > properties.maxConversationSize().toBytes()) {
            throw ApiException.conflict(ProblemCode.TOO_MANY_ATTACHMENTS, "The files of a conversation can have at "
                    + "most " + size(properties.maxConversationSize().toBytes()) + " in all");
        }
        for (StagedFileStore.StagedRow file : files) {
            AttachmentStore.AttachmentRow row = new AttachmentStore.AttachmentRow(Ids.newId(), companyId,
                    conversationId, file.fileName(), file.contentType(), file.sizeBytes(), file.sha256(), null, userId,
                    null, null, time.now(), null);
            staged.moveInto(file, row);
            auditLog.record(AuditRecord.company(companyId, AuditAction.ATTACHMENT_UPLOADED, CurrentActor.actor())
                .target(TARGET, row.id())
                .detail("conversationId", conversationId.toString())
                .detail("sha256", row.sha256())
                .detail("sizeBytes", row.sizeBytes())
                .detail("contentType", row.contentType())
                .detail("scanned", true)
                .detail("stagedFileId", file.id().toString())
                .build());
        }
    }

    // ---- For part 1 and the prompt -------------------------------------------------------------------------------

    /** The conversation's files as the API shows them, oldest first ({@link ConversationOutputs#attachments}). */
    @Transactional(readOnly = true)
    public List<Attachment> attachments(UUID companyId, UUID conversationId) {
        return toDtos(attachments.list(conversationId));
    }

    /** The conversation's files, oldest first, for a turn's {@code /attachments}. */
    @Transactional(readOnly = true)
    public List<AttachmentStore.AttachmentRow> rows(UUID conversationId) {
        return attachments.list(conversationId);
    }

    /** The content of a file of the conversation, for a runner host's download. */
    @Transactional(readOnly = true)
    public java.util.Optional<byte[]> content(UUID conversationId, UUID attachmentId) {
        return attachments.content(conversationId, attachmentId);
    }

    // ---- The agent's files ----------------------------------------------------------------------------------------

    /**
     * Stores a file the agent of an active run saved, in the caller's transaction and the run's tenant context: the
     * same name, size, limit and scan rules as a person's file, but nobody acknowledges a credential format for an
     * agent, and the answer is written for the agent instead of thrown. Audited as {@code attachment.uploaded} with the
     * agent as actor.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentUploadResult storeForAgent(ActiveRun run, AgentUpload upload) {
        AgentInfo agent = agents.find(run.companyId(), run.agentId()).filter(AgentInfo::live).orElse(null);
        AgentUploadResult refused = conversationForAgent(attachments.state(run.conversationId()).orElse(null), agent);
        if (refused != null) {
            return refused;
        }
        String fileName;
        try {
            fileName = upload.relativePath() ? pathName(upload.fileName()) : fileName(upload.fileName());
        }
        catch (ApiException ex) {
            return AgentUploadResult.rejected(INVALID_REQUEST, ex.getMessage()
                    + (upload.relativePath() ? ". Pass a path relative to /output." : ". Pass a plain file name.")
                    + " Nothing was stored.");
        }
        String note = upload.note() != null ? upload.note().strip() : "";
        if (note.codePointCount(0, note.length()) > MAX_NOTE_LENGTH) {
            return AgentUploadResult.rejected(INVALID_REQUEST, "The note can have at most " + MAX_NOTE_LENGTH
                    + " characters. Nothing was stored.");
        }
        byte[] content = upload.content();
        if (content.length > properties.maxFileSize().toBytes()) {
            return AgentUploadResult.rejected(ProblemCode.ATTACHMENT_TOO_LARGE.name(), "A file can have at most "
                    + size(properties.maxFileSize().toBytes()) + ". Nothing was stored.");
        }
        refused = roomForAgent(run.conversationId(), fileName, content.length);
        if (refused != null) {
            return refused;
        }
        // The scan before the lock: however long it takes, it holds up no other request of the conversation.
        ScanReport scan = vault.scan(run.companyId(), content);
        if (!scan.vaultSecrets().isEmpty() || !scan.formats().isEmpty()) {
            return AgentUploadResult.rejected(ProblemCode.CREDENTIAL_FOUND.name(), "The file contains a credential, "
                    + "so Tinfirm does not store it. Remove the credential from the file and try again. Nothing was "
                    + "stored.");
        }
        if (scan.unscannable() != null) {
            return AgentUploadResult.rejected(ProblemCode.ATTACHMENT_NOT_SCANNABLE.name(), "The file cannot be "
                    + "scanned for credentials: it contains " + scan.unscannable() + ". Nothing was stored.");
        }
        // Under the conversation's lock, what may have changed meanwhile, so that the limits hold.
        refused = conversationForAgent(attachments.lockConversation(run.conversationId()).orElse(null), agent);
        if (refused == null) {
            refused = roomForAgent(run.conversationId(), fileName, content.length);
        }
        if (refused != null) {
            return refused;
        }
        AttachmentStore.AttachmentRow row = new AttachmentStore.AttachmentRow(Ids.newId(), run.companyId(),
                run.conversationId(), fileName, contentType(upload.contentType()), content.length, sha256(content),
                note.isEmpty() ? null : note, null, agent.id(), run.runId(), time.now(), null);
        attachments.insert(row, content);
        auditLog.record(AuditRecord.company(run.companyId(), AuditAction.ATTACHMENT_UPLOADED, Actor.agent(agent.id()))
            .target(TARGET, row.id())
            .detail("conversationId", run.conversationId().toString())
            .detail("sha256", row.sha256())
            .detail("sizeBytes", row.sizeBytes())
            .detail("contentType", row.contentType())
            .detail("scanned", true)
            .detail("runId", run.runId().toString())
            .build());
        return AgentUploadResult.stored(row.id(), fileName, "Stored `" + fileName + "`.");
    }

    /** The refusal of an agent's file for its conversation's state (null: gone) and its agent; null when it may. */
    private static @Nullable AgentUploadResult conversationForAgent(@Nullable String state,
            @Nullable AgentInfo agent) {
        if (state == null || agent == null) {
            return AgentUploadResult.rejected(INVALID_REQUEST, "The conversation or its agent is gone. Nothing was "
                    + "stored.");
        }
        if (!ConversationInfo.State.OPEN.name().equals(state)) {
            return AgentUploadResult.rejected(ProblemCode.CONVERSATION_ENDED.name(),
                    "The conversation has ended. Nothing was stored.");
        }
        return null;
    }

    /** The refusal of an agent's file the conversation has no room or no free name for; null when it has. */
    private @Nullable AgentUploadResult roomForAgent(UUID conversationId, String fileName, long size) {
        if (attachments.exists(conversationId, fileName)) {
            return AgentUploadResult.rejected(ProblemCode.DUPLICATE_ATTACHMENT_NAME.name(), "The conversation "
                    + "already has a file named `" + fileName + "`. Choose another name. Nothing was stored.");
        }
        AttachmentStore.Totals totals = attachments.totals(conversationId);
        if (totals.count() >= properties.maxFilesPerConversation()
                || totals.bytes() + size > properties.maxConversationSize().toBytes()) {
            return AgentUploadResult.rejected(ProblemCode.TOO_MANY_ATTACHMENTS.name(), "A conversation can have at "
                    + "most " + properties.maxFilesPerConversation() + " files and "
                    + size(properties.maxConversationSize().toBytes()) + " of files in all, and this file does not "
                    + "fit. Nothing was stored.");
        }
        return null;
    }

    // ---- Rules ----------------------------------------------------------------------------------------------------

    /** The caller, their access, and the conversation they see. */
    private record Seen(CompanyAccess access, UUID userId, ConversationInfo conversation) {
    }

    /** The conversation, when the caller sees it in the app (§5.10, I17); else 404, as for another company's. */
    private Seen seen(UUID companyId, UUID conversationId) {
        return seen(companyId, conversationId, ConversationView.APP);
    }

    /** The conversation, when the caller sees it in the view (§5.10, I17, G9); else 404. */
    private Seen seen(UUID companyId, UUID conversationId, ConversationView view) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
        UUID userId = CurrentActor.requireUser().id();
        ConversationInfo conversation = conversations.find(companyId, conversationId)
            .filter(found -> conversations.maySee(access, userId, found, view))
            .orElseThrow(AttachmentService::conversationNotFound);
        return new Seen(access, userId, conversation);
    }

    /** Only the conversation's person adds and deletes its files (I15): OWNER sees it, and gets 409. */
    private void requirePerson(Seen seen) {
        if (!conversations.isPerson(seen.access(), seen.userId(), seen.conversation())) {
            throw ApiException.conflict(ProblemCode.CONVERSATION_BOUND,
                    "Only the person of the conversation adds and deletes its files");
        }
    }

    private void lockOpen(UUID conversationId) {
        requireOpen(attachments.lockConversation(conversationId).orElseThrow(AttachmentService::conversationNotFound));
    }

    private static void requireOpen(String state) {
        if (!ConversationInfo.State.OPEN.name().equals(state)) {
            throw ApiException.conflict(ProblemCode.CONVERSATION_ENDED, "The conversation has ended");
        }
    }

    private void requireRoom(UUID conversationId, long size) {
        AttachmentStore.Totals totals = attachments.totals(conversationId);
        if (totals.count() >= properties.maxFilesPerConversation()) {
            throw ApiException.conflict(ProblemCode.TOO_MANY_ATTACHMENTS,
                    "A conversation can have at most " + properties.maxFilesPerConversation() + " files");
        }
        if (totals.bytes() + size > properties.maxConversationSize().toBytes()) {
            throw ApiException.conflict(ProblemCode.TOO_MANY_ATTACHMENTS, "The files of a conversation can have at "
                    + "most " + size(properties.maxConversationSize().toBytes()) + " in all");
        }
    }

    /** Enforces the scan's result; answers the credential formats that were acknowledged, empty when none matched. */
    private static List<String> checkScan(ScanReport scan, CompanyAccess access, boolean acknowledge) {
        if (!scan.vaultSecrets().isEmpty()) {
            List<CredentialMatch> matches = new ArrayList<>();
            scan.vaultSecrets().forEach(name -> matches.add(new CredentialMatch(CredentialMatch.KindEnum.VAULT_SECRET,
                    name)));
            scan.formats().forEach(name -> matches.add(new CredentialMatch(CredentialMatch.KindEnum.FORMAT, name)));
            throw ApiException.unprocessable(ProblemCode.CREDENTIAL_FOUND,
                    "The file contains the value of the vault secret " + String.join(", ", scan.vaultSecrets())
                            + ". Remove it from the file, or rotate the secret.",
                    Map.of("credentialMatches", matches));
        }
        if (scan.unscannable() != null) {
            throw ApiException.unprocessable(ProblemCode.ATTACHMENT_NOT_SCANNABLE,
                    "The file cannot be scanned for credentials: it contains " + scan.unscannable() + ".", Map.of());
        }
        if (scan.formats().isEmpty()) {
            return List.of();
        }
        if (!acknowledge) {
            List<CredentialMatch> matches = scan.formats()
                .stream()
                .map(name -> new CredentialMatch(CredentialMatch.KindEnum.FORMAT, name))
                .toList();
            throw ApiException.unprocessable(ProblemCode.CREDENTIAL_FOUND,
                    "The file looks like it contains a credential: " + String.join(", ", scan.formats())
                            + ". An owner or admin can store it anyway.",
                    Map.of("credentialMatches", matches));
        }
        if (!access.covers(CompanyPermission.MANAGE_SECRETS)) {
            throw ApiException.forbidden("Only an owner or admin can store a file that looks like a credential");
        }
        return scan.formats();
    }

    /** A file's DTO, as the list shows it. */
    Attachment toDto(AttachmentStore.AttachmentRow row) {
        return toDtos(List.of(row)).getFirst();
    }

    private List<Attachment> toDtos(List<AttachmentStore.AttachmentRow> rows) {
        Set<UUID> users = new HashSet<>();
        Set<UUID> agentIds = new HashSet<>();
        for (AttachmentStore.AttachmentRow row : rows) {
            if (row.uploadedByUserId() != null) {
                users.add(row.uploadedByUserId());
            }
            if (row.uploadedByAgentId() != null) {
                agentIds.add(row.uploadedByAgentId());
            }
        }
        Map<UUID, String> userNamesById = users.isEmpty() ? Map.of() : userNames.displayNames(users);
        Map<UUID, String> agentNamesById = agentIds.isEmpty() ? Map.of() : agentNames.names(agentIds);
        return rows.stream().map(row -> {
            String uploadedBy = row.uploadedByAgentId() != null
                    ? agentNamesById.getOrDefault(row.uploadedByAgentId(), "agent")
                    : row.uploadedByUserId() != null
                            ? userNamesById.getOrDefault(row.uploadedByUserId(), "deleted user")
                            : "deleted user";
            return new Attachment(row.id(), row.conversationId(), row.fileName(), row.contentType(), row.sizeBytes(),
                    row.sha256(), uploadedBy, AttachmentStore.ts(row.createdAt()))
                .note(row.note())
                .uploadedByUserId(row.uploadedByUserId())
                .agentId(row.uploadedByAgentId())
                .runId(row.runId())
                .outputId(row.outputId())
                .contentPurgedAt(row.contentPurgedAt() != null ? AttachmentStore.ts(row.contentPurgedAt()) : null);
        }).toList();
    }

    private static @Nullable String note(@Nullable String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String stripped = note.strip();
        if (stripped.codePointCount(0, stripped.length()) > MAX_NOTE_LENGTH) {
            throw ApiException.validation("The note can have at most " + MAX_NOTE_LENGTH + " characters");
        }
        return stripped;
    }

    /**
     * The file name as the agent will see it in {@code /attachments}: the last path segment of the name the browser
     * sent, in Unicode NFC, without control or bidirectional formatting characters.
     */
    static String fileName(@Nullable String original) {
        String name = original != null ? original : "";
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFC).strip();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw ApiException.validation("The file needs a name");
        }
        if (characters(name) > MAX_FILE_NAME_LENGTH) {
            throw ApiException.validation("The file name can have at most " + MAX_FILE_NAME_LENGTH + " characters");
        }
        if (unsafe(name)) {
            throw ApiException.validation("The file name contains control or formatting characters");
        }
        return name;
    }

    /**
     * The name of a document from {@code /output}: its path relative to the folder, such as {@code reports/q3.md}, in
     * Unicode NFC, without control or bidirectional formatting characters or a backslash; segments separated by
     * {@code /}, none of them empty, {@code .} or {@code ..} or longer than {@value #MAX_FILE_NAME_LENGTH} characters,
     * at most {@value #MAX_PATH_NAME_LENGTH} characters in all. A single segment is a plain file name.
     */
    static String pathName(@Nullable String original) {
        String name = Normalizer.normalize(original != null ? original : "", Normalizer.Form.NFC).strip();
        if (name.isEmpty()) {
            throw ApiException.validation("The file needs a name");
        }
        if (characters(name) > MAX_PATH_NAME_LENGTH) {
            throw ApiException.validation("The path can have at most " + MAX_PATH_NAME_LENGTH + " characters");
        }
        if (name.indexOf('\\') >= 0 || name.startsWith("/")) {
            throw ApiException.validation("The path must be relative, with / between its parts");
        }
        if (unsafe(name)) {
            throw ApiException.validation("The path contains control or formatting characters");
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw ApiException.validation("The path has an empty, . or .. part");
            }
            if (characters(segment) > MAX_FILE_NAME_LENGTH || !segment.equals(segment.strip())) {
                throw ApiException.validation("Each part of the path can have at most " + MAX_FILE_NAME_LENGTH
                        + " characters, without spaces around it");
            }
        }
        return name;
    }

    /**
     * Characters as runner protocol v2 and PostgreSQL count them ({@code length()}, and {@code attachment_name_ck}'s
     * segments): code points, not UTF-16 units. A name's mounted form is cut to 255 bytes apart ({@code OutputPrompt}).
     */
    static int characters(String text) {
        return text.codePointCount(0, text.length());
    }

    private static boolean unsafe(String name) {
        return name.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT);
    }

    /** {@code type/subtype} of the part's content type, or {@code application/octet-stream}. */
    static String contentType(@Nullable String declared) {
        if (declared == null || declared.isBlank()) {
            return DEFAULT_CONTENT_TYPE;
        }
        try {
            MediaType type = MediaType.parseMediaType(declared);
            if (type.isWildcardType() || type.isWildcardSubtype()) {
                return DEFAULT_CONTENT_TYPE;
            }
            String value = type.getType() + "/" + type.getSubtype();
            return value.length() <= MAX_FILE_NAME_LENGTH ? value : DEFAULT_CONTENT_TYPE;
        }
        catch (InvalidMediaTypeException ex) {
            return DEFAULT_CONTENT_TYPE;
        }
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A size as people read it: bytes, KB or MB (of 1024). */
    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }
        if (bytes < 1024 * 1024) {
            return (bytes + 1023) / 1024 + " KB";
        }
        long tenths = Math.round(bytes * 10.0 / (1024 * 1024));
        return tenths % 10 == 0 ? tenths / 10 + " MB" : tenths / 10 + "." + tenths % 10 + " MB";
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        }
        catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private ApiException tooLarge() {
        return ApiException.payloadTooLarge(ProblemCode.ATTACHMENT_TOO_LARGE,
                "A file can have at most " + size(properties.maxFileSize().toBytes()));
    }

    private static ApiException duplicateName() {
        return ApiException.conflict(ProblemCode.DUPLICATE_ATTACHMENT_NAME,
                "The conversation already has a file with this name");
    }

    static ApiException notFound() {
        return ApiException.notFound("Attachment not found");
    }

    static ApiException conversationNotFound() {
        return ApiException.notFound("Conversation not found");
    }

    /** Whether the row is the agent's file of this run in this conversation. */
    static boolean ofRun(AttachmentStore.AttachmentRow row, UUID conversationId, UUID runId) {
        return row.conversationId().equals(conversationId) && Objects.equals(row.runId(), runId);
    }
}
