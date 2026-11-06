package com.flowtivo.tinfirm.run;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Purging a run's content for retention and erasure (the package 6 contract, §2.3, §3.7): its events (the transcript,
 * {@code run.events_purged_at}) and its texts (the summary, the failure's message, the finish result's steps and
 * questions, {@code run.texts_purged_at}). Its status, hosts, payer, usage and counters stay. The governance module
 * decides what is due and what an open legal hold covers; this module only purges. Runs in the caller's transaction
 * and tenant context, which locked the runs' conversations first (CONVENTIONS §10, the lock order).
 * <p>
 * Each purge has its count, which selects the same rows by the same predicate and changes nothing: the previews of
 * retention and erasure answer with them, so that a preview never drifts from what the purge removes.
 */
public interface RunPurge {

    /**
     * An ended run whose events are kept.
     *
     * @param finishedAt its end, from which its events' period counts
     */
    record EndedRun(UUID runId, UUID conversationId, Instant finishedAt) {
    }

    /**
     * Conversations selected by the module that owns them, as SQL that answers their ids, with its parameters in
     * order: the conversations a count of runs reads, so that each module writes SQL over its own tables only.
     */
    record ConversationSelection(String sql, List<Object> params) {

        public ConversationSelection {
            params = List.copyOf(params);
        }
    }

    /**
     * The company's ended runs whose events are kept and that ended before the cutoff, by their end, after the given
     * run when one is given: the candidates of the sweep, which keeps only those of ended conversations no hold
     * covers.
     */
    List<EndedRun> eventsKept(Instant endedBefore, @Nullable EndedRun after, int limit);

    /** Deletes the events of these ended runs and marks them purged; answers how many runs were purged. */
    int purgeEvents(Collection<UUID> runIds, Instant at);

    /** Deletes the events of the conversation's ended runs and marks them purged; answers how many. */
    int purgeEventsOf(UUID conversationId, Instant at);

    /** Removes the texts of the conversation's ended runs and marks them purged; answers how many runs changed. */
    int purgeTextsOf(UUID conversationId, Instant at);

    /** How many of these runs {@link #purgeEvents} would purge now. */
    long countEvents(Collection<UUID> runIds);

    /** How many runs of these conversations {@link #purgeEventsOf} would purge now. */
    long countEventsOf(Collection<UUID> conversationIds);

    /** How many runs of these conversations {@link #purgeTextsOf} would change now. */
    long countTextsOf(Collection<UUID> conversationIds);

    /** How many runs of the selected conversations {@link #purgeTextsOf} would change now. */
    long countTextsIn(ConversationSelection conversations);

    /**
     * How many runs of the selected conversations ended before the cutoff and keep their events: what the sweep's
     * purge of {@link #eventsKept} removes, when the selection is the conversations it keeps.
     */
    long countEventsKeptIn(Instant endedBefore, ConversationSelection conversations);
}
