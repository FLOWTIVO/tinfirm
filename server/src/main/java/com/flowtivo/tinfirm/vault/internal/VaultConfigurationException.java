package com.flowtivo.tinfirm.vault.internal;

import java.io.Serial;

/** Invalid or missing master key configuration. Messages name variables and key ids, never key material. */
final class VaultConfigurationException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    VaultConfigurationException(String message) {
        super(message);
    }
}
