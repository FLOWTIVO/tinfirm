package com.flowtivo.tinfirm.agent;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.security.Actor;

/**
 * Agent memory (ADR-0063, CONVENTIONS §15.18): the notes an agent keeps for the owner of its settings, a person or a
 * trigger, on that owner's settings row. Every method runs in the caller's transaction and tenant context; who may
 * read or change a memory is the caller's check (the agent's people for a person's, the trigger's audience for a
 * trigger's). The tiers that reach a conversation ({@link #forPrompt}, {@link #aboutSystem}, {@link #recall}) read
 * active notes only and mark what they show as used.
 */
public interface AgentMemory {

    /** The most characters a note has, on one line. */
    int MAX_NOTE_CHARACTERS = 300;

    /** The most active notes a memory holds. */
    int MAX_NOTES = 200;

    /** The most proposals of a trigger's memory that wait for review at once. */
    int MAX_PROPOSALS = 20;

    /** What a note is about: the owner in general, a system the agent uses, or one of the agent's repositories. */
    sealed interface About {

        /** About the person, or the trigger's work, in general. */
        record General() implements About {
        }

        /** About a system, by its id. */
        record System(UUID systemId) implements About {
        }

        /** About a repository, by its id. */
        record Repository(UUID repositoryId) implements About {
        }

        About GENERAL = new General();
    }

    /** Whether conversations read a note: {@code PROPOSED} is a trigger's note its audience has not accepted yet. */
    enum State {
        ACTIVE, PROPOSED
    }

    /**
     * One note.
     *
     * @param replaces       a proposal's: the number of the note it replaces once accepted
     * @param conversationId the conversation it was last saved in; null when written otherwise or once that one is gone
     * @param usedAt         when it was last saved, shown to an agent or recalled
     */
    record Note(int number, State state, About about, String body, @Nullable Integer replaces,
            @Nullable UUID conversationId, Instant createdAt, Instant updatedAt, Instant usedAt) {

        /** Its id in the prompt and the tools. */
        public String id() {
            return "m" + number;
        }
    }

    /** A memory as its owner's page shows it: every note, active and proposed, by number. */
    record Notes(boolean inEffect, List<Note> notes, int characters, int maxCharacters,
            @Nullable Instant compactedAt) {

        public Notes {
            notes = List.copyOf(notes);
        }
    }

    /**
     * What a session's first prompt shows of a memory (ADR-0063, decision 4): how many notes there are, the general
     * notes and the notes of the workspace's repositories, each within its budget, most recently used first.
     *
     * @param repositories by repository id, in the order asked, only those with notes
     */
    record PromptNotes(int active, int proposed, List<Note> general, Map<UUID, List<Note>> repositories) {

        public PromptNotes {
            general = List.copyOf(general);
            // In the order asked, the workspace's: Map.copyOf would not keep it.
            repositories = Collections.unmodifiableMap(new LinkedHashMap<>(repositories));
        }
    }

    /**
     * Where a write of the agent comes from: its conversation's settings in effect and the run. A write is not
     * idempotent here: the caller keeps the outcome of the run's request apart from the notes, so that a request sent
     * again writes nothing, whatever became of its note since (the conversation module's call journal, CONVENTIONS
     * §15.14, §15.18).
     */
    record Source(EffectiveSettings settings, UUID companyId, UUID conversationId, UUID runId) {
    }

    /** The answer to a write of the agent. */
    sealed interface Written {

        /** Saved and in effect at once (a person's memory). */
        record Saved(Note note) implements Written {
        }

        /** Proposed: a trigger's memory, which its audience reviews before conversations read it. */
        record Proposed(Note note) implements Written {
        }

        /** The same note was saved already about the same; its use is renewed. */
        record Known(Note note) implements Written {
        }

        /** Nothing was saved, with a code of the runner protocol and a message for the agent. */
        record Refused(String code, String message) implements Written {
        }
    }

    /**
     * The agent saves a note, or replaces one (ADR-0063, decisions 3, 5 to 7): one line of at most
     * {@value #MAX_NOTE_CHARACTERS} characters once its whitespace is folded; about something its settings list; scanned
     * by the vault; within the memory's limits. A person's note is active at once, a trigger's is proposed. Audited by
     * the agent.
     */
    Written remember(Source source, String note, About about, @Nullable Integer replaces);

