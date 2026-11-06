package com.flowtivo.tinfirm.diagnostics.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.CapturedException;
import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.Source;
import com.flowtivo.tinfirm.diagnostics.internal.ErrorCapture.Pending;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;

/**
 * SQL access to {@code error_report} and {@code error_reporting} (V046), instance-level tables without row-level
 * security.
 */
@Repository
class ErrorReportStore {

    private static final String COLUMNS = "id, fingerprint, source, title, message, logger, log_message, route,"
            + " runtime, sample, occurrences, reported_occurrences, first_seen_at, last_seen_at, first_version,"
            + " last_version, last_commit, reported_at";

    /** The sample's largest size as written; a larger one keeps fewer frames. */
    static final int MAX_SAMPLE = 64 * 1024;

    private static final TypeReference<Sample> SAMPLE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;

    private final JsonMapper json;

    ErrorReportStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** One report. */
    record ReportRow(UUID id, String fingerprint, Source source, String title, @Nullable String message,
            @Nullable String logger, @Nullable String logMessage, @Nullable String route, @Nullable String runtime,
            List<CapturedException> exceptions, long occurrences, long reportedOccurrences, Instant firstSeenAt,
            Instant lastSeenAt, String firstVersion, String lastVersion, @Nullable String lastCommit,
            @Nullable Instant reportedAt) {

        long unreported() {
            return occurrences - reportedOccurrences;
        }
    }

    /** The installation's row of {@code error_reporting}. */
    record SettingsRow(UUID instanceId, boolean automatic, @Nullable Instant changedAt, @Nullable UUID changedBy,
            @Nullable Instant lastAttemptAt, @Nullable Instant lastDeliveryAt, @Nullable String lastDeliveryError,
            @Nullable Instant retryAfter) {
    }

    /** What the sample column holds. */
    record Sample(List<CapturedException> exceptions) {
    }

    /**
     * Adds a flush's occurrences to their reports, a new one for a new fingerprint, in the order of the fingerprints,
     * so that two replicas' flushes never wait on each other's rows. The sample, the last version and what describes
     * the error come from the newer of the two, so that a replica's late flush of older occurrences (its database
     * was away, it runs the previous version during a rollout) does not put them back.
     */
    void add(List<Pending> pending, String version, @Nullable String commit) {
        for (Pending entry : pending.stream().sorted(Comparator.comparing(p -> p.error().fingerprint())).toList()) {
            CapturedError error = entry.error();
            jdbc.sql("""
                    INSERT INTO error_report (id, fingerprint, source, title, message, logger, log_message, route,
                                              runtime, sample, occurrences, first_seen_at, last_seen_at, first_version,
                                              last_version, last_commit)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (fingerprint) DO UPDATE SET
                        title = CASE WHEN newer THEN EXCLUDED.title ELSE error_report.title END,
                        message = CASE WHEN newer THEN EXCLUDED.message ELSE error_report.message END,
                        logger = CASE WHEN newer THEN EXCLUDED.logger ELSE error_report.logger END,
                        log_message = CASE WHEN newer THEN EXCLUDED.log_message ELSE error_report.log_message END,
                        route = CASE WHEN newer THEN EXCLUDED.route ELSE error_report.route END,
                        runtime = CASE WHEN newer THEN EXCLUDED.runtime ELSE error_report.runtime END,
                        sample = CASE WHEN newer THEN EXCLUDED.sample ELSE error_report.sample END,
                        last_version = CASE WHEN newer THEN EXCLUDED.last_version ELSE error_report.last_version END,
                        last_commit = CASE WHEN newer THEN EXCLUDED.last_commit ELSE error_report.last_commit END,
                        first_version = CASE WHEN EXCLUDED.first_seen_at < error_report.first_seen_at
                            THEN EXCLUDED.first_version ELSE error_report.first_version END,
                        occurrences = error_report.occurrences + EXCLUDED.occurrences,
                        first_seen_at = LEAST(error_report.first_seen_at, EXCLUDED.first_seen_at),
                        last_seen_at = GREATEST(error_report.last_seen_at, EXCLUDED.last_seen_at)""".replace(
                    "newer", "EXCLUDED.last_seen_at >= error_report.last_seen_at"))
                .params(Ids.newId(), error.fingerprint(), error.source().name(), error.title(), error.message(),
                        error.logger(), error.logMessage(), error.route(), error.runtime(),
                        sample(error.exceptions()), entry.count(), ts(entry.firstSeen()), ts(entry.lastSeen()),
                        version, version, commit)
                .update();
        }
    }

