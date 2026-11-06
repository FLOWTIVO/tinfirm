package com.flowtivo.tinfirm.vault;

import java.util.Set;

import javax.crypto.SecretKey;

/**
 * Source of the master keys that wrap data keys. Tinfirm reads them from the environment
 * ({@code TINFIRM_VAULT_KEYS}, {@code TINFIRM_VAULT_ACTIVE_KEY}); HashiCorp Vault or a cloud KMS can be plugged in
 * later by providing another bean of this type.
 */
public interface MasterKeyProvider {

    /** The key id used to wrap new data keys. */
    String activeKeyId();

    /** All key ids that can unwrap data keys. */
    Set<String> keyIds();

    /**
     * The AES-256 master key with the given id.
     * @throws IllegalArgumentException if the key id is unknown
     */
    SecretKey key(String keyId);
}
