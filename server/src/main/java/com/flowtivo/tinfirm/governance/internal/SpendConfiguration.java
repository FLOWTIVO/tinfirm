package com.flowtivo.tinfirm.governance.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SpendProperties.class)
class SpendConfiguration {
}
