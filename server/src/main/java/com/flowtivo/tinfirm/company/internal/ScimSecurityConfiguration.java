package com.flowtivo.tinfirm.company.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.company.scim.ScimTokenStore;
import com.flowtivo.tinfirm.company.scim.ScimSettings;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.SecurityHeaders;

/**
 * The SCIM endpoint's filter chain, {@code /scim/**} (ADR-0087, decision 1): stateless, as the MCP endpoint's is. No
 * CSRF, no session, no request cache; the session cookie is ignored (the security context lives in a request
 * attribute only), so a browser's session never reaches it. Every request is authenticated by
 * {@link ScimRequestFilter}, which answers its refusals in SCIM's form; what passes is let through to
 * {@link ScimController}. It runs after the MCP chain and before the BFF chain.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScimProperties.class)
class ScimSecurityConfiguration {

    static final String PATHS = "/scim/**";

    /** After the runner chain and the MCP endpoint's ({@code HIGHEST_PRECEDENCE + 1}). */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    @Bean
    @Order(ORDER)
    SecurityFilterChain scimSecurityFilterChain(HttpSecurity http, ScimTokenStore tokens, ScimSettings settings,
            AuditLog auditLog, PlatformTransactionManager transactionManager, TimeSource time,
            ScimProperties limits, TinfirmProperties tinfirm) throws Exception {
        ScimRequestFilter filter = new ScimRequestFilter(tokens, settings, auditLog,
                new TransactionTemplate(transactionManager), time, limits);
        http.securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(PATHS))
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .securityContext(context -> context.securityContextRepository(new RequestAttributeSecurityContextRepository()))
            .requestCache(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .anonymous(AbstractHttpConfigurer::disable)
            .headers(headers -> SecurityHeaders.apply(headers, tinfirm))
            .addFilterBefore(filter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
