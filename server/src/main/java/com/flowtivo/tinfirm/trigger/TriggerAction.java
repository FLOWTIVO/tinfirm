package com.flowtivo.tinfirm.trigger;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * An action of a trigger's results (the package 4 contract, §3.1, §5.9), per kind of system: Jira's
 * {@code TRANSITION} (a status's or a transition's name) and {@code ATTACH_DOCUMENTS}; GitHub's and GitLab's
 * {@code LABEL_ADD}, {@code LABEL_REMOVE} (a label) and {@code CLOSE}.
 */
public record TriggerAction(Kind kind, @Nullable String value) {

    public TriggerAction {
        Objects.requireNonNull(kind, "kind");
    }

    public enum Kind {
        TRANSITION, ATTACH_DOCUMENTS, LABEL_ADD, LABEL_REMOVE, CLOSE;

        /** Whether the action names a value. */
        public boolean takesValue() {
            return this == TRANSITION || this == LABEL_ADD || this == LABEL_REMOVE;
        }
    }
}
