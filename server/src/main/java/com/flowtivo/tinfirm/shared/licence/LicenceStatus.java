package com.flowtivo.tinfirm.shared.licence;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The licence in force at one moment (ADR-0077): what {@code GET /api/licence} answers and what the gates read.
 *
 * @param edition           the edition the instance runs as now
 * @param state             where the licence stands
 * @param customer          the customer the keys name; null without a key
 * @param endsAt            the last day of the key in force, or of the one that ended; null without a key
 * @param graceEndsAt       the last day of the grace, 30 days after {@code endsAt}
 * @param auditExportUntil  the last day the audit's export stays after the licence expired, 90 days after the grace
 * @param seatsLicensed     the seats the key in force holds; null when no seat is counted
 * @param companiesLicensed the declared companies the instance may run; null for no cap
 * @param features          the paid features in force now
 * @param lapsedEdition     the edition of the keys that expired, which decision 7's exceptions follow; null unless
 *                          {@code EXPIRED}
 */
public record LicenceStatus(Edition edition, LicenceState state, @Nullable String customer,
        @Nullable LocalDate endsAt, @Nullable LocalDate graceEndsAt, @Nullable LocalDate auditExportUntil,
        @Nullable Integer seatsLicensed, @Nullable Integer companiesLicensed, Set<Feature> features,
        @Nullable Edition lapsedEdition) {

    /** Community without any key: nothing paid, nothing counted, one declared company. */
    public static final LicenceStatus COMMUNITY = new LicenceStatus(Edition.COMMUNITY, LicenceState.NONE, null, null,
            null, null, null, 1, Set.of(), null);

    public LicenceStatus {
        features = features.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(features));
    }

    public boolean has(Feature feature) {
        return features.contains(feature);
    }

    /** Whether the roles that hold a seat are counted against the licence (decision 5): a key with a seat count. */
    public boolean countsSeats() {
        return seatsLicensed != null;
    }

    /** Whether the licence expired after its grace (decision 7.3), so that its exceptions apply. */
    public boolean expired() {
        return state == LicenceState.EXPIRED;
    }
}
