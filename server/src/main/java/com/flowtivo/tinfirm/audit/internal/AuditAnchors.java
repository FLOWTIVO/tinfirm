package com.flowtivo.tinfirm.audit.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.AuditAnchorStatus;

/**
 * The audit chains' heads anchored outside the database (ADR-0111, {@code AUDIT_ANCHOR}): signed checkpoints that a
 * worker writes to a sink the database's writer cannot change. A paid feature's code, under {@code ee/} (ADR-0077,
 * decision 1), which checks the licence in force. The core finds it when used ({@link AuditCheckService},
 * {@link AuditService}) and never names its implementation: without it the daily check compares nothing and the
 * status answers {@code 403 EDITION_REQUIRED}.
 * <p>
 * Each method runs in the caller's transaction and tenant context.
 */
interface AuditAnchors {

    /**
     * Compares the chain (the instance's when null) with the latest checkpoint that names it, read from the sink and
     * its signature verified where the sink can be read: empty when they agree, when no checkpoint names it, or while
     * the anchor is not in force.
     */
    Optional<Mismatch> compare(@Nullable UUID companyId);

    /** The latest checkpoint that names the chain, at or before {@code atOrBeforeSeq} when given. */
    Optional<Anchored> anchored(@Nullable UUID companyId, @Nullable Long atOrBeforeSeq);

    /** The anchor's status, for instance admins; {@code 403 EDITION_REQUIRED} while it is not in the licence. */
    AuditAnchorStatus status();

    /**
     * The chain disagrees with a checkpoint: the event at its {@code seq} is gone or has another hash.
     *
     * @param checkpoint the checkpoint's number
     * @param seq        the chain's head as the checkpoint names it
     */
    record Mismatch(long checkpoint, long seq) {
    }

    /** A chain's head as a checkpoint names it. */
    record Anchored(long checkpoint, Instant issuedAt, String keyId, long seq, String hash) {
    }
}
