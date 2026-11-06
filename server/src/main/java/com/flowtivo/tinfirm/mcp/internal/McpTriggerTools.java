package com.flowtivo.tinfirm.mcp.internal;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.api.model.AgentSettings;
import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.trigger.TriggerReads;
import com.flowtivo.tinfirm.trigger.TriggerStatus;
import com.flowtivo.tinfirm.trigger.WebhookReads;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.JsonNode;

/**
 * Triggers and webhook systems, read (the package 6 contract's later work; open point 15 stands: no tool creates,
 * edits, pauses or runs a trigger), through {@link TriggerReads} and {@link WebhookReads}, the reads of the Triggers
 * and Webhooks APIs with their rules: a trigger is seen by its author, its team, OWNER and ADMIN, and anyone else gets
 * {@code NOT_FOUND}; a webhook system's deliveries and the try of its mapping are for OWNER and ADMIN. The try stores
 * and fires nothing, so it is a read.
 */
@Component
class McpTriggerTools implements McpToolSet {

    private final TriggerReads triggers;

    private final WebhookReads webhooks;

    private final ToolInvoker invoker;

    McpTriggerTools(TriggerReads triggers, WebhookReads webhooks, ToolInvoker invoker) {
        this.triggers = triggers;
        this.webhooks = webhooks;
        this.invoker = invoker;
    }

