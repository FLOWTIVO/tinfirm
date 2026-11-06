package com.flowtivo.tinfirm.vault.internal;

import java.security.SecureRandom;
import java.util.Arrays;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.vault.DataKeys;

/** {@link DataKeys} with the vault's envelope cipher. */
@Component
class DataKeysImpl implements DataKeys {

    private static final int KEY_BYTES = 32;

    private final EnvelopeCipher cipher;

    private final SecureRandom random = new SecureRandom();

    DataKeysImpl(EnvelopeCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public DataKey create(String context) {
        byte[] key = new byte[KEY_BYTES];
        random.nextBytes(key);
        try {
            EnvelopeCipher.WrappedKey wrapped = cipher.wrapKey(new DataKeyAad(context), key);
            return new DataKey(key, new WrappedKey(wrapped.keyId(), wrapped.wrapped(), wrapped.nonce()));
        }
        finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public WrappedKey rewrap(String context, WrappedKey wrapped) {
        DataKeyAad aad = new DataKeyAad(context);
        byte[] key = cipher.unwrapKey(aad,
                new EnvelopeCipher.WrappedKey(wrapped.keyId(), wrapped.wrapped(), wrapped.nonce()));
        try {
            EnvelopeCipher.WrappedKey again = cipher.wrapKey(aad, key);
            return new WrappedKey(again.keyId(), again.wrapped(), again.nonce());
        }
        finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    @Override
    public DataKey unwrap(String context, WrappedKey wrapped) {
        byte[] key = cipher.unwrapKey(new DataKeyAad(context),
                new EnvelopeCipher.WrappedKey(wrapped.keyId(), wrapped.wrapped(), wrapped.nonce()));
        try {
            return new DataKey(key, wrapped);
        }
        finally {
            Arrays.fill(key, (byte) 0);
        }
    }
}
