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
 * <li>The target is the chosen level, else the strongest the hosts report ({@link Isolation#target}). A chosen level
 * that none of them reports makes the run wait ({@code NO_HOST_OF_LEVEL}).</li>
 * <li>The candidates are the hosts of the target level: a run never goes to a weaker host by itself, and waits for
 * these as it waits for any host today.</li>
 * <li>The session's host (the host of the conversation's latest run that reported a session) is preferred, as
 * today. An automatic agent's conversation stays on it while it takes the turn, even when it is weaker than the
 * target ({@link PlacementNote#STAYED_ON_WEAKER_HOST}); when it cannot, the turn goes to the target level with a
 * fresh session ({@link PlacementNote#FRESH_SESSION_STRONGER_LEVEL}). A chosen level holds for every turn: a session
 * on a host of another level starts again on the chosen level ({@link PlacementNote#FRESH_SESSION_CHOSEN_LEVEL}).
 * Where a bundle of that session is kept (ADR-0097), the turn moves with it: hosts that restore bundles come first,
 * and a turn that restores the session gets no note.</li>
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
     * @param preferred       the session's host, which the selection takes first when it is free; null for none
     * @param preferRestorers whether hosts that restore session bundles come first, for a session that moves
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
        List<RunnerHostInfo> tier = new ArrayList<>(counting.stream()
            .filter(host -> Isolation.placedAt(host) == level)
            .toList());
        boolean weakerLeftOut = counting.stream().anyMatch(host -> Isolation.stronger(level, Isolation.placedAt(host)));
        boolean isChosen = chosen != null;
        if (session == null) {
            return new Plan(tier, level, isChosen, null, false, weakerLeftOut);
        }
        RunnerHostInfo held = session.host();
        if (Isolation.placedAt(held) == level) {
            return new Plan(tier, level, isChosen, held.id(), false, weakerLeftOut);
        }
        if (session.bundleCarried()) {
            // The session moves with the turn: a host of the level that restores the bundle first.
            return new Plan(tier, level, isChosen, null, true, weakerLeftOut);
        }
        if (!isChosen) {
            // An automatic agent's conversation stays where its session is, while that host takes the turn.
            tier.add(held);
            return new Plan(tier, level, false, held.id(), false, weakerLeftOut);
        }
        return new Plan(tier, level, true, null, false, weakerLeftOut);
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
        IsolationLevel held = Isolation.placedAt(session.host());
        if (host.id().equals(session.host().id())) {
            return held != plan.level() ? PlacementNote.STAYED_ON_WEAKER_HOST : null;
        }
        if (held == plan.level() || restored) {
            // Another host of the same level, as before ADR-0113; or the session moved with its bundle.
            return null;
        }
        return plan.chosen() ? PlacementNote.FRESH_SESSION_CHOSEN_LEVEL : PlacementNote.FRESH_SESSION_STRONGER_LEVEL;
    }
}
