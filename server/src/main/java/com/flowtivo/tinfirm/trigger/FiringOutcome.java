package com.flowtivo.tinfirm.trigger;

/** What became of a firing (the package 4 contract, §3.11, §5.6, §5.10). */
public enum FiringOutcome {
    /** A new conversation started with its first turn. */
    STARTED,
    /** The trigger's open conversation on the subject took it: an {@code EVENT} turn, now or next. */
    CONTINUED,
    /** A schedule's overlap {@code SKIP}: the previous firing's conversation is busy. */
    SKIPPED_OPEN,
    /** A schedule's firing missed under catch-up {@code NONE}. */
    SKIPPED_MISSED,
    /** A subject Tinfirm created beyond the chain of 3 (§5.6). */
    SKIPPED_CHAIN,
    /** Past the trigger's {@code max_firings_per_hour}. */
    SKIPPED_RATE,
    /** A schedule's firing while its agent is paused. */
    SKIPPED_AGENT_PAUSED,
    /** It could not start: the reason says why. */
    FAILED
}
