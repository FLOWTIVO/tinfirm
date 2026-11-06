package com.flowtivo.tinfirm.conversation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Files a person's client holds for a start (ADR-0084): an email's attachments, which reach Tinfirm before the
 * conversation about the email exists. A file is checked and scanned as a conversation's file is (ADR-0014: its name,
 * at most the largest file, the value of a vault secret or a credential format refused, content that cannot be scanned
 * refused; nobody acknowledges a credential format here), held for an hour for the caller only, and moved into the
 * conversation by {@link ConversationCommands#startFromClient(UUID, com.flowtivo.tinfirm.shared.api.model.ConversationStartRequest, int, String, java.util.List)}
 * before its first turn. A person holds at most a conversation's files at once, by count and by bytes.
 */
public interface StagedFiles {

    /** A held file: its id, its name and type as a conversation's file would have them, its size, until when. */
    record StagedFile(UUID id, String fileName, String contentType, long sizeBytes, Instant expiresAt) {
    }

    /**
     * Holds a file for the caller, who may start conversations in the company ({@code WORK}). Refusals are the upload's:
     * {@code 400 VALIDATION_FAILED} for a name it cannot take, {@code 413 ATTACHMENT_TOO_LARGE}, {@code 409
     * TOO_MANY_ATTACHMENTS} when the caller's held files would exceed a conversation's limits, {@code 422
     * CREDENTIAL_FOUND} and {@code 422 ATTACHMENT_NOT_SCANNABLE}. Not audited: the start that moves it audits it as
     * {@code attachment.uploaded}.
     */
    StagedFile stage(UUID companyId, String fileName, @Nullable String contentType, byte[] content);

    /**
     * Lets go of files the caller holds that a start did not take: those of {@code ids} that are theirs, now, so that
     * a start answered as a replay of an earlier one (which had its own files), or refused, leaves nothing on the
     * caller's limits for an hour. A file a start moved into its conversation is gone already, and another person's
     * ids are not touched. Not audited.
     */
    void release(UUID companyId, List<UUID> ids);

    /** The largest file a person may hold, as a conversation's largest ({@code TINFIRM_ATTACHMENT_MAX_FILE_SIZE}). */
    long maxFileBytes();
}
