package com.flowtivo.tinfirm.agent;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.Agent;
import com.flowtivo.tinfirm.shared.api.model.AgentPage;
import com.flowtivo.tinfirm.shared.api.model.AgentReadiness;

/**
 * The agent module's public reads as the current user (the package 6 contract, §2.2, §5.7): the REST API's
 * {@code GET /agents}, {@code GET /agents/{agentId}} and {@code GET /agents/{agentId}/readiness}, which the app's
 * controller and the MCP endpoint share. Each authorises the caller for the company first, in a transaction of its
 * own, and answers the REST API's DTOs; an agent the caller does not see is {@code 404}.
 */
public interface AgentReads {

    /** {@code GET /agents}: with {@code usable}, only the agents the caller may start a conversation with. */
    AgentPage list(UUID companyId, @Nullable Boolean usable, @Nullable Integer limit, @Nullable String cursor);

    /** {@code GET /agents/{agentId}}. */
    Agent get(UUID companyId, UUID agentId);

    /**
     * {@code GET /agents/{agentId}/readiness} ({@code Agents.readiness}, the package 6 contract, §2.2): before a
     * conversation, and storing nothing, the caller's settings in effect, who would pay, how each system would bind
     * and whether a runner host could run it.
     */
    AgentReadiness readiness(UUID companyId, UUID agentId);

    /** The live agent with the handle ({@code dev} or {@code @dev}, ignoring case) that the caller sees. */
    Optional<UUID> idOfHandle(UUID companyId, String handle);
}
