package com.flowtivo.tinfirm.identity.internal;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import org.springaicommunity.mcp.security.authorizationserver.config.McpAuthorizationServerConfigurer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.McpDefaultJwtCustomizer;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.mcp.token.ResourceIdentifierAudienceTokenCustomizer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.vault.InstanceKeys;

/**
 * Tinfirm's OAuth 2.1 authorization server for people's MCP clients (ADR-0045): Spring Security 7's authorization
 * server, adapted for MCP by MCP Security's {@link McpAuthorizationServerConfigurer} on Tinfirm's own filter chain
 * (never the library's auto-configuration, which assumes it owns the chains).
 * <ul>
 * <li>Authorization code with PKCE ({@code S256}) and refresh tokens only, for public clients; one issuer,
 * {@code TINFIRM_PUBLIC_URL}, with the default endpoint paths; open dynamic registration off
 * ({@code /oauth2/register} is 404), Client ID Metadata Documents on, plus the built-in clients.</li>
 * <li>Every authorization names a company's MCP endpoint as its {@code resource}, needs a Tinfirm session (the login
 * round trip keeps the request), and a consent on the web app's page {@code /authorize}, every time. The page
 * offers all three levels whatever the client asked for ({@link ScopeWideningRequestConverter}), and the token
 * carries the level the person chose.</li>
 * <li>Access tokens are JWTs of 1 hour signed with the vault's instance key, {@code aud} the consented resource;
 * refresh tokens last 30 days and rotate on every use.</li>
 * </ul>
 * The chain sits between the MCP endpoint's bearer chain and the web app's session chain. It shares the session, the
 * security context repository and the CSRF cookie of the web app, and its headers.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OAuthProperties.class)
class AuthorizationServerConfiguration {

    /** After the bearer chain of the MCP endpoint; the session chain has the default, lowest order. */
    static final int ORDER = BearerSecurityConfiguration.ORDER + 1;

    static final String CONSENT_PAGE = "/authorize";

    @Bean
    JWKSource<SecurityContext> jwkSource(InstanceKeys keys) {
        return new InstanceJwkSource(keys);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(OAuthResources resources) {
        return AuthorizationServerSettings.builder().issuer(resources.issuer()).build();
    }

    @Bean
    RegisteredClientRepository registeredClientRepository(OAuthClients clients) {
        return clients.repository();
    }

    @Bean
    OAuth2AuthorizationService authorizationService(JdbcTemplate jdbc, RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationService(jdbc, clients);
    }

    @Bean
    OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbc,
            RegisteredClientRepository clients) {
        return new JdbcOAuth2AuthorizationConsentService(jdbc, clients);
    }

    /** The request cache of authorization requests interrupted by a login, shared with the OIDC success handler. */
    static HttpSessionRequestCache authorizationRequestCache() {
        HttpSessionRequestCache cache = new HttpSessionRequestCache();
        cache.setRequestMatcher(PathPatternRequestMatcher.withDefaults()
            .matcher(HttpMethod.GET, OidcLoginSuccessHandler.AUTHORIZATION_ENDPOINT));
        cache.setMatchingRequestParameterName(null);
        return cache;
    }

    @Bean
    @Order(ORDER)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http, TinfirmProperties tinfirm,
            SessionLifetimeProperties sessionLifetime, Clock clock, OAuthResources resources,
            RegisteredClientRepository clients, OAuth2AuthorizationService authorizations,
            OAuth2AuthorizationConsentService consents, AuthorizationServerSettings settings,
            JWKSource<SecurityContext> jwkSource, OAuthCompanyAccess access, OAuthGrantService grants,
            UserRepository users, CookieCsrfTokenRepository csrfTokenRepository,
            SecurityContextRepository securityContextRepository,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        ProblemWriter problems = new ProblemWriter(exceptionResolver);
        RequestMatcher authorizationEndpoint = paths.matcher(settings.getAuthorizationEndpoint());
        RequestMatcher notServed = new OrRequestMatcher(paths.matcher("/oauth2/register"),
                paths.matcher("/oauth2/register/**"), paths.matcher("/.well-known/openid-configuration"),
                paths.matcher("/.well-known/openid-configuration/**"));
        RequestMatcher chain = new OrRequestMatcher(
                new AndRequestMatcher(paths.matcher("/oauth2/**"),
                        new NegatedRequestMatcher(paths.matcher("/oauth2/authorization/**"))),
                paths.matcher("/.well-known/oauth-authorization-server/**"),
                paths.matcher(OAuthResources.RESOURCE_METADATA_PREFIX + "/**"),
                paths.matcher("/.well-known/openid-configuration/**"));

        AuthorizationResponses responses = new AuthorizationResponses(resources, authorizations, grants);
        JwtGenerator jwtGenerator = new JwtGenerator(new NimbusJwtEncoder(jwkSource));
        ResourceIdentifierAudienceTokenCustomizer audience = new ResourceIdentifierAudienceTokenCustomizer();
        OAuthAccessTokenCustomizer claims = new OAuthAccessTokenCustomizer(resources, users);
        jwtGenerator.setJwtCustomizer(context -> {
            McpDefaultJwtCustomizer.DEFAULT_JWT_CUSTOMIZER.customize(context);
            audience.customize(context);
            claims.customize(context);
        });
        OAuth2TokenGenerator<?> tokens = new DelegatingOAuth2TokenGenerator(jwtGenerator,
                new PublicClientRefreshTokenGenerator(clock));
        // McpAuthorizationServerConfigurer takes the generator from here instead of building one that gives public
        // clients no refresh token.
        http.setSharedObject(OAuth2TokenGenerator.class, tokens);

        http.securityMatcher(chain)
            .securityContext(context -> context.securityContextRepository(securityContextRepository))
            .requestCache(cache -> cache.requestCache(authorizationRequestCache()))
            .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, ProtectedResourceMetadata.PATH).permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                    new LoginRedirectEntryPoint(tinfirm.publicBaseUrl()), authorizationEndpoint))
            .headers(headers -> SecurityConfiguration.securityHeaders(headers, tinfirm))
            .with(McpAuthorizationServerConfigurer.mcpAuthorizationServer(), mcp -> mcp.cimd(true)
                .dynamicClientRegistration(false)
                .authorizationCodeRequestValidator(OAuthRequestValidators.authorizationRequest(resources, access))
                .authorizationServer(authorizationServer -> authorizationServer.registeredClientRepository(clients)
                    .authorizationService(authorizations)
                    .authorizationConsentService(new RememberNoConsentService(consents))
                    .authorizationServerSettings(settings)
                    .authorizationEndpoint(endpoint -> endpoint
                        .authorizationRequestConverters(ScopeWideningRequestConverter::wrap)
                        .consentPage(tinfirm.publicBaseUrl() + CONSENT_PAGE)
                        .authorizationResponseHandler(responses)
                        .errorResponseHandler(responses))
                    .clientAuthentication(clientAuthentication -> clientAuthentication
                        .authenticationConverter(
                                new PublicClientAuthentication.Converter(settings.getTokenRevocationEndpoint()))
                        .authenticationProvider(new PublicClientAuthentication.Provider(clients)))
                    .authorizationServerMetadataEndpoint(metadata -> metadata.authorizationServerMetadataCustomizer(
                            builder -> builder.claims(AuthorizationServerConfiguration::metadata)))
                    .withObjectPostProcessor(new ConsentEveryTime())
                    .withObjectPostProcessor(new ConsentCheck(resources, access))))
            .addFilterBefore(new NotServedFilter(notServed, problems), SecurityContextHolderFilter.class)
            .addFilterBefore(new SessionMaxAgeFilter(sessionLifetime.maxAge(), clock), SecurityContextHolderFilter.class)
            .addFilterAfter(new AuthorizationServerUserFilter(access), SecurityContextHolderFilter.class)
            .addFilterAfter(new ConsentCsrfFilter(paths.matcher(HttpMethod.POST, settings.getAuthorizationEndpoint()),
                    csrfTokenRepository, problems), CsrfFilter.class);
        return http.build();
    }

    /** What the RFC 8414 metadata says beyond Spring's defaults, and what it leaves out. */
    private static void metadata(Map<String, Object> claims) {
        claims.put("scopes_supported", OAuthScopes.ALL);
        claims.put("response_types_supported", List.of("code"));
        claims.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
        claims.put("code_challenge_methods_supported", List.of("S256"));
        claims.put("token_endpoint_auth_methods_supported", List.of("none"));
        claims.put("revocation_endpoint_auth_methods_supported", List.of("none"));
        claims.put("client_id_metadata_document_supported", true);
        claims.put("authorization_response_iss_parameter_supported", true);
        for (String unsupported : List.of("introspection_endpoint", "introspection_endpoint_auth_methods_supported",
                "device_authorization_endpoint", "pushed_authorization_request_endpoint",
                "registration_endpoint", "tls_client_certificate_bound_access_tokens",
                "dpop_signing_alg_values_supported")) {
            claims.remove(unsupported);
        }
    }

    /** Consent on every authorization (Spring and MCP Security skip it once the scopes were consented before). */
    static final class ConsentEveryTime
            implements ObjectPostProcessor<OAuth2AuthorizationCodeRequestAuthenticationProvider> {

        @Override
        public <O extends OAuth2AuthorizationCodeRequestAuthenticationProvider> O postProcess(O provider) {
            provider.setAuthorizationConsentRequired(context -> true);
            return provider;
        }
    }

    /** The consent's own check that the person may still act in the company. */
    static final class ConsentCheck implements ObjectPostProcessor<OAuth2AuthorizationConsentAuthenticationProvider> {

        private final OAuthResources resources;

        private final OAuthCompanyAccess access;

        ConsentCheck(OAuthResources resources, OAuthCompanyAccess access) {
            this.resources = resources;
            this.access = access;
        }

        @Override
        public <O extends OAuth2AuthorizationConsentAuthenticationProvider> O postProcess(O provider) {
            provider.setAuthorizationConsentCustomizer(OAuthRequestValidators.consent(resources, access));
            return provider;
        }
    }
}
