package com.flowtivo.tinfirm.system.internal;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.SystemWrites;
import com.flowtivo.tinfirm.system.internal.http.OutboundProperties;
import com.flowtivo.tinfirm.system.internal.tools.Tracker;
import com.flowtivo.tinfirm.system.internal.tools.Trackers;

import jakarta.annotation.PreDestroy;

/**
 * {@link SystemWrites} over the trackers' clients, as {@code SystemToolsImpl} runs a tool: the system's row read in a
 * short transaction of its own, the call outside any transaction on a virtual thread of its own, within the tool
 * timeout ({@code UNAVAILABLE} past it), and the credential's token taken out of every failure's text.
 */
@Component
class SystemWritesImpl implements SystemWrites {

    private static final Logger log = LoggerFactory.getLogger(SystemWritesImpl.class);

    private final SystemStore systems;

    private final SystemClients clients;

    private final TransactionTemplate transactions;

    private final Duration timeout;

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
        .name("system-write-", 0)
        .factory());

    SystemWritesImpl(SystemStore systems, SystemClients clients, PlatformTransactionManager transactionManager,
            OutboundProperties properties) {
        this.systems = systems;
        this.clients = clients;
        this.transactions = new TransactionTemplate(transactionManager);
        this.timeout = properties.toolTimeout();
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    @Override
    public CallResult<String> attach(UUID companyId, UUID systemId, CallCredential credential, String key,
            String fileName, String contentType, byte[] content) {
        NoTransaction.require("Attaching a file");
        return onTracker(companyId, systemId, credential, "Attaching " + fileName + " to " + key,
                tracker -> tracker.attach(key, fileName, contentType, content));
    }

    @Override
    public CallResult<List<Attachment>> attachments(UUID companyId, UUID systemId, CallCredential credential,
            String key) {
        NoTransaction.require("Reading attachments");
        return onTracker(companyId, systemId, credential, "Reading the attachments of " + key,
                tracker -> tracker.attachments(key));
    }

    @Override
    public CallResult<String> remoteLink(UUID companyId, UUID systemId, CallCredential credential, String key,
            String url, String title) {
        NoTransaction.require("Linking a URL");
        return onTracker(companyId, systemId, credential, "Linking " + key,
                tracker -> tracker.remoteLink(key, url, title));
    }

    /** The system's tracker, called with the credential within the tool timeout. */
    private <T> CallResult<T> onTracker(UUID companyId, UUID systemId, CallCredential credential, String what,
            Function<Tracker, CallResult<T>> call) {
        SystemStore.SystemRow system = transactions.execute(status -> systems.find(companyId, systemId)
            .orElse(null));
        if (system == null) {
            return CallResult.failed(CallResult.Kind.NOT_FOUND, "The system does not exist");
        }
        CallResult.Failure moved = EndpointPin.moved(system, credential);
        if (moved != null) {
            // The credential was revealed for another endpoint (ADR-0079): nothing is sent.
            return CallResult.failed(moved);
        }
        CallResult<Tracker> tracker = Trackers.of(clients.http(system), system.kind(), system.baseUrl(), credential);
        if (tracker.failure() != null) {
            return scrubbed(tracker.failedAs(), credential.token());
        }
        Future<CallResult<T>> future = executor.submit(() -> call.apply(Objects.requireNonNull(tracker.value())));
        CallResult<T> result;
        try {
            result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException ex) {
            future.cancel(true);
            log.warn("{} on system {} did not finish within {} ms", what, systemId, timeout.toMillis());
            result = CallResult.failed(CallResult.Kind.UNAVAILABLE, what + " did not finish within "
                    + Math.max(1, timeout.toSeconds()) + " s.");
        }
        catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            result = CallResult.failed(CallResult.Kind.UNAVAILABLE, what + " was interrupted.");
        }
        catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            // The exception's class only: a message could quote a header, a header could hold the token.
            log.warn("{} on system {} failed inside Tinfirm: {}", what, systemId, cause.getClass().getName());
            result = CallResult.failed(CallResult.Kind.OTHER, what + " failed inside Tinfirm ("
                    + cause.getClass().getSimpleName() + ").");
        }
        return scrubbed(result, credential.token());
    }

    /** The result without the token in its failure's text, should a system have quoted it. */
    private static <T> CallResult<T> scrubbed(CallResult<T> result, @Nullable String token) {
        CallResult.Failure failure = result.failure();
        if (failure == null || token == null || token.strip().length() < 8) {
            return result;
        }
        return CallResult.failed(new CallResult.Failure(failure.kind(), failure.message().replace(token.strip(),
                "***"), failure.retryAfter()));
    }
}
