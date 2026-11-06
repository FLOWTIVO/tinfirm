package com.flowtivo.tinfirm.agent.internal;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentMemory;
import com.flowtivo.tinfirm.agent.MemoryViews;
import com.flowtivo.tinfirm.agent.PersonalMemory;
import com.flowtivo.tinfirm.agent.SettingsOwner;
import com.flowtivo.tinfirm.shared.api.model.MemoryNotes;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * A person's memory of an agent through the API (ADR-0063): who may use the agent reads and deletes their own notes,
 * and only theirs (404 for another agent, 403 {@code AGENT_NOT_USABLE} for one they see but do not use); their AI
 * client reads and deletes them alike ({@link PersonalMemory}).
 */
@Service
class MemoryPages implements PersonalMemory {

    private final SettingsService settings;

    private final AgentMemory memory;

    MemoryPages(SettingsService settings, AgentMemory memory) {
        this.settings = settings;
        this.memory = memory;
    }

    @Transactional(readOnly = true)
    public MemoryNotes get(UUID companyId, UUID agentId) {
        SettingsService.Use use = settings.requireUsable(companyId, agentId);
        return MemoryViews.of(memory.notes(companyId, agentId, new SettingsOwner.Person(use.principalId())));
    }

    @Override
    @Transactional(readOnly = true)
    public MemoryNotes notes(UUID companyId, UUID agentId) {
        return get(companyId, agentId);
    }

    @Transactional
    public MemoryNotes clear(UUID companyId, UUID agentId) {
        SettingsService.Use use = settings.requireUsable(companyId, agentId);
        SettingsOwner owner = new SettingsOwner.Person(use.principalId());
        memory.clear(companyId, agentId, owner, CurrentActor.actor());
        return MemoryViews.of(memory.notes(companyId, agentId, owner));
    }

    @Override
    @Transactional
    public MemoryNotes delete(UUID companyId, UUID agentId, int number) {
        SettingsService.Use use = settings.requireUsable(companyId, agentId);
        SettingsOwner owner = new SettingsOwner.Person(use.principalId());
        if (!memory.delete(companyId, agentId, owner, number, CurrentActor.actor())) {
            throw ApiException.notFound("No note m" + number + " in your memory of this agent");
        }
        return MemoryViews.of(memory.notes(companyId, agentId, owner));
    }
}
