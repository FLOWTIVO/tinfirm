package com.flowtivo.tinfirm.runner;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A runner host whose connection has been authenticated by its token.
 * <p>
 * A company host is placed: {@code runnerHostId} and {@code companyId} are its row's. A member's personal host is a
 * machine of its owner's with a presence in every company the owner belongs to (ADR-0092): as authenticated it is
 * unplaced ({@code companyId} null, {@code runnerHostId} the machine's id), and {@link #in} places it in one of its
 * companies, where the run side sees the presence as any host of that company. Every call that concerns a company
 * takes a placed host.
 *
 * @param runnerHostId    the row the run side names: a company host's, a presence's, or, unplaced, the machine's id
 * @param companyId       the company the host is placed in; null for a machine as authenticated
 * @param personalHostId  the machine's id for a personal host; null for a company host
 * @param presences       the host's rows by company: one for a company host, one per company for a machine
 * @param protocolVersion the runner protocol the connection speaks, from the host's {@code hello}: 1 or 2. A host
 *                        that has not said hello yet, and a host's request outside the WebSocket, count as 1.
 */
public record ConnectedHost(UUID runnerHostId, @Nullable UUID companyId, @Nullable UUID personalHostId,
        Map<UUID, UUID> presences, int protocolVersion) {

    public ConnectedHost {
        presences = Map.copyOf(presences);
    }

    /** A company host before its {@code hello}, or outside the WebSocket: protocol v1. */
    public ConnectedHost(UUID runnerHostId, UUID companyId) {
        this(runnerHostId, companyId, RunnerMessages.PROTOCOL_V1);
    }

    /** A company host speaking the version. */
    public ConnectedHost(UUID runnerHostId, UUID companyId, int protocolVersion) {
        this(runnerHostId, companyId, null, Map.of(companyId, runnerHostId), protocolVersion);
    }

    /**
     * A machine as authenticated: unplaced, with its presences.
     *
     * @param presences the presence's id by company
     */
    public static ConnectedHost machine(UUID personalHostId, Map<UUID, UUID> presences) {
        return new ConnectedHost(personalHostId, null, personalHostId, presences, RunnerMessages.PROTOCOL_V1);
    }

    /** The same host speaking the version of its {@code hello}. */
    public ConnectedHost speaking(int version) {
        return new ConnectedHost(runnerHostId, companyId, personalHostId, presences, version);
    }

    /** The same host with its presences as they are now (a machine's owner joined a company meanwhile). */
    public ConnectedHost withPresences(Map<UUID, UUID> current) {
        return new ConnectedHost(runnerHostId, companyId, personalHostId, current, protocolVersion);
    }

    /** Whether the connection speaks protocol v2 (CONVENTIONS §14.10). */
    public boolean v2() {
        return protocolVersion >= RunnerMessages.PROTOCOL_V2;
    }

    /** Whether the host is a member's machine (ADR-0092) rather than a company host. */
    public boolean personal() {
        return personalHostId != null;
    }

    /** Whether the host is placed in a company. */
    public boolean placed() {
        return companyId != null;
    }

    /** The company the host is placed in; only for a placed host. */
    public UUID company() {
        return Objects.requireNonNull(companyId, "The host is not placed in a company");
    }

    /** The key of the host's connection: the machine's id, or the company host's. */
    public UUID machineId() {
        return personalHostId != null ? personalHostId : runnerHostId;
    }

    /** The host placed in the company, as its presence there; empty when it has none. */
    public Optional<ConnectedHost> in(UUID company) {
        UUID presence = presences.get(company);
        return presence == null ? Optional.empty()
                : Optional.of(new ConnectedHost(presence, company, personalHostId, presences, protocolVersion));
    }

    /** The host placed in each of its companies, in the order of their ids. */
    public List<ConnectedHost> placements() {
        return new TreeMap<>(presences).keySet().stream().map(company -> in(company).orElseThrow()).toList();
    }

    /** Whether the row with this id is the host's: its own, its machine's, or one of its presences'. */
    public boolean owns(UUID hostId) {
        return hostId.equals(runnerHostId) || hostId.equals(personalHostId) || presences.containsValue(hostId);
    }
}
