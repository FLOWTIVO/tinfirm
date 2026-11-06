package com.flowtivo.tinfirm.trigger.internal;

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
 * The webhook's settings ({@link WebhookProperties}), and the security chain of a webhook's deliveries (the package 5 contract, §5.12): {@code /api/hooks/**} is outside
 * {@code /api/companies} and the session chain, without cookies, CSRF or a request cache, as the chats' endpoints are.
 * {@link WebhookInbox} verifies each delivery itself before it counts or reads anything (C1).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookProperties.class)
class WebhookConfiguration {

    static final String INBOUND = "/api/hooks/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 2)
    SecurityFilterChain webhookInboundSecurityFilterChain(HttpSecurity http, TinfirmProperties tinfirm) throws Exception {
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
