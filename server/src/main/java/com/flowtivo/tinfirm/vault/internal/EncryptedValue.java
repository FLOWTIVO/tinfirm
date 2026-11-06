package com.flowtivo.tinfirm.vault.internal;

/**
 * One envelope-encrypted value: the value is encrypted under a random data key (DEK), and the DEK is wrapped under
 * the master key {@code keyId}. Both use AES-256-GCM with 96-bit random nonces.
 */
record EncryptedValue(String keyId, byte[] wrappedDek, byte[] dekNonce, byte[] valueNonce, byte[] ciphertext) {

    @Override
    public String toString() {
        return "EncryptedValue[keyId=" + keyId + "]";
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
