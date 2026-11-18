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
 * <li>A host is placed at the level it reports; a host that reports none (no {@code host.isolation}) at
 * {@link IsolationLevel#CONTAINER}, whatever its tier (decision 2).</li>
 * <li>The levels' order is {@code machine}, {@code vm-jail}, {@code container}. Within {@code machine}, a machine per
 * run ({@code hardened: none}) and a hardened node come first; within {@code vm-jail}, a hardened machine. Hardening
 * orders hosts of a level and refuses none.</li>
 * <li>An agent's runs go to the strongest level among the hosts that count for the run, or to the level chosen for the
 * agent; never to a weaker one by themselves.</li>
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
     * The runs go to hosts of this level.
     *
     * @param chosen whether the level was chosen for the agent; else it is the strongest the hosts report
     */
    public record Level(IsolationLevel level, boolean chosen) implements Target {
    }

    /** The level chosen for the agent, which none of the hosts that count reports: the run waits. */
    public record NoHostOfLevel(IsolationLevel chosen, List<IsolationLevel> offered) implements Target {

        public NoHostOfLevel {
            offered = List.copyOf(offered);
        }
    }

    /**
     * The target level of a run among the hosts that count, at least one.
     *
     * @param chosen the level chosen for the agent; null for automatic, the strongest
     */
    public static Target target(Collection<RunnerHostInfo> counting, @Nullable IsolationLevel chosen) {
        if (counting.isEmpty()) {
            throw new IllegalArgumentException("No host counts for the run");
        }
        if (chosen == null) {
            return new Level(strongest(counting).orElseThrow(), false);
        }
        return counting.stream().anyMatch(host -> placedAt(host) == chosen) ? new Level(chosen, true)
                : new NoHostOfLevel(chosen, offered(counting));
    }
}
