package com.flowtivo.tinfirm.vault.internal;

import java.io.Serial;

/**
 * Encryption or decryption failed (a wrong or missing master key, tampered data). Never carries key material or
 * plaintext.
 */
final class VaultCryptoException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    VaultCryptoException(String message) {
        super(message);
    }

    VaultCryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
