package com.flowtivo.tinfirm.run.internal;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.run.ConversationLock;
import com.flowtivo.tinfirm.run.RunStatus;

/**
 * The run module's side of the global lock order (CONVENTIONS §10): a run's conversation's row before the run's row,
 * several conversations in the order of their ids, and all of them before the company's audit chain head, which the
 * transaction's audit records lock when it commits. The conversation's lock comes from the conversation module
 * ({@link ConversationLock}): this module never reads the {@code conversation} table. A run's conversation never
 * changes, so a run is read unlocked first to name it.
 * <p>
 * Every transaction that locks a run and then may lock or write its conversation locks through here: a run's end
 * ({@link RunCompletion}, which tells {@code TurnEnded}), a change the conversation hears of in the transaction
 * ({@code RunEvents}: accepted, a queue reason, resumed), the dispatcher's {@code TurnSource.plan}, and a platform
 * request ({@code RunRequests.request}). A transaction that locks runs only (an event frame, a lease, the delivery's
 * record of an assignment, a token's revocation) locks them directly.
 */
@Component
class RunLocks {

    private final RunStore runs;

    private final RunPorts ports;

    RunLocks(RunStore runs, RunPorts ports) {
        this.runs = runs;
        this.ports = ports;
    }

    /** The run, locked after its conversation; empty when the tenant context does not see it. */
    Optional<RunStore.RunRow> lock(UUID runId) {
        Optional<RunStore.RunRow> run = runs.find(runId);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        lockConversation(run.get());
        return runs.lock(runId);
    }

    /**
     * The run while it is still queued and unassigned, locked after its conversation; empty when another dispatcher
     * holds it ({@code SKIP LOCKED}) or it was dispatched meanwhile.
     */
    Optional<RunStore.RunRow> lockDispatchable(UUID runId) {
        Optional<RunStore.RunRow> run = runs.find(runId)
            .filter(found -> found.status() == RunStatus.QUEUED && found.dispatchedAt() == null);
        if (run.isEmpty()) {
            return Optional.empty();
        }
        lockConversation(run.get());
        return runs.lockDispatchable(runId);
    }

    /**
     * Locks the conversations of these runs, each once, in the order of their ids: a transaction that handles several
     * runs (a host's {@code hello}, an agent paused with {@code stopRunning}) takes them all before it locks any run.
     */
    void lockConversations(Collection<RunStore.RunRow> of) {
        Map<UUID, UUID> companies = new TreeMap<>();
        of.forEach(run -> companies.putIfAbsent(run.conversationId(), run.companyId()));
        companies.forEach((conversationId, companyId) -> ports.conversationLock().lock(companyId, conversationId));
    }

    /** Locks the run's conversation. */
    void lockConversation(RunStore.RunRow run) {
        ports.conversationLock().lock(run.companyId(), run.conversationId());
    }
}
