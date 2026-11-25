package com.flowtivo.tinfirm.runner;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A runner host as other modules see it, for the eligibility and dispatch of runs (the package 2 contract, §5.2).
 *
 * @param scope                    whose host it is: the company's, or a member's personal host
 * @param ownerPrincipalId         the member's principal that owns a personal host; null for a company host
 * @param personalHostId           the machine a personal host is the presence of in this company (ADR-0092): its
 *                                 slots, runtimes and credentials are the machine's, shared with its presences in the
 *                                 owner's other companies; null for a company host
 * @param maxConcurrentRuns        the effective slots as last confirmed (0 while it drains)
 * @param maxConcurrentRunsCeiling the slots the host reported in its latest {@code hello} (1 before it connected)
 * @param desiredMaxConcurrentRuns the slots set in Tinfirm, or null
 * @param draining                 whether the host last reported that it drains (meaningful while connected)
 * @param runtimes                 the runtimes whose images the host reported in its latest {@code hello}; null when
 *                                 it has not reported them, which counts as every runtime
 * @param runnerVersion            the version of its latest {@code hello}; null before it connected. Shown and logged
 *                                 only: what a host can do is its protocol and its features (ADR-0109)
 * @param lastSeenAt               when the host was last connected, or null if it never was
 * @param isolationLevel           the level of isolation its latest {@code hello} reported in
 *                                 {@code isolationTiers} (ADR-0113); null before its first hello to a server of this
 *                                 release, and such a host is placed as {@link IsolationLevel#CONTAINER} (decision 2).
 *                                 Reported, never checked (decision 3)
 * @param hardened                 the hardening it reported with the level; null exactly when the level is
 * @param stated                   whether the level rests on its operator's statement rather than on what the host can
 *                                 see; false without a level
 */
public record RunnerHostInfo(UUID id, UUID companyId, String name, HostScope scope, @Nullable UUID ownerPrincipalId,
        @Nullable UUID personalHostId, int maxConcurrentRuns, int maxConcurrentRunsCeiling, @Nullable Integer desiredMaxConcurrentRuns,
        boolean draining, @Nullable Set<RuntimeKind> runtimes, @Nullable String runnerVersion,
        @Nullable Instant lastSeenAt, @Nullable IsolationLevel isolationLevel, @Nullable Hardening hardened,
        boolean stated) {

    public RunnerHostInfo {
        runtimes = runtimes != null ? Set.copyOf(runtimes) : null;
    }

    /** A host whose isolation is not known: it reports no level. */
    public RunnerHostInfo(UUID id, UUID companyId, String name, HostScope scope, @Nullable UUID ownerPrincipalId,
            @Nullable UUID personalHostId, int maxConcurrentRuns, int maxConcurrentRunsCeiling,
            @Nullable Integer desiredMaxConcurrentRuns, boolean draining, @Nullable Set<RuntimeKind> runtimes,
            @Nullable String runnerVersion, @Nullable Instant lastSeenAt) {
        this(id, companyId, name, scope, ownerPrincipalId, personalHostId, maxConcurrentRuns, maxConcurrentRunsCeiling,
                desiredMaxConcurrentRuns, draining, runtimes, runnerVersion, lastSeenAt, null, null, false);
    }

    /** Whether the host can run the runtime: it reported its image, or reported no runtimes at all yet. */
    public boolean serves(RuntimeKind runtime) {
        return runtimes == null || runtimes.contains(runtime);
    }

    /** A host of one company, for tests and callers that name no {@code personalHostId} (ADR-0092). */
    public RunnerHostInfo(UUID id, UUID companyId, String name, HostScope scope, @Nullable UUID ownerPrincipalId,
            int maxConcurrentRuns, int maxConcurrentRunsCeiling, @Nullable Integer desiredMaxConcurrentRuns,
            boolean draining, @Nullable Set<RuntimeKind> runtimes, @Nullable String runnerVersion,
            @Nullable Instant lastSeenAt) {
        this(id, companyId, name, scope, ownerPrincipalId, null, maxConcurrentRuns, maxConcurrentRunsCeiling,
                desiredMaxConcurrentRuns, draining, runtimes, runnerVersion, lastSeenAt, null, null, false);
    }

    /** Whether this is a company host. */
    public boolean company() {
        return scope == HostScope.COMPANY;
    }

    /** Whether this is the personal host of the principal. */
    public boolean personalOf(UUID principalId) {
        return scope == HostScope.PERSONAL && principalId.equals(ownerPrincipalId);
    }

    /**
     * The host's slots for the dispatcher (CONVENTIONS §15.6): the last confirmed effective value, or the desired
     * value when that is lower.
     */
    public int dispatchLimit() {
        return Math.min(maxConcurrentRuns,
                desiredMaxConcurrentRuns != null ? desiredMaxConcurrentRuns : RunnerMessages.MAX_HOST_CAPACITY);
    }
}
