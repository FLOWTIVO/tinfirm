package com.flowtivo.tinfirm.identity.internal.saml;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code saml_idp_metadata} (V063): each provider's IdP metadata as a worker last read it (ADR-0086, decision 3).
 * Instance-level: read before any company context exists.
 */
@Component
class SamlMetadataStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<List<SamlIdpMetadata.Certificate>> CERTIFICATES = new TypeReference<>() {
    };

    private final JdbcClient jdbc;

    SamlMetadataStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A provider's row.
     *
     * @param document the last good document, or null before one
     */
    record Row(String registrationId, @Nullable String document, @Nullable String documentHash,
            @Nullable String entityId, List<SamlIdpMetadata.Certificate> certificates, @Nullable Instant validUntil,
            @Nullable Instant readAt, @Nullable String lastError, @Nullable Instant lastErrorAt, Instant nextReadAt) {
    }

    Optional<Row> find(String registrationId) {
        return jdbc.sql("SELECT * FROM saml_idp_metadata WHERE registration_id = ?")
            .param(registrationId)
            .query(SamlMetadataStore::row)
            .optional();
    }

    /** The document's hash alone: a replica checks it to know whether to build its registration again. */
    Optional<String> hash(String registrationId) {
        return jdbc.sql("SELECT document_hash FROM saml_idp_metadata WHERE registration_id = ?")
            .param(registrationId)
            .query(String.class)
            .optional();
    }

    List<Row> all() {
        return jdbc.sql("SELECT * FROM saml_idp_metadata ORDER BY registration_id").query(SamlMetadataStore::row).list();
    }

    /** Stores a good read, in place of the last one, and clears the last error. */
    void stored(String registrationId, String document, String hash, SamlIdpMetadata.Idp idp, Instant now,
            Instant nextReadAt) {
        jdbc.sql("""
                INSERT INTO saml_idp_metadata (registration_id, document, document_hash, entity_id, certificates,
                                               valid_until, read_at, last_error, last_error_at, next_read_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, NULL, NULL, ?)
                ON CONFLICT (registration_id) DO UPDATE
                   SET document = excluded.document, document_hash = excluded.document_hash,
                       entity_id = excluded.entity_id, certificates = excluded.certificates,
                       valid_until = excluded.valid_until, read_at = excluded.read_at, last_error = NULL,
                       last_error_at = NULL, next_read_at = excluded.next_read_at
                """)
            .params(registrationId, document, hash, idp.entityId(), JSON.writeValueAsString(idp.certificates()),
                    utc(idp.validUntil()), utc(now), utc(nextReadAt))
            .update();
    }

    /** Records a failed read, keeping the last good document. */
    void failed(String registrationId, String reason, Instant now, Instant nextReadAt) {
        jdbc.sql("""
                INSERT INTO saml_idp_metadata (registration_id, last_error, last_error_at, next_read_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (registration_id) DO UPDATE
                   SET last_error = excluded.last_error, last_error_at = excluded.last_error_at,
                       next_read_at = excluded.next_read_at
                """)
            .params(registrationId, reason, utc(now), utc(nextReadAt))
            .update();
    }

    /** Removes the rows of providers the deployment no longer declares. */
    int deleteOthers(List<String> declared) {
        if (declared.isEmpty()) {
            return jdbc.sql("DELETE FROM saml_idp_metadata").update();
        }
        return jdbc.sql("DELETE FROM saml_idp_metadata WHERE registration_id <> ALL (?)")
            .param(declared.toArray(String[]::new))
            .update();
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getString("registration_id"), rs.getString("document"), rs.getString("document_hash"),
                rs.getString("entity_id"), JSON.readValue(rs.getString("certificates"), CERTIFICATES),
                instant(rs, "valid_until"), instant(rs, "read_at"), rs.getString("last_error"),
                instant(rs, "last_error_at"), java.util.Objects.requireNonNull(instant(rs, "next_read_at")));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
