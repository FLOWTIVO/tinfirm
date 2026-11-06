package com.flowtivo.tinfirm.mcp.internal;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.governance.Spend;
import com.flowtivo.tinfirm.shared.api.model.SpendDimension;
import com.flowtivo.tinfirm.shared.api.model.SpendSource;
import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.web.ApiException;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

/**
 * Spend (the package 6 contract, §2.3's {@code get_my_spend}), through {@link Spend#report}, the Spend API's JSON
 * report with its scope (G1): a member sees what they paid and the runs of the triggers whose author or team they are,
 * OWNER and ADMIN every run. The CSV export, which the API audits, has no tool.
 */
@Component
class McpSpendTools implements McpToolSet {

    private final Spend spend;

    private final ToolInvoker invoker;

    McpSpendTools(Spend spend, ToolInvoker invoker) {
        this.spend = spend;
        this.invoker = invoker;
    }

    @McpTool(name = "get_spend", title = "Read spend",
            description = """
                    What runs cost, as far as the person may see it: what they paid and the runs of the triggers \
                    whose author or team they are; every run for the company's owners and admins (another person's \
                    personal conversations folded or by key only). Runs that ended from `from` (inclusive) to `to` \
                    (exclusive), dates in `zone` (IANA, UTC by default); the current calendar month by default, at \
                    most 366 days. Group by up to two of payer, actor, agent, trigger, conversation, model, period \
                    (by bucket DAY, WEEK or MONTH), and filter by payerId, actorId, agentId, triggerId, \
                    conversationId, model or source (GATEWAY, RELAY, SUBSCRIPTION). Totals and rows of runs, \
                    requests, tokens and cost in USD.""",
            annotations = @McpAnnotations(title = "Read spend", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    @ToolScope(ApiScope.READ)
    CallToolResult getSpend(McpTransportContext context,
            @McpToolParam(required = false, description = "The first day, YYYY-MM-DD, inclusive") @Nullable String from,
            @McpToolParam(required = false, description = "The day after the last, YYYY-MM-DD, exclusive") @Nullable String to,
            @McpToolParam(required = false, description = "The days' time zone, IANA (Europe/Prague); UTC when left out") @Nullable String zone,
            @McpToolParam(required = false, description = "Up to two of payer, actor, agent, trigger, conversation, model, period") @Nullable List<String> groupBy,
            @McpToolParam(required = false, description = "The period's bucket: DAY (the default), WEEK or MONTH") @Nullable String bucket,
            @McpToolParam(required = false, description = "Only the runs this principal paid") @Nullable UUID payerId,
            @McpToolParam(required = false, description = "Only the runs this principal acted for") @Nullable UUID actorId,
            @McpToolParam(required = false, description = "Only this agent's runs") @Nullable UUID agentId,
            @McpToolParam(required = false, description = "Only this trigger's runs") @Nullable UUID triggerId,
            @McpToolParam(required = false, description = "Only this conversation's runs") @Nullable UUID conversationId,
            @McpToolParam(required = false, description = "Only the runs of this model") @Nullable String model,
            @McpToolParam(required = false, description = "GATEWAY, RELAY or SUBSCRIPTION") @Nullable String source) {
        return invoker.read(context, "get_spend", companyId -> spend.report(companyId, new Spend.Query(
                date(from, "from"), date(to, "to"), zone, dimensions(groupBy),
                bucket != null ? bucket.strip().toUpperCase(Locale.ROOT) : null, payerId, actorId, agentId,
                triggerId, conversationId, model, source(source))), SetupRenderings::spend);
    }

    private static @Nullable LocalDate date(@Nullable String value, String name) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip());
        }
        catch (DateTimeParseException ex) {
            throw ApiException.validation(name + " is a date, YYYY-MM-DD");
        }
    }

    private static @Nullable List<SpendDimension> dimensions(@Nullable List<String> values) {
        if (values == null) {
            return null;
        }
        try {
            return values.stream().map(value -> SpendDimension.fromValue(value.strip().toLowerCase(Locale.ROOT)))
                .toList();
        }
        catch (IllegalArgumentException ex) {
            throw ApiException.validation("groupBy is up to two of payer, actor, agent, trigger, conversation, model,"
                    + " period");
        }
    }

    private static @Nullable SpendSource source(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return SpendSource.fromValue(value.strip().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException ex) {
            throw ApiException.validation("source is GATEWAY, RELAY or SUBSCRIPTION");
        }
    }
}
