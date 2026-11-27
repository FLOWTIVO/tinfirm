package com.flowtivo.tinfirm.run.internal;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.runner.Isolation;
import com.flowtivo.tinfirm.runner.IsolationLevel;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.runner.RunnerMessages;

/**
 * Picks the runner host of a run among those package 2's rule leaves it ({@code Hosts.eligible}): the dispatcher's
 * own choice (the package 3 contract, §5.7 step 4 and 5; open point 13). A host takes the run when it is connected and
 * has confirmed its capacity on that connection, speaks runner protocol v2, does not drain, reports no problem with
 * {@code host.health} (ADR-0117) and has a free slot under
 * its dispatch limit. Of those, any host before the one that rejected the run last; then, for a session that moves
 * with its bundle, a host of its level that restores it;
 * then the conversation's previous host, so that the turn resumes its session there; then the stronger host by its
 * level and hardening ({@link Isolation#rank}, ADR-0113: the dispatcher hands it the hosts that take one level, the
 * payer's stronger personal hosts among them, and the session's host); else the one with the most free slots, then the first by name, so that runs spread over the hosts.
 * When none takes it, the queue reason says why, about the one eligible host when there is only one.
 */
final class HostSelection {

    private HostSelection() {
    }

    /**
     * An eligible host as the dispatcher sees it.
     *
     * @param configured whether the host is connected and has sent {@code host.config.applied} on this connection
     * @param protocol   the runner protocol of its current connection
     * @param used       runs that hold a slot of the host
     * @param skills     whether its connection lists {@code run.skills} (ADR-0064)
     * @param restores   whether it restores session bundles: a company host whose connection lists
     *                   {@code session.bundle} (ADR-0097)
     * @param appTokens  whether its connection lists {@code run.git.refresh} and {@code run.git.revoke} (ADR-0116)
     * @param problems   what its connection reports with {@code host.health} (ADR-0117); empty: it takes runs
     */
    record Host(RunnerHostInfo info, boolean configured, int protocol, int used, boolean skills, boolean restores,
            boolean appTokens, List<RunnerMessages.HostProblem> problems) {

        Host {
            problems = List.copyOf(problems);
        }

        /** A host that reports no problem. */
        Host(RunnerHostInfo info, boolean configured, int protocol, int used, boolean skills, boolean restores,
                boolean appTokens) {
            this(info, configured, protocol, used, skills, restores, appTokens, List.of());
        }

        /** A host that renews no App installation's token. */
        Host(RunnerHostInfo info, boolean configured, int protocol, int used, boolean skills, boolean restores) {
            this(info, configured, protocol, used, skills, restores, false);
        }

        /** A host that restores no session bundle. */
        Host(RunnerHostInfo info, boolean configured, int protocol, int used, boolean skills) {
            this(info, configured, protocol, used, skills, false);
        }

        /** A host as one that does not deliver skills. */
        Host(RunnerHostInfo info, boolean configured, int protocol, int used) {
            this(info, configured, protocol, used, false, false);
        }

        boolean v2() {
            return protocol >= 2;
        }

        int limit() {
            return info.dispatchLimit();
        }

        int free() {
            return Math.max(0, limit() - used);
        }
    }

    /** What the selection found. */
    sealed interface Outcome permits Chosen, Waits {
    }

    /** The run goes to this host. */
    record Chosen(Host host) implements Outcome {
    }

    /** No eligible host takes the run now. */
    record Waits(QueueReason reason) implements Outcome {
    }

    /**
     * @param eligible     the hosts package 2 allows, at least one
     * @param previousHost the host of the conversation's previous run, or null
     */
    static Outcome select(List<Host> eligible, @Nullable UUID previousHost) {
        return select(eligible, previousHost, false);
    }

    /**
     * @param eligible     the hosts package 2 allows, at least one
     * @param previousHost the host of the conversation's previous run, or null
     * @param needsSkills  whether the run has skills, which only a host that lists {@code run.skills} delivers
     */
    static Outcome select(List<Host> eligible, @Nullable UUID previousHost, boolean needsSkills) {
        return select(eligible, previousHost, needsSkills, null);
    }

    /**
     * @param eligible        the hosts package 2 allows and the level of isolation leaves, at least one
     * @param previousHost    the host that holds the conversation's session, or of its previous run; or null
     * @param needsSkills     whether the run has skills, which only a host that lists {@code run.skills} delivers
     * @param restorersOf     the level whose hosts that restore session bundles come first, even before the previous
     *                        host: the session moves with its bundle (ADR-0113, decision 4); null for none
     */
    static Outcome select(List<Host> eligible, @Nullable UUID previousHost, boolean needsSkills,
            @Nullable IsolationLevel restorersOf) {
        return select(eligible, previousHost, needsSkills, restorersOf, false);
    }

    /**
     * As {@link #select(List, UUID, boolean, IsolationLevel)}, for a run whose git tokens a GitHub App mints
     * ({@code needsAppTokens}, ADR-0116, decision 8): only a host that renews them takes it, else it waits
     * {@code NO_HOST_FOR_APP_TOKENS}.
     */
    static Outcome select(List<Host> eligible, @Nullable UUID previousHost, boolean needsSkills,
            @Nullable IsolationLevel restorersOf, boolean needsAppTokens) {
        return select(eligible, previousHost, needsSkills, restorersOf, needsAppTokens, null);
    }

