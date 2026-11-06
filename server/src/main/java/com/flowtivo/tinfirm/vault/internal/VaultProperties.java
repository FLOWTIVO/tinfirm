package com.flowtivo.tinfirm.vault.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param keys      {@code TINFIRM_VAULT_KEYS}: comma-separated {@code keyId:base64(32 bytes)}
 * @param activeKey {@code TINFIRM_VAULT_ACTIVE_KEY}: the key id used for new wraps
 */
@ConfigurationProperties("tinfirm.vault")
record VaultProperties(@Nullable String keys, @Nullable String activeKey) {

    @Override
    public String toString() {
        return "VaultProperties[keys=***, activeKey=" + activeKey + "]";
    }
}
