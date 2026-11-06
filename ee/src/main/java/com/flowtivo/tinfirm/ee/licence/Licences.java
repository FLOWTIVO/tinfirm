package com.flowtivo.tinfirm.ee.licence;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.licence.Edition;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.LicenceState;
import com.flowtivo.tinfirm.shared.licence.LicenceStatus;

/**
 * The edition's logic (ADR-0077, decisions 4, 6 and 7): the licence that the key in force, and the next one held,
 * put in force on a day. One key is in force at a time; the next one takes over on its start date.
 * <ul>
 * <li>A key in force gives its edition, its seats (none counted for an Enterprise key without a seat count) and its
 * cap of companies (one under Pro).</li>
 * <li>{@code EXPIRING} from 30 days before its end, unless the next key starts by the day after it.</li>
 * <li>{@code GRACE} for 30 days after it ended: as in force.</li>
 * <li>{@code EXPIRED} after: Community, with decision 7's exceptions ({@link LicenceStatus#lapsedEdition()}): SAML
 * keeps working after Enterprise, the audit's export for 90 more days.</li>
 * </ul>
 */
final class Licences {

    static final int NOTICE_DAYS = 30;

    static final int GRACE_DAYS = 30;

    static final int AUDIT_EXPORT_DAYS = 90;

    private Licences() {
    }

    /** The features the edition holds. */
    static Set<Feature> features(Edition edition) {
        Set<Feature> features = EnumSet.noneOf(Feature.class);
        for (Feature feature : Feature.values()) {
            if (edition.includes(feature.edition())) {
                features.add(feature);
            }
        }
        return features;
    }

    /** The key that rules on {@code today}: the next one once it started, else the current one. */
    static @Nullable LicencePayload ruling(@Nullable LicencePayload current, @Nullable LicencePayload next,
            LocalDate today) {
        return next != null && !next.startsAt().isAfter(today) ? next : current;
    }

    static LicenceStatus status(@Nullable LicencePayload current, @Nullable LicencePayload next, LocalDate today) {
        LicencePayload key = ruling(current, next, today);
        if (key == null || key.startsAt().isAfter(today)) {
            return LicenceStatus.COMMUNITY;
        }
        LocalDate end = key.endsAt();
        if (!today.isAfter(end)) {
            boolean renewed = key != next && next != null && !next.startsAt().isAfter(end.plusDays(1));
            LicenceState state = !renewed && !end.isAfter(today.plusDays(NOTICE_DAYS)) ? LicenceState.EXPIRING
                    : LicenceState.ACTIVE;
            return paid(key, state);
        }
        if (!today.isAfter(end.plusDays(GRACE_DAYS))) {
            return paid(key, LicenceState.GRACE);
        }
        Set<Feature> kept = key.edition() == Edition.ENTERPRISE ? Set.of(Feature.SAML) : Set.of();
        return new LicenceStatus(Edition.COMMUNITY, LicenceState.EXPIRED, key.customer(), end,
                end.plusDays(GRACE_DAYS), end.plusDays(GRACE_DAYS + AUDIT_EXPORT_DAYS), null, 1, kept,
                key.edition());
    }

    private static LicenceStatus paid(LicencePayload key, LicenceState state) {
        LocalDate end = key.endsAt();
        Integer companies = key.edition() == Edition.ENTERPRISE ? key.companies() : Integer.valueOf(1);
        return new LicenceStatus(key.edition(), state, key.customer(), end, end.plusDays(GRACE_DAYS),
                end.plusDays(GRACE_DAYS + AUDIT_EXPORT_DAYS), key.seats(), companies, features(key.edition()), null);
    }
}
