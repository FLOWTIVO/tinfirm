package com.flowtivo.tinfirm.conversation.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * A member's dismissals of the notices of their inbox ({@code inbox_dismissal}, V041; CONVENTIONS §15.7), by the
 * notice's key. Row level security limits every statement to the company of the tenant context.
 */
@Repository
class InboxDismissalStore {

    private final JdbcClient jdbc;

    InboxDismissalStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The keys the member dismissed. */
    Set<String> keys(UUID companyId, UUID principalId) {
        return new HashSet<>(jdbc.sql("SELECT notice_key FROM inbox_dismissal WHERE company_id = ? AND principal_id = ?")
            .params(companyId, principalId)
            .query(String.class)
            .list());
    }

    /** Dismisses the notice for the member; a second dismissal of it changes nothing. */
    void dismiss(UUID companyId, UUID principalId, String noticeKey, Instant at) {
        jdbc.sql("""
                INSERT INTO inbox_dismissal (company_id, principal_id, notice_key, dismissed_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (principal_id, notice_key) DO NOTHING""")
            .params(companyId, principalId, noticeKey, ts(at))
            .update();
    }

    /** Shows the notice to the member again; answers whether they had dismissed it. */
    boolean restore(UUID companyId, UUID principalId, String noticeKey) {
        return jdbc.sql("DELETE FROM inbox_dismissal WHERE company_id = ? AND principal_id = ? AND notice_key = ?")
            .params(companyId, principalId, noticeKey)
            .update() > 0;
    }

    /**
     * Removes every dismissal of the member, in the company of the tenant context: they left, or their content was
     * erased.
     */
    int forget(UUID principalId) {
        return jdbc.sql("DELETE FROM inbox_dismissal WHERE principal_id = ?")
            .params(principalId)
            .update();
    }

    /** How many dismissals the member keeps: what {@link #forget} would remove. */
    long count(UUID principalId) {
        return jdbc.sql("SELECT count(*) FROM inbox_dismissal WHERE principal_id = ?")
            .params(principalId)
            .query(Long.class)
            .single();
    }

    /** Removes the company's dismissals made before {@code before}: the daily sweep's. */
    long deleteBefore(UUID companyId, Instant before) {
        return jdbc.sql("DELETE FROM inbox_dismissal WHERE company_id = ? AND dismissed_at < ?")
            .params(companyId, ts(before))
            .update();
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
