package com.flowtivo.tinfirm.company;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.flowtivo.tinfirm.shared.access.CompanyRole;

/**
 * The new users of identity providers who joined a company as VIEWER because every seat was taken (ADR-0077,
 * decision 5), for the inbox of its Owners and Admins. Runs in the caller's transaction and the company's tenant
 * context.
 */
public interface SeatFallbacks {

    /** Those who joined since {@code since} and are Viewers still, the first to join first. */
    List<Fallback> since(UUID companyId, Instant since, int limit);

    /**
     * @param role     the role their provider gives, which holds a seat
     * @param provider the provider's registration id
     */
    record Fallback(UUID userId, CompanyRole role, String provider, Instant joinedAt) {
    }
}
