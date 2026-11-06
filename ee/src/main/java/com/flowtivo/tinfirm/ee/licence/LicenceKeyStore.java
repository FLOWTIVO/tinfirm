package com.flowtivo.tinfirm.ee.licence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.licence.Edition;

/**
 * The licence's tables (V053): {@code licence_key}, instance-level, one row per slot (the key in force and the next
 * one), and the SECURITY DEFINER counts of seats over the companies' memberships. Runs in the caller's transaction.
 */
@Component
class LicenceKeyStore {

    /** Where a key is held: in force (or ended), or a renewal waiting for its start. */
    enum Slot {
        CURRENT, NEXT
    }

    private static final String COLUMNS = "slot, licence_id, rev, customer, edition, seats, companies, starts_at,"
            + " ends_at, key_text, applied_at, applied_by";

    private final JdbcClient jdbc;

    LicenceKeyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A key as stored; {@code keyText} is never returned by the API. */
    record KeyRow(Slot slot, String licenceId, int rev, String customer, Edition edition, @Nullable Integer seats,
            @Nullable Integer companies, LocalDate startsAt, LocalDate endsAt, String keyText, Instant appliedAt,
            @Nullable UUID appliedBy) {

        KeyRow in(Slot other) {
            return new KeyRow(other, licenceId, rev, customer, edition, seats, companies, startsAt, endsAt, keyText,
                    appliedAt, appliedBy);
        }
    }

    Optional<KeyRow> find(Slot slot) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM licence_key WHERE slot = ?").param(slot.name())
            .query(LicenceKeyStore::row)
            .optional();
    }

    /** Holds the key in its slot, in place of the one held there. */
    void put(KeyRow row) {
        jdbc.sql("DELETE FROM licence_key WHERE slot = ?").param(row.slot().name()).update();
        jdbc.sql("INSERT INTO licence_key (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .params(row.slot().name(), row.licenceId(), row.rev(), row.customer(), row.edition().name(), row.seats(),
                    row.companies(), row.startsAt(), row.endsAt(), row.keyText(),
                    OffsetDateTime.ofInstant(row.appliedAt(), ZoneOffset.UTC), row.appliedBy())
            .update();
    }

    void clear(Slot slot) {
        jdbc.sql("DELETE FROM licence_key WHERE slot = ?").param(slot.name()).update();
    }

    /**
     * Serialises the instance's seat checks, and the changes of its keys, until the transaction ends, on every
     * replica: two grants never take the last seat twice, and a key is never replaced under a grant.
     */
    void lockSeats() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('licence:seats', 0))").query().singleRow();
    }

    int seatsUsed() {
        return jdbc.sql("SELECT licence_seats_used()").query(Integer.class).single();
    }

    boolean holdsSeat(UUID userId) {
        return jdbc.sql("SELECT licence_holds_seat(?)").param(userId).query(Boolean.class).single();
    }

    boolean hasSeatRole(UUID userId) {
        return jdbc.sql("SELECT licence_has_seat_role(?)").param(userId).query(Boolean.class).single();
    }

    private static KeyRow row(ResultSet rs, int rowNum) throws SQLException {
        return new KeyRow(Slot.valueOf(rs.getString("slot")), rs.getString("licence_id"), rs.getInt("rev"),
                rs.getString("customer"), Edition.valueOf(rs.getString("edition")),
                rs.getObject("seats", Integer.class), rs.getObject("companies", Integer.class),
                rs.getObject("starts_at", LocalDate.class), rs.getObject("ends_at", LocalDate.class),
                rs.getString("key_text"), rs.getObject("applied_at", OffsetDateTime.class).toInstant(),
                rs.getObject("applied_by", UUID.class));
    }
}
