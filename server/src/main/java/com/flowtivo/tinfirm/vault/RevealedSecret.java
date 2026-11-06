package com.flowtivo.tinfirm.vault;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A decrypted secret value. {@link #close()} wipes it; {@link #toString()} never shows it. */
public final class RevealedSecret implements AutoCloseable {

    private final byte[] value;

    public RevealedSecret(byte[] value) {
        this.value = value;
    }

    public String asString() {
        return new String(value, StandardCharsets.UTF_8);
    }

    public byte[] bytes() {
        return value.clone();
    }

    @Override
    public void close() {
        Arrays.fill(value, (byte) 0);
    }

    @Override
    public String toString() {
        return "RevealedSecret[***]";
    }
}
