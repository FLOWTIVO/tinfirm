package com.flowtivo.tinfirm.runner;

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** The runner hosts connected to this server process (one replica: an accepted limit, PLATFORM.md §14). */
public interface RunnerConnections {

    boolean isConnected(UUID runnerHostId);

    /** The runner hosts connected to this process now. */
    Collection<UUID> connectedHostIds();

    /**
     * Whether the host is connected and has confirmed its capacity with {@code host.config.applied} on the current
     * connection. Only then may runs be assigned to it (docs/archive/v1/CONVENTIONS.md §15.1, §15.7).
     */
    boolean isConfigured(UUID runnerHostId);

    /**
     * When the server last received a frame (a pong included) on the host's current connection; null when the host
     * is not connected. A host that talks is alive, whatever its heartbeats (docs/archive/v1/CONVENTIONS.md §15.1).
     */
    @Nullable Instant lastInbound(UUID runnerHostId);

    /**
     * The runner protocol version of the host's current connection (its {@code hello}), so that the module running work
     * sends each host the frames of its version; {@link RunnerMessages#PROTOCOL_V1} when the host is not connected.
     */
    int protocolVersion(UUID runnerHostId);

    /**
     * Whether the host's current connection implements an optional feature of protocol 2, which its {@code hello}
     * lists ({@link RunnerMessages#FEATURE_RUN_STEER}); false when the host is not connected.
     */
    boolean supports(UUID runnerHostId, String feature);

    /**
     * Sends one frame if the host is connected. Returns false when it is not, or the send failed; callers rely on
     * accept timeouts, leases and the replay of pending commands on reconnect, never on delivery.
     */
    boolean send(UUID runnerHostId, RunnerMessages.Outbound message);
}
