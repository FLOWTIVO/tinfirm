package com.flowtivo.tinfirm.model.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.flowtivo.tinfirm.shared.config.PublishedDevSecrets;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;

/**
 * The model module's settings ({@code tinfirm.model.*}). The LiteLLM master key that {@code deploy/compose/.env.example}
 * publishes stops the start unless {@code TINFIRM_PUBLIC_URL} is a localhost URL (L17): with it, anyone who read the
 * repository controls the gateway and its provider keys.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ModelProperties.class)
class ModelConfiguration {

    ModelConfiguration(ModelProperties properties, TinfirmProperties tinfirm) {
        if (PublishedDevSecrets.isPublishedLiteLlmMasterKey(properties.litellmMasterKey())) {
            PublishedDevSecrets.refuseOffLocalhost(tinfirm.publicUrl(), "TINFIRM_LITELLM_MASTER_KEY",
                    "Generate a key of your own (`echo \"sk-$(openssl rand -hex 32)\"`) and give it to LiteLLM and "
                            + "the server alike.");
        }
    }
}
