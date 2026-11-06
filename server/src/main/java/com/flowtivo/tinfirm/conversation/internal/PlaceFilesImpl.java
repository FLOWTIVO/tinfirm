package com.flowtivo.tinfirm.conversation.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.PlaceFiles;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.vault.ScanReport;
import com.flowtivo.tinfirm.vault.VaultService;

/**
 * {@link PlaceFiles} (the package 5 contract, §5.10): a file sent in a chat, by the conversation's person, under
 * package 3's rules for a person's file ({@link AttachmentService}'s name, size, limits and scan), but a credential
 * format is never acknowledged from a place, and a refusal is answered, not thrown. Audited as
 * {@code attachment.uploaded} with {@code via}.
 */
@Component
class PlaceFilesImpl implements PlaceFiles {

    private final AttachmentStore attachments;

    private final ConversationStore conversations;

    private final AttachmentProperties properties;

    private final VaultService vault;

    private final Principals principals;

    private final AuditLog auditLog;

    private final TimeSource time;

    PlaceFilesImpl(AttachmentStore attachments, ConversationStore conversations, AttachmentProperties properties,
            VaultService vault, Principals principals, AuditLog auditLog, TimeSource time) {
        this.attachments = attachments;
        this.conversations = conversations;
        this.properties = properties;
        this.vault = vault;
        this.principals = principals;
        this.auditLog = auditLog;
        this.time = time;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Added add(UUID companyId, UUID conversationId, PlaceFile file, String via) {
        ConversationInfo conversation = conversations.lock(companyId, conversationId).orElseThrow().info();
        if (!conversation.open()) {
            return new Refused(ProblemCode.CONVERSATION_ENDED.name(), "The conversation ended");
        }
        if (conversation.personPrincipalId() == null) {
            return new Refused(ProblemCode.PLACE_IS_EXTERNAL.name(), "A trigger's conversation takes no files here");
        }
        String fileName;
        try {
            fileName = AttachmentService.fileName(file.name());
        }
        catch (ApiException ex) {
            return new Refused(ProblemCode.VALIDATION_FAILED.name(), "Its name is not usable: " + ex.getMessage());
        }
        String note = file.note() != null && !file.note().isBlank() ? file.note().strip() : null;
        if (note != null && note.codePointCount(0, note.length()) > AttachmentService.MAX_NOTE_LENGTH) {
            note = note.substring(0, note.offsetByCodePoints(0, AttachmentService.MAX_NOTE_LENGTH));
        }
        byte[] content = file.content();
        long maxFileSize = properties.maxFileSize().toBytes();
        if (content.length > maxFileSize) {
            return new Refused(ProblemCode.ATTACHMENT_TOO_LARGE.name(), "It is larger than "
                    + AttachmentService.size(maxFileSize));
        }
        if (attachments.exists(conversationId, fileName)) {
            return new Refused(ProblemCode.DUPLICATE_ATTACHMENT_NAME.name(), "The conversation has a file of this "
                    + "name already");
        }
        AttachmentStore.Totals totals = attachments.totals(conversationId);
        if (totals.count() >= properties.maxFilesPerConversation()
                || totals.bytes() + content.length > properties.maxConversationSize().toBytes()) {
            return new Refused(ProblemCode.TOO_MANY_ATTACHMENTS.name(), "The conversation holds as many files as it "
                    + "may");
        }
        ScanReport scan = vault.scan(companyId, content);
        if (!scan.vaultSecrets().isEmpty() || !scan.formats().isEmpty()) {
            return new Refused(ProblemCode.CREDENTIAL_FOUND.name(), "It contains a credential, which Tinfirm does "
                    + "not store");
        }
        if (scan.unscannable() != null) {
            return new Refused(ProblemCode.ATTACHMENT_NOT_SCANNABLE.name(), "It cannot be scanned for credentials");
        }
        PrincipalInfo person = principals.find(companyId, conversation.personPrincipalId()).orElseThrow();
        AttachmentStore.AttachmentRow row = new AttachmentStore.AttachmentRow(Ids.newId(), companyId, conversationId,
                fileName, AttachmentService.contentType(file.contentType()), content.length,
                AttachmentService.sha256(content), note, person.userId(), null, null, time.now(), null);
        attachments.insert(row, content);
        auditLog.record(AuditRecord.company(companyId, AuditAction.ATTACHMENT_UPLOADED, CurrentActor.actor())
            .target(AttachmentService.TARGET, row.id())
            .detail("conversationId", conversationId.toString())
            .detail("sha256", row.sha256())
            .detail("sizeBytes", row.sizeBytes())
            .detail("contentType", row.contentType())
            .detail("scanned", true)
            .detail("via", via)
            .build());
        return new Stored(row.id(), fileName);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Content> content(UUID companyId, UUID conversationId, UUID attachmentId) {
        return attachments.find(conversationId, attachmentId)
            .filter(row -> row.companyId().equals(companyId))
            .flatMap(row -> attachments.content(conversationId, attachmentId)
                .map(bytes -> new Content(row.fileName(), row.contentType(), bytes)));
    }
}
