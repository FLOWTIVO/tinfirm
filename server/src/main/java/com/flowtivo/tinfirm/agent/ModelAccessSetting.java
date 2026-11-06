package com.flowtivo.tinfirm.agent;

import java.util.Optional;
import java.util.UUID;

import com.flowtivo.tinfirm.model.ModelAccessChoice;

/**
 * The model access a person's settings name (the package 3 contract, §3.7): their budget (the default), or one of
 * their subscriptions on their own personal runner host, by its label there. A conversation fixes it at its start;
 * Tinfirm never switches it by itself (package 2, M3).
 */
public sealed interface ModelAccessSetting {

    /** The choice to pass to package 2's {@code ModelAccess.forRun}; empty when the subscription is gone. */
    Optional<ModelAccessChoice> choice();

    /** The person's budget: their LiteLLM key. */
    record Budget() implements ModelAccessSetting {

        @Override
        public Optional<ModelAccessChoice> choice() {
            return Optional.of(ModelAccessChoice.BUDGET);
        }
    }

    /** A subscription on the person's own personal runner host. */
    record Subscription(UUID runnerHostId, String label) implements ModelAccessSetting {

        @Override
        public Optional<ModelAccessChoice> choice() {
            return Optional.of(new ModelAccessChoice.Subscription(runnerHostId, label));
        }
    }

    /**
     * A subscription whose runner host was removed: nothing pays until the person chooses again
     * ({@code SUBSCRIPTION_GONE}).
     */
    record SubscriptionGone(String label) implements ModelAccessSetting {

        @Override
        public Optional<ModelAccessChoice> choice() {
            return Optional.empty();
        }
    }
}
