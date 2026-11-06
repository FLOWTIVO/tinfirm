package com.flowtivo.tinfirm.shared.licence;

/**
 * Where the instance's licence stands (ADR-0077, decision 7).
 * <ul>
 * <li>{@code NONE}: no key, Community.</li>
 * <li>{@code ACTIVE}: a key in force.</li>
 * <li>{@code EXPIRING}: in force, 30 days or fewer before its end, and no renewal follows it.</li>
 * <li>{@code GRACE}: the 30 days after the end, in which everything keeps working.</li>
 * <li>{@code EXPIRED}: after the grace: Community, with decision 7's exceptions.</li>
 * </ul>
 */
public enum LicenceState {
    NONE, ACTIVE, EXPIRING, GRACE, EXPIRED
}
