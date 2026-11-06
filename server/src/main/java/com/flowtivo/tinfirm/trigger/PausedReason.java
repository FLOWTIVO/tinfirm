package com.flowtivo.tinfirm.trigger;

/**
 * Why a trigger is paused (the package 4 contract, §3.1, §5.3, §5.11 and §11). A paused trigger resumes only when a
 * person resumes it (its author or an admin; the team only pauses) and the gate passes.
 */
public enum PausedReason {
    /** Paused by its author, its team or an admin. */
    USER,
    /** Its author left the company: its conversations wait for an admin's reassignment (§11). */
    AUTHOR_LEFT,
    /**
     * Its author lost a right: the agent, a service identity's accounts or budget, a subscription, or their seat (a
     * VIEWER authors no trigger).
     */
    NOT_ENTITLED,
    /** An account of its table went, or is another identity now. */
    ACCOUNT_REMOVED,
    /** A system of its table, not its source, was removed. */
    SYSTEM_REMOVED,
    /** An account of its table cannot be used: it needs a reconnect, or its holder is suspended (§11, point 9). */
    ACCOUNT_BROKEN,
    /**
     * A company system of its table is paused by an admin (the package 6 contract, §5.4, open point 6): the system's
     * resume offers to resume it too, through the gate.
     */
    SYSTEM_PAUSED,
    /**
     * An admin reassigned it after its author's loss ({@code AUTHOR_LEFT}, {@code NOT_ENTITLED}): it waits for its new
     * author to accept its accounts, its payer and the acknowledgement of its open events, which resumes it through the
     * gate. Nobody else resumes it; only {@code AUTHOR_LEFT} replaces it (the security review of 2026-10-27, H2).
     */
    TO_ACCEPT
}
