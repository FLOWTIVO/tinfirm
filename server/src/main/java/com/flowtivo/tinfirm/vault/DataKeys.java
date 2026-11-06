package com.flowtivo.tinfirm.vault;

import java.util.Arrays;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Data keys for content that another module encrypts itself (ADR-0097: the session bundles): a fresh 256-bit AES key
 * per object, wrapped with AES-256-GCM under the active master key, as the vault wraps a secret's data key
 * (CONVENTIONS §9), with the AAD {@code data-key|<context>}, which binds the wrapped key to its object. The caller
 * stores the wrapped form next to its ciphertext and closes the key to wipe it. A key wrapped under a master key that
 * is no longer configured cannot be unwrapped: keep a retired master key for as long as such content lives.
 */
public interface DataKeys {

    /** A fresh data key, and the same wrapped under the active master key for {@code context}. */
    DataKey create(String context);

    /**
     * The data key of {@code wrapped}, for the same {@code context}; a {@link RuntimeException} when the master key it
     * names is not configured or the wrapped key was changed or moved.
     */
    DataKey unwrap(String context, WrappedKey wrapped);

    /**
     * {@code wrapped} wrapped again under the active master key, for the same {@code context}, for the vault's rewrap
     * ({@link StoredDataKeys}); the key itself is the same. A {@link RuntimeException} as for {@link #unwrap}.
     */
    WrappedKey rewrap(String context, WrappedKey wrapped);

    /** A data key as it is stored: the master key's id, the wrapped key and its nonce. */
    record WrappedKey(String keyId, byte[] wrapped, byte[] nonce) {

        public WrappedKey {
            wrapped = wrapped.clone();
            nonce = nonce.clone();
        }

        @Override
        public byte[] wrapped() {
            return wrapped.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }
    }

    /** A data key in the clear, and its wrapped form. Close it to wipe the key. */
    final class DataKey implements AutoCloseable {

        private final byte[] key;

        private final WrappedKey wrapped;

        public DataKey(byte[] key, WrappedKey wrapped) {
            if (key.length != 32) {
                throw new IllegalArgumentException("A data key has 32 bytes");
            }
            this.key = key.clone();
            this.wrapped = wrapped;
        }

        /** The AES key. */
        public SecretKey secretKey() {
            return new SecretKeySpec(key, "AES");
        }

        public WrappedKey wrapped() {
            return wrapped;
        }

        @Override
        public void close() {
            Arrays.fill(key, (byte) 0);
        }
    }
}
