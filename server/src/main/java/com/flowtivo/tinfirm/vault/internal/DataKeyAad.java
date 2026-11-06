package com.flowtivo.tinfirm.vault.internal;

import java.nio.charset.StandardCharsets;

/**
 * Additional authenticated data {@code data-key|<context>} of a data key another module wraps
 * ({@link com.flowtivo.tinfirm.vault.DataKeys}): the prefix keeps it apart from a secret's and an instance key's AAD.
 */
record DataKeyAad(String context) implements Aad {

    @Override
    public byte[] bytes() {
        return ("data-key|" + context).getBytes(StandardCharsets.UTF_8);
    }
}
