package com.flowtivo.tinfirm.shared.net;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The host names that guarded outbound requests are being sent to right now (CONVENTIONS §14.4), which the JVM's
 * resolver ({@link GuardedResolverProvider}) answers without loopback, link-local, private, shared, unique-local,
 * unspecified and multicast addresses. The connection module's outbound client and the identity module's fetch of
 * OAuth client metadata documents open a registration for the host of each request while they send it (retries
 * included), unless private addresses are allowed, and close it after the answer was read; a host stays registered as
 * long as any request to it is in flight. Other lookups of the JVM (the database, the identity provider, anything
 * else) are only filtered while they name such a host at that very moment, and then lose only addresses no guarded
 * request may reach anyway.
 * <p>
 * A request to one of the operator's internal hosts (ADR-0066) registers its host at
 * {@link AddressGuard.Reach#PRIVATE_NETWORK}, whose lookups keep the private addresses and lose the others. A host's
 * lookups keep only the addresses that every request in flight to it may reach: the two reaches share none, so while
 * requests of both are in flight to one host it resolves to nothing.
 */
public final class GuardedHosts {

    /** The registry the JVM's resolver reads. */
    public static final GuardedHosts GLOBAL = new GuardedHosts();

    /** The requests in flight per host that may reach public addresses only. */
    private final ConcurrentHashMap<String, Integer> inFlight = new ConcurrentHashMap<>();

    /** The requests in flight per host that may also reach private addresses (ADR-0066). */
    private final ConcurrentHashMap<String, Integer> privateNetwork = new ConcurrentHashMap<>();

    /** An open registration of one host; closing it twice is harmless. */
    public interface Registration extends AutoCloseable {

        /** No registration: the operator allows private addresses. */
        Registration NONE = () -> {
        };

        @Override
        void close();
    }

    /** Registers the host for the duration of a request to it that may reach public addresses only. */
    public Registration open(String host) {
        return register(inFlight, host);
    }

    /**
     * Registers the host for the duration of a request within the reach: none for {@code ANY}, which the resolver
     * does not filter.
     */
    public Registration open(String host, AddressGuard.Reach reach) {
        return switch (reach) {
            case PUBLIC -> register(inFlight, host);
            case PRIVATE_NETWORK -> register(privateNetwork, host);
            case ANY -> Registration.NONE;
        };
    }

    private static Registration register(ConcurrentHashMap<String, Integer> registry, String host) {
        String key = normalize(host);
        registry.merge(key, 1, Integer::sum);
        return new Registration() {

            private boolean closed;

            @Override
            public synchronized void close() {
                if (!closed) {
                    closed = true;
                    registry.computeIfPresent(key, (name, count) -> count > 1 ? count - 1 : null);
                }
            }
        };
    }

    /** Whether a request to the host is in flight, so its lookups must leave out the guarded addresses. */
    public boolean guarded(String host) {
        return !reaches(host).isEmpty();
    }

    /**
     * The reaches of the requests in flight to the host, whose lookups keep only the addresses every one of them
     * allows; empty when none is.
     */
    public Set<AddressGuard.Reach> reaches(String host) {
        if (inFlight.isEmpty() && privateNetwork.isEmpty()) {
            return Set.of();
        }
        String key = normalize(host);
        Set<AddressGuard.Reach> reaches = EnumSet.noneOf(AddressGuard.Reach.class);
        if (inFlight.containsKey(key)) {
            reaches.add(AddressGuard.Reach.PUBLIC);
        }
        if (privateNetwork.containsKey(key)) {
            reaches.add(AddressGuard.Reach.PRIVATE_NETWORK);
        }
        return reaches;
    }

    /** Host names compare ignoring case and a trailing dot (the root of a fully qualified name). */
    public static String normalize(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
    }
}
