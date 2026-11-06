package com.flowtivo.tinfirm.model;

import java.util.Objects;
import java.util.UUID;

/**
 * The model access a run names (the package 2 contract, §5.1), already authorised by the caller (a person's
 * settings, a trigger): the paying principal's budget on the company's LiteLLM, or a subscription on that principal's
 * own personal runner host, by the label its host reports.
 */
public sealed interface ModelAccessChoice {

    /** The paying principal's budget: their LiteLLM key. */
    ModelAccessChoice BUDGET = new Budget();

    /** The paying principal's budget: their LiteLLM key. */
    record Budget() implements ModelAccessChoice {
    }

    /** A subscription on the paying member's personal runner host. */
    record Subscription(UUID runnerHostId, String label) implements ModelAccessChoice {

        public Subscription {
            Objects.requireNonNull(runnerHostId, "runnerHostId");
            Objects.requireNonNull(label, "label");
        }
    }
}
