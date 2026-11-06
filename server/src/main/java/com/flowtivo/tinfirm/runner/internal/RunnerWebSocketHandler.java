package com.flowtivo.tinfirm.runner.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RunnerProperties;
import com.flowtivo.tinfirm.runner.RunnerSessionListener;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.SystemContext;

/**
 * The runner endpoint {@code /api/runner/v1/connect} (docs/archive/v1/CONVENTIONS.md §15.1).
 * <ul>
 * <li>Authentication: {@code Authorization: Bearer tfr_...}, outside the browser session. A missing, malformed,
 * unknown or revoked token closes the connection with 4401.</li>
 * <li>The first frame must be a valid {@code hello}, else the connection is closed with 1008; so is a hello that
 * lists {@code sandbox.bridge} or {@code sandbox.pool} while the licence in force does not hold
 * {@code ELASTIC_RUNNERS}, after an
 * {@code error} {@code EDITION_REQUIRED} (ADR-0100). Its
 * {@code protocolVersion} must be 1 or 2, else the connection is closed with 1002 after an {@code error}
 * {@code UNSUPPORTED_PROTOCOL}; the connection speaks that version from then on (CONVENTIONS §14.10). The hello
 * records the host, its ceiling and version, and a personal host's credentials ({@link RunnerHostSessions}; a company
 * host's are refused with an {@code error} {@code PERSONAL_HOST_CREDENTIAL_ONLY} after {@code host.config}), and is
 * answered with {@code welcome} (which names the version) and then {@code host.config}, before any other frame. Only
 * then does the host become connected (a second connection of the same host replaces the first with 4409); the
 * dispatcher assigns runs to it once it has answered with {@code host.config.applied}
 * (docs/archive/v1/CONVENTIONS.md §15.1, §15.7).</li>
 * <li>Every frame is validated against the connection's version; a bad or unknown frame, and a frame of the other
 * version, is answered with {@code error} and otherwise ignored.</li>
 * <li>Frames are processed one at a time per connection, as actor SYSTEM in the tenant context of the host's
 * company. A machine is present in every company of its owner (ADR-0092): the work of a connection, a disconnect or
 * a sign of life is done in each of them, and a run frame in the company of the run it names ({@code UNKNOWN_RUN}
 * when the machine is not in it). Frames are never logged: {@code run.assign} carries a git token.</li>
 * <li>Every frame after {@code hello}, a pong included, shows that the host is alive: at most once per
 * {@link RunnerProperties#leaseRenewalInterval()} it renews the leases of all the host's running runs. When the
 * connection closes and no newer one of the host replaced it, the host's runs keep their leases for the reconnect
 * grace (docs/archive/v1/CONVENTIONS.md §15.1).</li>
 * </ul>
 */
