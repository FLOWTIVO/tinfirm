package com.flowtivo.tinfirm.runner;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Whether the machine that runs a host's VMs is hardened (ADR-0113, decision 5), as the host reports it in
 * {@code hello.isolation.hardened}: a mark beside the level, which orders hosts of one level and refuses none.
 */
public enum Hardening {

    /** The machine is hardened beyond the jail. */
    @JsonProperty("yes")
    YES("yes"),

    /** It is not: the jail is there, and the kernel offers an escaped process more to try. */
    @JsonProperty("no")
    NO("no"),

    /**
     * The host runs no machine of its own for its VMs (a VM per run at a provider): the provider's hypervisor is the
     * boundary, and Tinfirm sees no machine.
     */
    @JsonProperty("none")
    NONE("none");

    private final String wire;

    Hardening(String wire) {
        this.wire = wire;
    }

    /** The protocol's name: {@code yes}, {@code no} or {@code none}. */
    public String wire() {
        return wire;
    }

    /** The mark stored under its {@link #name()}; null for null. */
    public static @Nullable Hardening ofName(@Nullable String name) {
        return name != null ? valueOf(name) : null;
    }
}
