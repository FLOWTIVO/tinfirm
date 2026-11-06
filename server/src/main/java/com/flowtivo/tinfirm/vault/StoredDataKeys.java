package com.flowtivo.tinfirm.vault;

import java.util.List;

/**
 * Data keys that another module stores next to its own ciphertext ({@link DataKeys}): the session bundles' (ADR-0097).
 * The vault's rewrap ({@link VaultService#rewrapAll()}) rewraps them with the secrets' and the instance's keys, so that
 * a retired master key can go once the rewrap ran (ADR-0101). Both methods run in the vault's SYSTEM tenant context,
 * across companies, in the caller's transaction.
 */
public interface StoredDataKeys {

    /** The ids of the master keys that wrap one of these keys, other than {@code activeKeyId}. */
    List<String> keyIdsOtherThan(String activeKeyId);

    /**
     * Locks up to {@code limit} of the keys not wrapped under {@code activeKeyId}, wraps each again under the active
     * master key ({@link DataKeys#rewrap}) and stores it. Answers how many it rewrapped.
     */
    int rewrapBatch(String activeKeyId, int limit);
}
