package com.flowtivo.tinfirm.identity.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * The protected-resource metadata (RFC 9728) of each company's MCP endpoint (ADR-0045), at
 * {@code /.well-known/oauth-protected-resource/api/companies/{companyId}/mcp}, which the endpoint's 401 names: the
 * resource's canonical URI, Tinfirm as its one authorization server, {@code tinfirm:read} as the scope a client asks
 * for first, and the bearer header as the only way to present a token. Public, like every well-known document, so it
 * names no company and answers the same document for every company id, one that no company has included: it never
 * tells a stranger whether a company exists, or what it is called ({@code resource_name} is optional in RFC 9728, and
 * the consent page names the company to the signed-in person). 404 only for an id that is no UUID. The issuer itself
 * needs no resource metadata.
 */
@Configuration(proxyBeanMethods = false)
class ProtectedResourceMetadata {

    static final String PATH = OAuthResources.RESOURCE_METADATA_PREFIX + "/api/companies/{companyId}/mcp";

    @Bean
    RouterFunction<ServerResponse> protectedResourceMetadataRoute(OAuthResources resources) {
        return RouterFunctions.route(RequestPredicates.GET(PATH), request -> {
            Optional<UUID> companyId = parse(request.pathVariable("companyId"));
            if (companyId.isEmpty()) {
                return ServerResponse.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No such resource"));
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("resource", resources.resource(companyId.get()));
            metadata.put("authorization_servers", List.of(resources.issuer()));
            metadata.put("scopes_supported", List.of(OAuthScopes.ADVERTISED));
            metadata.put("bearer_methods_supported", List.of("header"));
            return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(metadata);
        });
    }

    private static Optional<UUID> parse(String value) {
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equals(value) ? Optional.of(id) : Optional.empty();
        }
        catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