@Component
class RunnerWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RunnerWebSocketHandler.class);

    private static final String HOST = "tinfirm.runner.host";

    private static final String OUT = "tinfirm.runner.out";

    private static final String READY = "tinfirm.runner.ready";

    /** The labels of the credentials the connection's hello reported. */
    private static final String LABELS = "tinfirm.runner.labels";

    private static final int SEND_TIME_LIMIT_MS = 10_000;

    private static final int SEND_BUFFER_LIMIT_BYTES = 16 * 1024 * 1024;

    private static final int MAX_LOGGED_ERROR_LENGTH = 500;

    /** The company of a machine's {@code welcome} when its owner is in no company yet (ADR-0092). */
    private static final UUID NO_COMPANY = new UUID(0, 0);

    private final RunnerHostSessions sessions;

    private final RunnerMessageCodec codec;

    private final RunnerConnectionRegistry registry;

    private final ObjectProvider<RunnerSessionListener> listeners;

    private final RunnerProperties properties;

    private final @Nullable String serverVersion;

    private final Licensing licensing;

    RunnerWebSocketHandler(RunnerHostSessions sessions, RunnerMessageCodec codec, RunnerConnectionRegistry registry,
            ObjectProvider<RunnerSessionListener> listener, RunnerProperties properties,
            ObjectProvider<BuildProperties> buildProperties, Licensing licensing) {
        this.licensing = licensing;
        this.sessions = sessions;
        this.codec = codec;
        this.registry = registry;
        // The run module implements the port and depends on this one: resolved when used.
        this.listeners = listener;
        this.properties = properties;
        BuildProperties build = buildProperties.getIfAvailable();
        this.serverVersion = build != null ? build.getVersion() : null;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession out = new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS,
                SEND_BUFFER_LIMIT_BYTES);
        session.getAttributes().put(OUT, out);
        Optional<ConnectedHost> host = RunnerTokens
            .fromAuthorization(session.getHandshakeHeaders().getFirst(HttpHeaders.AUTHORIZATION))
            .flatMap(sessions::authenticate);
        if (host.isEmpty()) {
            RunnerConnectionRegistry.close(out, RunnerConnectionRegistry.INVALID_TOKEN);
            return;
        }
        session.getAttributes().put(HOST, host.get());
        registry.awaitHello(out);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage frame) {
        ConnectedHost host = current(session);
        WebSocketSession out = (WebSocketSession) session.getAttributes().get(OUT);
        if (host == null || out == null) {
            return;
        }
        boolean renew = registry.markInbound(host.machineId(), out.getId());
        process(session, out, host, frame);
        if (renew) {
            hostActive(host);
        }
    }

    private void process(WebSocketSession session, WebSocketSession out, ConnectedHost host, TextMessage frame) {
        RunnerMessages.Inbound message;
        RunnerMessageCodec.Decoded decoded;
        boolean ready = session.getAttributes().get(READY) != null;
        try {
            decoded = codec.decodeFrame(frame.getPayload(),
                    ready ? host.protocolVersion() : RunnerMessageCodec.ANY_VERSION);
            message = decoded.message();
        }
        catch (RunnerMessageCodec.BadFrame ex) {
            registry.sendTo(out, ex.toError());
            if (!ready) {
                // For example a hello with more than 16 slots from an older runner host.
                RunnerConnectionRegistry.close(out, CloseStatus.POLICY_VIOLATION.withReason("Expected a valid hello"));
            }
            return;
        }
        try {
            if (!ready) {
                if (message instanceof RunnerMessages.Hello hello) {
                    hello(session, out, host, hello, decoded.unknownCredentialKinds());
                }
                else {
                    registry.sendTo(out, RunnerMessages.ProtocolError.of(RunnerMessages.BAD_MESSAGE,
                            "The first frame must be hello"));
                    RunnerConnectionRegistry.close(out, CloseStatus.POLICY_VIOLATION.withReason("Expected hello"));
                }
                return;
            }
            dispatch(session, out, host, message);
        }
        catch (RuntimeException ex) {
            log.error("Processing a {} frame of runner host {} failed", message.getClass().getSimpleName(),
                    host.machineId(), ex);
            registry.sendTo(out, RunnerMessages.ProtocolError.of("INTERNAL_ERROR",
                    "The server could not process the frame; it was ignored"));
        }
    }

    private void hello(WebSocketSession session, WebSocketSession out, ConnectedHost authenticated,
            RunnerMessages.Hello hello, List<String> unknownCredentialKinds) {
        if (!RunnerMessages.supported(hello.protocolVersion())) {
            registry.sendTo(out, RunnerMessages.ProtocolError.of(RunnerMessages.UNSUPPORTED_PROTOCOL,
                    "This server speaks runner protocol " + RunnerMessages.PROTOCOL_V1 + " and "
                            + RunnerMessages.PROTOCOL_V2 + ", not " + hello.protocolVersion()));
            RunnerConnectionRegistry.close(out, CloseStatus.PROTOCOL_ERROR.withReason("Unsupported protocol version"));
            return;
        }
        // From here on the connection speaks the host's version: its frames decode, and run.assign is built, for it.
        ConnectedHost host = authenticated.speaking(hello.protocolVersion());
        session.getAttributes().put(HOST, host);
        String problem = RunnerHostSessions.helloProblem(hello);
        if (problem != null) {
            registry.sendTo(out, RunnerMessages.ProtocolError.of(RunnerMessages.BAD_MESSAGE, problem));
            RunnerConnectionRegistry.close(out, CloseStatus.POLICY_VIOLATION.withReason("Invalid hello"));
            return;
        }
        if ((hello.featureSet().contains(RunnerMessages.FEATURE_SANDBOX_BRIDGE)
                || hello.featureSet().contains(RunnerMessages.FEATURE_SANDBOX_POOL))
                && !licensing.has(Feature.ELASTIC_RUNNERS)) {
            // Elastic runners are Pro's and Enterprise's (ADR-0100): the host stops rather than reconnects. Ready
            // sandboxes are a VM per run's, which always lists the bridge: a pool without it is refused the same way,
            // so that no hello re-evaluates Tinfirm's schedule for a host the licence does not cover.
            registry.sendTo(out, RunnerMessages.ProtocolError.of(RunnerMessages.EDITION_REQUIRED,
                    "This server's licence does not hold elastic runners (" + Feature.ELASTIC_RUNNERS.name() + ", "
                            + "the " + Feature.ELASTIC_RUNNERS.edition().name() + " edition), which a runner host "
                            + "with the sandbox bridge or a VM per run needs. Run it with Docker and without "
                            + "--sandbox-bridge, or apply a Pro or Enterprise licence key"));
            RunnerConnectionRegistry.close(out, CloseStatus.POLICY_VIOLATION.withReason("Edition required"));
            return;
        }
        Optional<RunnerHostSessions.HelloOutcome> outcome = sessions.recordHello(host, hello);
        if (outcome.isEmpty()) {
            RunnerConnectionRegistry.close(out, RunnerConnectionRegistry.INVALID_TOKEN);
            return;
        }
        session.getAttributes().put(LABELS, RunnerHostSessions.acceptedLabels(hello, outcome.get()));
        RunnerMessages.HostConfig config = outcome.get().config();
        // A machine's welcome names the machine and the first of its companies (none: the nil UUID): the runner host
        // keeps them in its state file and uses neither (ADR-0092).
        List<ConnectedHost> placements = host.placements();
        registry.sendTo(out, new RunnerMessages.Welcome(host.protocolVersion(), host.machineId(),
                placements.isEmpty() ? NO_COMPANY : placements.getFirst().company(), serverVersion,
                Math.max(1, properties.heartbeatInterval().toSeconds()),
                Math.max(1, properties.leaseTtl().toSeconds())));
        registry.sendTo(out, config);
        if (outcome.get().credentialsRefused()) {
            // M5: only a personal host holds credentials; a company host runs budget-paid runs only.
            registry.sendTo(out, RunnerMessages.ProtocolError.of(RunnerMessages.PERSONAL_HOST_CREDENTIAL_ONLY,
                    "This is a company runner host: the credentials it reports are ignored, since only a member's "
                            + "personal host holds credentials"));
        }
        else if (!unknownCredentialKinds.isEmpty()) {
            // A host of another version (ADR-0085): its other credentials serve; these are named for its owner.
            registry.sendTo(out, RunnerMessages.ProtocolError.of(RunnerMessages.UNKNOWN_CREDENTIAL_KIND,
                    "This server does not know the kind of the credentials " + String.join(", ", unknownCredentialKinds)
                            + ": they are ignored. Upgrade the runner host, then remove them and add them again"));
        }
        session.getAttributes().put(READY, Boolean.TRUE);
        registry.register(host, out, hello.featureSet());
        // A change committed after the configuration above was read, but before the registration, found no
        // connection to send its host.config to: send the newer state now. Duplicates are harmless.
        long sent = config.version();
        sessions.hostConfig(host)
            .filter(latest -> latest.version() > sent)
            .ifPresent(latest -> registry.sendTo(out, latest));
        List<RunnerMessages.ActiveRun> activeRuns = hello.activeRuns() != null ? hello.activeRuns() : List.of();
        String publicSuffixSha256 = hello.publicSuffixList() != null ? hello.publicSuffixList().sha256() : null;
        send(out, connected(host, placements, activeRuns, publicSuffixSha256));
    }

    /**
     * Reconciles the host in each of its companies, with the active runs it reports that belong to that company. A
     * reported run of no company of the host's (one it left, or a run the server does not know) is cancelled.
     */
    private List<RunnerMessages.Outbound> connected(ConnectedHost host, List<ConnectedHost> placements,
            List<RunnerMessages.ActiveRun> activeRuns, @Nullable String publicSuffixSha256) {
        Map<UUID, List<RunnerMessages.ActiveRun>> byCompany = new HashMap<>();
        List<RunnerMessages.Outbound> frames = new ArrayList<>();
        for (RunnerMessages.ActiveRun active : activeRuns) {
            UUID company = sessions.placeForRun(host, active.runId()).map(ConnectedHost::company).orElse(null);
            if (company == null) {
                frames.add(new RunnerMessages.RunCancel(active.runId(), active.epoch()));
            }
            else {
                byCompany.computeIfAbsent(company, id -> new ArrayList<>()).add(active);
            }
        }
        boolean pslSent = false;
        for (ConnectedHost placed : placements) {
            List<RunnerMessages.ActiveRun> theirs = byCompany.getOrDefault(placed.company(), List.of());
            for (RunnerMessages.Outbound frame : SystemContext.callInCompany(placed.company(),
                    () -> listener().hostConnected(placed, theirs, publicSuffixSha256))) {
                // The Public Suffix List is the server's, not a company's: sent once.
                if (frame instanceof RunnerMessages.PslUpdate) {
                    if (pslSent) {
                        continue;
                    }
                    pslSent = true;
                }
                frames.add(frame);
            }
        }
        return frames;
    }

    @SuppressWarnings("unchecked")
    private void dispatch(WebSocketSession session, WebSocketSession out, ConnectedHost host,
            RunnerMessages.Inbound message) {
        switch (message) {
            case RunnerMessages.Hello ignored -> registry.sendTo(out,
                    RunnerMessages.ProtocolError.of(RunnerMessages.BAD_MESSAGE, "hello was already received"));
            case RunnerMessages.CredentialStatus status -> sessions
                .credentialStatus(host, (Set<String>) session.getAttributes().getOrDefault(LABELS, Set.of()), status)
                .ifPresent(error -> registry.sendTo(out, error));
            case RunnerMessages.HostConfigApplied applied -> applied(out, host, applied);
            case RunnerMessages.HostPoolStatus status -> {
                // Only a company host keeps ready sandboxes (ADR-0099), and only one whose hello listed the feature.
                if (!host.personal() && registry.supports(host.runnerHostId(), RunnerMessages.FEATURE_SANDBOX_POOL)) {
                    sessions.recordPoolStatus(host, status);
                }
                else {
                    registry.sendTo(out, RunnerMessages.ProtocolError.of(RunnerMessages.BAD_MESSAGE,
                            "host.pool.status needs a company host that lists the feature sandbox.pool in hello"));
                }
            }
            case RunnerMessages.RunHeartbeat heartbeat -> {
                // The runs of a heartbeat may be of several companies of a machine: each company hears of its own.
                Map<UUID, List<RunnerMessages.HeartbeatRun>> byCompany = new java.util.TreeMap<>();
                ConnectedHost current = host;
                for (RunnerMessages.HeartbeatRun entry : heartbeat.runs()) {
                    ConnectedHost placed = sessions.placeForRun(current, entry.runId()).orElse(null);
                    if (placed == null) {
                        registry.sendTo(out, unknownRun(entry.runId()));
                        continue;
                    }
                    current = adopt(session, current, placed);
                    byCompany.computeIfAbsent(placed.company(), id -> new ArrayList<>()).add(entry);
                }
                ConnectedHost known = current;
                byCompany.forEach((company, entries) -> {
                    ConnectedHost placed = known.in(company).orElseThrow();
                    RunnerMessages.RunHeartbeat theirs = new RunnerMessages.RunHeartbeat(entries);
                    send(out, SystemContext.callInCompany(company, () -> listener().runMessage(placed, theirs)));
                });
                sessions.touch(host);
            }
            case RunnerMessages.OneRunMessage runMessage -> {
                ConnectedHost placed = sessions.placeForRun(host, runMessage.runId()).orElse(null);
                if (placed == null) {
                    registry.sendTo(out, unknownRun(runMessage.runId()));
                    return;
                }
                adopt(session, host, placed);
                send(out, SystemContext.callInCompany(placed.company(), () -> listener().runMessage(placed, runMessage)));
            }
            case RunnerMessages.ProtocolError error -> log.warn("Runner host {} reported error {}: {}",
                    host.machineId(), abbreviate(error.code()), abbreviate(error.message()));
        }
    }

    /**
     * Stores the capacity the host confirms, marks the connection as configured, and dispatches (archived v1
     * CONVENTIONS §14.8). An effective value above its ceiling or a label listed twice is answered with
     * {@code BAD_MESSAGE} and ignored.
     */
    private void applied(WebSocketSession out, ConnectedHost host, RunnerMessages.HostConfigApplied applied) {
        String problem = RunnerHostSessions.appliedProblem(applied);
        if (problem != null) {
            registry.sendTo(out, RunnerMessages.ProtocolError.of(RunnerMessages.BAD_MESSAGE,
                    "Invalid host.config.applied message: " + problem));
            return;
        }
        // A pool from a host that did not list sandbox.pool in hello is ignored: its hello decides.
        if (!sessions.recordApplied(host, applied,
                registry.supports(host.runnerHostId(), RunnerMessages.FEATURE_SANDBOX_POOL))) {
            return;
        }
        registry.markConfigured(host.machineId(), out.getId());
        for (ConnectedHost placed : host.placements()) {
            SystemContext.runInCompany(placed.company(), () -> listener().hostConfigured(placed));
        }
    }

    /**
     * The connection's host, with a machine's presences as the registry keeps them (ADR-0092): the owner's joining
     * and leaving of companies while the host is connected change where its frames are placed, which companies its
     * signs of life reach, and which its disconnect reaches. Null before the token was checked.
     */
    private @Nullable ConnectedHost current(WebSocketSession session) {
        ConnectedHost host = (ConnectedHost) session.getAttributes().get(HOST);
        if (host == null || !host.personal() || session.getAttributes().get(READY) == null) {
            return host;
        }
        ConnectedHost registered = registry.connectedHost(host.machineId()).orElse(null);
        if (registered == null || registered.presences().equals(host.presences())) {
            return host;
        }
        ConnectedHost current = host.withPresences(registered.presences());
        session.getAttributes().put(HOST, current);
        return current;
    }

    /**
     * A machine whose owner joined a company while it was connected, before the registry knew (the window between
     * the commit and its notice), learns of its presence there from the first run of that company it is asked about
     * ({@link RunnerHostSessions#placeForRun}): the connection's host carries the presence from then on.
     *
     * @return the host to go on with
     */
    private static ConnectedHost adopt(WebSocketSession session, ConnectedHost host, ConnectedHost placed) {
        if (placed.presences().equals(host.presences())) {
            return host;
        }
        ConnectedHost current = host.withPresences(placed.presences());
        session.getAttributes().put(HOST, current);
        return current;
    }

    /** The error for a frame about a run of no company of the host's, or one the server does not know. */
    private static RunnerMessages.ProtocolError unknownRun(UUID runId) {
        return RunnerMessages.ProtocolError.of(RunnerMessages.UNKNOWN_RUN, "Unknown run", runId);
    }

    private RunnerSessionListener listener() {
        return listeners.getObject();
    }

    private void send(WebSocketSession out, List<RunnerMessages.Outbound> frames) {
        for (RunnerMessages.Outbound frame : frames) {
            registry.sendTo(out, frame);
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        ConnectedHost host = current(session);
        WebSocketSession out = (WebSocketSession) session.getAttributes().get(OUT);
        if (host != null && out != null && registry.markInbound(host.machineId(), out.getId())) {
            hostActive(host);
        }
    }

    /** The host talks, so it is alive: its running runs get a fresh lease. */
    private void hostActive(ConnectedHost host) {
        for (ConnectedHost placed : host.placements()) {
            try {
                SystemContext.runInCompany(placed.company(), () -> listener().hostActive(placed));
            }
            catch (RuntimeException ex) {
                log.warn("Renewing the leases of runner host {} failed", placed.runnerHostId(), ex);
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Runner connection {} failed: {}", session.getId(), exception.getClass().getSimpleName());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ConnectedHost host = current(session);
        WebSocketSession out = (WebSocketSession) session.getAttributes().get(OUT);
        if (host == null || out == null) {
            return;
        }
        registry.unregister(host.machineId(), out.getId());
        if (session.getAttributes().get(READY) != null) {
            try {
                sessions.touch(host);
            }
            catch (RuntimeException ex) {
                log.debug("Recording the disconnect of runner host {} failed", host.machineId(), ex);
            }
            if (!registry.isConnected(host.machineId())) {
                // Not replaced by a newer connection: the host's runs, in each company, wait for it to come back.
                for (ConnectedHost placed : host.placements()) {
                    try {
                        SystemContext.runInCompany(placed.company(), () -> listener().hostDisconnected(placed));
                    }
                    catch (RuntimeException ex) {
                        log.warn("Extending the leases of disconnected runner host {} failed",
                                placed.runnerHostId(), ex);
                    }
                }
            }
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= MAX_LOGGED_ERROR_LENGTH ? text : text.substring(0, MAX_LOGGED_ERROR_LENGTH) + "...";
    }
}
