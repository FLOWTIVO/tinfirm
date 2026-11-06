package com.flowtivo.tinfirm.mcp.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.system.SystemReads;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The company's systems, read, and their test (the package 6 contract's later work), through {@link SystemReads}, the
 * reads and the test of the Systems API with their rules: every member sees the company's systems and their own
 * personal servers, never another member's ({@code NOT_FOUND}); the test is for whoever manages the system. No secret
 * is answered: a webhook system's secret is shown once at its creation in Tinfirm and never here, an app's client
 * secret and a chat system's tokens are said to be kept. The test is a write (it records the system's last test) but
 * changes nothing else; no tool registers, changes or removes a system, its app or its tools.
 */
@Component
class McpSystemTools implements McpToolSet {

    private final SystemReads systems;

    private final ToolInvoker invoker;

    McpSystemTools(SystemReads systems, ToolInvoker invoker) {
        this.systems = systems;
        this.invoker = invoker;
    }

    @McpTool(name = "list_systems", title = "List systems",
            description = """
                    The company's systems the person sees (and their own personal MCP servers), by name: each with \
                    its id and @handle (its tools are @handle_{tool} in a run), kind (JIRA_DC, JIRA_CLOUD, GITHUB, \
                    GITLAB, GIT, MCP, SLACK, TEAMS, WEBHOOK), status (NEW, OK, ERROR), whether an admin paused it, \
                    and its last test. Page with cursor and nextCursor. Systems are changed in Tinfirm only.""",
            annotations = @McpAnnotations(title = "List systems", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @ToolScope(ApiScope.READ)
    CallToolResult listSystems(McpTransportContext context,
            @McpToolParam(required = false, description = "The nextCursor of the previous page; leave out for the first page") @Nullable String cursor,
            @McpToolParam(required = false, description = "Systems per page, 1 to 200; 50 when left out") @Nullable Integer limit) {
        return invoker.read(context, "list_systems", companyId -> systems.list(companyId, limit, cursor),
                SetupRenderings::systems);
    }

    @McpTool(name = "get_system", title = "Read a system",
            description = """
                    One system by its id, with its settings by kind, never a secret: a webhook system's delivery URL, \
                    scheme, secret header's name, mapping (JSON Pointers), deliveries a minute and when its secret \
                    was set; an MCP server's base URL, the header its token goes in, and whether accounts sign in \
                    with OAuth (its app); a tracker's base URL and poll interval; a chat system's bot and transport. \
                    What only its admins see (the mapping, the rate, the app, who tested it) is answered to them \
                    only. With its status, last error and last test. NOT_FOUND for a system the person does not \
                    see.""",
            annotations = @McpAnnotations(title = "Read a system", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @ToolScope(ApiScope.READ)
    CallToolResult getSystem(McpTransportContext context,
            @McpToolParam(description = "The system's id (a UUID)") UUID systemId) {
        return invoker.call(context, "get_system", companyId -> {
            JsonNode structured = invoker.structured(systems.get(companyId, systemId));
            // The API answers a webhook's secret on its creation only; never through a client, whatever changes.
            if (structured.get("webhook") instanceof ObjectNode webhook) {
                webhook.remove("secret");
            }
            return ToolInvoker.result(structured, SetupRenderings.system(structured));
        });
    }

    @McpTool(name = "list_system_tools", title = "List a system's tools",
            description = """
                    The tools a system offers the agents: an MCP server's catalogue as Tinfirm last read it, or a \
                    tracker's fixed tools: each with its name, description, effect (READ or WRITE once confirmed; \
                    UNKNOWN until an admin confirms it, which counts as WRITE, with the effect its hints suggest) \
                    and whether it is enabled (a disabled tool is denied to every agent). NOT_FOUND for a system \
                    the person does not see. The descriptions are the system's words.""",
            annotations = @McpAnnotations(title = "List a system's tools", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    @ToolScope(ApiScope.READ)
    CallToolResult listSystemTools(McpTransportContext context,
            @McpToolParam(description = "The system's id (a UUID)") UUID systemId) {
        return invoker.read(context, "list_system_tools", companyId -> systems.tools(companyId, systemId),
                SetupRenderings::tools);
    }

    @McpTool(name = "test_system", title = "Test whether Tinfirm reaches a system",
            description = """
                    Reaches the system as its Test button in Tinfirm does: with the person's own connected account \
                    on it when they hold one (its "who am I"), else with a request that needs no credential; and \
                    keeps the outcome as the system's last test and status. Nothing else changes. Answers whether it \
                    passed and, when not, why: for instance an address on the deployment's own network, which \
                    Tinfirm reaches only when the operator names its host in TINFIRM_CONNECTION_INTERNAL_HOSTS. For \
                    whoever manages the system (a company system's owners and admins, a personal server's owner; \
                    FORBIDDEN otherwise). Errors: SYSTEM_PAUSED, SYSTEM_KIND_INVALID (a system with nothing to \
                    reach), NOT_FOUND.""",
            annotations = @McpAnnotations(title = "Test a system", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = true))
    @ToolScope(ApiScope.WRITE)
    CallToolResult testSystem(McpTransportContext context,
            @McpToolParam(description = "The system's id (a UUID)") UUID systemId) {
        return invoker.write(context, "test_system", companyId -> systems.test(companyId, systemId),
                SetupRenderings::tested);
    }
}
