package com.flowtivo.tinfirm.trigger.internal;

import java.util.List;
import java.util.Objects;

/**
 * A webhook trigger's condition on a delivery's raw body (the package 5 contract, §3.10, §5.12): a JSON Pointer
 * (RFC 6901) and what its value must be, read as {@link WebhookMapping} reads one, as text. A trigger's conditions all
 * hold for it to fire; at most five.
 *
 * @param values {@code EQUALS}: one; {@code IN}: 1 to 20; none for {@code PRESENT} and {@code ABSENT}
 */
record WebhookCondition(String pointer, Op op, List<String> values) {

    /** At most this many conditions a trigger. */
    static final int MAX = 5;

    /** How the pointer's value is compared. */
    enum Op {
        /** Its text is the one value. */
        EQUALS,
        /** Its text is one of the values. */
        IN,
        /** The pointer reads a value (not {@code null}). */
        PRESENT,
        /** The pointer reads nothing, or {@code null}. */
        ABSENT
    }

    WebhookCondition {
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(op, "op");
        values = List.copyOf(values);
    }
}
