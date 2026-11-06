package com.flowtivo.tinfirm.trigger.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The write-back's settings ({@code tinfirm.trigger.outbox.*}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
class OutboxConfiguration {
}
