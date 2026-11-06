package com.flowtivo.tinfirm.audit.internal;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** An audit event as stored in {@code audit_event}; {@code detailsJson} is the canonical details JSON. */
record StoredAuditEvent(UUID id, @Nullable UUID companyId, long seq, Instant occurredAt, String actorType,
        @Nullable UUID actorId, String action, @Nullable String targetType, @Nullable String targetId,
        String detailsJson, String prevHash, String hash) {

    AuditCanonicalForm.Content content() {
        return new AuditCanonicalForm.Content(companyId, seq, occurredAt, actorType, actorId, action, targetType,
                targetId, AuditCanonicalForm.parseDetails(detailsJson));
    }
}
