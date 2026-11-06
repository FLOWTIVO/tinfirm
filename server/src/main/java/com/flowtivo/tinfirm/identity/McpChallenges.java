package com.flowtivo.tinfirm.identity;

import java.util.UUID;

import com.flowtivo.tinfirm.shared.security.ApiScope;

/**
 * The {@code WWW-Authenticate} challenges of a company's MCP endpoint (ADR-0045) that the {@code mcp} module answers
 * itself: the identity module owns the endpoint's resource metadata and the format of its challenges, so the 401 of
 * the bearer chain and the 403 of a tool call beyond the credential's scope name the same document.
 */
public interface McpChallenges {

    /**
     * {@code Bearer error="insufficient_scope", scope="tinfirm:write", resource_metadata="..."}: the value of
     * {@code WWW-Authenticate} on the 403 of a tool that needs {@code required}, which the credential lacks.
     */
    String insufficientScope(UUID companyId, ApiScope required);
}
