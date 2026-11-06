package com.flowtivo.tinfirm.runner.internal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RunnerProperties;

/**
 * The runner host connections of this server process (one replica: an accepted limit, PLATFORM.md §14).
 * <p>
 * A host is registered once its {@code hello} is processed and its first {@code host.config} is sent; a second
 * connection of the same host replaces the first, which is closed with 4409. A connection is keyed by the host's
 * {@link ConnectedHost#machineId()}: a company host's id, or a machine's (ADR-0092), and every presence of the
 * machine names the same connection, so that the run side of each company asks and sends by the presence id it
 * knows. A connection takes runs only once the host has confirmed a {@code host.config} on it ({@link #isConfigured},
 * docs/archive/v1/CONVENTIONS.md §15.7). Every session here is a {@code ConcurrentWebSocketSessionDecorator}, so
 * frames from the dispatcher, the reaper and request threads never interleave.
 * <p>
 * Keep-alive: every heartbeat interval each session gets a ping, and a session that has sent nothing (not even a
 * pong) for three intervals, or that has not said hello within that time, is closed. A vanished host therefore
 * stops receiving assignments within about half a minute.
 * <p>
 * Liveness: every frame of a registered connection, a pong included, is recorded ({@link #lastInbound}), and at most
 * once per {@link RunnerProperties#leaseRenewalInterval()} it asks for the leases of the host's runs to be renewed
 * ({@link #markInbound}, docs/archive/v1/CONVENTIONS.md §15.1).
 */
@Component
class RunnerConnectionRegistry implements RunnerConnections {

    static final CloseStatus INVALID_TOKEN = new CloseStatus(4401, "Invalid or revoked runner host token");

    static final CloseStatus REPLACED = new CloseStatus(4409, "Replaced by a newer connection of this host");

    private static final Logger log = LoggerFactory.getLogger(RunnerConnectionRegistry.class);

    private static final int SILENCE_LIMIT_INTERVALS = 3;

    private final Map<UUID, Connection> byHost = new ConcurrentHashMap<>();

    /** The connection key of each row id the run side may name: a company host's own, a presence's machine. */
    private final Map<UUID, UUID> keys = new ConcurrentHashMap<>();

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private final RunnerMessageCodec codec;

    private final RunnerProperties properties;

    private final Clock clock;

    RunnerConnectionRegistry(RunnerMessageCodec codec, RunnerProperties properties, Clock clock) {
        this.codec = codec;
        this.properties = properties;
        this.clock = clock;
    }

    /** A connected host that has said hello. */
    static final class Connection {

        /** The host as authenticated, with a machine's presences as they are now (ADR-0092). */
        private volatile ConnectedHost host;

        private final WebSocketSession session;

        /** The optional features of protocol 2 the host listed in its {@code hello}. */
        private final Set<String> features;

        private volatile Instant lastInbound;

        /** When the frames of this connection last renewed the leases of the host's runs. */
        private Instant lastRenewal;

        /** The host has sent {@code host.config.applied} on this connection. */
        private volatile boolean configured;

        Connection(ConnectedHost host, WebSocketSession session, Set<String> features, Instant now) {
            this.host = host;
            this.session = session;
            this.features = Set.copyOf(features);
            this.lastInbound = now;
            this.lastRenewal = now;
        }

        /** Whether the leases are due for renewal; if so, they count as renewed now. */
        synchronized boolean renewalDue(Instant now, Duration interval) {
            if (now.isBefore(lastRenewal.plus(interval))) {
                return false;
            }
            lastRenewal = now;
            return true;
        }
    }

    private record Pending(WebSocketSession session, Instant since) {
    }

    /** An authenticated session that has not said hello yet. */
    void awaitHello(WebSocketSession session) {
        pending.put(session.getId(), new Pending(session, clock.instant()));
    }

    void register(ConnectedHost host, WebSocketSession session) {
        register(host, session, Set.of());
    }

    /** As {@link #register(ConnectedHost, WebSocketSession)}, with the features the host's {@code hello} lists. */
    void register(ConnectedHost host, WebSocketSession session, Set<String> features) {
        pending.remove(session.getId());
        UUID key = host.machineId();
        host.presences().values().forEach(rowId -> keys.put(rowId, key));
        Connection previous = byHost.put(key, new Connection(host, session, features, clock.instant()));
        if (previous != null && !previous.session.getId().equals(session.getId())) {
            log.info("Runner host {} reconnected; closing its previous connection", key);
            close(previous.session, REPLACED);
        }
    }

    /**
     * A machine gained a presence in a company (its owner joined it, ADR-0092): its current connection serves there
     * too, from the next dispatch on, and its frames about that company's runs are placed there
     * ({@link #connectedHost}).
     */
    void addPresence(UUID personalHostId, UUID companyId, UUID runnerHostId) {
        byHost.computeIfPresent(personalHostId, (id, connection) -> {
            keys.put(runnerHostId, id);
            Map<UUID, UUID> presences = new java.util.HashMap<>(connection.host.presences());
            presences.put(companyId, runnerHostId);
            connection.host = connection.host.withPresences(presences);
            return connection;
        });
    }

    /**
     * A machine lost a presence (its owner left the company): the run side of that company sees it offline, and the
     * connection no longer places frames there, until a new presence comes (the owner joins again).
     */
    void removePresence(UUID runnerHostId) {
        UUID key = keys.remove(runnerHostId);
        if (key != null) {
            byHost.computeIfPresent(key, (id, connection) -> {
                Map<UUID, UUID> presences = new java.util.HashMap<>(connection.host.presences());
                presences.values().removeIf(runnerHostId::equals);
                connection.host = connection.host.withPresences(presences);
                return connection;
            });
        }
    }

