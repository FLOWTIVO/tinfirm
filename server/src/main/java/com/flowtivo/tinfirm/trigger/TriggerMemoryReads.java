package com.flowtivo.tinfirm.trigger;

import java.util.UUID;

import com.flowtivo.tinfirm.shared.api.model.MemoryNotes;

/**
 * A trigger's memory for the caller's AI client (the MCP endpoint, ADR-0045; ADR-0063, decision 5): its audience (its
 * author, its team, OWNER and ADMIN) reads the notes its agent keeps for it and those its conversations proposed;
 * anyone else gets 404, as for the trigger. Proposals are reviewed in Tinfirm only, never through a client.
 */
public interface TriggerMemoryReads {

    /** The trigger's notes and proposals, by number. */
    MemoryNotes notes(UUID companyId, UUID triggerId);
}
