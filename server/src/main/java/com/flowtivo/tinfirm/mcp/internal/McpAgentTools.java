package com.flowtivo.tinfirm.mcp.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentReads;
import com.flowtivo.tinfirm.shared.api.model.Agent;
import com.flowtivo.tinfirm.shared.security.ApiScope;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.JsonNode;

/**
 * The agents the member may use (the package 6 contract, §5.7), through {@link AgentReads}: the list, and one agent
 * with its readiness for the member (who would pay, how each system would bind, whether a runner host can run it), the
 * same reads as the app's agent page and start page.
 */
@Component
class McpAgentTools implements McpToolSet {

    private final AgentReads agents;

    private final McpReferences references;

    private final ToolInvoker invoker;

    McpAgentTools(AgentReads agents, McpReferences references, ToolInvoker invoker) {
        this.agents = agents;
        this.references = references;
        this.invoker = invoker;
    }

    @McpTool(name = "list_agents", title = "List agents",
            description = """
                    The company's agents the person sees: each with its @handle, status (ACTIVE or \
                    PAUSED), and whether the person may start a conversation with it (usableByMe). usable defaults \
                    to true: only the agents the person may use. Page with cursor and nextCursor.""",
            annotations = @McpAnnotations(title = "List agents", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @ToolScope(ApiScope.READ)
    CallToolResult listAgents(McpTransportContext context,
            @McpToolParam(required = false, description = "false to list every agent the person sees; true (the default) for only those they may use") @Nullable Boolean usable,
            @McpToolParam(required = false, description = "The nextCursor of the previous page; leave out for the first page") @Nullable String cursor,
            @McpToolParam(required = false, description = "Agents per page, 1 to 200; 50 when left out") @Nullable Integer limit) {
        return invoker.read(context, "list_agents",
                companyId -> agents.list(companyId, !Boolean.FALSE.equals(usable), limit, cursor), Renderings::agents);
    }

    @McpTool(name = "get_agent", title = "Get an agent and whether it is ready",
            description = """
                    One agent by its id or @handle: its description and instructions, and, when the person may use \
                    it, its readiness for them before a conversation: who would pay (READY, WAITING with the reason \
                    and until when, REFUSED), how each system would bind at its first use (OWN, GRANTED, CHOOSE; \
                    CONNECT and RECONNECT_* the person does in Tinfirm), and whether a runner host can run it.""",
            annotations = @McpAnnotations(title = "Get an agent", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @ToolScope(ApiScope.READ)
    CallToolResult getAgent(McpTransportContext context,
            @McpToolParam(description = "The agent's id (a UUID) or its handle (dev or @dev)") String agent) {
        return invoker.call(context, "get_agent", companyId -> {
            UUID agentId = references.agent(companyId, agent);
            Agent found = agents.get(companyId, agentId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("agent", found);
            result.put("readiness", found.getUsableByMe() ? agents.readiness(companyId, agentId) : null);
            JsonNode structured = invoker.structured(result);
            JsonNode readiness = structured.get("readiness");
            return ToolInvoker.result(structured, Renderings.agent(structured.get("agent"),
                    readiness != null && readiness.isObject() ? readiness : null));
        });
    }
}
