package com.flowtivo.tinfirm.mcp.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.conversation.ConversationCommands;
import com.flowtivo.tinfirm.identity.MembershipDirectory;
import com.flowtivo.tinfirm.identity.UserMembership;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.ActorAuthentication;
import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.security.BearerGrantHolder;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one tool call (ADR-0045): as the caller of its {@link McpCall} (the user's authentication, the tenant context
 * and the bearer grant are opened for the call, whatever thread it runs on, and restored afterwards), after the scope
 * check, and turns what the read or the write returned into the result. The grant held on the tool's thread is what
 * the audit log adds to every event the call's services record ({@code tokenId} or {@code grantId}).
 * <ul>
 * <li>{@code structuredContent} is the REST DTO as the REST API's own {@link JsonMapper} writes it (a list as
 * {@code {"items": [...]}}, since structured content is an object), so the two never disagree.</li>
 * <li>The text is the DTO's rendering for the client's model, in which other people's words stand in labelled
 * blocks.</li>
 * <li>An {@link ApiException} is a result with {@code isError}: its problem code and detail, as the REST API would
 * have answered; the access guard's 404 reads "not found". {@code RATE_LIMITED} adds its {@code retryAfter} in
 * seconds, {@code DECIDE_IN_APP} the decision's link in the app; both also stand in the error's
 * {@code structuredContent}. Any other failure is a generic error, logged.</li>
 * </ul>
 * The caller acts as a member of the company (the package 6 contract, G9): an instance admin who is not a member gets
 * {@code NOT_FOUND} from every tool, before the tool runs, so no break-glass opens through a client.
 * Every call is logged at INFO with the tool, the company, the user, the grant's kind and id, the duration and the
 * outcome; never its arguments or its result. Reads audit nothing, as the REST API's reads do not; writes audit what
 * their services audit, with the grant; whatever the services audit on a read (an instance admin's break-glass) they
 * still audit.
 */
@Component
class ToolInvoker {

    private static final Logger log = LoggerFactory.getLogger(ToolInvoker.class);

    private final JsonMapper json;

    private final McpToolCatalogue catalogue;

    private final MembershipDirectory memberships;

    private final TinfirmProperties properties;

    private final SecurityContextHolderStrategy security = SecurityContextHolder.getContextHolderStrategy();

    ToolInvoker(JsonMapper json, McpToolCatalogue catalogue, MembershipDirectory memberships,
            TinfirmProperties properties) {
        this.json = json;
        this.catalogue = catalogue;
        this.memberships = memberships;
        this.properties = properties;
    }

    /** A read tool's call: the DTO {@code read} returns, rendered by {@code render} with the default cap. */
    CallToolResult read(McpTransportContext context, String tool, Function<UUID, Object> read,
            Function<JsonNode, String> render) {
        return call(context, tool, companyId -> {
            JsonNode structured = structured(read.apply(companyId));
            return result(structured, render.apply(structured));
        });
    }

    /** A write tool's call: the DTO {@code write} returns, rendered by {@code render}; as a read's. */
    CallToolResult write(McpTransportContext context, String tool, Function<UUID, Object> write,
            Function<JsonNode, String> render) {
        return call(context, tool, true, companyId -> {
            JsonNode structured = structured(write.apply(companyId));
            return result(structured, render.apply(structured));
        });
    }

    /**
     * A submission's call (package R, §3.5): the DTO of what it made, rendered by {@code render}; when its
     * {@code requestId} was sent before, the text says that it answers that request again and that nothing new was
     * done.
     */
    CallToolResult submit(McpTransportContext context, String tool,
            Function<UUID, ConversationCommands.Submitted<?>> submit, Function<JsonNode, String> render) {
        return call(context, tool, true, companyId -> {
            ConversationCommands.Submitted<?> submitted = submit.apply(companyId);
            JsonNode structured = structured(submitted.body());
            String text = render.apply(structured);
            return result(structured, submitted.replayed() ? REPLAYED + "\n\n" + text : text);
        });
    }

    /** The first line of a replayed submission's text. */
    static final String REPLAYED = "This requestId was sent before: this answers that request again, as things are "
            + "now, and nothing new was done.";

    /** A call that builds its result itself from the company id ({@link #structured}, {@link #result}). */
    CallToolResult call(McpTransportContext context, String tool, Function<UUID, CallToolResult> body) {
        return call(context, tool, false, body);
    }

    /**
     * A call; one that {@code writes} is refused to a VIEWER (ADR-0077, decision 5), as their session's writes are
     * by the access guard.
     */
    private CallToolResult call(McpTransportContext context, String tool, boolean writes,
            Function<UUID, CallToolResult> body) {
        McpCall call = McpCall.of(context);
        long started = System.nanoTime();
        String outcome = "ok";
        SecurityContext previous = security.getContext();
        SecurityContext own = security.createEmptyContext();
        own.setAuthentication(new ActorAuthentication(call.user()));
        security.setContext(own);
        try (TenantContextHolder.Scope ignoredTenant = TenantContextHolder
            .open(TenantContext.forUser(call.user().id(), call.user().instanceAdmin()));
                BearerGrantHolder.Scope ignoredGrant = BearerGrantHolder.open(call.grant())) {
            ApiScope required = catalogue.requiredScope(tool)
                .orElseThrow(() -> new IllegalStateException("No scope for the tool " + tool));
            if (!call.grant().allows(required)) {
                // The HTTP filter refuses this first; this is the backstop.
                outcome = ProblemCode.INSUFFICIENT_SCOPE.name();
                return error(ProblemCode.INSUFFICIENT_SCOPE.name(),
                        "The tool needs the scope " + required.oauthScope() + ", which this token does not have.");
            }
            List<UserMembership> mine = memberships.membershipsOf(call.user().id());
            if (call.user().instanceAdmin()
                    && mine.stream().noneMatch(membership -> membership.companyId().equals(call.companyId()))) {
                // No break-glass through a client: an instance admin acts here only as a member (G9).
                outcome = "NOT_FOUND";
                return error("NOT_FOUND", "Company not found");
            }
            if (writes && mine.stream()
                .anyMatch(membership -> membership.companyId().equals(call.companyId())
                        && membership.role() == CompanyRole.VIEWER)) {
                outcome = ProblemCode.VIEWER_READ_ONLY.name();
                return error(ProblemCode.VIEWER_READ_ONLY.name(), "A Viewer reads the company and changes nothing.");
            }
            CallToolResult result = body.apply(call.companyId());
            outcome = Boolean.TRUE.equals(result.isError()) ? "error" : "ok";
            return result;
        }
        catch (ApiException ex) {
            String code = code(ex);
            outcome = code;
            return error(call.companyId(), code, ex);
        }
        catch (RuntimeException ex) {
            outcome = "failed";
            log.warn("MCP tool {} failed", tool, ex);
            return error("INTERNAL_ERROR", "The tool failed on the server. Try again later.");
        }
        finally {
            security.setContext(previous);
            long millis = (System.nanoTime() - started) / 1_000_000;
            log.atInfo()
                .addKeyValue("mcp.tool", tool)
                .addKeyValue("mcp.companyId", call.companyId())
                .addKeyValue("mcp.userId", call.user().id())
                .addKeyValue("mcp.grantKind", call.grant().kind())
                .addKeyValue("mcp.grantId", call.grant().id())
                .addKeyValue("mcp.durationMs", millis)
                .addKeyValue("mcp.outcome", outcome)
                .log("MCP tool {} in company {} by user {} ({} {}): {} in {} ms", tool, call.companyId(),
                        call.user().id(), call.grant().kind(), call.grant().id(), outcome, millis);
        }
    }

    /** The DTO as the REST API writes it; a list as {@code {"items": [...]}}. */
    JsonNode structured(Object dto) {
        Object value = dto instanceof List<?> list ? Map.of("items", list) : dto;
        return json.valueToTree(value);
    }

    static CallToolResult result(JsonNode structured, String text) {
        return CallToolResult.builder().structuredContent(structured).addTextContent(text).isError(false).build();
    }

    static CallToolResult error(String code, String detail) {
        return CallToolResult.builder().addTextContent(code + ": " + detail).isError(true).build();
    }

    /**
     * A service's refusal: its code and detail, and what the client needs to act on it: when to try again
     * ({@code retryAfter}, seconds), and where in the app a person decides what the client may not ({@code link}).
     */
    private CallToolResult error(UUID companyId, String code, ApiException ex) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("code", code);
        structured.put("detail", ex.getMessage());
        StringBuilder text = new StringBuilder(code).append(": ").append(ex.getMessage());
        if (ex.retryAfter() != null) {
            long seconds = Math.max(1, (ex.retryAfter().toMillis() + 999) / 1000);
            structured.put("retryAfter", seconds);
            text.append(" Try again in ").append(seconds).append(" s.");
        }
        if (ex.properties().get("conversationId") instanceof String conversationId) {
            String link = properties.publicBaseUrl() + "/c/" + companyId + "/conversations/" + conversationId;
            structured.putAll(ex.properties());
            structured.put("link", link);
            text.append(" Open it in Tinfirm: ").append(link);
        }
        return CallToolResult.builder()
            .structuredContent(json.valueToTree(structured))
            .addTextContent(text.toString())
            .isError(true)
            .build();
    }

    /** The problem code, or the status's name for an exception without one: the guard's 404 is NOT_FOUND. */
    private static String code(ApiException ex) {
        if (ex.code() != null) {
            return ex.code().name();
        }
        return switch (ex.status()) {
            case NOT_FOUND -> "NOT_FOUND";
            case FORBIDDEN -> "FORBIDDEN";
            case BAD_REQUEST -> ProblemCode.VALIDATION_FAILED.name();
            case CONFLICT -> "CONFLICT";
            default -> ex.status().name();
        };
    }
}
