package com.flowtivo.tinfirm.identity.internal;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.flowtivo.tinfirm.shared.config.TinfirmProperties;

/**
 * The names of Tinfirm's OAuth authorization server and of the protected resources it issues tokens for
 * (ADR-0045): the issuer is {@code TINFIRM_PUBLIC_URL}, and each company's MCP endpoint is a resource with the
 * canonical URI {@code {issuer}/api/companies/{companyId}/mcp}, the only {@code resource} the authorization server
 * accepts and the {@code aud} of every access token.
 */
@Component
class OAuthResources {

    static final String RESOURCE_METADATA_PREFIX = "/.well-known/oauth-protected-resource";

    private static final Pattern COMPANY_PATH = Pattern
        .compile("^/api/companies/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})/mcp(/.*)?$");

    private final String issuer;

    OAuthResources(TinfirmProperties tinfirm) {
        this.issuer = tinfirm.publicBaseUrl();
    }

    /** The issuer: {@code TINFIRM_PUBLIC_URL} without a trailing slash. */
    String issuer() {
        return issuer;
    }

    /** The canonical URI of the company's MCP endpoint, the {@code resource} and {@code aud} of its tokens. */
    String resource(UUID companyId) {
        return issuer + resourcePath(companyId);
    }

    static String resourcePath(UUID companyId) {
        return "/api/companies/" + companyId + "/mcp";
    }

    /** The URL of the company endpoint's protected-resource metadata (RFC 9728). */
    String resourceMetadata(UUID companyId) {
        return issuer + RESOURCE_METADATA_PREFIX + resourcePath(companyId);
    }

    /** The company of a {@code resource} value, when it is exactly the canonical URI of a company's MCP endpoint. */
    Optional<UUID> companyOfResource(@Nullable String resource) {
        if (resource == null || !resource.startsWith(issuer + "/")) {
            return Optional.empty();
        }
        Matcher matcher = COMPANY_PATH.matcher(resource.substring(issuer.length()));
        if (!matcher.matches() || matcher.group(2) != null) {
            return Optional.empty();
        }
        UUID companyId = UUID.fromString(matcher.group(1));
        // The canonical form only: lower-case hex, as UUID.toString writes it.
        return resource.equals(resource(companyId)) ? Optional.of(companyId) : Optional.empty();
    }

    /** The company of a request path on the MCP endpoint ({@code /api/companies/{companyId}/mcp/**}). */
    /** The company of the current request's path on the MCP endpoint; empty outside a request or another path. */
    static Optional<UUID> companyOfCurrentRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        HttpServletRequest request = attributes.getRequest();
        return companyOfPath(request.getRequestURI().substring(request.getContextPath().length()));
    }

    static Optional<UUID> companyOfPath(@Nullable String path) {
        if (path == null) {
            return Optional.empty();
        }
        Matcher matcher = COMPANY_PATH.matcher(path);
        return matcher.matches() ? Optional.of(UUID.fromString(matcher.group(1))) : Optional.empty();
    }
}
