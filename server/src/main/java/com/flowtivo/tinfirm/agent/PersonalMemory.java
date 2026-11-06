package com.flowtivo.tinfirm.agent;

import java.util.UUID;

import com.flowtivo.tinfirm.shared.api.model.MemoryNotes;

/**
 * The caller's own memory of an agent (ADR-0063), for their AI client (the MCP endpoint, ADR-0045): who may use the
 * agent reads their notes and deletes one, as on their settings page; only theirs (404 for another agent, 403
 * {@code AGENT_NOT_USABLE} for one they see but do not use). A deletion is audited as {@code memory.deleted}.
 */
public interface PersonalMemory {

    /** The caller's notes of the agent, by number. */
    MemoryNotes notes(UUID companyId, UUID agentId);

    /** Deletes the caller's note {@code number} of the agent (404 when there is none); the notes left. */
    MemoryNotes delete(UUID companyId, UUID agentId, int number);
}
