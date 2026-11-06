package com.flowtivo.tinfirm.mcp.internal;

import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.BearerGrant;

import io.modelcontextprotocol.common.McpTransportContext;

/**
 * Who calls a tool, and in which company: copied into the {@link McpTransportContext} on the request thread by the
 * transport's context extractor ({@link McpEndpointConfiguration}), from the request path and from what the bearer
 * filter chain established (the user's authentication and the {@link BearerGrant}), so a tool reads them from its
 * context and not from thread-locals, which another thread would not have. A tool never takes the company as an
 * argument.
 *
 * @param companyId the company of the endpoint's path
 * @param user      the user the credential belongs to
 * @param grant     the credential: its kind, id and scope
 */
record McpCall(UUID companyId, AuthenticatedUser user, BearerGrant grant) {

    static final String COMPANY_ID = "tinfirm.companyId";

    static final String USER = "tinfirm.user";

    static final String GRANT = "tinfirm.grant";

    /** The transport context of a call, with the three values. */
    McpTransportContext toContext() {
        return McpTransportContext.create(Map.of(COMPANY_ID, companyId, USER, user, GRANT, grant));
    }

    /** The call of a transport context; fails when the extractor did not fill it (never for an authenticated call). */
    static McpCall of(@Nullable McpTransportContext context) {
        if (context != null && context.get(COMPANY_ID) instanceof UUID companyId
                && context.get(USER) instanceof AuthenticatedUser user
                && context.get(GRANT) instanceof BearerGrant grant) {
            return new McpCall(companyId, user, grant);
        }
        throw new IllegalStateException("The MCP call has no company, user or grant in its transport context");
    }
}
