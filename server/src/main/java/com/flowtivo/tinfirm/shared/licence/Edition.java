package com.flowtivo.tinfirm.shared.licence;

/** The edition the instance runs as (ADR-0077, decision 3): Community without a key, Pro or Enterprise with one. */
public enum Edition {
    COMMUNITY, PRO, ENTERPRISE;

    /** Whether this edition holds everything {@code other} does. */
    public boolean includes(Edition other) {
        return compareTo(other) >= 0;
    }
}
