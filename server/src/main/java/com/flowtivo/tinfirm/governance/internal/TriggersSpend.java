package com.flowtivo.tinfirm.governance.internal;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.trigger.TriggerDirectory;
import com.flowtivo.tinfirm.trigger.TriggerInfo;

/**
 * {@link TriggerSpend} over package 4's public API: a run's trigger is its conversation's (the database keeps
 * {@code run.trigger_id} equal to it), and the triggers of a principal, their names and payers come from the trigger
 * module's {@link TriggerDirectory}. Governance depends on {@code trigger}, never the other way round.
 */
@Component
class TriggersSpend implements TriggerSpend {

    private final RunReads runs;

    private final Conversations conversations;

    private final TriggerDirectory triggers;

    TriggersSpend(RunReads runs, Conversations conversations, TriggerDirectory triggers) {
        this.runs = runs;
        this.conversations = conversations;
        this.triggers = triggers;
    }

    @Override
    public @Nullable UUID triggerOf(UUID companyId, UUID runId) {
        return runs.find(companyId, runId)
            .map(RunInfo::conversationId)
            .flatMap(conversationId -> conversations.find(companyId, conversationId))
            .map(ConversationInfo::triggerId)
            .orElse(null);
    }

    @Override
    public Set<UUID> audienceOf(UUID companyId, UUID principalId) {
        return triggers.ofPrincipal(companyId, principalId);
    }

    @Override
    public Map<UUID, String> names(UUID companyId, Collection<UUID> triggerIds) {
        return triggers.names(companyId, triggerIds);
    }

    @Override
    public Set<UUID> authorsPaidBy(UUID companyId, UUID payerPrincipalId) {
        return triggers.livePaidBy(companyId, payerPrincipalId).stream()
            .map(TriggerInfo::authorPrincipalId)
            .collect(Collectors.toSet());
    }

    @Override
    public int livePaidBy(UUID companyId, UUID payerPrincipalId) {
        return triggers.livePaidBy(companyId, payerPrincipalId).size();
    }
}
