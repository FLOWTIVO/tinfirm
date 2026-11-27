package com.flowtivo.tinfirm.runner;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

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
     * The {@code run.git.revoke} frames of an answer ({@link #hostConnected}, {@link #runMessage},
     * {@link #beforeCancelOfForeignRun}) that were written to the host's connection, after the write (ADR-0116): their
     * revocations count as sent from now. A frame whose write failed is not among them. Called in the tenant context of
     * the company whose runs they are, outside any transaction.
     */
    default void revocationsWritten(UUID companyId, List<RunnerMessages.RunGitRevoke> written) {
    }

    /**
     * ADR-0116: a run that the host reports in {@code hello} and that is of a company the host is not in (a machine
     * whose owner left it): the {@code run.git.revoke} frames that go before its {@code run.cancel}, for the epoch the
     * host reports, and only when that assignment went to this machine, so that a host learns nothing of a run it does
     * not hold. Called in the tenant context of the run's company, outside any transaction.
     *
     * @param personalHostId   the machine that reports the run
     * @param takesRevocations whether the connection takes {@code run.git.revoke} (protocol 2 and the feature)
     */
    default List<RunnerMessages.Outbound> beforeCancelOfForeignRun(UUID companyId, UUID personalHostId,
            RunnerMessages.ActiveRun run, boolean takesRevocations) {
        return List.of();
    }

    /**
     * ADR-0116: ends the answer to a {@code hello} that lists {@code run.git.revoke}, after its other frames were
     * written. Writes with {@code write}, in this order, the revocations of the runs the hello listed (in each company,
     * for the epoch it listed, of an assignment that went to that presence) that were not written to a connection yet,
     * and then {@code welcome.done}, while no revocation of a run on these hosts can commit; then counts the
     * revocations written as sent. Called outside any transaction and any tenant context.
     *
     * @param listed the host's presences, each with the runs the hello listed in its company
     * @param write  writes a frame to the connection; false when the write failed
     */
    default void endHelloAnswer(Map<ConnectedHost, List<RunnerMessages.ActiveRun>> listed,
            Predicate<RunnerMessages.Outbound> write) {
        write.test(new RunnerMessages.WelcomeDone());
    }

    /**
     * After {@code host.config.applied} was stored: the host's confirmed capacity or draining state changed, and on
     * the first one of a connection the host starts taking runs. Dispatches without waiting for the next tick.
     */
    void hostConfigured(ConnectedHost host);

    /**
     * ADR-0117: the host's problems changed from none to some ({@code healthy} false), or from some to none
     * ({@code healthy} true), in this company's context. The runs that wait in a back-off after this host's
     * rejections are placed again now, and the dispatcher runs without waiting for its next tick.
     */
    void hostHealthChanged(ConnectedHost host, boolean healthy);

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
