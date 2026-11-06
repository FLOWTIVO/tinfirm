package com.flowtivo.tinfirm.ee.licence;

import java.time.LocalDate;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.licence.Edition;

/**
 * What a licence key says (ADR-0077, decision 6): payload version 2, without the key id of its signer, which only
 * chooses the public key it is verified against.
 *
 * @param id        the licence's id
 * @param rev       the key's revision of the licence, from 1: seats bought during the year come as the next one
 * @param customer  the customer
 * @param edition   PRO or ENTERPRISE
 * @param seats     the seats; null only for an Enterprise key without a seat count
 * @param companies an Enterprise key's cap of declared companies; null for none, and always for Pro
 * @param startsAt  the first day (UTC), included
 * @param endsAt    the last day (UTC), included
 */
record LicencePayload(String id, int rev, String customer, Edition edition, @Nullable Integer seats,
        @Nullable Integer companies, LocalDate startsAt, LocalDate endsAt) {

    /** Whether the key is in force on {@code day}. */
    boolean inForce(LocalDate day) {
        return !day.isBefore(startsAt) && !day.isAfter(endsAt);
    }
}
