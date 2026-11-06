package com.flowtivo.tinfirm.runner;

import org.jspecify.annotations.Nullable;

/**
 * How much an agent's runtime thinks (protocol {@code Effort}, CONVENTIONS §14.10): {@code DEFAULT} leaves it to the
 * runtime and its model; the others go to the runtime in {@code run.assign.agent.effort}, from the least to the most.
 */
public enum EffortLevel {
    DEFAULT, LOW, MEDIUM, HIGH, XHIGH, MAX;

    /** The level for {@code run.assign.agent.effort}: null for {@code DEFAULT}, which the frame leaves out. */
    public @Nullable EffortLevel orNullForDefault() {
        return this == DEFAULT ? null : this;
    }
}
