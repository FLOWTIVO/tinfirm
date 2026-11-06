package com.flowtivo.tinfirm.run.internal;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.RunVisibility;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.RunEventPage;
import com.flowtivo.tinfirm.shared.api.model.RunEventType;
import com.flowtivo.tinfirm.shared.security.SessionWatch;
import com.flowtivo.tinfirm.shared.security.SessionWatches;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * Run events over HTTP: pages, and the live stream as Server-Sent Events.
 * <p>
 * A stream is one virtual thread in the caller's tenant context, established by the access check. It sends
 * {@code run-status} with the current run first, replays events after {@code afterSeq} (or {@code Last-Event-ID}) from
 * the database, then follows live, woken by {@link RunEventHub} signals and reading the database again each time, so
 * events arrive in sequence order without gaps or duplicates. Status changes, and changes of a QUEUED run's queue
 * reason, are sent as {@code run-status}; after the run ends, all remaining events are sent, then a final
 * {@code run-status}, and the stream closes. Only {@code run-event} frames carry an {@code id}. A comment every
 * {@code tinfirm.run.stream-keep-alive} (15 s) keeps idle connections open through proxies. The stream times out after
 * {@code tinfirm.run.stream-timeout}; the client then reconnects with {@code Last-Event-ID}. Each read of the database
 * checks again, as the caller, that they still may see the run ({@link CompanyAccessGuard#recheck} and the run's
 * visibility): a member removed, a role lowered, a user deactivated or a conversation no longer theirs to see ends the
 * stream without another frame. So does the end of the browser session that opened it: logout, a password change, the
 * idle timeout, or the session's absolute lifetime ({@link SessionWatch}).
 */
@Service
class RunEventService {

    private static final Logger log = LoggerFactory.getLogger(RunEventService.class);

    private static final int REPLAY_PAGE = 500;

    /** The most events one page holds. */
    static final int MAX_EVENTS = 1000;

    private final RunStore runs;

    private final RunEventStore events;

    private final RunEventHub hub;

    private final CompanyAccessGuard companyAccess;

    private final RunService runService;

    private final RunPresenter presenter;

    private final RunWorkers workers;

    private final RunProperties properties;

    private final TransactionTemplate transactions;

    private final TransactionTemplate readOnly;

    private final SessionWatches sessionWatches;

    RunEventService(RunStore runs, RunEventStore events, RunEventHub hub, CompanyAccessGuard companyAccess,
            RunService runService, RunPresenter presenter, RunWorkers workers, RunProperties properties,
            PlatformTransactionManager transactionManager, SessionWatches sessionWatches) {
        this.runs = runs;
        this.events = events;
        this.hub = hub;
        this.companyAccess = companyAccess;
        this.runService = runService;
        this.presenter = presenter;
        this.workers = workers;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.sessionWatches = sessionWatches;
    }

    /**
     * Events after {@code afterSeq}, only those of {@code types} unless it is empty; {@code complete} when the run has
     * ended and no later events exist.
     */
    RunEventPage list(UUID companyId, UUID runId, long afterSeq, Collection<RunEventType> types, int limit) {
        if (afterSeq < 0 || limit < 1 || limit > MAX_EVENTS) {
            throw ApiException.validation("afterSeq is 0 or more, and limit 1 to " + MAX_EVENTS);
        }
        List<String> typeNames = types.stream().map(RunEventType::getValue).distinct().toList();
        return transactions.execute(status -> {
            CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
            // The status is read before the events: once it is final, every event is already stored.
            RunStore.RunRow run = runService.visible(access, runId, RunVisibility.Read.EVENTS);
            List<RunEventStore.EventRow> rows = events.after(runId, afterSeq, typeNames, limit);
            return new RunEventPage(rows.stream().map(presenter::event).toList(),
                    !run.status().active() && rows.size() < limit);
        });
    }

    /**
     * The live stream of the run's events, once the caller may see the run ({@code 404} otherwise). Every poll checks
     * again that the caller still may (the company's access and the run's visibility), and the stream ends when they
     * no longer may, or when the HTTP session it was opened with no longer exists or is past its absolute lifetime.
     */
    SseEmitter stream(UUID companyId, UUID runId, long afterSeq) {
        Granted granted = transactions.execute(status -> {
            CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
            // The stream is one read, recorded when it opens; each poll checks again without recording.
            runService.visible(access, runId, RunVisibility.Read.STREAM);
            return new Granted(access, TenantContextHolder.current());
        });
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        // The session of this request, from its thread: the stream's own thread has no request.
        SessionWatch session = sessionWatches.open();
        SseEmitter emitter = new SseEmitter(properties.streamTimeout().toMillis());
        EventStream stream = new EventStream(runId, afterSeq, granted.context(), caller, session, granted.access(),
                emitter);
        emitter.onCompletion(stream::close);
        emitter.onTimeout(stream::close);
        emitter.onError(error -> stream.close());
        workers.execute("event stream of run " + runId, stream::pump);
        return emitter;
    }

    /** The access the stream was granted, and the tenant context it was granted in. */
    private record Granted(CompanyAccess access, TenantContext context) {
    }

    /** What one poll of the database saw: the run first, then its events after the last one sent. */
    private record Snapshot(RunStore.RunRow run, List<RunEventStore.EventRow> events) {

        /** What a {@code run-status} frame reports: the status and, while QUEUED, the queue reason. */
        Shown shown() {
            return new Shown(run.status(), run.queueReason(), run.queueReasonSince());
        }
    }

    private record Shown(RunStatus status, @Nullable QueueReason queueReason, @Nullable Instant queueReasonSince) {
    }

    private final class EventStream {

        private final UUID runId;

        private final TenantContext context;

        private final @Nullable Authentication caller;

        private final SessionWatch session;

        private final SseEmitter emitter;

        private final Semaphore signal = new Semaphore(0);

        private volatile boolean closed;

        private long lastSeq;

        private CompanyAccess access;

        EventStream(UUID runId, long afterSeq, TenantContext context, @Nullable Authentication caller,
                SessionWatch session, CompanyAccess access, SseEmitter emitter) {
            this.runId = runId;
            this.lastSeq = afterSeq;
            this.context = context;
            this.caller = caller;
            this.session = session;
            this.access = access;
            this.emitter = emitter;
        }

        void close() {
            closed = true;
            signal.release();
        }

        void pump() {
            // The caller's authentication on the stream's thread, for the checks of every poll.
            SecurityContext acting = SecurityContextHolder.createEmptyContext();
            acting.setAuthentication(caller);
            SecurityContextHolder.setContext(acting);
            try (TenantContextHolder.Scope scope = TenantContextHolder.open(context);
                    AutoCloseable subscription = hub.subscribe(runId, signal::release)) {
                Snapshot first = poll();
                if (first == null) {
                    emitter.complete();
                    return;
                }
                sendStatus(first.run());
                Shown lastShown = first.shown();
                Snapshot snapshot = first;
                while (!closed) {
                    for (RunEventStore.EventRow event : snapshot.events()) {
                        emitter.send(SseEmitter.event()
                            .name("run-event")
                            .id(Long.toString(event.seq()))
                            .data(presenter.event(event), MediaType.APPLICATION_JSON));
                        lastSeq = event.seq();
                    }
                    if (snapshot.events().size() < REPLAY_PAGE) {
                        RunStatus status = snapshot.run().status();
                        if (!status.active()) {
                            sendStatus(snapshot.run());
                            emitter.complete();
                            return;
                        }
                        if (!snapshot.shown().equals(lastShown)) {
                            sendStatus(snapshot.run());
                            lastShown = snapshot.shown();
                        }
                        if (!signal.tryAcquire(properties.streamKeepAlive().toMillis(), TimeUnit.MILLISECONDS)) {
                            emitter.send(SseEmitter.event().comment("keep-alive"));
                        }
                        signal.drainPermits();
                    }
                    if (!closed) {
                        Snapshot next = poll();
                        if (next == null) {
                            log.debug("Event stream of run {} ended: the caller no longer sees the run", runId);
                            emitter.complete();
                            return;
                        }
                        snapshot = next;
                    }
                }
            }
            catch (IOException | IllegalStateException ex) {
                // The client went away or the stream timed out.
                log.debug("Event stream of run {} ended: {}", runId, ex.getClass().getSimpleName());
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                emitter.complete();
            }
            catch (Exception ex) {
                log.warn("Event stream of run {} failed", runId, ex);
                emitter.completeWithError(ex);
            }
            finally {
                SecurityContextHolder.clearContext();
            }
        }

        /**
         * The run and its events after the last one sent, while the caller still may see them: the company's access
         * (a member removed, a role lowered, a user deactivated) and the run's visibility (its conversation) are
         * checked again each time, and so is the HTTP session the stream was opened with (outside the transaction: the
         * session store may delete an expired session). Null when they no longer may, which ends the stream.
         */
        private @Nullable Snapshot poll() {
            if (session.ended()) {
                return null;
            }
            try {
                return readOnly.execute(status -> {
                    access = companyAccess.recheck(access, CompanyPermission.VIEW_COMPANY);
                    RunStore.RunRow run = runService.stillVisible(access, runId);
                    return new Snapshot(run, events.after(runId, lastSeq, REPLAY_PAGE));
                });
            }
            catch (ApiException | AuthenticationException ex) {
                // Caught outside the transaction, which the guard's refusal marked for rollback.
                return null;
            }
        }

        private void sendStatus(RunStore.RunRow run) throws IOException {
            emitter.send(SseEmitter.event().name("run-status").data(presenter.run(run), MediaType.APPLICATION_JSON));
        }
    }
}
