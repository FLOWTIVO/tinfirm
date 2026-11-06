package com.flowtivo.tinfirm.audit.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SQL that only the audit's tools run (ADR-0077, decision 1, {@code AUDIT_TOOLS}): the range of an export by time
 * ({@link AuditExport}) and the acknowledgement of a failed check ({@link AuditCheckResults}). Under {@code ee/},
 * beside {@link AuditEventStore} and {@link AuditCheckStore}, whose chains, pages and daily checks every edition runs.
 */
@Repository
class AuditToolsStore {

    private final JdbcClient jdbc;

    AuditToolsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The first position at or after the time; empty when no event is. */
    Optional<Long> firstSeqFrom(UUID companyId, Instant from) {
        return jdbc.sql("SELECT min(seq) FROM audit_event WHERE company_id = ? AND occurred_at >= ?")
            .params(companyId, from.atOffset(ZoneOffset.UTC))
            .query((rs, row) -> rs.getObject(1, Long.class))
            .list()
            .stream()
            .filter(Objects::nonNull)
            .findFirst();
    }

    /** The last position before the time; empty when no event is. */
    Optional<Long> lastSeqBefore(UUID companyId, Instant to) {
        return jdbc.sql("SELECT max(seq) FROM audit_event WHERE company_id = ? AND occurred_at < ?")
            .params(companyId, to.atOffset(ZoneOffset.UTC))
            .query((rs, row) -> rs.getObject(1, Long.class))
            .list()
            .stream()
            .filter(Objects::nonNull)
            .findFirst();
    }

    /** Marks a failed check acknowledged, once; false when it was acknowledged already. */
    boolean acknowledge(UUID checkId, Instant at, UUID userId, String note) {
        return jdbc.sql("""
                UPDATE audit_check SET acknowledged_at = ?, acknowledged_by = ?, acknowledged_note = ?
                 WHERE id = ? AND NOT ok AND acknowledged_at IS NULL""")
            .params(at.atOffset(ZoneOffset.UTC), userId, note, checkId)
            .update() == 1;
    }
}
