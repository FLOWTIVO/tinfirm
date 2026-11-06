package com.flowtivo.tinfirm.identity.internal;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * The bearer filter chain of the MCP endpoint, {@code /api/companies/{companyId}/mcp} (ADR-0045).
 * <p>
 * It runs just after the runner chain and before the BFF chain, and is the only chain that accepts a bearer token;
 * every other {@code /api/**} path stays session-only. It is stateless: no CSRF, no session, no request cache, and
 * the session cookie is ignored entirely (the security context lives in a request attribute, never in the session),
 * so a browser's session cannot reach the endpoint. A request with an {@code Origin} header other than Tinfirm's own,
 * the Outlook add-in's (ADR-0074), is refused with 403 ({@link OriginRefusalFilter}).
 * {@link BearerAuthenticationFilter} resolves {@code Authorization: Bearer} through the ordered
 * {@link BearerCredentialResolver} beans; personal access tokens come first, then the access tokens of
 * Tinfirm's authorization server ({@link OAuthAccessTokenResolver}). A request without a token answers 401 with
 * {@code WWW-Authenticate: Bearer resource_metadata="...", scope="tinfirm:read"} ({@link McpChallenge}), from which
 * an MCP client discovers the authorization server. Its responses carry the security headers of every other chain
 * ({@link SecurityConfiguration#securityHeaders}). The endpoint behind the chain is the {@code mcp} module's.
 */
@Configuration(proxyBeanMethods = false)
class BearerSecurityConfiguration {

    /** Every path of the MCP endpoint of a company; {@code /**} also matches the endpoint itself. */
    static final String MCP_PATHS = "/api/companies/*/mcp/**";

    /** Just after the runner chain ({@code HIGHEST_PRECEDENCE}); the BFF chain has the default, lowest order. */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    @Bean
    @Order(ORDER)
    SecurityFilterChain bearerSecurityFilterChain(HttpSecurity http, List<BearerCredentialResolver> resolvers,
            OAuthResources resources, TinfirmProperties tinfirm,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
        ProblemWriter problems = new ProblemWriter(exceptionResolver);
        McpChallenge challenge = new McpChallenge(resources);
        AuthenticationEntryPoint bearerRequired = (request, response, exception) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge.header(request, null));
            problems.write(request, response, ApiException.unauthorized("A bearer token is required"));
        };

        http.securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(MCP_PATHS))
            .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(bearerRequired))
            .headers(headers -> SecurityConfiguration.securityHeaders(headers, tinfirm))
            .addFilterAfter(new OriginRefusalFilter(problems, tinfirm.publicUrl()), HeaderWriterFilter.class)
            .addFilterBefore(new BearerAuthenticationFilter(resolvers, problems,
                    request -> challenge.header(request, McpChallenge.INVALID_TOKEN)),
                    AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
