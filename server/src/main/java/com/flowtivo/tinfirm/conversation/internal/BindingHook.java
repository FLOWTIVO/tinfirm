package com.flowtivo.tinfirm.conversation.internal;

import com.flowtivo.tinfirm.conversation.BindingInfo;

/**
 * Seam for part 2: a binding became {@code BOUND} or {@code GONE}, in the transaction that changed it, after its audit
 * (and with the public {@code ConversationEvents.BindingBound} and {@code BindingGone} published after it). Part 2 runs
 * or answers the calls parked on it ({@code conversation_binding.waiting_calls}, §5.3) and starts the
 * {@code CONNECTED} turn through {@link ConversationTurns#request}. Every bean is called.
 */
public interface BindingHook {

    /** The binding is {@code BOUND}: first bound, or bound again after it waited. */
    default void bound(BindingInfo binding) {
    }

    /** The binding is {@code GONE}, with its reason. */
    default void gone(BindingInfo binding) {
    }
}
