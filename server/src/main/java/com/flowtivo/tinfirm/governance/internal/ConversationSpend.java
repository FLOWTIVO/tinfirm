package com.flowtivo.tinfirm.governance.internal;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.conversation.ConversationCosts;
import com.flowtivo.tinfirm.shared.api.model.ConversationCost;
import com.flowtivo.tinfirm.shared.security.CurrentActor;

/**
 * The conversation module's port {@link ConversationCosts} over {@code run_spend}: a conversation's cost is what
 * {@code getSpend} with its {@code conversationId} counts over all time, in the caller's scope (G1), so that the
 * conversation's page never shows more of spend than the spend views do. In the caller's transaction; nothing without
 * a user (a conversation built for the system, not for a reader).
 */
@Component
class ConversationSpend implements ConversationCosts {

    private final SpendQueryService queries;

    ConversationSpend(SpendQueryService queries) {
        this.queries = queries;
    }

    @Override
    public @Nullable ConversationCost of(UUID companyId, UUID conversationId) {
        return CurrentActor.user().isPresent() ? queries.conversationCost(companyId, conversationId) : null;
    }

    @Override
    public @Nullable Map<UUID, Total> totals(UUID companyId, Collection<UUID> conversationIds) {
        return CurrentActor.user().isPresent() ? queries.conversationTotals(companyId, conversationIds) : null;
    }
}
