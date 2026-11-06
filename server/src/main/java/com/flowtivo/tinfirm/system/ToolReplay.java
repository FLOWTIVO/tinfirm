package com.flowtivo.tinfirm.system;

/**
 * Whether a tool's call may run again after Tinfirm lost track of it (package R, the replay safety of tool calls:
 * {@code docs/reviews/2026-10-12-package-r-contract.md} §3.1), beside its {@link ToolEffect}. The default follows the
 * effect ({@link #of}): a {@code READ} is {@code SAFE}; a {@code WRITE} and an {@code UNKNOWN} effect are
 * {@code UNSAFE}. A system's catalogue may declare a write {@code SAFE} only when the system's API makes the call
 * idempotent, so that the same call twice leaves the same state and writes nothing new (a field set to a value with
 * {@code PUT}, a label added to a set); a comment, an issue, a change request or a transition with side effects is
 * never safe. No write is declared {@code SAFE} today: each declaration is reviewed with its test.
 * <p>
 * The conversation module decides by it: a {@code SAFE} call a stopped replica left running is run again, and one past
 * the tool timeout answers {@code TOOL_FAILED}; an {@code UNSAFE} one is never run twice, and answers
 * {@code TOOL_INTERRUPTED}. The run's tool list carries it ({@code run.assign.tools[].replay}) for the runtime's
 * information only.
 */
public enum ToolReplay {
    /** Running the call again does no harm: the same state, nothing new written. */
    SAFE,
    /** Running the call again may write twice: it never runs again once Tinfirm lost track of it. */
    UNSAFE;

    /** The default of an effect: {@code READ} is {@code SAFE}; {@code WRITE} and {@code UNKNOWN} are {@code UNSAFE}. */
    public static ToolReplay of(ToolEffect effect) {
        return effect == ToolEffect.READ ? SAFE : UNSAFE;
    }
}
