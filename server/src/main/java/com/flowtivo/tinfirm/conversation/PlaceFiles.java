package com.flowtivo.tinfirm.conversation;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Attachment intake from a place (the package 5 contract, §2.2 and §5.10): a file sent in a chat becomes the
 * conversation's file under package 3's limits and scan; a credential format is never acknowledged from a place
 * ({@code CREDENTIAL_FOUND}). Audited as {@code attachment.uploaded} with {@code via}. Runs in the caller's
 * transaction and tenant context, under the conversation's lock; files added in a conversation's starting transaction
 * are its first turn's.
 */
public interface PlaceFiles {

    record PlaceFile(String name, @Nullable String contentType, byte[] content, @Nullable String note) {
    }

    /** What became of a file. */
    sealed interface Added permits Stored, Refused {
    }

    record Stored(UUID attachmentId, String fileName) implements Added {
    }

    /** Left out, with the code of package 3's refusal and a message for the person. */
    record Refused(String code, String message) implements Added {
    }

    /**
     * @param via the place, {@code SLACK} or {@code TEAMS}
     */
    Added add(UUID companyId, UUID conversationId, PlaceFile file, String via);

    /** A document of the conversation, to upload it to the place. */
    record Content(String fileName, String contentType, byte[] bytes) {
    }

    Optional<Content> content(UUID companyId, UUID conversationId, UUID attachmentId);
}
