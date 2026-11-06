package com.flowtivo.tinfirm.vault.internal;

import java.nio.charset.StandardCharsets;

/**
 * Additional authenticated data {@code instance|name|version} of a key of the instance ({@code instance_key}): the
 * company of a secret's AAD is replaced by the word {@code instance}, which no company id can equal.
 */
record InstanceKeyAad(String name, int version) implements Aad {

    @Override
    public byte[] bytes() {
        return ("instance|" + name + "|" + version).getBytes(StandardCharsets.UTF_8);
    }
}
