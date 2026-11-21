package com.flowtivo.tinfirm.agent;

import java.util.UUID;

/**
 * Whether an agent's runs can start from outside the company (ADR-0113, decision 4: the warning where hostile input
 * meets a container), for the {@code agent} module, implemented by the {@code trigger} module, which depends on this
 * one; found when used. Runs in the caller's transaction and tenant context.
 */
public interface AgentStarts {

    /**
     * Whether the agent has a trigger, live or paused, whose source anyone may write to: a tracker, a chat channel or
     * a webhook. A schedule does not count, nor do members' own starts (the web app, their AI clients, a direct
     * message, an email add-in): a member decides each of those.
     */
    boolean startsFromOutside(UUID companyId, UUID agentId);
}
