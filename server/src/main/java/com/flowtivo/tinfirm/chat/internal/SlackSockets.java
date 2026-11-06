package com.flowtivo.tinfirm.chat.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChatCalls;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystems;
import com.flowtivo.tinfirm.system.SystemEvents;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;

/**
 * Slack's Socket Mode (the package 5 contract, §5.1, §5.2, open point 1): for a Slack system whose transport is
 * {@code SOCKET_MODE}, with an app-level token and a bot, the {@code worker} replicas hold
 * {@code tinfirm.chat.sockets-per-system} WebSocket connections in all, each a slot of {@code chat_socket} leased for
 * {@code tinfirm.chat.socket-lease} and renewed every {@code tinfirm.chat.socket-tick}. A connection is opened with
 * {@code apps.connections.open} and the app-level token, revealed for that use ({@code chat-socket:{systemId}}); an
 * envelope is acknowledged ({@code {"envelope_id"}}) once its event is stored, and not when the database failed, so
 * that Slack delivers it again; a {@code disconnect} envelope opens a new connection before the old one goes; a closed
 * connection is opened again after 1, 2, 4… seconds, at most a minute. A message longer than
 * {@code tinfirm.chat.max-body} characters is dropped as it arrives and not acknowledged, as the Events API answers a
 * longer request {@code 413}; the connection carries on. An app-level token that Slack refuses is revealed again once
 * before the system turns {@code ERROR}. The events then go the way of the Events API's ({@link ChatReceiver}).
 */
@Component
class SlackSockets {

    private static final Logger log = LoggerFactory.getLogger(SlackSockets.class);

    static final Duration MAX_BACKOFF = Duration.ofMinutes(1);

    /** One connection of a slot. */
    private final class Connection implements ChatCalls.SocketListener {

        final UUID companyId;

        final UUID systemId;

        final int slot;

        volatile ChatCalls.@Nullable ChatSocket socket;

        volatile boolean closed;

        Connection(UUID companyId, UUID systemId, int slot) {
            this.companyId = companyId;
            this.systemId = systemId;
            this.slot = slot;
        }

        @Override
        public void onText(String text) {
            envelope(this, text);
        }

        @Override
        public void onClosed(int code, String reason) {
            closed = true;
            log.info("A Socket Mode connection of the Slack system {} closed ({} {})", systemId, code, reason);
        }

        @Override
        public void onTooLong() {
            log.warn("A Socket Mode message of the Slack system {} was longer than tinfirm.chat.max-body and dropped "
                    + "unacknowledged", systemId);
        }

        boolean open() {
            ChatCalls.ChatSocket current = socket;
            return !closed && current != null && current.open();
        }

        void close() {
            closed = true;
            ChatCalls.ChatSocket current = socket;
            if (current != null) {
                current.close();
            }
        }
    }

    /** A slot's state on this replica: its connection, and when it may be opened again. */
    private record Slot(UUID systemId, int slot) {
    }

    private record Backoff(int failures, Instant next) {
    }

    private final Map<Slot, Connection> connections = new ConcurrentHashMap<>();

    private final Map<Slot, Backoff> backoffs = new ConcurrentHashMap<>();

    private final String holder = UUID.randomUUID().toString();

    private final ChatStore store;

    private final ChatSystems chatSystems;

    private final ChatCalls calls;

    private final ChatReceiver receiver;

    private final ChatProperties properties;

    private final SystemTransactions transactions;

    private final TimeSource time;

    private final boolean worker;

    SlackSockets(ChatStore store, ChatSystems chatSystems, ChatCalls calls, ChatReceiver receiver,
            ChatProperties properties, SystemTransactions transactions, TimeSource time, TinfirmProperties tinfirm) {
        this.store = store;
        this.chatSystems = chatSystems;
        this.calls = calls;
        this.receiver = receiver;
        this.properties = properties;
        this.transactions = transactions;
        this.time = time;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
    }

    /** Whether a pass runs: one at a time, never on the scheduler's own thread, which every fixed delay shares. */
    private final java.util.concurrent.atomic.AtomicBoolean maintaining = new java.util.concurrent.atomic.AtomicBoolean();

