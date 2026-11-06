package com.flowtivo.tinfirm.shared.licence;

import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * The core's reading of the licence (ADR-0077): the edition's gates ({@code 403 EDITION_REQUIRED}) and the seat check
 * ({@code 409 SEATS_EXHAUSTED}). The licence itself is {@code ee}'s ({@link Licence}), looked up when used; without it
 * the server runs as Community, which gates every paid feature and counts no seat.
 */
@Component
public class Licensing {

    private final ObjectProvider<Licence> licence;

    public Licensing(ObjectProvider<Licence> licence) {
        this.licence = licence;
    }

    public LicenceStatus status() {
        Licence current = licence.getIfAvailable();
        return current != null ? current.status() : LicenceStatus.COMMUNITY;
    }

    public boolean has(Feature feature) {
        return status().has(feature);
    }

    /** {@code 403 EDITION_REQUIRED} unless the feature is in force now. */
    public void require(Feature feature) {
        if (!has(feature)) {
            throw editionRequired(feature);
        }
    }

    public static ApiException editionRequired(Feature feature) {
        return ApiException.forbidden(ProblemCode.EDITION_REQUIRED,
                "This needs the " + feature.edition().name() + " edition (" + feature.name() + ")",
                Map.of("details", Map.of("feature", feature.name(), "edition", feature.edition().name())));
    }

    /** Whether the role holds a seat under a licence that counts them (decision 5): every role but VIEWER. */
    public static boolean holdsSeat(CompanyRole role) {
        return role != CompanyRole.VIEWER;
    }

    /**
     * Whether {@code userId} may be given {@code role}: VIEWER always; a role that holds a seat when they hold one
     * already, no seat is counted, or one is free ({@link Licence#seatAvailableFor}).
     */
    public boolean seatAvailableFor(UUID userId, CompanyRole role) {
        if (!holdsSeat(role)) {
            return true;
        }
        Licence current = licence.getIfAvailable();
        return current == null || current.seatAvailableFor(userId);
    }

    /** {@code 409 SEATS_EXHAUSTED} unless {@code userId} may be given {@code role} ({@link #seatAvailableFor}). */
    public void requireSeat(UUID userId, CompanyRole role) {
        if (!seatAvailableFor(userId, role)) {
            throw seatsExhausted();
        }
    }

    /**
     * {@code 409 SEATS_EXHAUSTED} unless the deactivated user may become active again
     * ({@link Licence#seatAvailableForReactivation}).
     */
    public void requireSeatForReactivation(UUID userId) {
        if (!seatAvailableForReactivation(userId)) {
            throw seatsExhausted();
        }
    }

    /**
     * Whether the deactivated user may become active again with the roles they hold
     * ({@link Licence#seatAvailableForReactivation}): SCIM gives a Viewer's role instead when not (ADR-0087, decision
     * 8).
     */
    public boolean seatAvailableForReactivation(UUID userId) {
        Licence current = licence.getIfAvailable();
        return current == null || current.seatAvailableForReactivation(userId);
    }

    private ApiException seatsExhausted() {
        Licence current = licence.getIfAvailable();
        int used = current != null ? current.seatsUsed() : 0;
        Integer licensed = current != null ? current.seatLimit() : null;
        return ApiException.conflict(ProblemCode.SEATS_EXHAUSTED,
                "Every seat of the licence is taken: make a member a Viewer, or add a key with more seats",
                Map.of("details", Map.of("licensed", licensed != null ? licensed : used, "used", used)));
    }
}
