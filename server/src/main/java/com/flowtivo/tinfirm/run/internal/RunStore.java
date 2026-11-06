package com.flowtivo.tinfirm.run.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.model.ModelRelay;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.RunTokens;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RuntimeKind;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL access to {@code run} (V004). Row-level security limits every statement to the company of the tenant context.
 * The cross-company scans of the dispatcher and the reaper go through the narrow {@code SECURITY DEFINER} functions
 * of V004, which return only (run id, company id); the caller then works on each run in its company context.
 */
@Repository
class RunStore {

    /** A run that occupies a slot: running, or assigned and awaiting {@code run.accepted}. */
    static final String OCCUPYING = "(status = 'RUNNING' OR (status = 'QUEUED' AND dispatched_at IS NOT NULL))";

    static final String BUDGET = "BUDGET";

    static final String SUBSCRIPTION = "SUBSCRIPTION";

    private static final String COLUMNS = """
            id, company_id, conversation_id, conversation_key, agent_id, runtime, turn_reason, turn_number,
            acting_principal_id, payer_principal_id, model_access, subscription_host_id, subscription_label,
            runner_host_id, runner_host_name, runner_host_kind, status, epoch, dispatched_at, dispatch_after,
            lease_expires_at, created_by, created_at, started_at, finished_at, failure_class, failure_message, summary,
            session_id, branch, input_tokens, output_tokens, cached_input_tokens, cache_write_tokens, cost_usd,
            last_event_seq, event_count, queue_reason, queue_reason_details, queue_reason_since, lost_cause,
            result_outcome, result_next_steps, result_open_questions, tool_call_count, message_count, thought_count,
            resumed, resume_outcome, relay_requests, relay_request_bytes, relay_response_bytes, texts_purged_at,
            events_purged_at, trigger_id""";

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    RunStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A run: one turn of a conversation. Its repositories, with their git results, are in {@link RunRepositoryStore}.
     *
     * @param modelAccess        {@code BUDGET} or {@code SUBSCRIPTION}
     * @param subscriptionHostId the subscription's personal host; null for a budget, and after the host was removed
     * @param branch             the conversation's branch {@code agent/{key}}
     * @param queueReason        why a QUEUED run waits, as the dispatcher last found; null otherwise
     * @param queueReasonSince   when the reason's code last changed
     * @param leaseExpiresAt     a RUNNING run's lease; for an assigned run awaiting {@code run.accepted}, what the
     *                           accept timeout waits for (its preparation, or the reconnect grace), else null
     * @param result             the agent's last {@code finish}, whose summary is {@code summary}; null when it made
     *                           none
     */
    record RunRow(UUID id, UUID companyId, UUID conversationId, String conversationKey, UUID agentId,
            RuntimeKind runtime, TurnReason turnReason, int turnNumber, UUID actingPrincipalId, UUID payerPrincipalId,
            String modelAccess, @Nullable UUID subscriptionHostId, @Nullable String subscriptionLabel,
            @Nullable UUID runnerHostId, @Nullable String runnerHostName, @Nullable HostScope runnerHostKind,
            RunStatus status, long epoch, @Nullable Instant dispatchedAt, @Nullable Instant dispatchAfter,
            @Nullable Instant leaseExpiresAt, @Nullable UUID createdBy, Instant createdAt, @Nullable Instant startedAt,
            @Nullable Instant finishedAt, @Nullable String failureClass, @Nullable String failureMessage,
            @Nullable String summary, @Nullable String sessionId, @Nullable String branch, @Nullable Long inputTokens,
            @Nullable Long outputTokens, @Nullable Long cachedInputTokens, @Nullable Long cacheWriteTokens,
            @Nullable Double costUsd, long lastEventSeq, int eventCount, @Nullable QueueReason queueReason,
            @Nullable Instant queueReasonSince, @Nullable LostCause lostCause, @Nullable Result result,
            @Nullable Counts counts, @Nullable Boolean resumed, RunnerMessages.@Nullable ResumeOutcome resumeOutcome,
            ModelRelay.@Nullable RelayUsage relay, @Nullable Instant textsPurgedAt,
            @Nullable Instant eventsPurgedAt, @Nullable UUID triggerId) {

        boolean budget() {
            return BUDGET.equals(modelAccess);
        }

        /**
         * The model access the run named, for package 2's rules; null for a subscription whose host was removed
         * (the run can never be paid: {@code SUBSCRIPTION_GONE}).
         */
        @Nullable ModelAccessChoice choice() {
            if (budget()) {
                return ModelAccessChoice.BUDGET;
            }
            return subscriptionHostId != null && subscriptionLabel != null
                    ? new ModelAccessChoice.Subscription(subscriptionHostId, subscriptionLabel) : null;
        }

        RunInfo info() {
            QueueReason reason = queueReason;
            return new RunInfo(id, companyId, conversationId, conversationKey, agentId, runtime, turnReason,
                    turnNumber, actingPrincipalId, payerPrincipalId, choice(), status, runnerHostId,
                    runnerHostKind, reason != null ? reason.code() : null, reason != null ? reason.retryAt() : null,
                    failureClass, failureMessage, summary, publicResult(), sessionId, epoch, createdAt, startedAt,
                    finishedAt);
        }

        RunInfo.@Nullable Result publicResult() {
            return result != null ? new RunInfo.Result(result.outcome(), summary != null ? summary : "",
                    result.nextSteps(), result.openQuestions()) : null;
        }

        /** The run as a RUNNING run of its host, for the ports; only meaningful for a run that has a host. */
        ActiveRun active() {
            if (runnerHostId == null) {
                throw new IllegalStateException("Run " + id + " has no runner host");
            }
            return new ActiveRun(companyId, id, epoch, runnerHostId, conversationId, agentId, actingPrincipalId,
                    payerPrincipalId, turnNumber);
        }
    }

