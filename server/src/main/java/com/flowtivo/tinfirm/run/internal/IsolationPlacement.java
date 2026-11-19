package com.flowtivo.tinfirm.run.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.run.PlacementNote;
import com.flowtivo.tinfirm.runner.Isolation;
import com.flowtivo.tinfirm.runner.IsolationLevel;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;

/**
 * The dispatcher's step for the level of isolation (ADR-0113, decision 4), a pure function of the hosts that count for
 * the run (package 2's rule, {@code Hosts.eligible}), the level chosen for its agent and the conversation's session.
 * <ul>
 * <li>The target is the chosen level, else the strongest the company's hosts that count report, or the payer's
 * personal hosts' where no company host counts ({@link Isolation#target}). A chosen level that no host takes makes the
 * run wait ({@code NO_HOST_OF_LEVEL}).</li>
 * <li>The candidates are the hosts that take the target level ({@link Isolation#takes}): the company hosts of the
 * level, and the payer's personal hosts of the level or a stronger one. A run never goes to a weaker host by itself,
 * waits for these as it waits for any host today, and never waits for a personal host alone while a company host of
 * the level counts.</li>
 * <li>The session's host (the host of the conversation's latest run that reported a session) is preferred, as
 * today. An automatic agent's conversation stays on it while it takes the turn, even when it does not take the
 * target level (a weaker host, a personal one included) ({@link PlacementNote#STAYED_ON_WEAKER_HOST}); when it cannot, the turn goes to the target level with a
 * fresh session ({@link PlacementNote#FRESH_SESSION_STRONGER_LEVEL}). A chosen level holds for every turn: a session
 * on a host of another level starts again on the chosen level ({@link PlacementNote#FRESH_SESSION_CHOSEN_LEVEL}).
 * Where a bundle of that session is kept (ADR-0097), the turn moves with it: a free host of the level that restores
 * bundles comes first, then (automatic) the session's host, then the level's other hosts; a turn that restores the
 * session gets no note.</li>
 * </ul>
 */
final class IsolationPlacement {

    private IsolationPlacement() {
    }

    /**
     * The conversation's session: the host that holds it, among the hosts that count, and whether a bundle of it is
     * kept, which moves the turn with it to a host that restores bundles.
     */
    record Session(RunnerHostInfo host, boolean bundleCarried) {
    }

    /** What a placement found. */
    sealed interface Outcome permits Plan, Waits {
    }

    /**
     * The run goes to one of {@code candidates}.
     *
     * @param level           the target level
     * @param chosen          whether the level was chosen for the agent
     * @param preferred       the session's host, which the selection takes first when it is free (after a restorer of
     *                        the level when {@code preferRestorers}); null for none
     * @param preferRestorers whether the level's hosts that restore session bundles come first, for a session that
     *                        moves with its bundle
     * @param weakerLeftOut   whether a weaker host that counts for the run was left out: the queue reason names the
     *                        level
     */
    record Plan(List<RunnerHostInfo> candidates, IsolationLevel level, boolean chosen, @Nullable UUID preferred,
            boolean preferRestorers, boolean weakerLeftOut) implements Outcome {

        Plan {
            candidates = List.copyOf(candidates);
        }
    }

    /** The run waits: no host of the chosen level counts for it. */
    record Waits(QueueReason reason) implements Outcome {
    }

    /**
     * @param counting the hosts that count for the run, at least one
     * @param chosen   the level chosen for the agent; null for automatic
     * @param session  the conversation's session, when its host counts for the run; else null
     */
    static Outcome plan(List<RunnerHostInfo> counting, @Nullable IsolationLevel chosen, @Nullable Session session) {
        IsolationLevel level;
        switch (Isolation.target(counting, chosen)) {
            case Isolation.NoHostOfLevel none -> {
                return new Waits(QueueReason.noHostOfLevel(none.chosen()));
            }
            case Isolation.Level found -> level = found.level();
        }
        List<RunnerHostInfo> candidates = new ArrayList<>(counting.stream()
            .filter(host -> Isolation.takes(host, level))
            .toList());
        boolean isChosen = chosen != null;
        UUID preferred = null;
        boolean preferRestorers = false;
        if (session != null) {
            RunnerHostInfo held = session.host();
            if (Isolation.takes(held, level)) {
                preferred = held.id();
            }
            else {
                // A kept bundle moves the session with the turn: a free host of the level that restores it first.
                preferRestorers = session.bundleCarried();
                if (!isChosen) {
                    // An automatic agent's conversation stays where its session is, while that host takes the turn
                    // and no host of the level restores its session.
                    candidates.add(held);
                    preferred = held.id();
                }
            }
        }
        // The weaker hosts that count and were left out: then the queue reason names the level.
        boolean weakerLeftOut = counting.stream()
            .anyMatch(host -> Isolation.stronger(level, Isolation.placedAt(host)) && !candidates.contains(host));
        return new Plan(candidates, level, isChosen, preferred, preferRestorers, weakerLeftOut);
    }

    /**
     * The plan's candidates as the dispatcher's lock read them again: those that still take the plan's level, and an
     * automatic agent's session host (ADR-0113, decision 4). A host whose report changed between the plan and the lock
     * is left out, so that a run never goes to a level it was not planned for; with none left, it waits for the next
     * pass, which plans again.
     */
    static List<RunnerHostInfo> stillPlaced(Plan plan, List<RunnerHostInfo> locked, @Nullable Session session) {
        UUID held = !plan.chosen() && session != null ? session.host().id() : null;
        return locked.stream()
            .filter(host -> Isolation.takes(host, plan.level()) || host.id().equals(held))
            .toList();
    }

    /**
     * The note the conversation gets about where its turn ran; null for none.
     *
     * @param host     the host the selection chose among the plan's candidates
     * @param restored whether the turn restores the session's bundle there
     */
    static @Nullable PlacementNote note(Plan plan, RunnerHostInfo host, @Nullable Session session,
            boolean restored) {
        if (session == null) {
            return null;
        }
        // The session's host took the level itself: a move between hosts that take it is as before ADR-0113.
        boolean heldTakes = Isolation.takes(session.host(), plan.level());
        if (host.id().equals(session.host().id())) {
            return heldTakes ? null : PlacementNote.STAYED_ON_WEAKER_HOST;
        }
        if (heldTakes || restored) {
            // Another host that takes the level; or the session moved with its bundle.
            return null;
        }
        return plan.chosen() ? PlacementNote.FRESH_SESSION_CHOSEN_LEVEL : PlacementNote.FRESH_SESSION_STRONGER_LEVEL;
    }
}
