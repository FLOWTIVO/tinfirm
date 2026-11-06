package com.flowtivo.tinfirm.run.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The run module's settings ({@code tinfirm.run.*}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunProperties.class)
class RunConfiguration {
}
