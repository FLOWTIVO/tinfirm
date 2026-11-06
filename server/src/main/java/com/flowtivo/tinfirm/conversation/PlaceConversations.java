package com.flowtivo.tinfirm.conversation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A conversation held in a place outside the app (the package 5 contract, §2.2): what is said there, Tinfirm's notes,
 * its end, and what a turn produced for the place. Each method runs in the caller's transaction and tenant context and
 * locks the conversation first (CONVENTIONS §10); whether the speaker may speak there is the caller's rule (a direct
 * message's sender is linked to the conversation's person, C5).
 */
public interface PlaceConversations {

    /** Who said it: the person ({@code PERSON}), or someone of the place ({@code EXTERNAL}, package 4's kind). */
    enum Kind {
        PERSON, EXTERNAL
    }

    /**
     * A message of the place.
     *
     * @param authorPrincipalId the person for a {@code PERSON} message; for an {@code EXTERNAL} one the member linked
     *                          to its chat user, else null
     * @param externalId        the place's id of the message: stored once per conversation (C3)
     * @param externalAuthor    {@code {id, name, via: SLACK | TEAMS, verified}}
     * @param answersOpenQuestions whether it answers every open question (ADR-0033)
     */
    record PlaceMessage(Kind kind, @Nullable UUID authorPrincipalId, String externalId,
            Map<String, Object> externalAuthor, String body, boolean answersOpenQuestions) {
    }

    /** What became of a message. */
    sealed interface Received permits Stored, Duplicate, Ended {
    }

    /**
     * Stored: it answered {@code answered} open questions, and started the run when one started (a {@code DECIDED} turn
     * when it answered, else {@code MESSAGE}); else it waits for the next turn.
     */
    record Stored(UUID messageId, int answered, @Nullable UUID runId) implements Received {
    }

    /** The conversation holds a message of this id already (C3); nothing changed. */
    record Duplicate(UUID messageId) implements Received {
    }

    /** The conversation ended and takes nothing (C8). */
    record Ended() implements Received {
    }

    /** The external-message entry point. */
    Received receive(UUID companyId, UUID conversationId, PlaceMessage message);

    /** Tinfirm's note in the conversation (a {@code SYSTEM} message): its id. */
    UUID system(UUID companyId, UUID conversationId, String body);

    /**
     * Ends an open conversation for good, with the reason: the person's End button ({@code USER}), Teams' next
     * direct-message conversation ({@code SUPERSEDED}), its chat system removed ({@code SYSTEM_REMOVED}). Audited as
     * {@code conversation.ended} by the current actor. False when it had ended already.
     */
    boolean end(UUID companyId, UUID conversationId, ConversationInfo.EndedReason reason, @Nullable UUID endedByUserId);

    /**
     * What a turn produced, for the place: its end, the {@code AGENT} message it posted (null for a turn that ended on
     * its own questions, ADR-0036), its failure, and the outputs it made.
     */
    record TurnResult(UUID conversationId, UUID runId, String status, @Nullable String agentMessage,
            @Nullable String failureClass, @Nullable String failureMessage, List<Output> outputs) {
    }

    /**
     * An output of a turn: {@code DOCUMENT} with its file, {@code LINK} and {@code CHANGE_REQUEST} with their link.
     */
    record Output(UUID id, String kind, String title, @Nullable String url, @Nullable UUID attachmentId,
            @Nullable Long sizeBytes) {
    }

    Optional<TurnResult> result(UUID companyId, UUID runId);

    /** The conversation's place reference ({@code place_ref}), as text values. */
    Optional<Map<String, String>> placeRef(UUID companyId, UUID conversationId);

    /** The conversation of a chat thread, open or ended (C8: at most one). */
    Optional<ConversationInfo> ofThread(UUID companyId, UUID systemId, String channelId, String threadId);

    /** The person's open conversations held in a chat thread of the system, oldest first. */
    List<ConversationInfo> openInChat(UUID companyId, UUID personPrincipalId, UUID systemId);

    /** Every open conversation held in a chat thread of the system, for its removal. */
    List<UUID> openOnSystem(UUID companyId, UUID systemId);
}
