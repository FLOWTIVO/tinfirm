package com.flowtivo.tinfirm.trigger.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The trigger module's settings ({@code tinfirm.trigger.*}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({TriggerProperties.class, PollProperties.class})
class TriggerConfiguration {
}
