package com.flowtivo.tinfirm.identity.internal.saml;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@code saml_assertion_seen} (V063): the assertions already used (ADR-0086, decision 8), in PostgreSQL so that every
 * replica sees them. Every assertion is used once, whether or not it carries {@code OneTimeUse}; a row is kept until
 * the assertion would be refused for its validity anyway.
 */
@Component
class SamlAssertionsSeen {

    private final JdbcClient jdbc;

    SamlAssertionsSeen(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records the assertion's use; false when it was used before, a replay. */
    boolean firstUse(String registrationId, String assertionId, Instant expiresAt) {
        return jdbc.sql("""
                INSERT INTO saml_assertion_seen (registration_id, assertion_id, expires_at) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """)
            .params(registrationId, assertionId, OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
            .update() == 1;
    }

    int sweep(Instant now) {
        return jdbc.sql("DELETE FROM saml_assertion_seen WHERE expires_at < ?")
            .param(OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
            .update();
    }
}
