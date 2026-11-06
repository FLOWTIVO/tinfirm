package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import com.flowtivo.tinfirm.shared.security.ActorAuthentication;
import com.flowtivo.tinfirm.shared.security.BearerGrantHolder;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * Authenticates {@code Authorization: Bearer} on the MCP endpoint (ADR-0045) through the ordered
 * {@link BearerCredentialResolver}s: the first that supports the token's format resolves it. An accepted token
 * becomes the same {@link ActorAuthentication} a browser session gets from {@link ActiveUserFilter}, so the company
 * access guard and the tenant context work unchanged, plus the {@link BearerGrantHolder grant} (credential id and
 * scope) for the rest of the request.
 * <p>
 * A request without a Bearer header continues unauthenticated, and the chain's entry point answers 401. A Bearer
 * token that no resolver supports, or that its resolver refuses, is answered here with 401 {@code TOKEN_INVALID}
 * and {@code WWW-Authenticate: Bearer error="invalid_token"}; on the chain, the challenge also names the company
 * endpoint's protected-resource metadata and scope ({@link McpChallenge}).
 */
final class BearerAuthenticationFilter extends OncePerRequestFilter {

    static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

    private final List<BearerCredentialResolver> resolvers;

    private final ProblemWriter problems;

    private final Function<HttpServletRequest, String> invalidTokenChallenge;

    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    BearerAuthenticationFilter(List<BearerCredentialResolver> resolvers, ProblemWriter problems) {
        this(resolvers, problems, request -> INVALID_TOKEN_CHALLENGE);
    }

    BearerAuthenticationFilter(List<BearerCredentialResolver> resolvers, ProblemWriter problems,
            Function<HttpServletRequest, String> invalidTokenChallenge) {
        this.resolvers = List.copyOf(resolvers);
        this.problems = problems;
        this.invalidTokenChallenge = invalidTokenChallenge;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<String> token = ApiTokens.bearerOf(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Optional<ResolvedBearer> resolved = resolve(token.get());
        if (resolved.isEmpty()) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, invalidTokenChallenge.apply(request));
            problems.write(request, response, ApiException.unauthorized(ProblemCode.TOKEN_INVALID,
                    "The bearer token is invalid, expired or revoked"));
            return;
        }

        ResolvedBearer bearer = resolved.get();
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(new ActorAuthentication(bearer.user()));
        holder.setContext(context);
        try (TenantContextHolder.Scope ignoredTenant = TenantContextHolder
            .open(TenantContext.forUser(bearer.user().id(), bearer.user().instanceAdmin()));
                BearerGrantHolder.Scope ignoredGrant = BearerGrantHolder.open(bearer.grant())) {
            chain.doFilter(request, response);
        }
        finally {
            holder.clearContext();
        }
    }

    private Optional<ResolvedBearer> resolve(String token) {
        if (token.isEmpty()) {
            return Optional.empty();
        }
        for (BearerCredentialResolver resolver : resolvers) {
            if (resolver.supports(token)) {
                return resolver.resolve(token);
            }
        }
        return Optional.empty();
    }
}
