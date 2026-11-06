package com.flowtivo.tinfirm.conversation.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.company.CompanySwitches;
import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;

/**
 * The kill switches that hold a conversation's every use of its bindings (the security review of 2026-10-27, D5, M6):
 * the company's runs stopped, the conversation's trigger paused, its agent paused, asked in this order. The reveal
 * ({@code ConversationBindings.use}) is the gate that asks it; the approved and parked calls ask it too, as early
 * exits that say why in the thread. Also whether a personal conversation's person may still use its agent (L22), which
 * a message, a retry and the dispatcher's plan ask. Runs in the caller's transaction and tenant context.
 */
@Component
class ConversationHolds {

    private final CompanySwitches companySwitches;

    private final TriggerPorts triggers;

    private final Agents agents;

    private final Principals principals;

    ConversationHolds(CompanySwitches companySwitches, TriggerPorts triggers, Agents agents, Principals principals) {
        this.companySwitches = companySwitches;
        this.triggers = triggers;
        this.agents = agents;
        this.principals = principals;
    }

    /** A switch that holds the conversation, and its words for the thread and the agent. */
    record Held(ConversationBindings.Hold hold, String why) {
    }

    /** The first switch that holds the conversation now; empty when none does. */
    Optional<Held> of(ConversationInfo conversation) {
        if (companySwitches.runsStopped(conversation.companyId()).isPresent()) {
            return Optional.of(new Held(ConversationBindings.Hold.COMPANY_STOPPED, "the company's runs are stopped"));
        }
        Optional<String> trigger = triggers.pausedTrigger(conversation).map(paused -> paused.name());
        if (trigger.isPresent()) {
            return Optional.of(new Held(ConversationBindings.Hold.TRIGGER_PAUSED, "the trigger " + trigger.get()
                    + " is paused"));
        }
        Optional<AgentInfo> agent = agents.find(conversation.companyId(), conversation.agentId())
            .filter(AgentInfo::paused);
        return agent.map(paused -> new Held(ConversationBindings.Hold.AGENT_PAUSED, paused.name() + " is paused"));
    }

    /**
     * Whether the conversation's person may still use its agent ({@code Agents.access(...).uses()}, L22): the share
     * that let them may have been removed, or they may have left its group. Always for a trigger's conversation, whose
     * author the trigger's gate checks.
     */
    boolean personMayUseAgent(ConversationInfo conversation) {
        UUID person = conversation.personPrincipalId();
        if (conversation.triggered() || person == null) {
            return true;
        }
        UUID userId = principals.find(conversation.companyId(), person).map(PrincipalInfo::userId).orElse(null);
        return userId != null && agents.access(conversation.companyId(), conversation.agentId(), userId).uses();
    }
}