    /**
     * What a finished run did, as its transcript shows it: the tool calls (each {@code toolCallId} once), the
     * messages and the thoughts (consecutive AGENT_MESSAGE or AGENT_THOUGHT events are one, until another row of the
     * transcript comes between them). Counted from the events when the run ends.
     */
    record Counts(int toolCalls, int messages, int thoughts) {
    }

    /** The structured result of a run, the agent's last {@code finish} call; its summary is the run's summary. */
    record Result(RunnerMessages.ResultOutcome outcome, List<String> nextSteps, List<String> openQuestions) {

        static final int MAX_NEXT_STEPS = 20;

        static final int MAX_OPEN_QUESTIONS = 10;

        static final int MAX_ITEM_LENGTH = 2000;

        Result {
            nextSteps = List.copyOf(nextSteps);
            openQuestions = List.copyOf(openQuestions);
        }

        /**
         * The result as the runner host reported it, within the protocol's limits whatever was sent: blank entries
         * dropped, at most 20 next steps and 10 open questions, each cut to 2000 characters.
         */
        static Result of(RunnerMessages.RunResult reported) {
            return new Result(reported.outcome(), items(reported.nextSteps(), MAX_NEXT_STEPS),
                    items(reported.openQuestions(), MAX_OPEN_QUESTIONS));
        }

        private static List<String> items(@Nullable List<String> items, int max) {
            if (items == null) {
                return List.of();
            }
            return items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> cut(item.strip(), MAX_ITEM_LENGTH))
                .limit(max)
                .toList();
        }

