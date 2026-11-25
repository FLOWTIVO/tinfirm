package com.flowtivo.tinfirm.runner;

import java.util.Locale;

/**
 * A value of {@code hello.isolationTiers} (ADR-0113, decision 1, as the owner settled it on 2026-11-14): in protocol 2
 * the host's level of isolation, {@code machine}, {@code vm-jail} or {@code container}, with {@code gvisor} for a host
 * whose runtime is {@code runsc}, placed as {@code container}. The constants run from the strongest wall to the
 * weakest, so that a hello with several tiers is read as its weakest ({@link RunnerMessages.Hello#tier()}).
 * <p>
 * {@link #MICROVM} is no value of protocol 2 any more: a hello of protocol 2 that carries it is refused with
 * {@code UPGRADE_REQUIRED}, since only a Kata host or a host with a VM per run of an older release sends it. Protocol 1
 * keeps its tiers ({@code container}, {@code gvisor}, and {@code microvm}, which its schema allows and a host of
 * protocol 1 never sent: read as {@code container}, the level of a Kata host).
 */
public enum IsolationTier {

    MACHINE("machine", IsolationLevel.MACHINE, false),

    VM_JAIL("vm-jail", IsolationLevel.VM_JAIL, false),

    GVISOR("gvisor", IsolationLevel.CONTAINER, true),

    CONTAINER("container", IsolationLevel.CONTAINER, false),

    /** Protocol 1's Kata tier; refused in protocol 2. */
    MICROVM("microvm", IsolationLevel.CONTAINER, false);

    private final String wire;

    private final IsolationLevel level;

    private final boolean gvisor;

    IsolationTier(String wire, IsolationLevel level, boolean gvisor) {
        this.wire = wire;
        this.level = level;
        this.gvisor = gvisor;
    }

    /** The protocol's name. */
    public String wire() {
        return wire;
    }

    /** The level a host of this tier is placed at. */
    public IsolationLevel level() {
        return level;
    }

    /** Whether it is gVisor ({@code runsc}), a container host whose kernel is gVisor's. */
    public boolean gvisor() {
        return gvisor;
    }

    /** The tier of a protocol name the constraints allow. */
    public static IsolationTier ofWire(String wire) {
        return valueOf(wire.toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
