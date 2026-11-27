package com.flowtivo.tinfirm.runner;

import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * Where an agent's runs go by the level of isolation its company's hosts report (ADR-0113, decision 4): the level a
 * host is placed at, the order of the hosts, and the level an agent's runs go to. Pure functions of what the hosts
 * reported, never checked (decision 3).
 * <ul>
 * <li>A host is placed at the level it reports; a host that has not said hello to a server of this release yet at
 * {@link IsolationLevel#CONTAINER} (decision 2).</li>
 * <li>The levels' order is {@code machine}, {@code vm-jail}, {@code container}. Within {@code machine}, a machine per
 * run ({@code hardened: none}) and a hardened node come first; within {@code vm-jail}, a hardened machine. Hardening
 * orders hosts of a level and refuses none.</li>
 * <li>The company's hosts set the level (the owner's rule of 2026-11-19): an automatic agent's runs go to the strongest
 * level among the company hosts that count for the run, and only where none counts (a subscription's run, or a
 * company without a host for the runtime) to the strongest among the member's personal hosts that count. A chosen
 * level is the level. The company hosts of the level take the run, and so do the payer's personal hosts that count and
 * report that level or a stronger one ({@link #takes}); a weaker personal host does not, and no run waits for a
 * personal host.</li>
 * </ul>
 */
public final class Isolation {

    private Isolation() {
    }

    /** The level a host is placed at: the one it reports, else {@code container}. */
    public static IsolationLevel placedAt(RunnerHostInfo host) {
        return host.isolationLevel() != null ? host.isolationLevel() : IsolationLevel.CONTAINER;
    }

    /** Whether {@code level} is stronger than {@code than}. */
    public static boolean stronger(IsolationLevel level, IsolationLevel than) {
        return level.ordinal() < than.ordinal();
    }

    /**
     * Whether the host takes runs that go to {@code level}: a company host placed at it, a personal host placed at it
     * or a stronger one.
     */
    public static boolean takes(RunnerHostInfo host, IsolationLevel level) {
        IsolationLevel placed = placedAt(host);
        return placed == level || !host.company() && stronger(placed, level);
    }

    /**
     * A host's place in the order, lower first: 0 {@code machine} with {@code none} or {@code yes}; 1 {@code machine}
     * otherwise; 2 {@code vm-jail} with {@code yes}; 3 {@code vm-jail} otherwise; 4 {@code container}.
     */
    public static int rank(RunnerHostInfo host) {
        Hardening hardened = host.isolationLevel() != null ? host.hardened() : null;
        return switch (placedAt(host)) {
            case MACHINE -> hardened == Hardening.NONE || hardened == Hardening.YES ? 0 : 1;
            case VM_JAIL -> hardened == Hardening.YES ? 2 : 3;
            case CONTAINER -> 4;
        };
    }

    /** The strongest level among the hosts; empty for none. */
    public static Optional<IsolationLevel> strongest(Collection<RunnerHostInfo> hosts) {
        return hosts.stream().map(Isolation::placedAt).min(Comparator.naturalOrder());
    }

    /**
     * The level an automatic agent's runs go to among the hosts that count: the strongest of the company hosts among
     * them, or of all of them where no company host counts; empty for none.
     */
    public static Optional<IsolationLevel> automatic(Collection<RunnerHostInfo> counting) {
        List<RunnerHostInfo> company = counting.stream().filter(RunnerHostInfo::company).toList();
        return strongest(company.isEmpty() ? counting : company);
    }

    /** The levels the hosts are placed at, strongest first, each once. */
    public static List<IsolationLevel> offered(Collection<RunnerHostInfo> hosts) {
        Set<IsolationLevel> levels = EnumSet.noneOf(IsolationLevel.class);
        hosts.forEach(host -> levels.add(placedAt(host)));
        return List.copyOf(levels);
    }

    /** The level an agent's runs go to among the hosts that count, at least one. */
    public sealed interface Target permits Level, NoHostOfLevel {
    }

    /**
     * The runs go to the hosts that take this level ({@link #takes}).
     *
     * @param chosen whether the level was chosen for the agent; else it is the one the hosts set ({@link #automatic})
     */
    public record Level(IsolationLevel level, boolean chosen) implements Target {
    }

    /** The level chosen for the agent, which no host that counts takes: the run waits. */
    public record NoHostOfLevel(IsolationLevel chosen, List<IsolationLevel> offered) implements Target {

        public NoHostOfLevel {
            offered = List.copyOf(offered);
        }
    }

    /**
     * The target level of a run among the hosts that count, at least one.
     *
     * @param chosen the level chosen for the agent; null for automatic
     */
    public static Target target(Collection<RunnerHostInfo> counting, @Nullable IsolationLevel chosen) {
        if (counting.isEmpty()) {
            throw new IllegalArgumentException("No host counts for the run");
        }
        if (chosen == null) {
            return new Level(automatic(counting).orElseThrow(), false);
        }
        return counting.stream().anyMatch(host -> takes(host, chosen)) ? new Level(chosen, true)
                : new NoHostOfLevel(chosen, offered(counting));
    }

    /** The hosts among those that count that take runs of the level, in their order. */
    public static List<RunnerHostInfo> taking(Collection<RunnerHostInfo> counting, IsolationLevel level) {
        return counting.stream().filter(host -> takes(host, level)).toList();
    }
}
