package com.flowtivo.tinfirm.conversation;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.ConversationCost;
import com.flowtivo.tinfirm.shared.api.model.ConversationTokens;

/**
 * Port: what conversations cost, as the caller sees spend (the package 6 contract, §5.2; G1), which the conversation's
 * views show ({@code Conversation.cost}, {@code ConversationSummary.costUsd} and {@code tokens}). Looked up when used,
 * so that the conversation module depends on none of its implementations; the governance module serves it from
 * {@code run_spend}, exactly as {@code getSpend} with the conversation's {@code conversationId} counts it over all time.
 * Runs in the caller's tenant context and transaction, for the request's user; without one it answers nothing (null).
 */
public interface ConversationCosts {

    /** The conversation's settled turns, their cost and tokens; null without a user. */
    @Nullable ConversationCost of(UUID companyId, UUID conversationId);

    /**
     * What each conversation's settled turns cost ({@code GATEWAY} and {@code RELAY}) and their tokens; a conversation
     * whose spend the caller sees none of is absent. Null without a user.
     */
    @Nullable Map<UUID, Total> totals(UUID companyId, Collection<UUID> conversationIds);

    /** A conversation's settled cost and tokens. */
    record Total(BigDecimal costUsd, ConversationTokens tokens) {
    }
}