    /**
     * Deletes the reports not seen since {@code before}, and per source the least recently seen beyond its budget:
     * {@code keepServer} of the server's, {@code keepWeb} of the web app's, so that the web app's reports, which any
     * member can send, never evict the server's (L18).
     */
    int prune(Instant before, int keepServer, int keepWeb) {
        int old = jdbc.sql("DELETE FROM error_report WHERE last_seen_at < ?").params(ts(before)).update();
        int beyond = 0;
        for (Map.Entry<Source, Integer> budget : Map.of(Source.SERVER, keepServer, Source.WEB, keepWeb).entrySet()) {
            beyond += jdbc.sql("""
                    DELETE FROM error_report WHERE id IN (
                        SELECT id FROM error_report WHERE source = ? ORDER BY last_seen_at DESC, id DESC OFFSET ?)""")
                .params(budget.getKey().name(), budget.getValue())
                .update();
        }
        return old + beyond;
    }

    Page<ReportRow> page(PageRequest request) {
        List<ReportRow> rows;
        if (request.firstPage()) {
            rows = jdbc.sql("SELECT " + COLUMNS + " FROM error_report ORDER BY last_seen_at DESC, id DESC LIMIT ?")
                .params(request.fetchSize())
                .query(this::map)
                .list();
        }
        else {
            rows = jdbc.sql("SELECT " + COLUMNS + " FROM error_report WHERE (last_seen_at, id) < (?, ?)"
                    + " ORDER BY last_seen_at DESC, id DESC LIMIT ?")
                .params(ts(Instant.parse(request.key(0, 2))), UUID.fromString(request.key(1, 2)),
                        request.fetchSize())
                .query(this::map)
                .list();
        }
        return Page.of(rows, request, row -> List.of(row.lastSeenAt().toString(), row.id().toString()));
    }

    /** Every report, the most recently seen first, at most {@code limit}. */
    List<ReportRow> all(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM error_report ORDER BY last_seen_at DESC, id DESC LIMIT ?")
            .params(limit)
            .query(this::map)
            .list();
    }

