package com.flowtivo.tinfirm.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.runner.RuntimeKind;

/**
 * An agent as other modules see it (the package 3 contract, §3.1). Its instructions are the first part of every
 * prompt; its ceiling is an {@link AgentDefinition}.
 *
 * @param runtimes             the runtimes its instructions are tested on; the first is the default
 * @param turnTimeLimitMinutes how long one of its turns may run; null for the deployment's
 *                             {@code TINFIRM_RUN_MAX_WALL_CLOCK}
 * @param memoryAllowed        whether the people who use it, and its triggers, may turn its memory on (ADR-0063)
 * @param ownerPrincipalId     the member's principal who owns it; null once it passed to the admins
 * @param version              the current version (contract §11)
 * @param deletedAt            set for a deleted agent, which stays for its conversations' history
 */
public record AgentInfo(UUID id, UUID companyId, String name, String handle, @Nullable String description,
        String instructions, List<RuntimeKind> runtimes, @Nullable Integer turnTimeLimitMinutes,
        boolean memoryAllowed, AgentStatus status, @Nullable UUID ownerPrincipalId, int version, @Nullable Instant deletedAt) {

    public AgentInfo {
        runtimes = List.copyOf(runtimes);
    }

    /** An agent whose memory is allowed. */
    public AgentInfo(UUID id, UUID companyId, String name, String handle, @Nullable String description,
            String instructions, List<RuntimeKind> runtimes, @Nullable Integer turnTimeLimitMinutes,
            AgentStatus status, @Nullable UUID ownerPrincipalId, int version, @Nullable Instant deletedAt) {
        this(id, companyId, name, handle, description, instructions, runtimes, turnTimeLimitMinutes, true, status,
                ownerPrincipalId, version, deletedAt);
    }

    /** An agent whose turns take the deployment's time limit. */
    public AgentInfo(UUID id, UUID companyId, String name, String handle, @Nullable String description,
            String instructions, List<RuntimeKind> runtimes, AgentStatus status, @Nullable UUID ownerPrincipalId,
            int version, @Nullable Instant deletedAt) {
        this(id, companyId, name, handle, description, instructions, runtimes, null, true, status, ownerPrincipalId,
                version, deletedAt);
    }

    /** Whether the agent is not deleted. */
    public boolean live() {
        return deletedAt == null;
    }

    public boolean paused() {
        return status == AgentStatus.PAUSED;
    }

    /** The runtime of a person who chose none: the first. */
    public RuntimeKind defaultRuntime() {
        return runtimes.getFirst();
    }
}
