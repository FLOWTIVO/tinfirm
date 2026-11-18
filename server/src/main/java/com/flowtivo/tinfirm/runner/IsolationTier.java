package com.flowtivo.tinfirm.runner;

import java.util.Locale;

/**
 * The isolation tier of a host's sandboxes (ADR-0006, ADR-0025), as it reports it in {@code hello.isolationTiers}: the
 * technology, {@code container} (runc), {@code gvisor} (runsc) or {@code microvm} (Kata Containers, or a VM per run).
 * It does not say what an escape reaches; the level does ({@link IsolationLevel}, ADR-0113).
 */
public enum IsolationTier {
    CONTAINER, GVISOR, MICROVM;

    /** The protocol's name: {@code container}, {@code gvisor} or {@code microvm}. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The tier of a protocol name the schema allows. */
    public static IsolationTier ofWire(String wire) {
        return valueOf(wire.toUpperCase(Locale.ROOT));
    }
}
