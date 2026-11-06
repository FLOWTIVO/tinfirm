package com.flowtivo.tinfirm.runner.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import com.flowtivo.tinfirm.runner.RunnerProperties;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.SecurityHeaders;

/**
 * The runner WebSocket endpoint and its own security filter chain.
 * <p>
 * The endpoint is not part of the browser session (docs/archive/v1/CONVENTIONS.md §15.1): the chain matches only
 * {@code /api/runner/v1/**}, runs before the BFF chain, creates and reads no session, has no CSRF and no request
 * cache, and lets the upgrade request through. The handler authenticates the {@code Authorization: Bearer} runner
 * host token itself, so that a bad token can be answered with close code 4401 as the protocol requires. Browsers
 * cannot set that header on a WebSocket, and cross-origin browser upgrades are refused by the default same-origin
 * check.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableConfigurationProperties(RunnerProperties.class)
class RunnerWebSocketConfiguration implements WebSocketConfigurer {

    private final RunnerWebSocketHandler handler;

    RunnerWebSocketConfiguration(RunnerWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, RunnerHostService.CONNECT_PATH);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain runnerTransportSecurityFilterChain(HttpSecurity http, TinfirmProperties tinfirm) throws Exception {
        http.securityMatcher("/api/runner/v1/**")
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

    /** Inbound frames up to {@code tinfirm.runner.max-message-bytes} (the default 8 KiB is too small). */
    @Bean
    ServletServerContainerFactoryBean runnerWebSocketContainer(RunnerProperties properties) {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(properties.maxMessageBytes());
        container.setMaxBinaryMessageBufferSize(64 * 1024);
        return container;
    }
}
