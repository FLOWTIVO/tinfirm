package com.flowtivo.tinfirm.vault.internal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Additional authenticated data {@code companyId|secretId|version}. It binds a ciphertext and its wrapped data key
 * to their row: moving them to another secret, version or company makes decryption fail.
 */
record SecretAad(UUID companyId, UUID secretId, int version) implements Aad {

    @Override
    public byte[] bytes() {
        return (companyId + "|" + secretId + "|" + version).getBytes(StandardCharsets.UTF_8);
    }
}
