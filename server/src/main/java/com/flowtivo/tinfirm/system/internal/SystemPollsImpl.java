package com.flowtivo.tinfirm.system.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.SystemPolls;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.tools.Trackers;

import tools.jackson.databind.JsonNode;

/**
 * {@link SystemPolls} over the trackers' poll sessions ({@code JiraPolls}, {@code GitHubPolls}, {@code GitLabPolls}),
 * in the caller's tenant context: the system's row is read in a short transaction of its own, then every read of the
 * session goes through the system's outbound client, outside any transaction.
 */
@Component
class SystemPollsImpl implements SystemPolls {

    /** The shortest and the longest poll interval a system's settings may name. */
    static final Duration MIN_INTERVAL = Duration.ofSeconds(30);

    static final Duration MAX_INTERVAL = Duration.ofSeconds(3600);

    private final SystemStore systems;

    private final SystemClients clients;

    private final TransactionTemplate transactions;

    SystemPollsImpl(SystemStore systems, SystemClients clients, PlatformTransactionManager transactionManager) {
        this.systems = systems;
        this.clients = clients;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public CallResult<Session> open(UUID companyId, UUID systemId, CallCredential credential) {
        NoTransaction.require("A poll of a system");
        SystemStore.SystemRow system = transactions.execute(status -> systems.find(companyId, systemId).orElse(null));
        if (system == null) {
            return CallResult.failed(CallResult.Kind.INVALID, "The system does not exist");
        }
        if (system.personal()) {
            return CallResult.failed(CallResult.Kind.INVALID, "A personal system is not polled");
        }
        if (system.paused()) {
            // The package 6 contract, §5.4: a paused system's accounts are used for nothing, its polls call nothing.
            return CallResult.failed(CallResult.Kind.OTHER, system.name() + " is paused by an admin");
        }
        CallResult.Failure moved = EndpointPin.moved(system, credential);
        if (moved != null) {
            // The credential was revealed for another endpoint (ADR-0079): nothing is sent.
            return CallResult.failed(moved);
        }
        return Trackers.polls(clients.http(system), system.kind(), system.baseUrl(), credential);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Duration interval(UUID companyId, UUID systemId) {
        return interval(systems.find(companyId, systemId).map(SystemStore.SystemRow::settings).orElse(null));
    }

    /**
     * The poll interval the settings name ({@code pollIntervalSeconds}, set through the Systems API), held to 30 to
     * 3600 seconds; {@link #DEFAULT_INTERVAL} when they name none.
     */
    static Duration interval(@Nullable JsonNode settings) {
        Long seconds = settings != null ? Json.longValue(settings, "pollIntervalSeconds") : null;
        if (seconds == null) {
            return DEFAULT_INTERVAL;
        }
        Duration interval = Duration.ofSeconds(seconds);
        return interval.compareTo(MIN_INTERVAL) < 0 ? MIN_INTERVAL
                : interval.compareTo(MAX_INTERVAL) > 0 ? MAX_INTERVAL : Objects.requireNonNull(interval);
    }
}
