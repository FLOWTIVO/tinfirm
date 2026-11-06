package com.flowtivo.tinfirm.run.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.runner.RunnerMessages;

/**
 * Keeping session bundles (ADR-0097), part of elastic runners, Pro's (ADR-0100, {@code ELASTIC_RUNNERS}): a paid
 * feature's code, under {@code ee/} (ADR-0077, decision 1), which checks the licence in force and also serves the
 * host's upload and download. The core finds it when used ({@link RunDelivery}) and never names its implementation:
 * without {@code ee/}, or without the feature, a run gets no bundle and its host keeps its session homes itself.
 * The bundles that exist are the core's in every edition: a purge of their conversation's content, a run that reports
 * its session without one, and the sweep delete them ({@link SessionBundleCleanup}).
 */
interface SessionBundles {

    /**
     * {@code run.assign.sessionBundle} for the run on a host of this scope that does or does not list the feature:
     * absent unless the server keeps bundles for it; with the bundle to restore when there is one of the run's
     * {@code resumeSessionId}, of its runtime. In the company's transaction.
     */
    RunnerMessages.@Nullable SessionBundle forAssignment(UUID conversationId, UUID agentId, String runtime,
            HostScope scope, boolean feature, @Nullable String resumeSessionId);
}
