package com.flowtivo.tinfirm.trigger.internal;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentMemory;
import com.flowtivo.tinfirm.agent.MemoryViews;
import com.flowtivo.tinfirm.agent.SettingsOwner;
import com.flowtivo.tinfirm.shared.api.model.MemoryNotes;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerMemoryReads;

/**
 * A trigger's memory (ADR-0063, decision 5): the notes its agent keeps for it, which its audience (its author, its
 * team, OWNER and ADMIN) reads, reviews and deletes. A note its conversations write is proposed, and no conversation
 * reads it until one of them accepts it. Anyone else gets 404, as for the trigger. Their AI client reads the notes
 * ({@link TriggerMemoryReads}); it reviews none.
 */
@Service
class TriggerMemory implements TriggerMemoryReads {

    private final TriggerService triggers;

    private final AgentMemory memory;

    TriggerMemory(TriggerService triggers, AgentMemory memory) {
        this.triggers = triggers;
        this.memory = memory;
    }

    @Transactional(readOnly = true)
    public MemoryNotes get(UUID companyId, UUID triggerId) {
        TriggerInfo trigger = triggers.audience(companyId, triggerId, false);
        return view(trigger);
    }

    @Override
    @Transactional(readOnly = true)
    public MemoryNotes notes(UUID companyId, UUID triggerId) {
        return get(companyId, triggerId);
    }

    @Transactional
    public MemoryNotes clear(UUID companyId, UUID triggerId) {
        TriggerInfo trigger = triggers.audience(companyId, triggerId, false);
        memory.clear(companyId, trigger.agentId(), owner(trigger), CurrentActor.actor());
        return view(trigger);
    }

    @Transactional
    public MemoryNotes delete(UUID companyId, UUID triggerId, int number) {
        TriggerInfo trigger = triggers.audience(companyId, triggerId, false);
        if (!memory.delete(companyId, trigger.agentId(), owner(trigger), number, CurrentActor.actor())) {
            throw ApiException.notFound("No note m" + number + " in the trigger's memory");
        }
        return view(trigger);
    }

    @Transactional
    public MemoryNotes accept(UUID companyId, UUID triggerId, int number) {
        TriggerInfo trigger = triggers.audience(companyId, triggerId, false);
        switch (memory.accept(companyId, trigger.agentId(), owner(trigger), number, CurrentActor.actor())) {
            case ACCEPTED -> {
            }
            case NOT_PROPOSED -> throw ApiException.notFound("No proposed note m" + number + " in the trigger's memory");
            case MEMORY_FULL -> throw ApiException.conflict(ProblemCode.MEMORY_FULL, "The trigger's memory has no room "
                    + "for m" + number + ": delete a note first");
        }
        return view(trigger);
    }

    private MemoryNotes view(TriggerInfo trigger) {
        return MemoryViews.of(memory.notes(trigger.companyId(), trigger.agentId(), owner(trigger)));
    }

    private static SettingsOwner owner(TriggerInfo trigger) {
        return new SettingsOwner.Trigger(trigger.id());
    }
}
