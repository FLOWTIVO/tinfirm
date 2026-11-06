package com.flowtivo.tinfirm.model.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.vault.SecretKeeper;

/**
 * The model settings keep the admin key of a company's own gateway ({@code EXTERNAL}) in a company secret,
 * {@code MODEL_GATEWAY_ADMIN_KEY} ({@code LITELLM_ADMIN_KEY} when it was entered before ADR-0094; the package 2
 * contract, §3.1): the Secrets API neither deletes nor replaces it while they do. A delete would leave the gateway
 * without its key and the principals' keys unmanaged; a replaced value would point Tinfirm's management of the gateway
 * at another key without the test that turns the relay on. A new key is entered in the model settings instead, which
 * turn the relay off until the gateway passes its test again, and the settings delete the key when they no longer need
 * it.
 */
@Component
class ModelSecretKeeper implements SecretKeeper {

    private final ModelSettingsStore settings;

    ModelSecretKeeper(ModelSettingsStore settings) {
        this.settings = settings;
    }

    @Override
    public Optional<String> keeperOf(UUID companyId, UUID secretId) {
        return settings.keepsAdminKey(companyId, secretId)
                ? Optional.of("the company's model gateway: enter a new admin key or change the gateway in the "
                        + "model settings, which turn the relay off until the gateway passes its test again")
                : Optional.empty();
    }
}
