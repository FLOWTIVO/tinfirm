package com.flowtivo.tinfirm.vault.internal;

/**
 * Additional authenticated data of an envelope-encrypted value: binds the ciphertext and its wrapped data key to
 * their row, so that moving them to another row makes decryption fail. {@link SecretAad} for a company's secret,
 * {@link InstanceKeyAad} for a key of the instance.
 */
interface Aad {

    byte[] bytes();
}
