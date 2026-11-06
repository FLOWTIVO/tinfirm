package com.flowtivo.tinfirm.shared.licence;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Port: the licence in force and its seats (ADR-0077), implemented by {@code ee}. The core reads it through
 * {@link Licensing}, which answers Community when no implementation is present.
 */
public interface Licence {

    /** The licence in force now. */
    LicenceStatus status();

    /** The people who hold a seat now: the distinct active users with OWNER, ADMIN or MEMBER in any company. */
    int seatsUsed();

    /**
     * Whether {@code userId} may be given a role that holds a seat: they hold one already, no seat is counted, or one
     * is free. Called in the transaction that gives the role, which it serialises with every other seat check of the
     * instance until it ends, so that two grants never take the last seat twice.
     */
    boolean seatAvailableFor(UUID userId);

    /**
     * Whether the deactivated user may become active again: they hold no role that holds a seat, no seat is counted,
     * or one is free. Serialised as {@link #seatAvailableFor} is.
     */
    boolean seatAvailableForReactivation(UUID userId);

    /**
     * The seats a role may be given within: those the licence in force holds, and no more than a renewal held for
     * later holds, which takes over with its own; null when no seat is counted.
     */
    @Nullable Integer seatLimit();
}