    /** Removes the connection if it is still the registered one for the host. */
    void unregister(UUID key, String sessionId) {
        pending.remove(sessionId);
        byHost.computeIfPresent(key, (id, connection) -> {
            if (!connection.session.getId().equals(sessionId)) {
                return connection;
            }
            // Its presences as authenticated, and those added while it was connected.
            keys.values().removeIf(id::equals);
            return null;
        });
    }

    /** The connection a row id names: by the host's own id, a machine's, or a presence's. */
    private @Nullable Connection connection(UUID hostId) {
        Connection direct = byHost.get(hostId);
        if (direct != null) {
            return direct;
        }
        UUID key = keys.get(hostId);
        return key != null ? byHost.get(key) : null;
    }

    /**
     * Records a frame of the host's current connection.
     *
     * @return true when the frame should renew the leases of the host's runs: the connection is the registered one,
     *         and the last renewal of this kind is at least {@link RunnerProperties#leaseRenewalInterval()} ago
     */
    boolean markInbound(UUID hostId, String sessionId) {
        Connection connection = connection(hostId);
        if (connection == null || !connection.session.getId().equals(sessionId)) {
            return false;
        }
        Instant now = clock.instant();
        connection.lastInbound = now;
        return connection.renewalDue(now, properties.leaseRenewalInterval());
    }

    /** Closes the host's connection, e.g. with 4401 when the host was deleted. */
    void disconnect(UUID hostId, CloseStatus status) {
        Connection connection = connection(hostId);
        if (connection != null) {
            unregister(connection.host.machineId(), connection.session.getId());
            close(connection.session, status);
        }
    }

    /** The host confirmed a {@code host.config} on this connection; ignored when it is no longer the current one. */
    void markConfigured(UUID hostId, String sessionId) {
        Connection connection = connection(hostId);
        if (connection != null && connection.session.getId().equals(sessionId)) {
            connection.configured = true;
        }
    }

    Optional<ConnectedHost> connectedHost(UUID hostId) {
        return Optional.ofNullable(connection(hostId)).map(connection -> connection.host);
    }

    /** The row ids connected: company hosts, and the presences of connected machines. */
    @Override
    public java.util.Collection<UUID> connectedHostIds() {
        return List.copyOf(keys.keySet());
    }

    @Override
    public boolean isConnected(UUID runnerHostId) {
        Connection connection = connection(runnerHostId);
        return connection != null && connection.session.isOpen();
    }

    @Override
    public boolean isConfigured(UUID runnerHostId) {
        Connection connection = connection(runnerHostId);
        return connection != null && connection.configured && connection.session.isOpen();
    }

    @Override
    public @Nullable Instant lastInbound(UUID runnerHostId) {
        Connection connection = connection(runnerHostId);
        return connection != null && connection.session.isOpen() ? connection.lastInbound : null;
    }

    @Override
    public int protocolVersion(UUID runnerHostId) {
        Connection connection = connection(runnerHostId);
        return connection != null ? connection.host.protocolVersion() : RunnerMessages.PROTOCOL_V1;
    }

    @Override
    public boolean supports(UUID runnerHostId, String feature) {
        Connection connection = connection(runnerHostId);
        return connection != null && connection.session.isOpen() && connection.features.contains(feature);
    }

    @Override
    public boolean send(UUID runnerHostId, RunnerMessages.Outbound message) {
        Connection connection = connection(runnerHostId);
        return connection != null && sendTo(connection.session, message);
    }

    /** Sends one frame on a session. The frame is never logged: {@code run.assign} carries a git token. */
    boolean sendTo(WebSocketSession session, RunnerMessages.Outbound message) {
        if (!session.isOpen()) {
            return false;
        }
        try {
            session.sendMessage(new TextMessage(codec.encode(message)));
            return true;
        }
        catch (IOException | RuntimeException ex) {
            log.debug("Sending a {} frame on session {} failed: {}", message.getClass().getSimpleName(),
                    session.getId(), ex.getClass().getSimpleName());
            return false;
        }
    }

    static void close(WebSocketSession session, CloseStatus status) {
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        }
        catch (IOException | RuntimeException ex) {
            log.debug("Closing session {} failed: {}", session.getId(), ex.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${tinfirm.runner.heartbeat-interval:PT10S}",
            initialDelayString = "${tinfirm.runner.heartbeat-interval:PT10S}")
    void keepAlive() {
        Instant now = clock.instant();
        Instant silentSince = now.minus(properties.heartbeatInterval().multipliedBy(SILENCE_LIMIT_INTERVALS));
        pending.values().removeIf(entry -> {
            boolean expired = entry.since().isBefore(silentSince) || !entry.session().isOpen();
            if (expired) {
                close(entry.session(), CloseStatus.POLICY_VIOLATION.withReason("No hello received"));
            }
            return expired;
        });
        for (Connection connection : byHost.values()) {
            if (connection.lastInbound.isBefore(silentSince)) {
                log.info("Runner host {} was silent for {}; closing its connection", connection.host.machineId(),
                        Duration.between(connection.lastInbound, now));
                unregister(connection.host.machineId(), connection.session.getId());
                close(connection.session, CloseStatus.SESSION_NOT_RELIABLE);
                continue;
            }
            try {
                connection.session.sendMessage(new PingMessage(ByteBuffer.allocate(0)));
            }
            catch (IOException | RuntimeException ex) {
                log.debug("Ping to runner host {} failed: {}", connection.host.machineId(),
                        ex.getClass().getSimpleName());
            }
        }
    }
}
