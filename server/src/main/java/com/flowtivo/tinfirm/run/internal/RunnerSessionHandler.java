package com.flowtivo.tinfirm.run.internal;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.run.RunEvents;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnEnded;
import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RunnerProperties;
import com.flowtivo.tinfirm.runner.RunnerSessionListener;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The run side of the runner protocol ({@link RunnerSessionListener}), each frame kind behind its own method. Every
 * frame is handled in one transaction in the tenant context of the host's company, after these checks:
 * <ol>
 * <li>the run exists in that company, else {@code error UNKNOWN_RUN};</li>
 * <li>the run is assigned to this host (or was taken back from it), else {@code error UNKNOWN_RUN};</li>
 * <li>the frame carries the run's current epoch, else {@code error STALE_EPOCH} and the frame is ignored. For
 * {@code run.accepted} and {@code run.heartbeat} the host is also told to cancel that stale execution.</li>
 * </ol>
 * The platform requests are the exceptions: each v2 {@code run.request} gets exactly one {@code run.request.ack}
 * ({@link RunRequestRouter}), and each v1 {@code run.ask}, {@code run.repository_request} and {@code run.task_create}
 * one ack of its kind, {@code REJECTED} / {@code RUN_NOT_ACTIVE}: runs go to hosts of protocol v2 only, so a v1 host
 * has none.
 * <p>
 * Leases: heartbeats, events, requests and a repeated {@code run.accepted} of a running run renew its lease; any frame
 * of the host renews all its running runs ({@link #hostActive}); a closed connection gives them the reconnect grace
 * ({@link #hostDisconnected}). A run lost because its lease expired is resumed ({@link RunResumption}) when its host
 * still runs it: it lists the run in {@code hello}, or sends a heartbeat or {@code run.finished} for it.
 * <p>
 * Locks (CONVENTIONS §10): a frame that may write the run's conversation ({@code run.accepted}, {@code run.rejected},
 * {@code run.finished}, a heartbeat that resumes a run, {@code hello}, a platform request, {@code run.steer.ack}) locks
 * the conversation's row before the run's, and {@code hello} the conversations of all the runs it handles, in the
 * order of their ids, before any run; an event frame and a lease's renewal lock runs only.
 * <p>
 * Flood controls (the security review of 2026-10-27, M4), before any transaction ({@link RunnerFloodControl}): a
 * {@code run.request} over its host's or its company's rate is answered {@code REJECTED TOO_MANY_REQUESTS}; a
 * {@code run.event} over its run's, its host's or its company's rate, or over the schema's sizes, is not stored and
 * answered {@code error TOO_MANY_REQUESTS} or {@code error PAYLOAD_TOO_LARGE} (a {@code LOG} event over the rate is
 * dropped without an answer). In the event's transaction, a run's stored payloads are at most
 * {@code tinfirm.runner.events-max-bytes}: the first event past them is stored as one {@code LOG} warning in its place,
 * and from then on the run's {@code LOG} events are dropped and its other events answered
 * {@code error RUN_EVENTS_FULL}.
 */
@Component
class RunnerSessionHandler implements RunnerSessionListener {

    private static final Logger log = LoggerFactory.getLogger(RunnerSessionHandler.class);

    private static final int MAX_SUMMARY_LENGTH = 65_536;

    private static final int MAX_RESULT_SUMMARY_LENGTH = 20_000;

    /**
     * The largest token count kept from a host (the security review of 2026-10-27, I7): a quarter of a {@code long}, so
     * that the sums of a few runs' counts cannot overflow; a larger count is kept as this, a negative one as 0.
     */
    static final long MAX_USAGE = Long.MAX_VALUE / 4;

    private static final BigInteger MAX_USAGE_BIG = BigInteger.valueOf(MAX_USAGE);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RunStore runs;

    private final RunLocks locks;

    private final RunEventStore events;

    private final RunCommandStore commands;

    private final RunCompletion completion;

    private final RunResumption resumption;

    private final RunRequestRouter requests;

    private final RunDispatcher dispatcher;

    private final RunEventHub hub;

    private final RunMetrics metrics;

    private final ApplicationEventPublisher publisher;

    private final SystemTransactions transactions;

    private final RunnerProperties runnerProperties;

    private final RunProperties properties;

    private final TimeSource time;

    private final PublicSuffixPush publicSuffixes;

    private final RunPorts ports;

    private final SessionBundleCleanup bundles;

    private final RunnerFloodControl flood;

    RunnerSessionHandler(RunStore runs, RunLocks locks, RunEventStore events, RunCommandStore commands,
            RunCompletion completion, RunResumption resumption, RunRequestRouter requests, RunDispatcher dispatcher,
            RunEventHub hub, RunMetrics metrics, ApplicationEventPublisher publisher, SystemTransactions transactions,
            RunnerProperties runnerProperties, RunProperties properties, TimeSource time,
            PublicSuffixPush publicSuffixes, RunPorts ports, SessionBundleCleanup bundles, RunnerFloodControl flood) {
        this.flood = flood;
        this.ports = ports;
        this.bundles = bundles;
        this.runs = runs;
        this.locks = locks;
        this.events = events;
        this.commands = commands;
        this.completion = completion;
        this.resumption = resumption;
        this.requests = requests;
        this.dispatcher = dispatcher;
        this.hub = hub;
        this.metrics = metrics;
        this.publisher = publisher;
        this.transactions = transactions;
        this.runnerProperties = runnerProperties;
        this.properties = properties;
        this.time = time;
        this.publicSuffixes = publicSuffixes;
    }

    /** The host confirmed its capacity: it may take runs now (the first time on a connection) or fewer or more. */
    @Override
    public void hostConfigured(ConnectedHost host) {
        dispatcher.nudge();
    }

    /** The host talks, so it is alive: all its running runs get a lease of at least now + TTL. */
    @Override
    public void hostActive(ConnectedHost host) {
        transactions.runInCompany(host.companyId(),
                () -> runs.renewHostLeases(host.runnerHostId(), lease(time.now())));
    }

    /**
     * The host's connection is gone: its runs that hold a slot keep a lease (an assigned run: its accept deadline) of
     * at least now + the reconnect grace, never a shorter one. The host reconnects with backoff and reports them in
     * {@code hello}.
     */
    @Override
    public void hostDisconnected(ConnectedHost host) {
        Instant until = time.now().plus(runnerProperties.reconnectGrace());
        int extended = transactions.inCompany(host.companyId(),
                () -> runs.extendHostLeases(host.runnerHostId(), until));
        if (extended > 0) {
            log.info("Runner host {} disconnected; its {} active runs keep their lease until at least {}",
                    host.runnerHostId(), extended, until);
        }
    }

    @Override
    public List<RunnerMessages.Outbound> runMessage(ConnectedHost host, RunnerMessages.RunMessage message) {
        if (message instanceof RunnerMessages.RunRequest request) {
            if (!flood.admitRequest(host)) {
                metrics.refused(RunMetrics.REQUEST, RunMetrics.RATE);
                return List.of(RunRequestRouter.rejected(request, RunnerMessages.TOO_MANY_REQUESTS,
                        "This runner host sends more requests than the server takes. Wait a moment before you "
                                + "ask again."));
            }
            if (RunnerMessages.RequestKind.TOOL.name().equals(request.kind())) {
                // A call on a system may take a minute: never in a transaction, never holding up the frames.
                return requests.call(host, request);
            }
        }
        if (message instanceof RunnerMessages.RunEvent event) {
            return event(host, event);
        }
        return transactions.inCompany(host.companyId(), () -> switch (message) {
            case RunnerMessages.RunAccepted accepted -> accepted(host, accepted);
            case RunnerMessages.RunRejected rejected -> rejected(host, rejected);
            case RunnerMessages.RunEvent event -> throw new IllegalStateException("An event is handled above");
            case RunnerMessages.RunHeartbeat heartbeat -> heartbeat(host, heartbeat);
            case RunnerMessages.RunFinished finished -> finished(host, finished);
            case RunnerMessages.RunRequest request -> requests.request(host, request);
            case RunnerMessages.RunSteerAck ack -> steered(host, ack);
            case RunnerMessages.RunRepositoryRequest request -> List.of(RunnerMessages.RunRepositoryRequestAck
                .rejected(request.runId(), request.requestId(), RunRequestRouter.RUN_NOT_ACTIVE, NOT_V2));
            case RunnerMessages.RunTaskCreate create -> List.of(RunnerMessages.RunTaskCreateAck
                .rejected(create.runId(), create.requestId(), RunRequestRouter.RUN_NOT_ACTIVE, NOT_V2));
            case RunnerMessages.RunAsk ask -> List.of(RunnerMessages.RunAskAck
                .rejected(ask.runId(), ask.requestId(), RunRequestRouter.RUN_NOT_ACTIVE, NOT_V2));
        });
    }

    private static final String NOT_V2 = "The run is not active: this server runs conversations on hosts of runner "
            + "protocol 2.";

    /**
     * Reconciles after {@code hello}:
     * <ul>
     * <li>a running run the host reports with the current epoch is kept with a fresh lease, also when its lease
     * expired less than the resume window ago and the reaper has not marked it LOST yet (it would be resumed);</li>
     * <li>a running run the host no longer reports is LOST (the host restarted), and one it reports with another
     * epoch, or long after its lease expired, is LOST and cancelled;</li>
     * <li>an assignment the host never accepted goes back to the queue unless the host reports it running;</li>
     * <li>any other run the host reports is resumed when it was lost by lease expiry and the rule of
     * {@link RunResumption} allows it, and cancelled otherwise.</li>
     * </ul>
     * Pending cancel commands of the kept and resumed runs are sent again. A v2 host that holds no Public Suffix List,
     * or another version than the server's, gets the server's first ({@code psl.update}, {@link PublicSuffixPush}).
     */
    @Override
    public List<RunnerMessages.Outbound> hostConnected(ConnectedHost host, List<RunnerMessages.ActiveRun> activeRuns,
            @Nullable String publicSuffixSha256) {
        return transactions.inCompany(host.companyId(), () -> {
            Instant now = time.now();
            Map<UUID, RunnerMessages.ActiveRun> reported = new HashMap<>();
            activeRuns.forEach(active -> reported.put(active.runId(), active));
            List<RunnerMessages.Outbound> replies = new ArrayList<>();
            publicSuffixes.forHost(host, publicSuffixSha256).ifPresent(replies::add);
            List<UUID> kept = new ArrayList<>();
            List<RunStore.RunRow> occupying = runs.occupyingOnHost(host.runnerHostId());
            // Every run below may end, go back to the queue or run again, which its conversation hears of: all their
            // conversations first, in the order of their ids, then the runs (CONVENTIONS §10).
            List<RunStore.RunRow> handled = new ArrayList<>(occupying);
            reported.keySet().forEach(runId -> runs.find(runId).ifPresent(handled::add));
            locks.lockConversations(handled);
            for (RunStore.RunRow listed : occupying) {
                RunStore.RunRow run = runs.lock(listed.id()).orElse(null);
                if (run == null || !run.status().active()) {
                    continue;
                }
                RunnerMessages.ActiveRun active = reported.remove(run.id());
                boolean current = active != null && active.epoch() == run.epoch();
                if (run.status() == RunStatus.RUNNING) {
                    if (current && run.leaseExpiresAt() != null
                            && now.isBefore(run.leaseExpiresAt().plus(runnerProperties.lostResumeWindow()))) {
                        runs.renewLease(run.id(), run.epoch(), host.runnerHostId(), lease(now));
                        kept.add(run.id());
                        continue;
                    }
                    if (active == null) {
                        completion.lose(run, LostCause.HOST_RESTARTED,
                                "The runner host restarted and no longer runs it.");
                    }
                    else {
                        completion.lose(run, LostCause.STALE_ON_RECONNECT, current
                                ? "The run's lease expired while the runner host was disconnected."
                                : "The runner host reconnected with another execution of the run.");
                        replies.add(new RunnerMessages.RunCancel(active.runId(), active.epoch()));
                    }
                }
                else if (current) {
                    // Assigned, and running on the host although its run.accepted never arrived.
                    markRunning(run, now);
                    kept.add(run.id());
                }
                else {
                    runs.requeue(run.id(), null, null, now);
                    publisher.publishEvent(TurnStarter.queueReasonChanged(run, null));
                    hub.changedAfterCommit(run.id());
                    if (active != null) {
                        replies.add(new RunnerMessages.RunCancel(active.runId(), active.epoch()));
                    }
                }
            }
            for (RunnerMessages.ActiveRun other : reported.values()) {
                RunStore.RunRow run = runs.lock(other.runId()).orElse(null);
                if (run != null && resumption.resume(host, run, other.epoch(), now, "hello")) {
                    kept.add(run.id());
                }
                else {
                    replies.add(new RunnerMessages.RunCancel(other.runId(), other.epoch()));
                }
            }
            replies.addAll(pendingCancels(kept, now));
            return replies;
        });
    }

    /** {@code run.cancel} for the runs with a pending cancel command, which count as sent again. */
    private List<RunnerMessages.Outbound> pendingCancels(List<UUID> runIds, Instant now) {
        List<RunnerMessages.Outbound> cancels = new ArrayList<>();
        for (UUID runId : commands.pendingCancels(runIds)) {
            runs.find(runId).ifPresent(run -> cancels.add(new RunnerMessages.RunCancel(run.id(), run.epoch())));
            commands.markSent(runId, now);
        }
        return cancels;
    }

    /** The assigned run runs on its host: RUNNING with a lease, and its conversation is told. */
    private void markRunning(RunStore.RunRow run, Instant now) {
        runs.markRunning(run.id(), now, lease(now));
        publisher.publishEvent(new RunEvents.RunAccepted(run.companyId(), run.id(), run.conversationId()));
        hub.changedAfterCommit(run.id());
    }

    // ---- run.accepted / run.rejected ------------------------------------------------------------------------------

    private List<RunnerMessages.Outbound> accepted(ConnectedHost host, RunnerMessages.RunAccepted accepted) {
        RunStore.RunRow run = locks.lock(accepted.runId()).orElse(null);
        List<RunnerMessages.Outbound> problem = check(host, run, accepted.runId(), accepted.epoch(), true);
        if (problem != null) {
            return problem;
        }
        switch (run.status()) {
            case QUEUED -> {
                if (run.dispatchedAt() == null) {
                    return stale(accepted.runId(), accepted.epoch(), true);
                }
                markRunning(run, time.now());
                log.info("Run {} of conversation {} accepted by runner host {}", run.id(), run.conversationKey(),
                        host.runnerHostId());
                return List.of();
            }
            case RUNNING -> {
                runs.renewLease(run.id(), run.epoch(), host.runnerHostId(), lease(time.now()));
                return List.of();
            }
            default -> {
                // Cancelled (or otherwise ended) while the assignment was on its way.
                return List.of(new RunnerMessages.RunCancel(run.id(), run.epoch()));
            }
        }
    }

    private List<RunnerMessages.Outbound> rejected(ConnectedHost host, RunnerMessages.RunRejected rejected) {
        RunStore.RunRow run = locks.lock(rejected.runId()).orElse(null);
        List<RunnerMessages.Outbound> problem = check(host, run, rejected.runId(), rejected.epoch(), false);
        if (problem != null) {
            return problem;
        }
        if (run.status() == RunStatus.QUEUED && run.dispatchedAt() != null) {
            log.info("Runner host {} rejected run {} ({}): {}", host.runnerHostId(), run.id(),
                    abbreviate(rejected.code(), 300), abbreviate(rejected.reason(), 300));
            // The run waits until retryAt: the dispatcher does not look at it before, so the reason stays.
            Instant now = time.now();
            Instant retryAt = now.plus(properties.rejectBackoff());
            QueueReason reason = QueueReason.rejected(run.runnerHostId(), run.runnerHostName(), rejected.code(),
                    rejected.reason(), retryAt);
            runs.requeue(run.id(), retryAt, reason, now);
            publisher.publishEvent(TurnStarter.queueReasonChanged(run, reason));
            hub.changedAfterCommit(run.id());
        }
        return List.of();
    }

    // ---- run.event / run.heartbeat --------------------------------------------------------------------------------

    /**
     * The flood controls of an event, before any transaction: its sizes, then the rates. An event within them is
     * stored ({@link #storeEvent}).
     */
    private List<RunnerMessages.Outbound> event(ConnectedHost host, RunnerMessages.RunEvent event) {
        String payload = event.payload().toString();
        long bytes = RunnerFloodControl.utf8Length(payload);
        String tooLarge = RunnerFloodControl.oversized(event.eventType(), event.payload(), bytes);
        if (tooLarge != null) {
            metrics.refused(RunMetrics.EVENT, RunMetrics.SIZE);
            return List.of(RunnerMessages.ProtocolError.of(RunnerMessages.PAYLOAD_TOO_LARGE,
                    "Event " + event.seq() + ": " + tooLarge, event.runId()));
        }
        if (!flood.admitEvent(host, event.runId())) {
            metrics.refused(RunMetrics.EVENT, RunMetrics.RATE);
            return event.eventType() == RunnerMessages.EventType.LOG ? List.of()
                    : List.of(RunnerMessages.ProtocolError.of(RunnerMessages.TOO_MANY_REQUESTS, "Event "
                            + event.seq() + " was not stored: this run, its runner host or its company sends more "
                            + "events than the server takes.", event.runId()));
        }
        return transactions.inCompany(host.companyId(), () -> storeEvent(host, event, payload, bytes));
    }

    /**
     * Stores an event of an active run unless (run, seq) is already stored. At the cap, the oldest LOG event makes
     * room; a LOG event that finds no room is dropped, and so is any event once no LOG event is left. An event of a
     * run that ended (a host that lags behind the run's end on the server, or sends its last frames again after a
     * reconnect) is dropped and counted: the run's events are complete once it ended. Past the run's byte budget, the
     * first event is stored as a {@code LOG} warning, and every later one is dropped ({@code LOG}) or refused.
     *
     * @param payload the event's payload as stored
     * @param bytes   its size in UTF-8
     */
    private List<RunnerMessages.Outbound> storeEvent(ConnectedHost host, RunnerMessages.RunEvent event,
            String payload, long bytes) {
        RunStore.RunRow run = runs.lock(event.runId()).orElse(null);
        List<RunnerMessages.Outbound> problem = check(host, run, event.runId(), event.epoch(), false);
        if (problem != null) {
            return problem;
        }
        if (!run.status().active()) {
            metrics.lateEvent();
            log.debug("Run {} ended {}; event {} of runner host {} is dropped", run.id(), run.status(), event.seq(),
                    host.runnerHostId());
            return List.of();
        }
        if (events.exists(run.id(), event.seq())) {
            return List.of();
        }
        long budget = runnerProperties.eventsMaxBytes().toBytes();
        long used = runs.eventBytes(run.id());
        if (used + bytes > budget) {
            return overBudget(run, event, used, budget);
        }
        Stored stored = store(run, event.seq(), event.eventType(), RunnerMessages.parseInstant(event.at()), payload,
                event.eventType() != RunnerMessages.EventType.LOG);
        if (event.eventType() == RunnerMessages.EventType.USAGE && event.seq() >= run.lastEventSeq()) {
            // Cumulative totals: the newest USAGE event is the run's usage.
            JsonNode usage = event.payload();
            runs.recordUsage(run.id(), usage(usage, "inputTokens"), usage(usage, "outputTokens"),
                    usage(usage, "cachedInputTokens"), usage(usage, "cacheWriteTokens"), cost(usage, "costUsd"));
        }
        runs.recordEvents(run.id(), stored.added(), stored.inserted() ? bytes : 0, event.seq(), lease(time.now()));
        hub.changedAfterCommit(run.id());
        return List.of();
    }

    /**
     * An event past the run's byte budget. The first one is replaced by a {@code LOG} warning at its {@code seq}, and
     * the budget counts as spent; this one and every later one is dropped, a {@code LOG} event without an answer.
     */
    private List<RunnerMessages.Outbound> overBudget(RunStore.RunRow run, RunnerMessages.RunEvent event, long used,
            long budget) {
        metrics.refused(RunMetrics.EVENT, RunMetrics.BUDGET);
        Stored stored = Stored.NOTHING;
        if (used < budget) {
            log.warn("Run {} on runner host {} reached its event budget of {} bytes; later events are not stored",
                    run.id(), run.runnerHostId(), budget);
            // The warning makes room at the event cap as any event but a LOG one does.
            stored = store(run, event.seq(), RunnerMessages.EventType.LOG, time.now(), budgetWarning(budget), true);
        }
        runs.recordEvents(run.id(), stored.added(), Math.max(0, budget - used), event.seq(), lease(time.now()));
        if (stored.inserted()) {
            hub.changedAfterCommit(run.id());
        }
        return event.eventType() == RunnerMessages.EventType.LOG ? List.of()
                : List.of(RunnerMessages.ProtocolError.of(RunnerMessages.RUN_EVENTS_FULL, "Event " + event.seq()
                        + " was not stored: the run's events reached the server's limit of " + budget + " bytes.",
                        run.id()));
    }

    /** The {@code LOG} payload that tells the run's viewers its later events are not kept. */
    static String budgetWarning(long budget) {
        return JSON.createObjectNode()
            .put("level", "warn")
            .put("message", "The run's events reached the server's limit of " + mebibytes(budget) + " MiB: its "
                    + "later events are not kept.")
            .toString();
    }

    private static String mebibytes(long bytes) {
        long mebibytes = bytes / (1024 * 1024);
        return mebibytes * 1024 * 1024 == bytes ? Long.toString(mebibytes)
                : String.format(java.util.Locale.ROOT, "%.1f", bytes / (1024.0 * 1024));
    }

    /**
     * What {@link #store} did.
     *
     * @param added    the change of the run's event count: -1, 0 or 1
     * @param inserted whether the event was stored
     */
    private record Stored(int added, boolean inserted) {

        static final Stored NOTHING = new Stored(0, false);
    }

    /**
     * Stores the event within the run's event cap: at the cap, the oldest LOG event makes room for an event that
     * may make room (any but a LOG event); one that finds no room is dropped.
     */
    private Stored store(RunStore.RunRow run, long seq, RunnerMessages.EventType type, Instant at, String payload,
            boolean makesRoom) {
        int added = 0;
        boolean room = run.eventCount() < properties.maxEventsPerRun();
        if (!room && makesRoom && events.deleteOldestLog(run.id())) {
            room = true;
            added--;
        }
        boolean inserted = room && events.insert(run.companyId(), run.id(), seq, type.name(), at, payload);
        return new Stored(inserted ? added + 1 : added, inserted);
    }

    /**
     * A token count of a {@code USAGE} payload, within {@code [0, MAX_USAGE]}: a larger one, even beyond a
     * {@code long}, is {@link #MAX_USAGE}, a negative one 0; null when the field is absent or not an integer.
     */
    static @Nullable Long usage(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        if (value == null || !value.isIntegralNumber()) {
            return null;
        }
        BigInteger count = value.bigIntegerValue();
        return count.signum() < 0 ? 0L : count.compareTo(MAX_USAGE_BIG) > 0 ? MAX_USAGE : count.longValue();
    }

    /** A cost of a {@code USAGE} payload: null when absent, not a number, negative or not finite. */
    static @Nullable Double cost(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value != null && value.isNumber() ? cost(value.doubleValue(Double.NaN)) : null;
    }

    /** A token count of {@code run.finished.usage}, within {@code [0, MAX_USAGE]}. */
    static @Nullable Long usage(@Nullable Long count) {
        return count == null ? null : Math.clamp(count, 0, MAX_USAGE);
    }

    /** A cost of {@code run.finished.usage}: null when negative or not finite. */
    static @Nullable Double cost(@Nullable Double cost) {
        return cost == null || !Double.isFinite(cost) || cost < 0 ? null : cost;
    }

    private List<RunnerMessages.Outbound> heartbeat(ConnectedHost host, RunnerMessages.RunHeartbeat heartbeat) {
        List<RunnerMessages.Outbound> replies = new ArrayList<>();
        Instant now = time.now();
        Instant lease = lease(now);
        // A run that ended on the server may be resumed below, which its conversation hears of: the conversations of
        // those runs first, in the order of their ids (CONVENTIONS §10).
        locks.lockConversations(heartbeat.runs()
            .stream()
            .map(entry -> runs.find(entry.runId()).orElse(null))
            .filter(found -> found != null && !found.status().active())
            .toList());
        for (RunnerMessages.HeartbeatRun entry : heartbeat.runs()) {
            RunStore.RunRow run = runs.find(entry.runId()).orElse(null);
            List<RunnerMessages.Outbound> problem = check(host, run, entry.runId(), entry.epoch(), true);
            if (problem != null) {
                replies.addAll(problem);
                continue;
            }
            if (run.status() == RunStatus.RUNNING) {
                runs.renewLease(run.id(), run.epoch(), host.runnerHostId(), lease);
            }
            else if (!run.status().active()) {
                // Ended on the server. A run lost by lease expiry that the host still runs is resumed; the host must
                // stop any other.
                RunStore.RunRow locked = locks.lock(run.id()).orElse(run);
                if (locked.status() == RunStatus.RUNNING) {
                    runs.renewLease(locked.id(), locked.epoch(), host.runnerHostId(), lease);
                }
                else if (resumption.resume(host, locked, entry.epoch(), now, "run.heartbeat")) {
                    replies.addAll(pendingCancels(List.of(locked.id()), now));
                }
                else {
                    replies.add(new RunnerMessages.RunCancel(run.id(), run.epoch()));
                }
            }
        }
        return replies;
    }

    // ---- run.steer.ack --------------------------------------------------------------------------------------------

    /**
     * The host's answer to {@code run.steer}: the run's conversation hears whether the running turn took the person's
     * message. An answer about a run that is no longer running changes nothing: what the turn did not take waits for
     * the next one.
     */
    private List<RunnerMessages.Outbound> steered(ConnectedHost host, RunnerMessages.RunSteerAck ack) {
        RunStore.RunRow run = locks.lock(ack.runId()).orElse(null);
        List<RunnerMessages.Outbound> problem = check(host, run, ack.runId(), ack.epoch(), false);
        if (problem != null) {
            return problem;
        }
        if (run.status() != RunStatus.RUNNING) {
            return List.of();
        }
        boolean delivered = ack.status() == RunnerMessages.SteerStatus.DELIVERED;
        if (!delivered) {
            log.info("Run {} did not take message {}: {} {}", run.id(), ack.steerId(), abbreviate(ack.code(), 100),
                    abbreviate(ack.message(), 300));
        }
        ports.steered().steered(run.active(), ack.steerId(), delivered, delivered ? null : ack.code());
        return List.of();
    }

    // ---- run.finished ---------------------------------------------------------------------------------------------

    private List<RunnerMessages.Outbound> finished(ConnectedHost host, RunnerMessages.RunFinished finished) {
        RunStore.RunRow run = locks.lock(finished.runId()).orElse(null);
        List<RunnerMessages.Outbound> problem = check(host, run, finished.runId(), finished.epoch(), false);
        if (problem != null) {
            return problem;
        }
        if (!run.status().active()) {
            // Ended on the server. A run lost by lease expiry that the host still ran is resumed and ends with the
            // host's outcome, so its git results count; any other finish is ignored.
            if (!resumption.resume(host, run, finished.epoch(), time.now(), "run.finished")) {
                return List.of();
            }
            run = runs.find(run.id()).orElseThrow();
        }
        RunStore.Outcome outcome = outcome(finished);
        completion.finish(run, outcome, outputFiles(finished));
        // A session reported without its bundle stored leaves no older bundle behind (ADR-0097).
        bundles.finished(run.conversationId(), run.agentId(), run.id(), finished.sessionId(),
                finished.sessionBundle());
        metrics.runFinished(outcome.resumed(), outcome.resumeOutcome(), run.runtime());
        log.info("Run {} of conversation {} finished on runner host {}: {}", run.id(), run.conversationKey(),
                host.runnerHostId(), finished.outcome());
        return List.of();
    }

    /** The files of {@code /output} a v2 host lists in {@code run.finished.artifacts}, for the conversation. */
    private static List<TurnEnded.OutputFile> outputFiles(RunnerMessages.RunFinished finished) {
        if (finished.artifacts() == null) {
            return List.of();
        }
        return finished.artifacts()
            .stream()
            .map(artifact -> new TurnEnded.OutputFile(artifact.attachmentId(), artifact.name(), artifact.sizeBytes()))
            .toList();
    }

    /**
     * What the host reported, within the server's limits ({@code componentId} is the repository id). A resumed run
     * has no resume outcome, and an outcome without {@code resumed} is not kept.
     */
    static RunStore.Outcome outcome(RunnerMessages.RunFinished finished) {
        List<RunStore.GitResult> git = finished.git() != null ? finished.git()
            .stream()
            .map(result -> new RunStore.GitResult(result.componentId(), result.pushed(), result.headSha(),
                    result.commits()))
            .toList() : null;
        RunnerMessages.Usage usage = finished.usage();
        RunStatus status = RunStatus.valueOf(finished.outcome().name());
        String failureClass = finished.failureClass() != null ? finished.failureClass().name() : null;
        // With finish, its summary is the run's summary; else the agent's last message. A summary is at most 64 KiB,
        // a result's 20 000 characters by the protocol; a longer one is cut rather than losing the run's result.
        RunnerMessages.RunResult reported = finished.result();
        String summary = reported != null ? RunStore.Result.cut(reported.summary(), MAX_RESULT_SUMMARY_LENGTH)
                : abbreviate(finished.summary(), MAX_SUMMARY_LENGTH);
        return new RunStore.Outcome(status, failureClass, abbreviate(finished.failureMessage(), 2000), summary,
                finished.sessionId(), git, usage != null ? usage(usage.inputTokens()) : null,
                usage != null ? usage(usage.outputTokens()) : null,
                usage != null ? usage(usage.cachedInputTokens()) : null,
                usage != null ? usage(usage.cacheWriteTokens()) : null, usage != null ? cost(usage.costUsd()) : null,
                finished.lastSeq(), reported != null ? RunStore.Result.of(reported) : null, finished.resumed(),
                Boolean.FALSE.equals(finished.resumed()) ? finished.resumeOutcome() : null);
    }

    // ---- common checks --------------------------------------------------------------------------------------------

    /**
     * The common checks of a run frame; null when the frame may be processed.
     *
     * @param cancelStale whether a stale execution should also be told to stop
     */
    private @Nullable List<RunnerMessages.Outbound> check(ConnectedHost host, RunStore.@Nullable RunRow run,
            UUID runId, long epoch, boolean cancelStale) {
        if (run == null) {
            return List.of(RunnerMessages.ProtocolError.of(RunnerMessages.UNKNOWN_RUN, "Unknown run", runId));
        }
        if (run.runnerHostId() != null && !run.runnerHostId().equals(host.runnerHostId())) {
            return List.of(RunnerMessages.ProtocolError.of(RunnerMessages.UNKNOWN_RUN,
                    "The run is not assigned to this runner host", runId));
        }
        if (run.epoch() != epoch) {
            return stale(runId, epoch, cancelStale);
        }
        if (run.runnerHostId() == null) {
            return List.of(RunnerMessages.ProtocolError.of(RunnerMessages.UNKNOWN_RUN,
                    "The run is not assigned to this runner host", runId));
        }
        return null;
    }

    private static List<RunnerMessages.Outbound> stale(UUID runId, long epoch, boolean cancel) {
        RunnerMessages.ProtocolError error = RunnerMessages.ProtocolError.of(RunnerMessages.STALE_EPOCH,
                "Epoch " + epoch + " of this run is no longer current", runId);
        return cancel ? List.of(error, new RunnerMessages.RunCancel(runId, epoch)) : List.of(error);
    }

    private Instant lease(Instant now) {
        return now.plus(runnerProperties.leaseTtl());
    }

    private static @Nullable String abbreviate(@Nullable String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