    @McpTool(name = "list_triggers", title = "List triggers",
            description = """
                    The company's triggers the person sees (those whose author or team they are; every trigger for \
                    owners and admins), by name: each with its id, source (TRACKER, WEBHOOK, CHANNEL, SCHEDULE), \
                    system, agent, status (ACTIVE, PAUSED with the reason), what it waits on, and its last firing. \
                    Filter by systemId, agentId, authorId (a principal), teamGroupId, status or \
                    accountHolderPrincipalId. Page with cursor and nextCursor. Triggers are changed in Tinfirm \
                    only.""",
            annotations = @McpAnnotations(title = "List triggers", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @ToolScope(ApiScope.READ)
    CallToolResult listTriggers(McpTransportContext context,
            @McpToolParam(required = false, description = "Only the triggers on this system (its id)") @Nullable UUID systemId,
            @McpToolParam(required = false, description = "Only the triggers of this agent (its id)") @Nullable UUID agentId,
            @McpToolParam(required = false, description = "Only the triggers of this author (their principal's id)") @Nullable UUID authorId,
            @McpToolParam(required = false, description = "Only the triggers of this team (its group's id)") @Nullable UUID teamGroupId,
            @McpToolParam(required = false, description = "ACTIVE or PAUSED; both when left out") @Nullable String status,
            @McpToolParam(required = false, description = "Only the triggers whose accounts' table acts through this principal (a service account's)") @Nullable UUID accountHolderPrincipalId,
            @McpToolParam(required = false, description = "The nextCursor of the previous page; leave out for the first page") @Nullable String cursor,
            @McpToolParam(required = false, description = "Triggers per page, 1 to 200; 50 when left out") @Nullable Integer limit) {
        return invoker.read(context, "list_triggers", companyId -> triggers.list(companyId,
                new TriggerReads.Filter(systemId, agentId, authorId, teamGroupId, status(status),
                        accountHolderPrincipalId), limit, cursor), SetupRenderings::triggers);
    }

    @McpTool(name = "get_trigger", title = "Read a trigger",
            description = """
                    One trigger by its id, as the person sees it in Tinfirm, to check how it is set up: its source \
                    and system, its events and their values, its scope and conditions (a webhook trigger's \
                    conditions on the body: pointer, op, values), its repeat and max firings per hour, its agent \
                    and instruction, the accounts its runs use (one row per system of the agent, each with its \
                    state) and the agent's systems it has no account for, who pays and its model access, its team, \
                    results and review, its status, and its last firing. structuredContent is {"trigger": ..., \
                    "settings": ...}. NOT_FOUND for a trigger the person does not see. To see whether it fires, \
                    list_trigger_firings; whether a webhook body would fire it, try_webhook_delivery.""",
            annotations = @McpAnnotations(title = "Read a trigger", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @ToolScope(ApiScope.READ)
    CallToolResult getTrigger(McpTransportContext context,
            @McpToolParam(description = "The trigger's id (a UUID)") UUID triggerId) {
        return invoker.call(context, "get_trigger", companyId -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("trigger", triggers.get(companyId, triggerId));
            result.put("settings", settings(companyId, triggerId));
            JsonNode structured = invoker.structured(result);
            JsonNode settings = structured.get("settings");
            return ToolInvoker.result(structured, SetupRenderings.trigger(structured.get("trigger"),
                    settings != null && settings.isObject() ? settings : null));
        });
    }

    /** The trigger's settings; null when its agent's settings cannot be read (an agent gone), as the page shows. */
    private @Nullable AgentSettings settings(UUID companyId, UUID triggerId) {
        try {
            return triggers.settings(companyId, triggerId);
        }
        catch (ApiException ex) {
            return null;
        }
    }

    @McpTool(name = "list_trigger_firings", title = "List a trigger's firings",
            description = """
                    A trigger's firings, newest first, to see whether and how it fires: each with when, its event \
                    and value, its subject, and its outcome: STARTED (a new conversation), CONTINUED (a turn of an \
                    open one), or why it gave no turn (SKIPPED_OPEN, SKIPPED_MISSED, SKIPPED_CHAIN, SKIPPED_RATE, \
                    SKIPPED_AGENT_PAUSED, FAILED, with the reason), with the conversation and run it gave. For \
                    whoever sees the trigger. Page with cursor and nextCursor.""",
            annotations = @McpAnnotations(title = "List firings", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @ToolScope(ApiScope.READ)
    CallToolResult listTriggerFirings(McpTransportContext context,
            @McpToolParam(description = "The trigger's id (a UUID)") UUID triggerId,
            @McpToolParam(required = false, description = "The nextCursor of the previous page; leave out for the first page") @Nullable String cursor,
            @McpToolParam(required = false, description = "Firings per page, 1 to 200; 50 when left out") @Nullable Integer limit) {
        return invoker.read(context, "list_trigger_firings",
                companyId -> triggers.firings(companyId, triggerId, limit, cursor), SetupRenderings::firings);
    }

    @McpTool(name = "list_webhook_deliveries", title = "List a webhook system's deliveries",
            description = """
                    A webhook system's verified deliveries, newest first (kept 7 days, their bodies 24 hours): each \
                    with when it was received, its delivery id, its status (FIRED, NO_TRIGGER, MAPPING_FAILED with \
                    the reason), the mapped event and subject key, and how many triggers it fired; the first page \
                    also counts the last 7 days, those refused before they were stored included. The bodies are \
                    the sender's words, cut in the text. For the company's owners and admins (FORBIDDEN otherwise). \
                    A body may be up to 1 MiB, so a page holds at most 20 deliveries: page with cursor and \
                    nextCursor.""",
            annotations = @McpAnnotations(title = "List deliveries", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @ToolScope(ApiScope.READ)
    CallToolResult listWebhookDeliveries(McpTransportContext context,
            @McpToolParam(description = "The webhook system's id (a UUID)") UUID systemId,
            @McpToolParam(required = false, description = "The nextCursor of the previous page; leave out for the first page") @Nullable String cursor,
            @McpToolParam(required = false, description = "Deliveries per page, 1 to 20; 10 when left out") @Nullable Integer limit) {
        return invoker.read(context, "list_webhook_deliveries", companyId -> {
            // Each body may be 1 MiB and stays whole in structuredContent: a client's page is small.
            if (limit != null && (limit < 1 || limit > MAX_DELIVERIES)) {
                throw ApiException.validation("limit must be between 1 and " + MAX_DELIVERIES);
            }
            return webhooks.deliveries(companyId, systemId, limit != null ? limit : DEFAULT_DELIVERIES, cursor);
        }, SetupRenderings::deliveries);
    }

    /** A page of deliveries through a client: at most this many, each body up to 1 MiB. */
    static final int MAX_DELIVERIES = 20;

    static final int DEFAULT_DELIVERIES = 10;

    @McpTool(name = "try_webhook_delivery", title = "Try a body against a webhook system's triggers",
            description = """
                    Maps a JSON body as a delivery to the webhook system would be mapped, without storing or firing \
                    anything, and answers what the mapping reads (event, subject key and title, actor, text), or \
                    why it cannot; and for each of the system's webhook triggers whether it matches, its status, \
                    and, when it does not match, the first of its events or conditions the body fails, with what \
                    the body holds there. So "would this alert fire that trigger, and if not, why?" has an answer. \
                    A trigger that matches, is ACTIVE and waits on nothing fires, within its max firings per hour. \
                    For the company's owners and admins (FORBIDDEN otherwise).""",
            annotations = @McpAnnotations(title = "Try a webhook body", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @ToolScope(ApiScope.READ)
    CallToolResult tryWebhookDelivery(McpTransportContext context,
            @McpToolParam(description = "The webhook system's id (a UUID)") UUID systemId,
            @McpToolParam(description = "The delivery's JSON body, an object, as the sender would post it") Map<String, Object> body) {
        return invoker.read(context, "try_webhook_delivery", companyId -> {
            if (body == null) {
                throw ApiException.validation("body is required: the delivery's JSON object");
            }
            return webhooks.tryMapping(companyId, systemId, body);
        }, SetupRenderings::mapped);
    }

    private static @Nullable TriggerStatus status(@Nullable String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return TriggerStatus.valueOf(status.strip().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            throw ApiException.validation("status is ACTIVE or PAUSED");
        }
    }
}
