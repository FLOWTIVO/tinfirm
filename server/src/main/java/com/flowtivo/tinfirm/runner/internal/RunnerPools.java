package com.flowtivo.tinfirm.runner.internal;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.RunnerHostPoolSettings;

/**
 * Tinfirm's schedule of ready sandboxes (ADR-0099), part of elastic runners, Pro's (ADR-0100,
 * {@code ELASTIC_RUNNERS}): a paid feature's code, under {@code ee/} (ADR-0077, decision 1), which checks the licence
 * in force. It evaluates each company host's schedule and sends the count in {@code host.config.pool}, every minute
 * too. The core finds it when used ({@link RunnerHostService}, {@link RunnerHostSessions}) and never names its
 * implementation: without {@code ee/}, a pool's settings are {@code 403 EDITION_REQUIRED} and a host's page shows no
 * pool. What a host reports of its pool is the core's in every edition ({@link RunnerHostStore}).
 */
interface RunnerPools {

    /**
     * Evaluates the host's schedule now and stores its count and next change for {@code host.config.pool}; true when
     * they changed and {@code config_version} moved on. Nothing for a host that keeps no ready sandboxes. In the
     * company's transaction.
     */
    boolean refresh(UUID hostId);

    /**
     * Sets a company host's pool settings ({@code settings}), or clears them (null), checked against its pool size,
     * after the caller's access check and in its transaction, with the host row locked. Answers null when they change
     * nothing; else the old and new settings for the audit, the new ones stored and {@code config_version} moved on.
     */
    @Nullable Change update(RunnerHostStore.HostRow host, @Nullable RunnerHostPoolSettings settings, int poolSize);

    /** The pools of these hosts of the company as their page shows them, evaluated now; a host without one is absent. */
    Map<UUID, RunnerViews.PoolDetails> details(UUID companyId, Collection<UUID> hostIds);

    /** A change of a pool's settings, for the audit: their values, never a person's. */
    record Change(@Nullable Map<String, Object> oldPool, @Nullable Map<String, Object> newPool) {
    }
}
