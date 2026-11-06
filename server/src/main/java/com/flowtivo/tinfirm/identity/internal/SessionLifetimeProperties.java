package com.flowtivo.tinfirm.identity.internal;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param maxAge absolute session lifetime ({@code TINFIRM_SESSION_MAX_AGE}), counted from session creation.
 *               The idle timeout is {@code spring.session.timeout} ({@code TINFIRM_SESSION_IDLE_TIMEOUT}).
 */
@Validated
@ConfigurationProperties("tinfirm.session")
record SessionLifetimeProperties(@NotNull Duration maxAge) {
}
