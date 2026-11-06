package com.flowtivo.tinfirm.runner;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Port: the run side of the runner protocol, implemented by the module that runs work (the run module, package 3 of
 * the platform redesign).
 * <p>
 * The endpoint calls it as actor SYSTEM in the tenant context of the host's company, without a transaction (the
 * listener opens its own), for one frame at a time per connection. The returned frames are sent to the host in
 * order, after the work has committed. Work that takes long, such as a v2 server-executed tool call, must not hold
 * the connection's frames up: the listener returns nothing for it, does it on a thread of its own, and sends its
 * answer later with {@link RunnerConnections#send}, to whichever connection of the host is current then.
 */
public interface RunnerSessionListener {

    /**
     * After {@code hello} (credentials recorded, {@code welcome} sent): reconcile the runs the host reports as still
     * running, and return the frames to send, such as {@code run.cancel} for stale runs and pending commands, and for
     * a v2 host whose Public Suffix List ({@code publicSuffixSha256}, null when it holds none) is not the server's
     * the {@code psl.update}.
     */
    List<RunnerMessages.Outbound> hostConnected(ConnectedHost host, List<RunnerMessages.ActiveRun> activeRuns,
            @Nullable String publicSuffixSha256);

    /**
     * {@code run.accepted}, {@code run.rejected}, {@code run.event}, {@code run.heartbeat}, {@code run.finished};
     * from a v1 host {@code run.repository_request} (always answered with one {@code run.repository_request.ack}),
     * {@code run.task_create} (always answered with one {@code run.task_create.ack}) or {@code run.ask} (always
     * answered with one {@code run.ask.ack}); from a v2 host {@code run.request} (always answered with one
     * {@code run.request.ack}, at once or, for a TOOL request, later through {@link RunnerConnections#send}). The
     * host's protocol version is {@link ConnectedHost#protocolVersion()}.
     */
    List<RunnerMessages.Outbound> runMessage(ConnectedHost host, RunnerMessages.RunMessage message);

    /**
     * After {@code host.config.applied} was stored: the host's confirmed capacity or draining state changed, and on
     * the first one of a connection the host starts taking runs. Dispatches without waiting for the next tick.
     */
    void hostConfigured(ConnectedHost host);

    /**
     * The connected host sent a frame, and its runs' leases were last renewed this way at least
     * {@link RunnerProperties#leaseRenewalInterval()} ago: a host that talks is alive, so all its running runs get a
     * fresh lease (docs/archive/v1/CONVENTIONS.md §15.1).
     */
    void hostActive(ConnectedHost host);

    /**
     * The host's connection closed, and no newer connection of the host has replaced it: its runs keep a lease of at
     * least the reconnect grace, so a network interruption or a restart of the host loses no run (docs/archive/v1/CONVENTIONS.md §15.1).
     */
    void hostDisconnected(ConnectedHost host);
}
