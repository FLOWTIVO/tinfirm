package com.flowtivo.tinfirm.vault.internal;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.vault.MasterKeyProvider;

/**
 * Envelope encryption (CONVENTIONS §9). Each value gets a fresh 256-bit data key (DEK); the value is encrypted with
 * AES-256-GCM under the DEK and the DEK with AES-256-GCM under the active master key, both with a random 96-bit
 * nonce and the AAD of the row ({@code companyId|secretId|version} for a secret, {@code instance|name|version} for a key
 * of the instance). Plaintext and DEK buffers are wiped after use.
 */
@Component
class EnvelopeCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private static final int DEK_BYTES = 32;

    private static final int NONCE_BYTES = 12;

    private static final int TAG_BITS = 128;

    private final MasterKeyProvider masterKeys;

    private final SecureRandom random = new SecureRandom();

    EnvelopeCipher(MasterKeyProvider masterKeys) {
        this.masterKeys = masterKeys;
    }

    EncryptedValue encrypt(Aad aad, byte[] plaintext) {
        byte[] dek = new byte[DEK_BYTES];
        random.nextBytes(dek);
        try {
            byte[] valueNonce = nonce();
            byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), valueNonce, aad, plaintext);
            return wrap(aad, dek, valueNonce, ciphertext);
        }
        finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    byte[] decrypt(Aad aad, EncryptedValue value) {
        byte[] dek = unwrap(aad, value);
        try {
            return gcm(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), value.valueNonce(), aad, value.ciphertext());
        }
        finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** Re-wraps the data key under the active master key. The value ciphertext is unchanged. */
    EncryptedValue rewrap(Aad aad, EncryptedValue value) {
        byte[] dek = unwrap(aad, value);
        try {
            return wrap(aad, dek, value.valueNonce(), value.ciphertext());
        }
        finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    /** A data key wrapped under a master key: the key's id, the wrapped bytes and their nonce. */
    record WrappedKey(String keyId, byte[] wrapped, byte[] nonce) {
    }

    /** Wraps a data key of another module's ({@link DataKeysImpl}) under the active master key. */
    WrappedKey wrapKey(Aad aad, byte[] dek) {
        String keyId = masterKeys.activeKeyId();
        byte[] dekNonce = nonce();
        return new WrappedKey(keyId, gcm(Cipher.ENCRYPT_MODE, masterKeys.key(keyId), dekNonce, aad, dek), dekNonce);
    }

    /** The data key of {@link #wrapKey}, unwrapped under the master key it names. */
    byte[] unwrapKey(Aad aad, WrappedKey wrapped) {
        if (!masterKeys.keyIds().contains(wrapped.keyId())) {
            throw new VaultCryptoException("Decryption failed: the master key '" + wrapped.keyId()
                    + "' is not configured");
        }
        return gcm(Cipher.DECRYPT_MODE, masterKeys.key(wrapped.keyId()), wrapped.nonce(), aad, wrapped.wrapped());
    }

    private EncryptedValue wrap(Aad aad, byte[] dek, byte[] valueNonce, byte[] ciphertext) {
        String keyId = masterKeys.activeKeyId();
        byte[] dekNonce = nonce();
        byte[] wrappedDek = gcm(Cipher.ENCRYPT_MODE, masterKeys.key(keyId), dekNonce, aad, dek);
        return new EncryptedValue(keyId, wrappedDek, dekNonce, valueNonce, ciphertext);
    }

    /**
     * The data key, unwrapped under the master key it names; a master key that is not configured (dropped before its
     * data keys were rewrapped) fails as any other decryption does.
     */
    private byte[] unwrap(Aad aad, EncryptedValue value) {
        if (!masterKeys.keyIds().contains(value.keyId())) {
            throw new VaultCryptoException("Decryption failed: the master key '" + value.keyId()
                    + "' is not configured");
        }
        return gcm(Cipher.DECRYPT_MODE, masterKeys.key(value.keyId()), value.dekNonce(), aad, value.wrappedDek());
    }

    private byte[] nonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        return nonce;
    }

    private static byte[] gcm(int mode, SecretKey key, byte[] nonce, Aad aad, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad.bytes());
            return cipher.doFinal(input);
        }
        catch (GeneralSecurityException ex) {
            throw new VaultCryptoException(mode == Cipher.ENCRYPT_MODE ? "Encryption failed"
                    : "Decryption failed: wrong master key or tampered data", ex);
        }
    }
}
