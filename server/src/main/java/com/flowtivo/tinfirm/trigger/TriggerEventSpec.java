package com.flowtivo.tinfirm.trigger;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * One of a trigger's one to five events (ADR-0043): the event, and its value where it takes one (a status, a label).
 */
public record TriggerEventSpec(TriggerEvent event, @Nullable String value) {

    public TriggerEventSpec {
        Objects.requireNonNull(event, "event");
    }
}
