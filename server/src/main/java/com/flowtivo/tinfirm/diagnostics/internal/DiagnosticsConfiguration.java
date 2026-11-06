package com.flowtivo.tinfirm.diagnostics.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The diagnostics module's settings (ADR-0070). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ErrorReportProperties.class)
class DiagnosticsConfiguration {
}
