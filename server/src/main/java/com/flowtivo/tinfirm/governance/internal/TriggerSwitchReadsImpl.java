package com.flowtivo.tinfirm.governance.internal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.governance.TriggerSwitchReads;
import com.flowtivo.tinfirm.trigger.PausedReason;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.Triggers;

/**
 * {@link TriggerSwitchReads} over the trigger module's public API ({@link Triggers}) and the conversation module's
 * {@link TriggerConversations} for the conversations that wait on a trigger: governance reads triggers, the trigger
 * module never depends on governance. In the caller's transaction and tenant context. Who paused a trigger is on its
 * audit record ({@code trigger.paused}), not here.
 */
@Component
class TriggerSwitchReadsImpl implements TriggerSwitchReads {

    private final Triggers triggers;

    private final TriggerConversations conversations;

    TriggerSwitchReadsImpl(Triggers triggers, TriggerConversations conversations) {
        this.triggers = triggers;
        this.conversations = conversations;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PausedTrigger> paused(UUID companyId) {
        return triggers.paused(companyId).stream().map(this::paused).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PausedTrigger> pausedBySystem(UUID companyId, UUID systemId) {
        return triggers.onSystem(companyId, systemId)
            .stream()
            .filter(trigger -> trigger.paused() && trigger.pausedReason() == PausedReason.SYSTEM_PAUSED)
            .map(this::paused)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PausedTrigger> pausedAuthorLeft(UUID companyId, UUID authorPrincipalId) {
        return triggers.ofAuthor(companyId, authorPrincipalId)
            .stream()
            .filter(trigger -> trigger.paused() && trigger.pausedReason() == PausedReason.AUTHOR_LEFT)
            .map(this::paused)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public int authoredBy(UUID companyId, UUID authorPrincipalId) {
        return triggers.ofAuthor(companyId, authorPrincipalId).size();
    }

    private PausedTrigger paused(TriggerInfo trigger) {
        return new PausedTrigger(trigger.id(), trigger.name(), trigger.authorPrincipalId(),
                Objects.requireNonNull(trigger.pausedReason(), "pausedReason").name(), trigger.pausedAt(), null,
                conversations.openOf(trigger.companyId(), trigger.id()).size());
    }
}
