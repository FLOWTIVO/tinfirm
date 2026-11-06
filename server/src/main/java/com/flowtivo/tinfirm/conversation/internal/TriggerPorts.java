package com.flowtivo.tinfirm.conversation.internal;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerAudience;
import com.flowtivo.tinfirm.conversation.TriggerTurns;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyRole;

/**
 * The {@code trigger} module's ports, looked up when used (the module depends on this one): without them no
 * conversation has a trigger's audience, and no firing waits (the package 4 contract, §5.6, §5.13).
 */
@Component
class TriggerPorts {

    private final ObjectProvider<TriggerAudience> audience;

    private final ObjectProvider<TriggerTurns> turns;

    TriggerPorts(ObjectProvider<TriggerAudience> audience, ObjectProvider<TriggerTurns> turns) {
        this.audience = audience;
        this.turns = turns;
    }

    Optional<TriggerAudience.TriggerView> trigger(UUID companyId, @Nullable UUID triggerId) {
        TriggerAudience port = audience.getIfAvailable();
        return port == null || triggerId == null ? Optional.empty() : port.trigger(companyId, triggerId);
    }

    /** The trigger's name, or a stand-in once it is unknown. */
    String name(UUID companyId, @Nullable UUID triggerId) {
        return trigger(companyId, triggerId).map(TriggerAudience.TriggerView::name).orElse("a deleted trigger");
    }

    /** The triggers whose conversations the caller sees (§5.13): every one for OWNER and ADMIN. */
    TriggerAudience.Seen seenBy(CompanyAccess access, UUID userId) {
        TriggerAudience port = audience.getIfAvailable();
        return port == null ? TriggerAudience.Seen.NONE : port.seenBy(access, userId);
    }

    /**
     * The triggers in whose conversations the caller acts as their people do (T12): those they authored or whose team
     * they are in, whatever their role.
     */
    TriggerAudience.Seen actsOn(CompanyAccess access, UUID userId) {
        return actsOn(access.companyId(), userId);
    }

    /** {@link #actsOn(CompanyAccess, UUID)}: the triggers a member acts on, whatever their role. */
    TriggerAudience.Seen actsOn(UUID companyId, UUID userId) {
        TriggerAudience port = audience.getIfAvailable();
        // Asked as a MEMBER, who sees the triggers they authored or whose team they are in, and no others.
        return port == null ? TriggerAudience.Seen.NONE
                : port.seenBy(new CompanyAccess(companyId, CompanyRole.MEMBER, false), userId);
    }

    /** Whether the caller acts in the trigger's conversations (T12). */
    boolean acts(UUID companyId, UUID userId, @Nullable UUID triggerId) {
        TriggerAudience port = audience.getIfAvailable();
        return port != null && triggerId != null && port.acts(companyId, userId, triggerId);
    }

    /** Whether the conversation's trigger is paused: it takes no turn ({@code TRIGGER_PAUSED}). */
    Optional<TriggerAudience.TriggerView> pausedTrigger(ConversationInfo conversation) {
        return trigger(conversation.companyId(), conversation.triggerId()).filter(TriggerAudience.TriggerView::paused);
    }

    /** Whether firings of the trigger's conversation wait for a turn. */
    boolean firingsWaiting(ConversationInfo conversation) {
        TriggerTurns port = turns.getIfAvailable();
        return port != null && conversation.triggered() && port.waiting(conversation.companyId(), conversation.id());
    }

    /** The turn takes the firings that wait. */
    void taken(ConversationInfo conversation, UUID runId) {
        TriggerTurns port = turns.getIfAvailable();
        if (port != null && conversation.triggered()) {
            port.taken(conversation.companyId(), conversation.id(), runId);
        }
    }

    Optional<String> request(UUID companyId, UUID runId) {
        TriggerTurns port = turns.getIfAvailable();
        return port == null ? Optional.empty() : port.request(companyId, runId);
    }

    Optional<String> subject(UUID companyId, UUID conversationId, UUID runId) {
        TriggerTurns port = turns.getIfAvailable();
        return port == null ? Optional.empty() : port.subject(companyId, conversationId, runId);
    }
}
