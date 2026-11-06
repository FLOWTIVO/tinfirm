package com.flowtivo.tinfirm.shared.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TinfirmProperties.class)
class SharedConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