    Optional<ReportRow> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM error_report WHERE id = ?").params(id).query(this::map).optional();
    }

    Optional<ReportRow> delete(UUID id) {
        return jdbc.sql("DELETE FROM error_report WHERE id = ? RETURNING " + COLUMNS)
            .params(id)
            .query(this::map)
            .optional();
    }

    /** The reports with occurrences not yet delivered, the longest waiting first. */
    List<ReportRow> undelivered(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM error_report WHERE occurrences > reported_occurrences"
                + " ORDER BY last_seen_at, id LIMIT ?")
            .params(limit)
            .query(this::map)
            .list();
    }

    long undeliveredCount() {
        return jdbc.sql("SELECT count(*) FROM error_report WHERE occurrences > reported_occurrences")
            .query(Long.class)
            .single();
    }

    /** A report delivered with {@code occurrences} of its count; a flush meanwhile leaves the rest undelivered. */
    void delivered(UUID id, long occurrences, Instant at) {
        jdbc.sql("""
                UPDATE error_report SET reported_occurrences = LEAST(GREATEST(reported_occurrences, ?), occurrences),
                                        reported_at = ?
                WHERE id = ?""")
            .params(occurrences, ts(at), id)
            .update();
    }

    /** Makes the installation's row once, with its random id; a row that exists is kept. */
    void ensureSettings() {
        jdbc.sql("INSERT INTO error_reporting (id, instance_id) VALUES (1, ?) ON CONFLICT (id) DO NOTHING")
            .params(Ids.newId())
            .update();
    }

    SettingsRow settings() {
        return jdbc.sql("""
                SELECT instance_id, automatic, changed_at, changed_by, last_attempt_at, last_delivery_at,
                       last_delivery_error, retry_after
                FROM error_reporting WHERE id = 1""")
            .query((rs, n) -> new SettingsRow(rs.getObject("instance_id", UUID.class), rs.getBoolean("automatic"),
                    instant(rs, "changed_at"), rs.getObject("changed_by", UUID.class), instant(rs, "last_attempt_at"),
                    instant(rs, "last_delivery_at"), rs.getString("last_delivery_error"), instant(rs, "retry_after")))
            .single();
    }

    /** The row locked for a change of the admin's choice. */
    boolean lockAutomatic() {
        return jdbc.sql("SELECT automatic FROM error_reporting WHERE id = 1 FOR NO KEY UPDATE")
            .query(Boolean.class)
            .single();
    }

    void setAutomatic(boolean automatic, Instant at, UUID by) {
        jdbc.sql("UPDATE error_reporting SET automatic = ?, changed_at = ?, changed_by = ? WHERE id = 1")
            .params(automatic, ts(at), by)
            .update();
    }

    /** Starts a pass, which holds the delivery's lock: its attempt is recorded, unless a {@code 429} asks to wait. */
    boolean startDelivery(Instant now) {
        return jdbc.sql("""
                UPDATE error_reporting SET last_attempt_at = ?
                WHERE id = 1 AND (retry_after IS NULL OR retry_after <= ?)""")
            .params(ts(now), ts(now))
            .update() == 1;
    }

    /** Ends a pass: a success clears the last error, a failure keeps it with its reason and a {@code 429}'s wait. */
    void finishDelivery(Instant now, @Nullable String error, @Nullable Instant retryAfter) {
        jdbc.sql("""
                UPDATE error_reporting SET retry_after = ?, last_delivery_error = ?,
                       last_delivery_at = CASE WHEN ?::varchar IS NULL THEN ? ELSE last_delivery_at END
                WHERE id = 1""")
            .params(ts(retryAfter), error, error, ts(now))
            .update();
    }

    private String sample(List<CapturedException> exceptions) {
        String written = json.writeValueAsString(new Sample(exceptions));
        int frames = ServerErrors.MAX_FRAMES;
        List<CapturedException> fewer = exceptions;
        while (written.length() > MAX_SAMPLE && frames > 1) {
            frames /= 2;
            int keep = frames;
            fewer = fewer.stream()
                .map(e -> new CapturedException(e.type(), e.message(),
                        e.frames().subList(0, Math.min(keep, e.frames().size()))))
                .toList();
            written = json.writeValueAsString(new Sample(fewer));
        }
        return written.length() > MAX_SAMPLE ? json.writeValueAsString(new Sample(List.of())) : written;
    }

    private ReportRow map(ResultSet rs, int rowNum) throws SQLException {
        Sample sample = json.readValue(rs.getString("sample"), SAMPLE);
        return new ReportRow(rs.getObject("id", UUID.class), rs.getString("fingerprint"),
                Source.valueOf(rs.getString("source")), rs.getString("title"), rs.getString("message"),
                rs.getString("logger"), rs.getString("log_message"), rs.getString("route"), rs.getString("runtime"),
                sample.exceptions() == null ? List.of() : sample.exceptions(), rs.getLong("occurrences"),
                rs.getLong("reported_occurrences"), required(rs, "first_seen_at"), required(rs, "last_seen_at"),
                rs.getString("first_version"), rs.getString("last_version"), rs.getString("last_commit"),
                instant(rs, "reported_at"));
    }

    private static Instant required(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
