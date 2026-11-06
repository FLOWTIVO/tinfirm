package com.flowtivo.tinfirm.runner.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** Read models of the runner API, with every display value resolved. */
final class RunnerViews {

    private RunnerViews() {
    }

    /**
     * {@code maxConcurrentRuns} is the effective value as last confirmed (0 while draining); {@code draining} is
     * true only while the host is connected. A company host has no owner; a personal host lists the credentials it
     * reported (a company host holds none).
     */
    record HostDetails(UUID id, String name, String scope, @Nullable UUID ownerPrincipalId,
            @Nullable String ownerDisplay, boolean online, boolean draining, @Nullable Instant lastSeenAt,
            @Nullable String version, @Nullable String hostname, @Nullable String os,
            @Nullable String arch, int maxConcurrentRuns, int maxConcurrentRunsCeiling,
            @Nullable Integer desiredMaxConcurrentRuns, boolean configPending, int activeRuns,
            List<CredentialDetails> credentials, Instant createdAt, @Nullable PoolDetails pool) {
    }

    /**
     * A company host's sandbox pool (ADR-0099): its settings (null: none), the operator's ceiling, the count now and
     * its next change, the week's change points (the count now first), and the host's last report.
     */
    record PoolDetails(@Nullable PoolSettingsView settings, int maxReadyCeiling, int readyTarget,
            @Nullable PoolChangeView next, List<PoolChangeView> week, @Nullable PoolStatusView status) {
    }

    record PoolSettingsView(@Nullable String zone, int readyDefault, List<PoolWindowView> windows) {
    }

    record PoolWindowView(List<String> days, String from, String to, int ready, @Nullable String zone) {
    }

    record PoolChangeView(Instant at, int readyTarget) {
    }

    record PoolStatusView(int ready, int starting, int busy, @Nullable Long coldStartP90Seconds, int servedReady,
            int servedCold, Instant reportedAt) {
    }

    /** A personal host's credential; {@code status} is {@code OFFLINE} while the host is not connected. */
    record CredentialDetails(String label, String kind, List<String> runtimes, String status,
            @Nullable Instant cooldownUntil, boolean reported) {
    }
}
