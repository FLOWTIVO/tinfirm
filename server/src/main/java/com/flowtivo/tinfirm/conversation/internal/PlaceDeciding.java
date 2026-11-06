package com.flowtivo.tinfirm.conversation.internal;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

/**
 * Who decides from a place, for the decisions decided on this thread meanwhile (the package 5 contract, §5.8): a press
 * or an answer in a chat. {@link DecisionOps} records it on each decision it decides ({@code decided_via} {@code CHAT},
 * the chat user and their name), in its {@code decision.decided} ({@code via}, {@code chatUserId}; row 7) and in
 * {@code ConversationEvents.DecisionClosed}. Without one a decision is decided in the app.
 */
final class PlaceDeciding {

    /**
     * @param via        {@code CHAT}
     * @param place      {@code SLACK} or {@code TEAMS}
     * @param externalId the chat user's id
     * @param name       their name in the chat
     */
    record Context(String via, String place, String externalId, String name) {
    }

    private static final ThreadLocal<@Nullable Context> CURRENT = new ThreadLocal<>();

    private PlaceDeciding() {
    }

    static <T> T with(Context context, Supplier<T> work) {
        Context previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return work.get();
        }
        finally {
            if (previous == null) {
                CURRENT.remove();
            }
            else {
                CURRENT.set(previous);
            }
        }
    }

    static @Nullable Context current() {
        return CURRENT.get();
    }
}