    @Scheduled(fixedDelayString = "${tinfirm.chat.socket-tick:PT5S}", initialDelayString = "PT2S")
    void tick() {
        if (worker && maintaining.compareAndSet(false, true)) {
            Thread.ofVirtual().name("slack-sockets").start(() -> {
                try {
                    maintain();
                }
                catch (RuntimeException ex) {
                    log.warn("The Socket Mode connections were not looked after ({})", ex.getClass().getSimpleName());
                }
                finally {
                    maintaining.set(false);
                }
            });
        }
    }

    /** One pass: every Socket Mode system's slots leased, renewed, opened; the rest closed. */
    void maintain() {
        Set<Slot> wanted = new HashSet<>();
        for (ChatStore.SystemRef ref : transactions.unscoped(store::systemsWithBot)) {
            if (!"SLACK".equals(ref.kind())) {
                continue;
            }
            try {
                maintain(ref, wanted);
            }
            catch (RuntimeException ex) {
                log.warn("The Socket Mode connections of the Slack system {} were not looked after ({})",
                        ref.systemId(), ex.getClass().getSimpleName(), ex);
            }
        }
        for (Map.Entry<Slot, Connection> entry : connections.entrySet()) {
            if (!wanted.contains(entry.getKey())) {
                close(entry.getKey());
            }
        }
    }

    private void maintain(ChatStore.SystemRef ref, Set<Slot> wanted) {
        UUID companyId = ref.companyId();
        ChatSystemInfo system = transactions.inCompany(companyId, () -> chatSystems.find(companyId,
                ref.systemId()).orElse(null));
        if (system == null || !system.socketMode() || system.appTokenId() == null) {
            return;
        }
        Instant now = time.now();
        for (int slot = 0; slot < properties.socketsPerSystem(); slot++) {
            int index = slot;
            boolean leased = transactions.inCompany(companyId, () -> store.leaseSocket(companyId, system.id(),
                    index, holder, now, now.plus(properties.socketLease())));
            Slot key = new Slot(system.id(), slot);
            if (!leased) {
                continue;
            }
            wanted.add(key);
            Connection current = connections.get(key);
            if (current != null && current.open()) {
                continue;
            }
            Backoff backoff = backoffs.get(key);
            if (backoff != null && backoff.next().isAfter(now)) {
                continue;
            }
            if (current != null) {
                connections.remove(key, current);
            }
            open(system, slot);
        }
    }

    /** Opens a connection of the slot: the URL with the app-level token, then the WebSocket. */
    private boolean open(ChatSystemInfo system, int slot) {
        Slot key = new Slot(system.id(), slot);
        Optional<String> url = socketUrl(system);
        if (url.isEmpty()) {
            failed(key);
            return false;
        }
        Connection connection = new Connection(system.companyId(), system.id(), slot);
        CallResult<ChatCalls.ChatSocket> opened = calls.slackConnect(system.system(), url.get(), maxChars(),
                connection);
        if (opened.failure() != null) {
            log.info("A Socket Mode connection of the Slack system {} was not opened: {}", system.id(),
                    opened.failureMessage());
            failed(key);
            return false;
        }
        connection.socket = opened.value();
        Connection previous = connections.put(key, connection);
        if (previous != null && previous != connection) {
            previous.close();
        }
        backoffs.remove(key);
        log.info("A Socket Mode connection of the Slack system {} is open (slot {})", system.id(), slot);
        return true;
    }

