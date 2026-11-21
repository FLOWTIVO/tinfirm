package com.flowtivo.tinfirm.runner;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A runner host's level of isolation (ADR-0113, decision 1), as it reports it in {@code hello.isolation.level}: named
 * for the strongest wall between an agent's code and the credentials. The server stores and shows it as reported, and
 * never checks it (decision 3).
 */
public enum IsolationLevel {

    /** Another machine: the credentials are on another machine than the VM. */
    @JsonProperty("machine")
    MACHINE("machine"),

    /** A jail around each VM: the credentials are on the same machine, out of a VMM's user's reach. */
    @JsonProperty("vm-jail")
    VM_JAIL("vm-jail"),

    /** The container and the kernel: the credentials are on the same machine, behind the kernel alone. */
    @JsonProperty("container")
    CONTAINER("container");

    private final String wire;

    IsolationLevel(String wire) {
        this.wire = wire;
    }

    /** The protocol's name: {@code machine}, {@code vm-jail} or {@code container}. */
    public String wire() {
        return wire;
    }

    /** The level stored under its {@link #name()}; null for null. */
    public static @Nullable IsolationLevel ofName(@Nullable String name) {
        return name != null ? valueOf(name) : null;
    }
}
