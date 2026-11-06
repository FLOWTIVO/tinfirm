package com.flowtivo.tinfirm.mcp.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.SecurityHeaders;

/**
 * The Gmail add-on's settings, and the security chain of Google's requests (ADR-0083): {@code /api/gmail/**} is outside
 * {@code /api/companies} and the session chain, without cookies, CSRF or a request cache, as the chat platforms'
 * endpoints are. The endpoint verifies Google's tokens itself before it reads anything ({@link GmailAddOnController}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GmailAddOnProperties.class)
class GmailConfiguration {

    static final String INBOUND = "/api/gmail/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    SecurityFilterChain gmailAddOnSecurityFilterChain(HttpSecurity http, TinfirmProperties tinfirm) throws Exception {
        http.securityMatcher(INBOUND)
            .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(AbstractHttpConfigurer::disable)
            .securityContext(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .headers(headers -> SecurityHeaders.apply(headers, tinfirm));
        return http.build();
    }
}
