package com.flowtivo.tinfirm.chat.internal;

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
 * The chat module's settings, and the security chain of the platforms' own requests (the package 5 contract, §5.2):
 * {@code /api/chat/**} is outside {@code /api/companies} and the session chain, without cookies, CSRF or a request
 * cache, as the runner's endpoints are. Each endpoint verifies its request itself (Slack's signature, the Bot
 * Framework's token) before it reads anything (C1).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatProperties.class)
class ChatConfiguration {

    static final String INBOUND = "/api/chat/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain chatInboundSecurityFilterChain(HttpSecurity http, TinfirmProperties tinfirm) throws Exception {
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
