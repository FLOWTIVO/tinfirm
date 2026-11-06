package com.flowtivo.tinfirm.vault.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.vault.MasterKeyProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VaultProperties.class)
class VaultConfiguration {

    /** Created eagerly at startup, so invalid key configuration fails fast. */
    @Bean
    @ConditionalOnMissingBean(MasterKeyProvider.class)
    MasterKeyProvider masterKeyProvider(VaultProperties properties, TinfirmProperties tinfirm) {
        return new EnvMasterKeyProvider(properties.keys(), properties.activeKey(), tinfirm.publicUrl());
    }
}