    /**
     * As {@link #select(List, UUID, boolean, IsolationLevel, boolean)}, for a run that {@code rejectedBy} rejected
     * last ({@link QueueReason#rejections}): another free host takes it before that one, even before the session's
     * host, so that a host that cannot run it does not get it again while another stands idle. With no other free
     * host it goes to that one again, after its back-off.
     */
    static Outcome select(List<Host> eligible, @Nullable UUID previousHost, boolean needsSkills,
            @Nullable IsolationLevel restorersOf, boolean needsAppTokens, @Nullable UUID rejectedBy) {
        if (eligible.isEmpty()) {
            throw new IllegalArgumentException("No eligible host to select from");
        }
        List<Host> connected = eligible.stream().filter(Host::configured).toList();
        if (connected.isEmpty()) {
            return new Waits(eligible.size() == 1 ? QueueReason.hostOffline(eligible.getFirst().info())
                    : QueueReason.noRunnerHost(null, "None of the " + eligible.size() + " runner hosts that may run "
                            + "this conversation is connected."));
        }
        List<Host> current = connected.stream().filter(Host::v2).toList();
        if (current.isEmpty()) {
            return new Waits(QueueReason.other(connected.size() == 1
                    ? "Runner host " + connected.getFirst().info().name() + " speaks runner protocol "
                            + connected.getFirst().protocol() + "; conversations run on hosts of protocol 2."
                    : "The connected runner hosts that may run this conversation speak runner protocol 1; "
                            + "conversations run on hosts of protocol 2."));
        }
        if (needsSkills) {
            List<Host> delivering = current.stream().filter(Host::skills).toList();
            if (delivering.isEmpty()) {
                return new Waits(QueueReason.noHostForSkills(current.size() == 1
                        ? "The agent has skills, and runner host " + current.getFirst().info().name() + " does not "
                                + "deliver skills yet: it needs a runner host version with run.skills."
                        : "The agent has skills, and none of the " + current.size() + " connected runner hosts that "
                                + "may run this conversation delivers skills yet."));
            }
            current = delivering;
        }
        if (needsAppTokens) {
            List<Host> renewing = current.stream().filter(Host::appTokens).toList();
            if (renewing.isEmpty()) {
                return new Waits(QueueReason.noHostForAppTokens(current.size() == 1
                        ? "A repository of this conversation acts through a GitHub App's installation, and runner host "
                                + current.getFirst().info().name() + " cannot renew its tokens yet: it needs a runner "
                                + "host version with run.git.refresh."
                        : "A repository of this conversation acts through a GitHub App's installation, and none of "
                                + "the " + current.size() + " connected runner hosts that may run it renews its tokens "
                                + "yet."));
            }
            current = renewing;
        }
        List<Host> open = current.stream().filter(host -> !host.info().draining()).toList();
        if (open.isEmpty()) {
            return new Waits(current.size() == 1 ? QueueReason.hostDraining(current.getFirst().info())
                    : QueueReason.noRunnerHost(null, "Every runner host that may run this conversation is finishing "
                            + "its runs before it restarts."));
        }
        // ADR-0117: a host that says it takes no run gets none, so no run.assign, token or epoch is spent on it.
        List<Host> healthy = open.stream().filter(host -> host.problems().isEmpty()).toList();
        if (healthy.isEmpty()) {
            return new Waits(open.size() == 1
                    ? QueueReason.hostUnhealthy(open.getFirst().info(), open.getFirst().problems().getFirst())
                    : QueueReason.hostsUnhealthy(open.size()));
        }
        List<Host> free = healthy.stream().filter(host -> host.free() > 0).toList();
        if (free.isEmpty()) {
            int used = healthy.stream().mapToInt(Host::used).sum();
            int limit = healthy.stream().mapToInt(Host::limit).sum();
            return new Waits(QueueReason.hostsFull(healthy.size() == 1 ? healthy.getFirst().info() : null, used,
                    limit));
        }
        // A free host that did not reject the run last first; then one of the level that restores a moving session;
        // then the session's (or previous) host; then the stronger by level and hardening, the one with more room, the
        // name.
        Comparator<Host> notRejecting = Comparator.comparing(host -> host.info().id().equals(rejectedBy));
        Comparator<Host> restoring = Comparator.comparing(host -> !(restorersOf != null && host.restores()
                && Isolation.takes(host.info(), restorersOf)));
        Comparator<Host> previous = Comparator.comparing(host -> !host.info().id().equals(previousHost));
        return new Chosen(free.stream()
            .min(notRejecting.thenComparing(restoring)
                .thenComparing(previous)
                .thenComparingInt(host -> Isolation.rank(host.info()))
                .thenComparing(Comparator.comparingInt(Host::free).reversed())
                .thenComparing(host -> host.info().name())
                .thenComparing(host -> host.info().id()))
            .orElseThrow());
    }
}
