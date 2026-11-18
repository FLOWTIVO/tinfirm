package com.flowtivo.tinfirm.run;

/**
 * Why a turn ran where it did, when its conversation's session was on a host of another level of isolation than the
 * level the run went to (ADR-0113, decision 4; {@code run.placement_note}). A run without a note ran where its session
 * was, or had none, or moved with its session's bundle.
 */
public enum PlacementNote {
    /** An automatic agent's conversation stayed on the weaker host that holds its session. */
    STAYED_ON_WEAKER_HOST,
    /** The level chosen for the agent holds: the turn started a fresh session of the runtime on a host of that level. */
    FRESH_SESSION_CHOSEN_LEVEL,
    /** The weaker host that held the session could not take the turn: it started a fresh session on the target level. */
    FRESH_SESSION_STRONGER_LEVEL
}
