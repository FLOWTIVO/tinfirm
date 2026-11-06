package com.flowtivo.tinfirm.conversation;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.run.RunPurge;

/**
 * Purging a conversation's content for retention and erasure (the package 6 contract, §2.3, §3.7, §5.9): what the
 * governance module decides is due, this module removes, row by row with each row's marker, and the tombstone stays
 * (the conversation's key, agent, person, place, subject key, dates, turn count and ended reason; decisions' kinds and
 * outcomes; outputs' kinds and links; files' names, sizes and hashes). This module never depends on the governance
 * module: what an open legal hold covers comes in as {@link Held}. Runs in the caller's transaction and tenant context;
 * a transaction that purges locks the conversations first ({@link #lock}, CONVENTIONS §10, the lock order).
 * <p>
 * Each purge has its count, which selects the same conversations and rows by the same predicates and changes nothing:
 * the previews of retention and erasure answer with them, so that a preview never drifts from what the purge removes.
 */
public interface ConversationPurge {

    /** The places whose content is a chat's or a webhook's (the {@code chat_content_days} period). */
    Set<String> CHAT_PLACES = Set.of("CHAT_THREAD", "WEBHOOK");

    /**
     * What retention purges, by period (§3.7): {@code CHAT} a chat's or a webhook's messages and decisions' texts;
     * {@code CONTENT} every message, the title, decisions' texts, outputs' titles and the latest turn's result;
     * {@code FILES} files' content.
     */
    enum Kind {
        CHAT, CONTENT, FILES
    }

    /**
     * What the open legal holds cover (G16): whole conversations, and people (their personal conversations, the
     * conversations of the triggers they authored, which act for them and have no person (the package 4 contract,
     * §3.4), and what they said, added and decided in others).
     */
    record Held(Set<UUID> conversationIds, Set<UUID> principalIds) {

        public static final Held NONE = new Held(Set.of(), Set.of());

        public Held {
            conversationIds = Set.copyOf(conversationIds);
            principalIds = Set.copyOf(principalIds);
        }
    }

    /** An ended conversation due for a kind, by its end: the sweep's keyset. */
    record Due(UUID conversationId, Instant endedAt) {
    }

    /** How much a purge removed. */
    record Purged(long conversations, long messages, long decisions, long outputs, long files) {

        public static final Purged NONE = new Purged(0, 0, 0, 0, 0);

        public Purged plus(Purged other) {
            return new Purged(conversations + other.conversations, messages + other.messages,
                    decisions + other.decisions, outputs + other.outputs, files + other.files);
        }

        public boolean any() {
            return conversations + messages + decisions + outputs + files > 0;
        }
    }

    /** A personal conversation of a person, for an erasure. */
    record Personal(UUID id, String key, boolean open) {
    }

    /** An open conversation, by key, place and start; never its title (open point 25). */
    record Open(UUID id, String key, String place, Instant createdAt) {
    }

    /**
     * The ended conversations no hold covers that ended before the cutoff and still hold something of the kind, by
     * their end, after {@code after} when given. {@code CHAT} takes only the chats' and webhooks' places.
     */
    List<Due> due(Kind kind, Instant endedBefore, Held held, @Nullable Due after, int limit);

    /** The conversations {@link #due} answers, as SQL for a count of their runs ({@link RunPurge#countTextsIn}). */
    RunPurge.ConversationSelection dueSelection(Kind kind, Instant endedBefore, Held held);

    /**
     * What one sweep's purges would remove now ({@link #purge} of each conversation {@link #due} answers), each row
     * once, as the sweep removes it once: {@code CHAT} before {@code chatBefore}, then {@code CONTENT} before
     * {@code contentBefore} and {@code FILES} before {@code filesBefore} (null: kept). Changes nothing.
     */
    Purged countSweep(Instant chatBefore, @Nullable Instant contentBefore, @Nullable Instant filesBefore, Held held);

    /** Of these conversations, the ended ones no hold covers. */
    Set<UUID> endedUnheld(Collection<UUID> conversationIds, Held held);

    /**
     * The ended conversations no hold covers ({@link #endedUnheld}), as SQL for a count of their runs
     * ({@link RunPurge#countEventsKeptIn}).
     */
    RunPurge.ConversationSelection endedUnheldSelection(Held held);

    /** Locks the conversations, in the order of their ids. */
    void lock(Collection<UUID> conversationIds);

    /**
     * Purges what the kind names in the conversation, outside what a hold covers in it (a held person's messages,
     * decisions and files); {@code CONTENT} also marks the conversation's content removed, for its tombstone, and
     * deletes its tool calls' journal rows (package R).
     */
    Purged purge(UUID conversationId, Kind kind, Held held, Instant at);

    /**
     * Erases the conversation on request: every kind at once, and marks it erased. Its tool calls' journal rows (package
     * R) go with it.
     */
    Purged erase(UUID conversationId, Instant at);

    /** What {@link #erase} would remove now from these conversations, counted; changes nothing. */
    Purged countErase(Collection<UUID> conversationIds);

    /**
     * Deletes the journal rows of these runs' tool calls (package R, §3.2), with their events: the retention's purge of
     * run events and an erasure that purges them call it beside {@code RunPurge}. Answers how many rows went.
     */
    int purgeToolCalls(Collection<UUID> runIds);

    /**
     * Erases what the person said, added and decided in conversations that are not theirs, outside the excluded ones
     * (in an open one, a message's body is replaced by the marker at once).
     *
     * @param userId the person's user, whose files are theirs; null for none
     */
    Purged eraseAuthored(UUID principalId, @Nullable UUID userId, Set<UUID> excludedConversationIds, Instant at);

    /** What {@link #eraseAuthored} would remove now, counted; changes nothing. */
    Purged countAuthored(UUID principalId, @Nullable UUID userId, Set<UUID> excludedConversationIds);

    /** The person's personal conversations, oldest first. */
    List<Personal> personal(UUID principalId);

    /**
     * Removes the person's dismissals of the notices of their inbox (CONVENTIONS §15.7), which go with their erasure.
     * Answers how many went.
     */
    long eraseDismissals(UUID principalId);

    /** What {@link #eraseDismissals} would remove now, counted; changes nothing. */
    long countDismissals(UUID principalId);

    /**
     * The conversations that are not the person's where they said, added or decided something that is kept, a
     * trigger's conversations (which have no person) among them.
     */
    Set<UUID> authoredIn(UUID principalId, @Nullable UUID userId);

    /**
     * Of these conversations, those whose person (a trigger's conversation's: its author) is one of these principals,
     * or that are these.
     */
    Set<UUID> covered(Collection<UUID> conversationIds, Held held);

    /**
     * The open conversations started before their period's cutoff, oldest first, after {@code after} when given:
     * chats' and webhooks' before {@code chatBefore}, the others' before {@code contentBefore} (none when null).
     */
    List<Open> openOlder(Instant chatBefore, @Nullable Instant contentBefore, @Nullable Open after, int limit);

    /** How many {@link #openOlder} would answer in all. */
    long countOpenOlder(Instant chatBefore, @Nullable Instant contentBefore);
}