    /** {@code apps.connections.open}: a refused token is revealed again once, then the system turns {@code ERROR}. */
    private Optional<String> socketUrl(ChatSystemInfo system) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<String> token = SystemContext.callInCompany(system.companyId(), () -> chatSystems.revealSecret(
                    system.companyId(), system.id(), ChatSystems.ChatSecret.APP_TOKEN, "chat-socket:" + system.id()));
            if (token.isEmpty()) {
                return Optional.empty();
            }
            CallResult<String> url = calls.slackSocketUrl(system.system(), token.get());
            if (url.failure() == null) {
                return Optional.of(Objects.requireNonNull(url.value()));
            }
            if (url.failure().kind() != CallResult.Kind.UNAUTHORIZED) {
                log.info("Slack did not open a Socket Mode connection for the system {}: {}", system.id(),
                        url.failureMessage());
                return Optional.empty();
            }
        }
        transactions.runInCompany(system.companyId(), () -> chatSystems.status(system.companyId(), system.id(),
                "ERROR", "Slack refused the app-level token: set a new one"));
        log.warn("Slack refused the app-level token of the system {}", system.id());
        return Optional.empty();
    }

    /** The longest message a connection puts together: {@code tinfirm.chat.max-body}, as the Events API's. */
    private int maxChars() {
        return (int) Math.min(Integer.MAX_VALUE - 8, properties.maxBody().toBytes());
    }

    private void failed(Slot key) {
        Backoff previous = backoffs.get(key);
        int failures = previous == null ? 1 : previous.failures() + 1;
        long seconds = Math.min(MAX_BACKOFF.toSeconds(), 1L << Math.min(failures - 1, 6));
        backoffs.put(key, new Backoff(failures, time.now().plusSeconds(seconds)));
    }

    private void close(Slot key) {
        Connection connection = connections.remove(key);
        backoffs.remove(key);
        if (connection != null) {
            connection.close();
            try {
                transactions.runInCompany(connection.companyId, () -> store.releaseSocket(key.systemId(),
                        key.slot(), holder));
            }
            catch (RuntimeException ex) {
                log.debug("A Socket Mode slot was not released ({})", ex.getClass().getSimpleName());
            }
        }
    }

    /** One envelope from Slack, on the connection's own thread. */
    private void envelope(Connection connection, String text) {
        JsonNode envelope;
        try {
            envelope = ChatInboundController.INBOUND.readTree(text);
        }
        catch (RuntimeException ex) {
            log.info("A Socket Mode frame of the Slack system {} was not JSON", connection.systemId);
            return;
        }
        String type = SlackEvents.text(envelope, "type");
        String envelopeId = SlackEvents.text(envelope, "envelope_id");
        if ("hello".equals(type)) {
            return;
        }
        if ("disconnect".equals(type)) {
            // Slack refreshes its connections: a new one first, then the old one goes.
            Thread.ofVirtual().name("slack-socket-refresh").start(() -> refresh(connection));
            return;
        }
        ChatSystemInfo system = transactions.inCompany(connection.companyId, () -> chatSystems.find(
                connection.companyId, connection.systemId).orElse(null));
        if (system == null) {
            return;
        }
        int retry = envelope.path("retry_attempt").isNumber() ? envelope.path("retry_attempt").intValue() : 0;
        Optional<Inbound> inbound = switch (type == null ? "" : type) {
            case "events_api" -> SlackEvents.event(envelope.path("payload"), retry);
            case "interactive" -> SlackEvents.action(envelope.path("payload"), retry);
            default -> Optional.empty();
        };
        boolean acknowledge = inbound.isEmpty()
                || receiver.receive(system, inbound.get(), false) != ChatReceiver.Received.FAILED;
        if (acknowledge && envelopeId != null) {
            ChatCalls.ChatSocket socket = connection.socket;
            if (socket != null) {
                socket.send(ChatStore.JSON.writeValueAsString(Map.of("envelope_id", envelopeId)));
            }
        }
    }

    /** A {@code disconnect} envelope: the slot's next connection is opened, then this one closed. */
    private void refresh(Connection old) {
        ChatSystemInfo system = transactions.inCompany(old.companyId, () -> chatSystems.find(old.companyId,
                old.systemId).orElse(null));
        if (system == null || !system.socketMode()) {
            old.close();
            return;
        }
        if (!open(system, old.slot)) {
            old.close();
        }
    }

    /** A system's transport changed: its connections follow at once (opened or closed by the next pass). */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void transportChanged(SystemEvents.ChatTransportChanged event) {
        for (Slot key : Set.copyOf(connections.keySet())) {
            if (key.systemId().equals(event.systemId())) {
                close(key);
            }
        }
    }

    /** Whether this replica holds an open connection of the system, for the tests. */
    boolean connected(UUID systemId) {
        return connections.entrySet()
            .stream()
            .anyMatch(entry -> entry.getKey().systemId().equals(systemId) && entry.getValue().open());
    }

    @PreDestroy
    void stop() {
        for (Slot key : Set.copyOf(connections.keySet())) {
            close(key);
        }
    }
}
