package com.flowtivo.tinfirm.identity.internal;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter;
import org.springframework.security.web.header.writers.frameoptions.XFrameOptionsHeaderWriter.XFrameOptionsMode;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.flowtivo.tinfirm.identity.ProvisionedMemberships;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.SecurityHeaders;
import com.flowtivo.tinfirm.shared.security.ActorAuthentication;
import com.flowtivo.tinfirm.shared.web.SpaConfiguration;

/**
 * BFF security (CONVENTIONS §5): server-side JDBC sessions behind the {@code TINFIRM_SESSION} cookie, SPA-style CSRF
 * ({@code XSRF-TOKEN} cookie + {@code X-XSRF-TOKEN} header) on every mutating request, JSON login instead of form
 * login, OIDC login with PKCE, JSON 401/403 for {@code /api/**}, and the security headers. An OIDC login ends on
 * the OAuth authorization request it interrupted (ADR-0045), else on the page its start named ({@link ReturnPaths}),
 * else on {@code /} ({@link OidcLoginSuccessHandler}).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableConfigurationProperties({ AuthProperties.class, SamlProperties.class, SessionLifetimeProperties.class,
        OAuth2ClientProperties.class, OutlookAddInProperties.class })
class SecurityConfiguration {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SecurityConfiguration.class);

    static final String SESSION_COOKIE = "TINFIRM_SESSION";

    static final String OIDC_CALLBACK = "/login/oauth2/code/{registrationId}";

    static final String CONTENT_SECURITY_POLICY = SecurityHeaders.CONTENT_SECURITY_POLICY;

    /** The Outlook add-in's pages, routes of the web app (ADR-0074): its task pane and its sign-in callback. */
    static final String OUTLOOK_PAGES = "/outlook/**";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TinfirmProperties tinfirm,
            SessionLifetimeProperties sessionLifetime, Clock clock, UserRepository users,
            CookieCsrfTokenRepository csrfTokenRepository, SecurityContextRepository securityContextRepository,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
            TinfirmClientRegistrations registrations, ExternalProvisioning externalProvisioning, LoginAudit loginAudit,
            AuthProperties authProperties, ProvisionedMemberships memberships, OutlookAddInProperties outlook,
            SamlSettings saml, ObjectProvider<SamlLogin> samlLogin, SamlSignIns samlSignIns) throws Exception {
        RequestMatcher api = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        ProblemSecurityHandlers problems = new ProblemSecurityHandlers(exceptionResolver);

        http.securityContext(context -> context.securityContextRepository(securityContextRepository))
            .requestCache(cache -> cache.requestCache(new NullRequestCache()))
            .csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/api/auth/providers", "/api/system/info").permitAll()
                // Tinfirm's Client ID Metadata Document, which authorization servers fetch without a session
                // (ADR-0049 §6). The OAuth callback of accounts, GET /api/oauth/callback, is a browser redirect under
                // /api/** below: it needs the session (401 without), and a GET needs no CSRF token.
                .requestMatchers(HttpMethod.GET, ClientMetadataDocument.PATH).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/logout").permitAll()
                .requestMatchers("/api/admin/**").hasAuthority(ActorAuthentication.ROLE_INSTANCE_ADMIN)
                .requestMatchers("/api/**").authenticated()
                // Everything else is the SPA, static files and the OIDC endpoints.
                .anyRequest().permitAll())
            .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(problems, api)
                .defaultAccessDeniedHandlerFor(problems, api))
            .headers(headers -> securityHeaders(headers, tinfirm, outlook))
            .addFilterBefore(new SessionMaxAgeFilter(sessionLifetime.maxAge(), clock), SecurityContextHolderFilter.class)
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .addFilterBefore(new ActiveUserFilter(users), AnonymousAuthenticationFilter.class);

        authProperties.oidc().verifyRegistrations(registrations::isInstanceRegistration);
        authProperties.oidc().verifyCompanies(memberships::declares);
        // Always on, with or without providers: an unknown registration id then ends on the login page.
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                registrations, OAuth2AuthorizationRequestRedirectFilter.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        // PKCE for every registration, confidential clients included.
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        // On the OAuth authorization request the login interrupted (ADR-0045), else on the start's checked return
        // path (ReturnPaths), else on "/".
        OidcLoginSuccessHandler success = new OidcLoginSuccessHandler(tinfirm.publicBaseUrl(),
                AuthorizationServerConfiguration.authorizationRequestCache());
        String unknownProvider = tinfirm.publicBaseUrl() + "/login?error=" + OidcLoginDeniedException.OIDC_LOGIN_FAILED;
        http.oauth2Login(oauth2 -> oauth2
            // The SPA owns /login; Spring Security must not generate its own login page.
            .loginPage("/login")
            .clientRegistrationRepository(registrations)
            // The start's checked return path travels with the authorization request in the session (Q44).
            .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(OidcReturnPath.resolver(resolver))
                .authorizationRequestRepository(OidcReturnPath.repository()))
            .userInfoEndpoint(userInfo -> userInfo.oidcUserService(new TinfirmOidcUserService(externalProvisioning,
                    registrationId -> {
                        AuthProperties.OidcProvider settings = authProperties.oidc().provider(registrationId);
                        return ProviderCompanies.resolve(memberships, settings.company(), settings.roles());
                    })))
            .successHandler(success)
            .failureHandler(new OidcLoginFailureHandler(tinfirm.publicBaseUrl(), loginAudit))
            // An unknown or unreachable provider at /oauth2/authorization/{id}: back to the login page,
            // not audited (anyone can ask for any id) and not a 500.
            .withObjectPostProcessor(new ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter>() {
                @Override
                public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(O filter) {
                    filter.setAuthenticationFailureHandler(
                            (request, response, exception) -> response.sendRedirect(unknownProvider));
                    return filter;
                }
            }));
        // SAML (ADR-0086, decision 11): ee's, when the deployment declares providers.
        SamlLogin samlProviders = samlLogin.getIfAvailable();
        if (saml.declared() && samlProviders != null) {
            samlProviders.configure(http, samlSignIns);
        }
        else if (saml.declared()) {
            log.warn("SAML providers are declared, but this build has no SAML sign-in (ee/): they are not offered");
        }
        return http.build();
    }

    /**
     * The web app's hashed build files ({@link SpaConfiguration#BUILD_FILES}): public and immutable, so served with
     * the same headers but without the session. On the session chain each one cost four trips to the database (the
     * session's load, the user's check, the session's write-back), and a page's first load fetches every lazy chunk,
     * well over a hundred, while its own API calls queue behind them for the connection pool.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 3)
    SecurityFilterChain buildFilesSecurityFilterChain(HttpSecurity http, TinfirmProperties tinfirm,
            OutlookAddInProperties outlook) throws Exception {
        List<RequestMatcher> files = new ArrayList<>();
        for (String pattern : SpaConfiguration.BUILD_FILES) {
            files.add(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, pattern));
            files.add(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.HEAD, pattern));
        }
        // Nothing here may read the session: the anonymous token's details read its id, and session management asks
        // the context repository, and either one loads the session from the database.
        http.securityMatcher(new OrRequestMatcher(files))
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(AbstractHttpConfigurer::disable)
            .anonymous(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .securityContext(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .headers(headers -> securityHeaders(headers, tinfirm, outlook));
        return http.build();
    }

    /**
     * The security headers of every response (CONVENTIONS §5), on this chain, on the authorization server's and on the
     * MCP endpoint's: the strict CSP, {@code X-Frame-Options: DENY}, nosniff, the referrer policy, and HSTS when the
     * public URL is https. The chains without a session write the same ones ({@link SecurityHeaders#apply}).
     */
    static void securityHeaders(HeadersConfigurer<HttpSecurity> headers, TinfirmProperties tinfirm) {
        SecurityHeaders.apply(headers, tinfirm);
    }

    /**
     * The security headers of the web app's chain, which serves the SPA: those of {@link #securityHeaders}, except
     * that the Outlook add-in's pages ({@link #OUTLOOK_PAGES}, ADR-0074) carry the add-in's CSP, which lets Office.js
     * load and Outlook frame the task pane, and no {@code X-Frame-Options}.
     */
    static void securityHeaders(HeadersConfigurer<HttpSecurity> headers, TinfirmProperties tinfirm,
            OutlookAddInProperties outlook) {
        RequestMatcher addInPages = PathPatternRequestMatcher.withDefaults().matcher(OUTLOOK_PAGES);
        RequestMatcher otherPages = new NegatedRequestMatcher(addInPages);
        headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable);
        headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(otherPages,
                new XFrameOptionsHeaderWriter(XFrameOptionsMode.DENY)));
        headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(otherPages,
                new ContentSecurityPolicyHeaderWriter(CONTENT_SECURITY_POLICY)));
        headers.addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(addInPages,
                new ContentSecurityPolicyHeaderWriter(outlook.contentSecurityPolicy())));
        SecurityHeaders.common(headers, tinfirm);
    }

    /**
     * The OIDC registrations of the instance, from the standard {@code spring.security.oauth2.client.*} properties,
     * with the redirect URI resolved against {@code TINFIRM_PUBLIC_URL} and PKCE required. The server does not start
     * on a registration without an {@code issuer-uri} or without the {@code openid} scope
     * ({@link TinfirmClientRegistrations#problems}).
     */
    @Bean
    TinfirmClientRegistrations clientRegistrationRepository(OAuth2ClientProperties properties,
            Environment environment) {
        List<ClientRegistration> prepared = new ArrayList<>();
        for (ClientRegistration registration : new OAuth2ClientPropertiesMapper(properties).asClientRegistrations()
            .values()) {
            prepared.add(ClientRegistration.withClientRegistration(registration)
                .redirectUri(registration.getRedirectUri().replace("{baseUrl}", publicBaseUrl(environment)))
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build())
                .build());
        }
        List<String> problems = TinfirmClientRegistrations.problems(prepared);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid OIDC registrations (CONVENTIONS §5): "
                    + String.join("; ", problems));
        }
        return new TinfirmClientRegistrations(prepared);
    }

    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(TinfirmProperties tinfirm) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookiePath("/");
        repository.setCookieCustomizer(cookie -> cookie.secure(tinfirm.secureCookies()).sameSite("Lax"));
        return repository;
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new DelegatingSecurityContextRepository(new RequestAttributeSecurityContextRepository(),
                new HttpSessionSecurityContextRepository());
    }

    @Bean
    LoginSessions loginSessions(SecurityContextRepository securityContextRepository,
            CookieCsrfTokenRepository csrfTokenRepository) {
        return new LoginSessions(securityContextRepository, new ChangeSessionIdAuthenticationStrategy(),
                csrfTokenRepository);
    }

    /** {@code TINFIRM_SESSION}: HttpOnly, SameSite=Lax, Secure when the public URL is https. */
    @Bean
    CookieSerializer cookieSerializer(TinfirmProperties tinfirm) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(SESSION_COOKIE);
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie(tinfirm.secureCookies());
        return serializer;
    }

    /**
     * Prepares the standard {@code spring.security.oauth2.client.*} properties: the redirect URI defaults to
     * {@code TINFIRM_PUBLIC_URL/login/oauth2/code/{registrationId}}. {@link #clientRegistrationRepository} then resolves
     * a {@code {baseUrl}} template against {@code TINFIRM_PUBLIC_URL} instead of the incoming request, so redirects
     * are right behind proxies and the dev server proxy, and requires PKCE of every registration, confidential clients
     * included.
     */
    @Bean
    static BeanPostProcessor oidcRegistrationPostProcessor(Environment environment) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof OAuth2ClientProperties properties) {
                    properties.getRegistration().values().forEach(registration -> {
                        if (!StringUtils.hasText(registration.getRedirectUri())) {
                            registration.setRedirectUri(publicBaseUrl(environment) + OIDC_CALLBACK);
                        }
                    });
                    return bean;
                }
                return bean;
            }
        };
    }

    private static String publicBaseUrl(Environment environment) {
        String publicUrl = environment.getProperty("tinfirm.public-url", "http://localhost:8080");
        return publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }
}