    /** What the agent's {@code forget} did. */
    enum Forgotten {
        FORGOTTEN, NO_SUCH_NOTE,
        /** An accepted note of a trigger's memory: its audience deletes it, never the agent. */
        REVIEWED
    }

    /**
     * The agent deletes one of its notes: any of a person's memory, only a proposal of a trigger's (ADR-0063, decision
     * 5: an accepted note is its audience's to delete). Audited by the agent.
     */
    Forgotten forget(Source source, int number);

    /**
     * The tiers of a session's first prompt; nothing when memory is not in effect. Marks the notes shown as used.
     *
     * @param repositoryIds the workspace's repositories, in their order; the first three count
     */
    PromptNotes forPrompt(EffectiveSettings settings, UUID companyId, List<UUID> repositoryIds, Instant now);

    /** The active notes about a system, within their budget, most recently used first; marks them used. */
    List<Note> aboutSystem(EffectiveSettings settings, UUID companyId, UUID systemId, Instant now);

    /** At most five active notes that match the query's words, best first; marks them used. */
    List<Note> recall(EffectiveSettings settings, UUID companyId, String query, Instant now);

    // ---- the owner's page ---------------------------------------------------------------------------------------

    /** The memory of an owner, with every note; empty notes when the owner has no settings row. */
    Notes notes(UUID companyId, UUID agentId, SettingsOwner owner);

    /** Deletes a note, active or proposed; false when there is none. Audited by the actor. */
    boolean delete(UUID companyId, UUID agentId, SettingsOwner owner, int number, Actor actor);

    /** Deletes every note of the memory; answers how many. Audited by the actor when any went. */
    int clear(UUID companyId, UUID agentId, SettingsOwner owner, Actor actor);

    /** What a review of a proposal did. */
    enum Review {
        ACCEPTED, NOT_PROPOSED, MEMORY_FULL
    }

    /**
     * A trigger's audience accepts a proposal: it becomes active, in place of the note it replaces. Audited by the
     * actor.
     */
    Review accept(UUID companyId, UUID agentId, SettingsOwner owner, int number, Actor actor);

    /** The triggers with proposals waiting, by trigger id: how many and since when the oldest waits. */
    Map<UUID, Waiting> proposalsOf(UUID companyId, Collection<UUID> triggerIds);

    /** A trigger memory's proposals that wait for review. */
    record Waiting(int count, Instant since) {
    }

    // ---- retention and erasure (the governance module) -------------------------------------------------------------

    /**
     * Deletes the notes not used since {@code before}, and the proposals made before {@code proposedBefore}, except
     * those a hold keeps: of a held person, of a held trigger, or learned in a held conversation. Answers how many.
     */
    long purge(UUID companyId, @Nullable Instant before, Instant proposedBefore, Set<UUID> heldPrincipalIds,
            Set<UUID> heldTriggerIds, Set<UUID> heldConversationIds);

    /** What {@link #purge} would delete now, counted by its own selection, for the retention preview. */
    long countPurge(UUID companyId, @Nullable Instant before, Instant proposedBefore, Set<UUID> heldPrincipalIds,
            Set<UUID> heldTriggerIds, Set<UUID> heldConversationIds);

    /** Deletes the notes learned in the conversations, a merged note's sources' included; answers how many. */
    long eraseLearnedIn(UUID companyId, Collection<UUID> conversationIds);

    /** What {@link #eraseLearnedIn} would delete now, counted, for the erasure's dry run; changes nothing. */
    long countLearnedIn(UUID companyId, Collection<UUID> conversationIds);

    // ---- compaction ------------------------------------------------------------------------------------------------

    /** What a company's compaction did: how many memories, and the notes that went as duplicates, merges and drops. */
    record Compaction(int memories, int duplicates, int merged, int dropped) {

        public static final Compaction NONE = new Compaction(0, 0, 0, 0);
    }

    /**
     * Compacts the company's memories that changed since their last compaction, at most once in 30 days each
     * (ADR-0063, decision 8), outside any transaction, as of {@code now}, when it starts: it starts no model request
     * 15 minutes after, and none once the model does not answer. The governance module's daily sweep queues each
     * company it purged, and calls it for one company at a time on a thread of its own.
     */
    Compaction compact(UUID companyId, Instant now);
}
