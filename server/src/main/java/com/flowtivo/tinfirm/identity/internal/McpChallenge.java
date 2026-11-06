package com.flowtivo.tinfirm.identity.internal;

import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.security.ApiScope;

/**
 * The {@code WWW-Authenticate} challenge of the MCP endpoint's 401 (ADR-0045, RFC 6750 and RFC 9728): it names the
 * protected-resource metadata of the company endpoint of the request, through which a client discovers Tinfirm's
 * authorization server, and the scope to ask for first, {@code tinfirm:read}. When a token was presented and refused,
 * it also carries {@code error="invalid_token"}. A path without a valid company id gets the bare challenge. The
 * {@code mcp} module's 403 for a tool beyond the credential's scope is built here too ({@link McpChallengesImpl}).
 */
final class McpChallenge {

    static final String INVALID_TOKEN = "invalid_token";

    static final String INSUFFICIENT_SCOPE = "insufficient_scope";

    private final OAuthResources resources;

    McpChallenge(OAuthResources resources) {
        this.resources = resources;
    }

    String header(HttpServletRequest request, @Nullable String error) {
        StringBuilder header = new StringBuilder("Bearer");
        String separator = " ";
        if (error != null) {
            header.append(separator).append("error=\"").append(error).append('"');
            separator = ", ";
        }
        Optional<UUID> company = OAuthResources
            .companyOfPath(request.getRequestURI().substring(request.getContextPath().length()));
        if (company.isPresent()) {
            header.append(separator)
                .append("resource_metadata=\"")
                .append(resources.resourceMetadata(company.get()))
                .append("\", scope=\"")
                .append(OAuthScopes.ADVERTISED)
                .append('"');
        }
        return header.toString();
    }

    /**
     * The challenge of a 403 for a tool beyond the credential's scope (RFC 6750 §3.1, the MCP specification's step-up
     * authorization): {@code error="insufficient_scope"}, the scope the tool needs, and the company endpoint's
     * protected-resource metadata, from which a client may ask the person to authorize again with that scope (Claude
     * Code does not; the person chooses the level on the consent page).
     */
    String insufficientScope(UUID companyId, ApiScope required) {
        return "Bearer error=\"" + INSUFFICIENT_SCOPE + "\", scope=\"" + required.oauthScope()
                + "\", resource_metadata=\"" + resources.resourceMetadata(companyId) + '"';
    }
}
