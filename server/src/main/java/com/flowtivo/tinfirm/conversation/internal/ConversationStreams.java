package com.flowtivo.tinfirm.conversation.internal;

import java.io.IOException;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.ConversationView;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.ConversationChanged;
import com.flowtivo.tinfirm.shared.api.model.ConversationEndedReason;
import com.flowtivo.tinfirm.shared.api.model.ConversationState;
import com.flowtivo.tinfirm.shared.api.model.ConversationStatus;
import com.flowtivo.tinfirm.shared.api.model.ConversationStreamPart;
import com.flowtivo.tinfirm.shared.api.model.ConversationTurnStatus;
import com.flowtivo.tinfirm.shared.api.model.ConversationWaitingOn;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.security.SessionWatch;
import com.flowtivo.tinfirm.shared.security.SessionWatches;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * {@code GET /conversations/{id}/stream}: a conversation's changes as Server-Sent Events, signals only, never its
 * content. A stream is one virtual thread in the caller's tenant context, established by the same check as
 * {@code GET /conversations/{id}} (the app's view; nobody else learns the conversation exists), but opening it records
 * no {@code conversation.read}: the client reads the conversation again on each signal, and that read records as a
 * poll's read does. It sends {@code conversation-status} first (the state, what it waits on, the active run), then,
 * after each committed change ({@link ConversationSignals}), {@code conversation-changed} with the parts that changed,
 * and {@code conversation-status} again when that summary changed. A comment every
 * {@code tinfirm.run.stream-keep-alive} (15 s) keeps it open through proxies. It closes with a final
 * {@code conversation-status} once the conversation ended, after {@code tinfirm.run.stream-timeout} (30 minutes; the
 * client then opens it again and reads the conversation), or without another frame as soon as the caller no longer sees
 * it: every signal and every keep-alive checks again, as the caller, the company's access
 * ({@link CompanyAccessGuard#recheck}: a member removed or suspended, a role lowered, a user deactivated) and the
 * conversation's visibility, and that the browser session it was opened with still exists and is within its absolute
 * lifetime ({@link SessionWatch}: logout, a password change and the timeouts end it). In process, on one replica, like
 * the run stream.
 */
@Service
class ConversationStreams {

    private static final Logger log = LoggerFactory.getLogger(ConversationStreams.class);

    private final ConversationStore store;

    private final ConversationsImpl conversations;

    private final ConversationSignals signals;

    private final CompanyAccessGuard companyAccess;

    private final TransactionTemplate transactions;

    private final TransactionTemplate readOnly;

    private final Duration timeout;

    private final Duration keepAlive;

    private final SessionWatches sessionWatches;

    private final ExecutorService pumps = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
        .name("conversation-stream-", 0)
        .factory());

    ConversationStreams(ConversationStore store, ConversationsImpl conversations, ConversationSignals signals,
            CompanyAccessGuard companyAccess, PlatformTransactionManager transactionManager,
            @Value("${tinfirm.run.stream-timeout:PT30M}") Duration timeout,
            @Value("${tinfirm.run.stream-keep-alive:PT15S}") Duration keepAlive, SessionWatches sessionWatches) {
        this.store = store;
        this.conversations = conversations;
        this.signals = signals;
        this.companyAccess = companyAccess;
        this.transactions = new TransactionTemplate(transactionManager);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.timeout = timeout;
        this.keepAlive = keepAlive;
        this.sessionWatches = sessionWatches;
    }

    @PreDestroy
    void stop() {
        pumps.shutdownNow();
    }

    /** Opens the stream: 404 for a conversation the caller does not see in the app, as its read answers. */
    SseEmitter stream(UUID companyId, UUID conversationId) {
        UUID userId = CurrentActor.requireUser().id();
        Granted granted = transactions.execute(status -> {
            CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
            seen(access, userId, conversationId);
            return new Granted(access, TenantContextHolder.current());
        });
        Authentication caller = SecurityContextHolder.getContext().getAuthentication();
        // The session of this request, from its thread: the stream's own thread has no request.
        SessionWatch session = sessionWatches.open();
        SseEmitter emitter = new SseEmitter(timeout.toMillis());
        Stream stream = new Stream(conversationId, userId, granted.context(), caller, session, granted.access(),
                emitter);
        emitter.onCompletion(stream::close);
        emitter.onTimeout(stream::close);
        emitter.onError(error -> stream.close());
        try {
            pumps.execute(stream::pump);
        }
        catch (RuntimeException ex) {
            log.debug("The stream of conversation {} could not start", conversationId, ex);
            emitter.complete();
        }
        return emitter;
    }

    /** The conversation, when the caller sees it in the app; 404 otherwise. */
    private ConversationStore.ConversationRow seen(CompanyAccess access, UUID userId, UUID conversationId) {
        ConversationsImpl.Caller caller = conversations.caller(access, userId);
        ConversationStore.ConversationRow row = store.find(access.companyId(), conversationId)
            .orElseThrow(() -> ApiException.notFound("Conversation not found"));
        if (!caller.sees(row.info(), ConversationView.APP)) {
            throw ApiException.notFound("Conversation not found");
        }
        return row;
    }

    /** The access the stream was granted, and the tenant context it was granted in. */
    private record Granted(CompanyAccess access, TenantContext context) {
    }

    /** A {@code conversation-status} frame: what it reports of the conversation. */
    static ConversationStatus status(ConversationInfo info) {
        return new ConversationStatus(info.id(), ConversationState.fromValue(info.state().name()),
                ConversationWaitingOn.fromValue(info.waitingOn().name()), info.turnCount())
            .endedReason(info.endedReason() != null ? ConversationEndedReason.fromValue(info.endedReason().name())
                    : null)
            .turnStatus(info.turnStatus() != null ? ConversationTurnStatus.fromValue(info.turnStatus().name()) : null)
            .waitingReason(info.waitingReason())
            .waitingSince(info.waitingSince() != null ? info.waitingSince().atOffset(ZoneOffset.UTC) : null)
            .activeRunId(info.activeRunId());
    }

    private final class Stream {

        private final UUID conversationId;

        private final UUID userId;

        private final TenantContext context;

        private final @Nullable Authentication caller;

        private final SessionWatch session;

        private final SseEmitter emitter;

        private final Semaphore signal = new Semaphore(0);

        /** The parts signalled since the last {@code conversation-changed}. */
        private final Set<ConversationSignals.Part> pending = EnumSet.noneOf(ConversationSignals.Part.class);

        private volatile boolean closed;

        private CompanyAccess access;

        Stream(UUID conversationId, UUID userId, TenantContext context, @Nullable Authentication caller,
                SessionWatch session, CompanyAccess access, SseEmitter emitter) {
            this.conversationId = conversationId;
            this.userId = userId;
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

        private void signalled(Set<ConversationSignals.Part> parts) {
            synchronized (pending) {
                pending.addAll(parts);
            }
            signal.release();
        }

        private Set<ConversationSignals.Part> takePending() {
            synchronized (pending) {
                Set<ConversationSignals.Part> taken = pending.isEmpty() ? EnumSet.noneOf(ConversationSignals.Part.class)
                        : EnumSet.copyOf(pending);
                pending.clear();
                return taken;
            }
        }

        void pump() {
            // The caller's authentication on the stream's thread, for the checks of every read.
            SecurityContext acting = SecurityContextHolder.createEmptyContext();
            acting.setAuthentication(caller);
            SecurityContextHolder.setContext(acting);
            try (TenantContextHolder.Scope scope = TenantContextHolder.open(context);
                    AutoCloseable subscription = signals.subscribe(conversationId, this::signalled)) {
                ConversationInfo first = read();
                if (first == null) {
                    emitter.complete();
                    return;
                }
                ConversationStatus shown = status(first);
                sendStatus(shown);
                if (!first.open()) {
                    // Ended already: its status is the final one.
                    emitter.complete();
                    return;
                }
                ConversationInfo now = first;
                while (!closed && now.open()) {
                    if (!signal.tryAcquire(keepAlive.toMillis(), TimeUnit.MILLISECONDS)) {
                        emitter.send(SseEmitter.event().comment("keep-alive"));
                    }
                    signal.drainPermits();
                    if (closed) {
                        break;
                    }
                    // Checked again at every signal and every keep-alive: the caller may no longer see it.
                    ConversationInfo next = read();
                    if (next == null) {
                        log.debug("The stream of conversation {} ended: the caller no longer sees it", conversationId);
                        emitter.complete();
                        return;
                    }
                    now = next;
                    Set<ConversationSignals.Part> parts = takePending();
                    if (!parts.isEmpty()) {
                        emitter.send(SseEmitter.event().name("conversation-changed").data(new ConversationChanged(
                                new ArrayList<>(parts.stream()
                                    .map(part -> ConversationStreamPart.fromValue(part.name()))
                                    .toList())), MediaType.APPLICATION_JSON));
                    }
                    ConversationStatus status = status(now);
                    if (!status.equals(shown) && now.open()) {
                        sendStatus(status);
                        shown = status;
                    }
                }
                if (!closed) {
                    // Ended: the final status, then the stream closes.
                    sendStatus(status(now));
                    emitter.complete();
                }
            }
            catch (IOException | IllegalStateException ex) {
                // The client went away or the stream timed out.
                log.debug("The stream of conversation {} ended: {}", conversationId, ex.getClass().getSimpleName());
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                emitter.complete();
            }
            catch (Exception ex) {
                log.warn("The stream of conversation {} failed", conversationId, ex);
                emitter.completeWithError(ex);
            }
            finally {
                SecurityContextHolder.clearContext();
            }
        }

        /**
         * The conversation while the caller still sees it and the HTTP session the stream was opened with lives (checked
         * outside the transaction: the session store may delete an expired session); null when not, which ends the
         * stream.
         */
        private @Nullable ConversationInfo read() {
            if (session.ended()) {
                return null;
            }
            try {
                return readOnly.execute(status -> {
                    access = companyAccess.recheck(access, CompanyPermission.VIEW_COMPANY);
                    return seen(access, userId, conversationId).info();
                });
            }
            catch (ApiException | AuthenticationException ex) {
                // Caught outside the transaction, which the guard's refusal marked for rollback.
                return null;
            }
        }

        private void sendStatus(ConversationStatus status) throws IOException {
            emitter.send(SseEmitter.event().name("conversation-status").data(status, MediaType.APPLICATION_JSON));
        }
    }
}
