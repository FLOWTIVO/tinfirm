package com.flowtivo.tinfirm.trigger.internal;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.RepositoryAccess;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.agent.SettingsOwner;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.trigger.TriggerAccount;
import com.flowtivo.tinfirm.trigger.TriggerInfo;

/**
 * {@link FiringConversations} (the package 4 contract, §5.6, §5.7), for part b's firings: a new conversation built from
 * the trigger at this moment, or an {@code EVENT} turn of its open one. In the firing's transaction, which locked the
 * trigger's row (and the subject's snapshot before it); the conversation is locked after.
 */
@Component
class FiringConversationsImpl implements FiringConversations {

    private final TriggerStore store;

    private final TriggerConversations conversations;

    private final Settings settings;

    FiringConversationsImpl(TriggerStore store, TriggerConversations conversations, Settings settings) {
        this.store = store;
        this.conversations = conversations;
        this.settings = settings;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TriggerConversations.Started start(UUID companyId, UUID triggerId, Place place, int chainDepth,
            @Nullable UUID firingId, @Nullable UUID startedBy) {
        TriggerInfo trigger = store.find(companyId, triggerId).filter(TriggerInfo::live)
            .orElseThrow(() -> new IllegalArgumentException("No live trigger " + triggerId));
        if (trigger.payerPrincipalId() == null) {
            throw new IllegalStateException("The trigger " + triggerId + " has no payer");
        }
        EffectiveSettings effective = settings.effective(companyId, trigger.agentId(),
                new SettingsOwner.Trigger(triggerId));
        ModelAccessChoice access = effective.modelAccess().choice()
            .orElseThrow(() -> new IllegalStateException("The trigger's subscription is gone: its gate pauses it"));
        List<TriggerAccount> rows = store.accounts(companyId, triggerId);
        List<TriggerConversations.FixedAccount> accounts = rows.stream()
            .filter(row -> row.accountId() != null)
            .map(row -> new TriggerConversations.FixedAccount(row.systemId(), row.accountId(), row.holderPrincipalId(),
                    row.externalId()))
            .toList();
        Set<UUID> withRow = accounts.stream().map(TriggerConversations.FixedAccount::systemId)
            .collect(Collectors.toSet());
        List<ConversationInfo.WorkspaceEntry> workspace = new ArrayList<>();
        for (AgentDefinition.RepositoryEntry repository : effective.startingRepositories()) {
            if (withRow.contains(repository.systemId()) && workspace.size() < ConversationInfo.MAX_WORKSPACE) {
                workspace.add(new ConversationInfo.WorkspaceEntry(repository.repositoryId(),
                        repository.access() == RepositoryAccess.READ, ConversationInfo.WorkspaceEntry.Source.AGENT));
            }
        }
        TriggerConversations.NewTriggerConversation start = switch (place) {
            case Place.Subject subject -> new TriggerConversations.NewTriggerConversation(companyId, triggerId,
                    trigger.name(), trigger.agentId(), trigger.authorPrincipalId(), trigger.payerPrincipalId(), access,
                    effective.runtime(), ConversationInfo.Place.TRACKER_ISSUE, subject.subject(), null, accounts,
                    workspace, chainDepth, firingId, startedBy, null);
            case Place.Schedule schedule -> new TriggerConversations.NewTriggerConversation(companyId, triggerId,
                    trigger.name(), trigger.agentId(), trigger.authorPrincipalId(), trigger.payerPrincipalId(), access,
                    effective.runtime(), ConversationInfo.Place.SCHEDULE, null,
                    new TriggerConversations.ScheduleFiring(firingId, schedule.dueAt(),
                            schedule.dueAt().atZone(zone(trigger)).toLocalDate()),
                    accounts, workspace, chainDepth, firingId, startedBy, null);
            case Place.Held held -> new TriggerConversations.NewTriggerConversation(companyId, triggerId,
                    trigger.name(), trigger.agentId(), trigger.authorPrincipalId(), trigger.payerPrincipalId(), access,
                    effective.runtime(), held.place(), null, null, accounts, workspace, chainDepth, firingId,
                    startedBy, held.held());
        };
        TriggerConversations.Started started = conversations.start(start);
        if (firingId != null) {
            // The first turn's request: the firing, which waits for the next turn when none started.
            store.firingTaken(companyId, firingId, started.conversation().id(), started.runId().orElse(null));
        }
        return started;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> continueWith(UUID companyId, UUID conversationId) {
        return conversations.event(companyId, conversationId);
    }

    private static ZoneId zone(TriggerInfo trigger) {
        try {
            return trigger.schedule() != null ? ZoneId.of(trigger.schedule().zone()) : ZoneId.of("UTC");
        }
        catch (RuntimeException ex) {
            return ZoneId.of("UTC");
        }
    }
}
