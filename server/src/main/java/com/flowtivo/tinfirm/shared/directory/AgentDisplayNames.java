package com.flowtivo.tinfirm.shared.directory;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Port for resolving agent names at read time, for modules that must not depend on the module that keeps agents, such
 * as the audit log, whose actor can be an agent (CONVENTIONS §8). The agents of package 3 of the platform redesign
 * implement it. Runs in the caller's tenant context.
 */
public interface AgentDisplayNames {

    /** Names of the given agents, deleted ones included; unknown ids are absent from the result. */
    Map<UUID, String> names(Collection<UUID> agentIds);
}
