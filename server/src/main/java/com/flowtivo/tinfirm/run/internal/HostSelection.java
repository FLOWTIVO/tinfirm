package com.flowtivo.tinfirm.run.internal;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.runner.RunnerHostInfo;

/**
 * Picks the runner host of a run among those package 2's rule leaves it ({@code Hosts.eligible}): the dispatcher's
 * own choice (the package 3 contract, §5.7 step 4 and 5; open point 13). A host takes the run when it is connected and
 * has confirmed its capacity on that connection, speaks runner protocol v2, does not drain and has a free slot under
 * its dispatch limit. Of those, the conversation's previous host wins, so that the turn resumes its session there;
 * else the one with the most free slots, then the first by name, so that runs spread over the hosts. When none takes
 * it, the queue reason says why, about the one eligible host when there is only one.
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
     */
    record Host(RunnerHostInfo info, boolean configured, int protocol, int used, boolean skills) {

        /** A host as one that does not deliver skills. */
        Host(RunnerHostInfo info, boolean configured, int protocol, int used) {
            this(info, configured, protocol, used, false);
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
        List<Host> open = current.stream().filter(host -> !host.info().draining()).toList();
        if (open.isEmpty()) {
            return new Waits(current.size() == 1 ? QueueReason.hostDraining(current.getFirst().info())
                    : QueueReason.noRunnerHost(null, "Every runner host that may run this conversation is finishing "
                            + "its runs before it restarts."));
        }
        List<Host> free = open.stream().filter(host -> host.free() > 0).toList();
        if (free.isEmpty()) {
            int used = open.stream().mapToInt(Host::used).sum();
            int limit = open.stream().mapToInt(Host::limit).sum();
            return new Waits(QueueReason.hostsFull(open.size() == 1 ? open.getFirst().info() : null, used, limit));
        }
        Optional<Host> previous = previousHost != null
                ? free.stream().filter(host -> host.info().id().equals(previousHost)).findFirst() : Optional.empty();
        return new Chosen(previous.orElseGet(() -> free.stream()
            .min(Comparator.comparingInt(Host::free)
                .reversed()
                .thenComparing(host -> host.info().name())
                .thenComparing(host -> host.info().id()))
            .orElseThrow()));
    }
}