        /** The first {@code max} characters (code points, as PostgreSQL counts them). */
        static String cut(String text, int max) {
            return text.codePointCount(0, text.length()) <= max ? text
                    : text.substring(0, text.offsetByCodePoints(0, max));
        }
    }

    /** A run found by a cross-company lookup. */
    record RunRef(UUID runId, UUID companyId) {
    }

    /** A queued run found by the dispatcher's lookup, with its position in the queue. */
    record QueuedRef(UUID runId, UUID companyId, Instant createdAt) {
    }

    /** A new QUEUED run. */
    record NewRun(UUID id, UUID companyId, UUID conversationId, String conversationKey, UUID agentId,
            RuntimeKind runtime, TurnReason turnReason, int turnNumber, UUID actingPrincipalId, UUID payerPrincipalId,
            ModelAccessChoice modelAccess, String branch, @Nullable UUID createdBy, Instant createdAt,
            @Nullable UUID triggerId, @Nullable Instant dispatchAfter, @Nullable QueueReason queueReason) {
    }

    /**
     * What a finished run reports.
     *
     * @param summary the result's summary when the agent called {@code finish}, else its last message
     * @param git     the git result of each repository the host cloned; null when the host reported none
     * @param result  the agent's last {@code finish}, or null
     * @param resumed whether the run resumed its session, as its host reported; null when the server ends the run, or
     *                the host did not report it
     */
    record Outcome(RunStatus status, @Nullable String failureClass, @Nullable String failureMessage,
            @Nullable String summary, @Nullable String sessionId, @Nullable List<GitResult> git,
            @Nullable Long inputTokens, @Nullable Long outputTokens, @Nullable Long cachedInputTokens,
            @Nullable Long cacheWriteTokens, @Nullable Double costUsd, @Nullable Long lastSeq, @Nullable Result result,
            @Nullable Boolean resumed, RunnerMessages.@Nullable ResumeOutcome resumeOutcome) {

        static Outcome of(RunStatus status, @Nullable String failureClass, @Nullable String failureMessage) {
            return new Outcome(status, failureClass, failureMessage, null, null, null, null, null, null, null, null,
                    null, null, null, null);
        }

        /** This outcome with another failure class and message, the rest as it is. */
        Outcome failedAs(String otherClass, String otherMessage) {
            return new Outcome(status, otherClass, otherMessage, summary, sessionId, git, inputTokens, outputTokens,
                    cachedInputTokens, cacheWriteTokens, costUsd, lastSeq, result, resumed, resumeOutcome);
        }
    }

    /** The end state of one repository, matched to the run's workspace by repository. */
    record GitResult(UUID repositoryId, boolean pushed, @Nullable String headSha, @Nullable Integer commits) {
    }

    /**
     * Inserts the QUEUED run, unless the conversation has an active run: the partial unique index
     * {@code run_one_active_per_conversation_uq} decides, without aborting the caller's transaction.
     *
     * @return false when another run of the conversation is QUEUED or RUNNING, and nothing was inserted
     */
    boolean insert(NewRun run) {
        UUID subscriptionHost = null;
        String subscriptionLabel = null;
        if (run.modelAccess() instanceof ModelAccessChoice.Subscription subscription) {
            subscriptionHost = subscription.runnerHostId();
            subscriptionLabel = subscription.label();
        }
        return jdbc.sql("""
                INSERT INTO run (id, company_id, conversation_id, conversation_key, agent_id, runtime, turn_reason,
                                 turn_number, acting_principal_id, payer_principal_id, model_access,
                                 subscription_host_id, subscription_label, branch, status, created_by, created_at,
                                 trigger_id, dispatch_after, queue_reason, queue_reason_details, queue_reason_since)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (conversation_id) WHERE status IN ('QUEUED', 'RUNNING') DO NOTHING""")
            .params(run.id(), run.companyId(), run.conversationId(), run.conversationKey(), run.agentId(),
                    run.runtime().name(), run.turnReason().name(), run.turnNumber(), run.actingPrincipalId(),
                    run.payerPrincipalId(), subscriptionHost != null ? SUBSCRIPTION : BUDGET, subscriptionHost,
                    subscriptionLabel, run.branch(), run.createdBy(), ts(run.createdAt()), run.triggerId(),
                    run.dispatchAfter() != null ? ts(run.dispatchAfter()) : null,
                    run.queueReason() != null ? run.queueReason().code().name() : null,
                    run.queueReason() != null ? run.queueReason().detailsJson() : null,
                    run.queueReason() != null ? ts(run.createdAt()) : null)
            .update() == 1;
    }

    Optional<RunRow> find(UUID runId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM run WHERE id = ?").param(runId).query(RunStore::run).optional();
    }

    /**
     * The run, locked ({@code FOR NO KEY UPDATE}: the foreign keys' checks of the rows that name a run, a message's or
     * a decision's, never wait on it; no run's id changes and no run is deleted). A transaction that may also lock or
     * write the run's conversation locks the conversation first ({@link RunLocks}, CONVENTIONS §10).
     */
    Optional<RunRow> lock(UUID runId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM run WHERE id = ? FOR NO KEY UPDATE")
            .param(runId)
            .query(RunStore::run)
            .optional();
    }

    /** The run if it is still queued and unassigned; skipped when another dispatcher holds it. */
    Optional<RunRow> lockDispatchable(UUID runId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM run WHERE id = ? AND status = 'QUEUED' AND dispatched_at IS NULL"
                + " FOR NO KEY UPDATE SKIP LOCKED")
            .param(runId)
            .query(RunStore::run)
            .optional();
    }

    Optional<RunRow> activeOfConversation(UUID conversationId) {
        return jdbc.sql("SELECT " + COLUMNS
                + " FROM run WHERE conversation_id = ? AND status IN ('QUEUED', 'RUNNING')")
            .param(conversationId)
            .query(RunStore::run)
            .optional();
    }

    /** The runs of a conversation, oldest first. */
    List<RunRow> ofConversation(UUID conversationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM run WHERE conversation_id = ? ORDER BY created_at, id")
            .param(conversationId)
            .query(RunStore::run)
            .list();
    }

    /** The latest earlier run of the conversation that reported a session id. */
    Optional<RunRow> previousWithSession(UUID conversationId, UUID exceptRunId, Instant before) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM run
                WHERE conversation_id = ? AND id <> ? AND created_at <= ? AND session_id IS NOT NULL
                ORDER BY created_at DESC, id DESC
                LIMIT 1""")
            .params(conversationId, exceptRunId, ts(before))
            .query(RunStore::run)
            .optional();
    }

    /** The host of the conversation's latest earlier run that had one: the dispatcher prefers it (open point 13). */
    Optional<UUID> previousHost(UUID conversationId, UUID exceptRunId) {
        return jdbc.sql("""
                SELECT runner_host_id FROM run
                 WHERE conversation_id = ? AND id <> ? AND runner_host_id IS NOT NULL
                 ORDER BY created_at DESC, id DESC
                 LIMIT 1""")
            .params(conversationId, exceptRunId)
            .query(UUID.class)
            .optional();
    }

    /** The newest run of the conversation. */
    Optional<UUID> latestOfConversation(UUID conversationId) {
        return jdbc.sql("SELECT id FROM run WHERE conversation_id = ? ORDER BY created_at DESC, id DESC LIMIT 1")
            .param(conversationId)
            .query(UUID.class)
            .optional();
    }

    /** Runs the server has on a host: running, or assigned and awaiting {@code run.accepted}. */
    List<RunRow> occupyingOnHost(UUID hostId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM run WHERE runner_host_id = ? AND " + OCCUPYING)
            .param(hostId)
            .query(RunStore::run)
            .list();
    }

    /** The slots each host's runs hold. */
    Map<UUID, Integer> occupyingByHosts(Collection<UUID> hostIds) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (hostIds.isEmpty()) {
            return counts;
        }
        jdbc.sql("SELECT runner_host_id AS id, count(*) AS n FROM run WHERE runner_host_id IN (:ids) AND "
                + OCCUPYING + " GROUP BY runner_host_id")
            .param("ids", hostIds)
            .query(rs -> {
                counts.put(rs.getObject("id", UUID.class), rs.getInt("n"));
            });
        return counts;
    }

    /**
     * The slots each machine's runs hold across its presences in every company (ADR-0092), through the narrow
     * {@code SECURITY DEFINER} count: a dispatcher of any company counts them under the machine's lock.
     */
    Map<UUID, Integer> occupyingByMachines(Collection<UUID> personalHostIds) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (personalHostIds.isEmpty()) {
            return counts;
        }
        jdbc.sql("SELECT personal_host_id AS id, occupying AS n FROM personal_runner_host_occupying(?)")
            .param(personalHostIds.toArray(UUID[]::new))
            .query(rs -> {
                counts.put(rs.getObject("id", UUID.class), rs.getInt("n"));
            });
        return counts;
    }

    /** The slots of a subscription's pool its runs hold, across the machine's presences (ADR-0092). */
    int occupyingBySubscriptionOfMachine(UUID personalHostId, String label) {
        return jdbc.sql("SELECT personal_runner_host_occupying_subscription(?, ?)")
            .params(personalHostId, label)
            .query(Integer.class)
            .single();
    }

    /** Whether a QUEUED or RUNNING run names a presence of the machine, as its host or its subscription's host. */
    boolean hasActiveForMachine(UUID personalHostId) {
        return jdbc.sql("SELECT personal_runner_host_active(?)").param(personalHostId).query(Boolean.class).single();
    }

    /** The slots of a subscription's pool its runs hold. */
    int occupyingBySubscription(UUID hostId, String label) {
        return jdbc.sql("SELECT count(*) FROM run WHERE subscription_host_id = ? AND subscription_label = ? AND "
                + OCCUPYING)
            .params(hostId, label)
            .query(Integer.class)
            .single();
    }

    /** Whether a QUEUED or RUNNING run is assigned to the host, or waits for it as a subscription's host. */
    boolean hasActiveForHost(UUID hostId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM run
                                WHERE status IN ('QUEUED', 'RUNNING')
                                  AND (runner_host_id = ? OR subscription_host_id = ?))""")
            .params(hostId, hostId)
            .query(Boolean.class)
            .single();
    }

    /** Whether the agent has a QUEUED or RUNNING run. */
    boolean hasActiveForAgent(UUID agentId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM run WHERE agent_id = ? AND status IN ('QUEUED', 'RUNNING'))")
            .param(agentId)
            .query(Boolean.class)
            .single();
    }

    /**
     * The QUEUED and RUNNING runs that act for or are paid by the principal, oldest first, unlocked: what its
     * suspension cancels (the package 6 contract, §5.4).
     */
    List<RunRow> activeOfPrincipal(UUID principalId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM run
                WHERE (acting_principal_id = ? OR payer_principal_id = ?) AND status IN ('QUEUED', 'RUNNING')
                ORDER BY created_at, id""")
            .params(principalId, principalId)
            .query(RunStore::run)
            .list();
    }

    /** The company's RUNNING runs and those assigned to a host, oldest first: what its stop cancels (§5.4). */
    List<RunRow> runningOfCompany(UUID companyId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM run
                WHERE company_id = ? AND (status = 'RUNNING' OR status = 'QUEUED' AND dispatched_at IS NOT NULL)
                ORDER BY created_at, id""")
            .param(companyId)
            .query(RunStore::run)
            .list();
    }

    /** The RUNNING runs of the conversations, oldest first: what a system's pause with stopRunning cancels (§5.4). */
    List<RunRow> runningOfConversations(UUID companyId, Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM run
                WHERE company_id = :companyId AND conversation_id IN (:ids) AND status = 'RUNNING'
                ORDER BY created_at, id""")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(conversationIds))
            .query(RunStore::run)
            .list();
    }

    /** The agent's QUEUED and RUNNING runs, oldest first, unlocked: their conversations are locked before them. */
    List<RunRow> activeOfAgent(UUID agentId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM run
                WHERE agent_id = ? AND status IN ('QUEUED', 'RUNNING')
                ORDER BY created_at, id""")
            .param(agentId)
            .query(RunStore::run)
            .list();
    }

    /** Whether the run is RUNNING on the host with the epoch: the model relay's question (M7). */
    boolean runningOn(UUID runId, long epoch, UUID hostId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM run
                                WHERE id = ? AND status = 'RUNNING' AND epoch = ? AND runner_host_id = ?)""")
            .params(runId, epoch, hostId)
            .query(Boolean.class)
            .single();
    }

    /**
     * Assignment: a new epoch fences every earlier assignment of the run; the queue reason becomes {@code assigned},
     * since now; the accept timeout waits until {@code prepareUntil} while the assignment is prepared.
     */
    void markDispatched(UUID runId, long epoch, UUID hostId, String hostName, HostScope hostKind, QueueReason assigned,
            Instant now, Instant prepareUntil) {
        jdbc.sql("""
                UPDATE run SET epoch = ?, dispatched_at = ?, dispatch_after = NULL, lease_expires_at = ?,
                               runner_host_id = ?, runner_host_name = ?, runner_host_kind = ?, queue_reason = ?,
                               queue_reason_details = CAST(? AS jsonb), queue_reason_since = ?
                 WHERE id = ? AND status = 'QUEUED'""")
            .params(epoch, ts(now), ts(prepareUntil), hostId, hostName, hostKind.name(), assigned.code().name(),
                    assigned.detailsJson(), ts(now), runId)
            .update();
    }

    /**
     * The prepared assignment is sent now: the accept timeout counts from here. False when the run is no longer this
     * assignment (cancelled, taken back, assigned anew).
     */
    /** The settings' model the run is assigned with (package 6, §3.1); null for the runtime's default. */
    void recordModel(UUID runId, @Nullable String model) {
        jdbc.sql("UPDATE run SET model = ? WHERE id = ?")
            .params(model != null && !model.isBlank() && model.length() <= 200 ? model : null, runId)
            .update();
    }

    /** The run's model and the totals of its {@code USAGE} events (package 6, §3.1). */
    Optional<RunTokens> tokens(UUID runId) {
        return jdbc.sql("""
                SELECT model, input_tokens, output_tokens, cached_input_tokens, cache_write_tokens, cost_usd,
                       relay_requests, created_at, started_at, finished_at
                  FROM run WHERE id = ?""")
            .param(runId)
            .query((rs, row) -> new RunTokens(rs.getString("model"), rs.getLong("input_tokens"),
                    rs.getLong("output_tokens"), rs.getLong("cached_input_tokens"), rs.getLong("cache_write_tokens"),
                    rs.getObject("cost_usd") != null ? rs.getDouble("cost_usd") : null,
                    rs.getObject("relay_requests") != null ? rs.getLong("relay_requests") : null,
                    rs.getObject("created_at", OffsetDateTime.class).toInstant(), instant(rs, "started_at"),
                    instant(rs, "finished_at")))
            .optional();
    }

    /** The QUEUED runs paid by the principal that wait for the reason. */
    int countQueued(UUID payerPrincipalId, String queueReason) {
        return jdbc.sql("""
                SELECT count(*) FROM run WHERE payer_principal_id = ? AND status = 'QUEUED' AND queue_reason = ?""")
            .params(payerPrincipalId, queueReason)
            .query(Integer.class)
            .single();
    }

    boolean markSent(UUID runId, long epoch, UUID hostId, Instant now) {
        return jdbc.sql("""
                UPDATE run SET dispatched_at = ?, lease_expires_at = NULL
                 WHERE id = ? AND status = 'QUEUED' AND epoch = ? AND runner_host_id = ? AND dispatched_at IS NOT NULL""")
            .params(ts(now), runId, epoch, hostId)
            .update() == 1;
    }

    /**
     * Back to the queue; the epoch moves on, so late messages about the old assignment are stale, and its model token
     * is inactive. The queue reason becomes {@code reason} since {@code now} (a rejection), or is cleared.
     */
    void requeue(UUID runId, @Nullable Instant dispatchAfter, @Nullable QueueReason reason, Instant now) {
        jdbc.sql("""
                UPDATE run SET epoch = epoch + 1, dispatched_at = NULL, dispatch_after = ?, lease_expires_at = NULL,
                               runner_host_id = NULL, runner_host_name = NULL, runner_host_kind = NULL,
                               queue_reason = ?, queue_reason_details = CAST(? AS jsonb), queue_reason_since = ?
                 WHERE id = ? AND status = 'QUEUED'""")
            .params(dispatchAfter != null ? ts(dispatchAfter) : null, reason != null ? reason.code().name() : null,
                    reason != null ? reason.detailsJson() : null, reason != null ? ts(now) : null, runId)
            .update();
    }

    /** Records why a QUEUED run waits; {@code since} is when its code last changed. */
    void setQueueReason(UUID runId, QueueReason reason, Instant since) {
        jdbc.sql("""
                UPDATE run SET queue_reason = ?, queue_reason_details = CAST(? AS jsonb), queue_reason_since = ?
                 WHERE id = ? AND status = 'QUEUED'""")
            .params(reason.code().name(), reason.detailsJson(), ts(since), runId)
            .update();
    }

    /** Accepted: the run leaves QUEUED, and its queue reason goes with the same update. */
    void markRunning(UUID runId, Instant now, Instant leaseExpiresAt) {
        jdbc.sql("""
                UPDATE run SET status = 'RUNNING', dispatched_at = NULL, started_at = coalesce(started_at, ?),
                               lease_expires_at = ?,
                               queue_reason = NULL, queue_reason_details = NULL, queue_reason_since = NULL
                 WHERE id = ? AND status = 'QUEUED'""")
            .params(ts(now), ts(leaseExpiresAt), runId)
            .update();
    }

    int renewLease(UUID runId, long epoch, UUID hostId, Instant leaseExpiresAt) {
        return jdbc.sql("""
                UPDATE run SET lease_expires_at = ?
                 WHERE id = ? AND status = 'RUNNING' AND epoch = ? AND runner_host_id = ?""")
            .params(ts(leaseExpiresAt), runId, epoch, hostId)
            .update();
    }

    /**
     * The host talks: its running runs get a lease of at least {@code until} (never a shorter one). Also renews runs
     * whose lease has expired but that the reaper has not marked LOST yet.
     */
    int renewHostLeases(UUID hostId, Instant until) {
        return jdbc.sql("""
                UPDATE run SET lease_expires_at = greatest(lease_expires_at, ?)
                 WHERE runner_host_id = ? AND status = 'RUNNING'""")
            .params(ts(until), hostId)
            .update();
    }

    /**
     * The reconnect grace of one host: its runs that hold a slot keep a lease (an assigned run: an accept deadline)
     * of at least {@code until}. A lease is never shortened.
     */
    int extendHostLeases(UUID hostId, Instant until) {
        return jdbc.sql("UPDATE run SET lease_expires_at = greatest(lease_expires_at, ?) WHERE runner_host_id = ? AND "
                + OCCUPYING)
            .params(ts(until), hostId)
            .update();
    }

    /** The reconnect grace at startup: every run of the tenant context's company that holds a slot. */
    int extendLeases(Instant until) {
        return jdbc.sql("UPDATE run SET lease_expires_at = greatest(lease_expires_at, ?) WHERE " + OCCUPYING)
            .param(ts(until))
            .update();
    }

    /** Records why the run is LOST, in the transaction that ends it. */
    void recordLoss(UUID runId, LostCause cause) {
        jdbc.sql("UPDATE run SET lost_cause = ? WHERE id = ? AND status = 'LOST'")
            .params(cause.name(), runId)
            .update();
    }

    /**
     * A LOST run of this epoch is RUNNING again, with a fresh lease: everything that marking it LOST wrote on the row
     * is undone. A running run has no summary, result, session id, resume outcome or failure yet.
     *
     * @return false when the run is no longer LOST with this epoch
     */
    boolean resume(UUID runId, long epoch, Instant leaseExpiresAt) {
        return jdbc.sql("""
                UPDATE run SET status = 'RUNNING', finished_at = NULL, failure_class = NULL, failure_message = NULL,
                               summary = NULL, session_id = NULL, lease_expires_at = ?, lost_cause = NULL,
                               result_outcome = NULL, result_next_steps = NULL, result_open_questions = NULL,
                               tool_call_count = NULL, message_count = NULL, thought_count = NULL, resumed = NULL,
                               resume_outcome = NULL, relay_requests = NULL, relay_request_bytes = NULL,
                               relay_response_bytes = NULL
                 WHERE id = ? AND status = 'LOST' AND epoch = ?""")
            .params(ts(leaseExpiresAt), runId, epoch)
            .update() == 1;
    }

    /**
     * Ends the run, with its result when it has one, and clears its queue reason; the git results of its
     * repositories are in {@link RunRepositoryStore}. The same statement counts what the run did from its events
     * ({@link Counts}): a message or a thought starts at an event of its type whose previous event, leaving out usage,
     * rate limits and updates of tool calls (which add no row to a transcript), is of another type.
     */
    void finish(UUID runId, Outcome outcome, ModelRelay.RelayUsage relay, Instant now) {
        Result result = outcome.result();
        jdbc.sql("""
                UPDATE run
                   SET status = ?, finished_at = ?, dispatched_at = NULL, failure_class = ?, failure_message = ?,
                       summary = ?, session_id = ?, result_outcome = ?, result_next_steps = CAST(? AS jsonb),
                       result_open_questions = CAST(? AS jsonb),
                       input_tokens = coalesce(?, input_tokens), output_tokens = coalesce(?, output_tokens),
                       cached_input_tokens = coalesce(?, cached_input_tokens),
                       cache_write_tokens = coalesce(?, cache_write_tokens), cost_usd = coalesce(?, cost_usd),
                       last_event_seq = greatest(last_event_seq, coalesce(?, 0)), resumed = ?, resume_outcome = ?,
                       relay_requests = ?, relay_request_bytes = ?, relay_response_bytes = ?,
                       queue_reason = NULL, queue_reason_details = NULL, queue_reason_since = NULL,
                       (tool_call_count, message_count, thought_count) = (
                           SELECT (SELECT count(DISTINCT coalesce(calls.payload ->> 'toolCallId', 'seq:' || calls.seq))
                                     FROM run_event calls
                                    WHERE calls.run_id = run.id AND calls.type IN ('TOOL_CALL', 'TOOL_CALL_UPDATE')),
                                  count(*) FILTER (WHERE entries.type = 'AGENT_MESSAGE'
                                                     AND entries.previous IS DISTINCT FROM 'AGENT_MESSAGE'),
                                  count(*) FILTER (WHERE entries.type = 'AGENT_THOUGHT'
                                                     AND entries.previous IS DISTINCT FROM 'AGENT_THOUGHT')
                             FROM (SELECT events.type, lag(events.type) OVER (ORDER BY events.seq) AS previous
                                     FROM run_event events
                                    WHERE events.run_id = run.id
                                      AND events.type NOT IN ('USAGE', 'RATE_LIMIT', 'TOOL_CALL_UPDATE')) entries)
                 WHERE id = ? AND status IN ('QUEUED', 'RUNNING')""")
            .params(outcome.status().name(), ts(now), outcome.failureClass(), outcome.failureMessage(),
                    outcome.summary(), outcome.sessionId(), result != null ? result.outcome().name() : null,
                    result != null ? JSON.writeValueAsString(result.nextSteps()) : null,
                    result != null ? JSON.writeValueAsString(result.openQuestions()) : null, outcome.inputTokens(),
                    outcome.outputTokens(), outcome.cachedInputTokens(), outcome.cacheWriteTokens(), outcome.costUsd(),
                    outcome.lastSeq(), outcome.resumed(),
                    outcome.resumeOutcome() != null ? outcome.resumeOutcome().name() : null, relay.requests(),
                    relay.requestBytes(), relay.responseBytes(), runId)
            .update();
    }

    /** The run's usage: USAGE payloads carry cumulative totals, so the latest one replaces the previous. */
    void recordUsage(UUID runId, @Nullable Long inputTokens, @Nullable Long outputTokens,
            @Nullable Long cachedInputTokens, @Nullable Long cacheWriteTokens, @Nullable Double costUsd) {
        jdbc.sql("""
                UPDATE run SET input_tokens = ?, output_tokens = ?, cached_input_tokens = ?, cache_write_tokens = ?,
                               cost_usd = ?
                 WHERE id = ?""")
            .params(inputTokens, outputTokens, cachedInputTokens, cacheWriteTokens, costUsd, runId)
            .update();
    }

    /**
     * Keeps the event counters of a run, and renews a running run's lease: an event of the current execution shows
     * that it is alive. The run row must be locked by the caller.
     */
    void recordEvents(UUID runId, int addedEvents, long addedBytes, long seq, Instant leaseExpiresAt) {
        jdbc.sql("""
                UPDATE run SET event_count = event_count + ?, event_bytes = event_bytes + ?,
                               last_event_seq = greatest(last_event_seq, ?),
                               lease_expires_at = CASE WHEN status = 'RUNNING'
                                                       THEN greatest(lease_expires_at, ?) ELSE lease_expires_at END
                 WHERE id = ?""")
            .params(addedEvents, addedBytes, seq, ts(leaseExpiresAt), runId)
            .update();
    }

    /**
     * The bytes of event payloads the run stored, as UTF-8 JSON, which its byte budget bounds
     * ({@code tinfirm.runner.events-max-bytes}); never less after an event was dropped to make room.
     */
    long eventBytes(UUID runId) {
        return jdbc.sql("SELECT event_bytes FROM run WHERE id = ?")
            .params(runId)
            .query(Long.class)
            .optional()
            .orElse(0L);
    }

    // ---- cross-company lookups (SECURITY DEFINER functions, ids only) ---------------------------------------------

    /** The queue, oldest first, after the given position (the start when null). */
    List<QueuedRef> dispatchQueue(Instant now, @Nullable QueuedRef after, int limit) {
        return jdbc.sql("SELECT run_id, company_id, created_at FROM run_dispatch_queue(?, ?, ?, ?)")
            .params(ts(now), after != null ? ts(after.createdAt()) : null, after != null ? after.runId() : null, limit)
            .query((rs, row) -> new QueuedRef(rs.getObject("run_id", UUID.class),
                    rs.getObject("company_id", UUID.class),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()))
            .list();
    }

    /** Assigned runs without {@code run.accepted} since before the cutoff, whose grace is over. */
    List<RunRef> acceptOverdue(Instant dispatchedBefore, Instant now, int limit) {
        return refs("SELECT run_id, company_id FROM run_accept_overdue(?, ?, ?)", ts(dispatchedBefore), ts(now),
                limit);
    }

    /** The companies with runs that hold a slot. */
    List<UUID> occupyingCompanies() {
        return jdbc.sql("SELECT company_id FROM run_occupying_companies()").query(UUID.class).list();
    }

    List<RunRef> leaseExpired(Instant now, int limit) {
        return refs("SELECT run_id, company_id FROM run_lease_expired(?, ?)", ts(now), limit);
    }

    /** Runs lost by lease expiry before the cutoff whose model tokens are still unrevoked. */
    List<RunRef> lostUnrevoked(Instant finishedBefore, int limit) {
        return refs("SELECT run_id, company_id FROM run_lost_unrevoked(?, ?)", ts(finishedBefore), limit);
    }

    private List<RunRef> refs(String sql, Object... params) {
        return jdbc.sql(sql)
            .params(params)
            .query((rs, row) -> new RunRef(rs.getObject("run_id", UUID.class), rs.getObject("company_id", UUID.class)))
            .list();
    }

    private static RunRow run(ResultSet rs, int row) throws SQLException {
        String hostKind = rs.getString("runner_host_kind");
        Long relayRequests = rs.getObject("relay_requests", Long.class);
        return new RunRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getString("conversation_key"),
                rs.getObject("agent_id", UUID.class), RuntimeKind.valueOf(rs.getString("runtime")),
                TurnReason.valueOf(rs.getString("turn_reason")), rs.getInt("turn_number"),
                rs.getObject("acting_principal_id", UUID.class), rs.getObject("payer_principal_id", UUID.class),
                rs.getString("model_access"), rs.getObject("subscription_host_id", UUID.class),
                rs.getString("subscription_label"), rs.getObject("runner_host_id", UUID.class),
                rs.getString("runner_host_name"), hostKind != null ? HostScope.valueOf(hostKind) : null,
                RunStatus.valueOf(rs.getString("status")), rs.getLong("epoch"), instant(rs, "dispatched_at"),
                instant(rs, "dispatch_after"), instant(rs, "lease_expires_at"), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), instant(rs, "started_at"),
                instant(rs, "finished_at"), rs.getString("failure_class"), rs.getString("failure_message"),
                rs.getString("summary"), rs.getString("session_id"), rs.getString("branch"),
                rs.getObject("input_tokens", Long.class), rs.getObject("output_tokens", Long.class),
                rs.getObject("cached_input_tokens", Long.class), rs.getObject("cache_write_tokens", Long.class),
                rs.getObject("cost_usd", Double.class), rs.getLong("last_event_seq"), rs.getInt("event_count"),
                QueueReason.fromColumns(rs.getString("queue_reason"), rs.getString("queue_reason_details")),
                instant(rs, "queue_reason_since"), enumOrNull(LostCause.class, rs.getString("lost_cause")),
                result(rs), counts(rs), rs.getObject("resumed", Boolean.class),
                enumOrNull(RunnerMessages.ResumeOutcome.class, rs.getString("resume_outcome")),
                relayRequests != null ? new ModelRelay.RelayUsage(relayRequests, rs.getLong("relay_request_bytes"),
                        rs.getLong("relay_response_bytes")) : null,
                instant(rs, "texts_purged_at"), instant(rs, "events_purged_at"),
                rs.getObject("trigger_id", UUID.class));
    }

    private static @Nullable Counts counts(ResultSet rs) throws SQLException {
        Integer toolCalls = rs.getObject("tool_call_count", Integer.class);
        if (toolCalls == null) {
            return null;
        }
        return new Counts(toolCalls, rs.getInt("message_count"), rs.getInt("thought_count"));
    }

    private static @Nullable Result result(ResultSet rs) throws SQLException {
        String outcome = rs.getString("result_outcome");
        if (outcome == null) {
            return null;
        }
        return new Result(RunnerMessages.ResultOutcome.valueOf(outcome),
                JSON.readValue(rs.getString("result_next_steps"), STRINGS),
                JSON.readValue(rs.getString("result_open_questions"), STRINGS));
    }

    private static <E extends Enum<E>> @Nullable E enumOrNull(Class<E> type, @Nullable String value) {
        return value != null ? Enum.valueOf(type, value) : null;
    }

    static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
