package com.flowtivo.tinfirm.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.security.BearerGrant;

/** The bearer credential in an audit event's details (ADR-0045), added before the canonical form is hashed. */
class JdbcAuditLogGrantTest {

    private static final UUID ID = UUID.fromString("0190f5a4-1c2d-7e3f-8a9b-0c1d2e3f4a5b");

    @Test
    void aPersonalAccessTokenAddsTokenIdAndAnOAuthGrantAddsGrantId() {
        Map<String, Object> details = Map.of("workItemId", "w");
        assertThat(JdbcAuditLog.withGrant(details,
                Optional.of(new BearerGrant(BearerGrant.Kind.PERSONAL_ACCESS_TOKEN, ID, ApiScope.WRITE))))
            .containsEntry("tokenId", ID.toString())
            .containsEntry("workItemId", "w")
            .doesNotContainKey("grantId");
        assertThat(JdbcAuditLog.withGrant(details,
                Optional.of(new BearerGrant(BearerGrant.Kind.OAUTH_GRANT, ID, ApiScope.DECIDE))))
            .containsEntry("grantId", ID.toString())
            .doesNotContainKey("tokenId");
        // The enriched details stay canonicalizable.
        assertThat(AuditCanonicalForm.detailsTree(JdbcAuditLog.withGrant(details,
                Optional.of(new BearerGrant(BearerGrant.Kind.OAUTH_GRANT, ID, ApiScope.DECIDE)))).get("grantId")
            .asString()).isEqualTo(ID.toString());
    }

    @Test
    void withoutAGrantOrWithTheKeySetTheDetailsAreKept() {
        Map<String, Object> details = Map.of("workItemId", "w");
        assertThat(JdbcAuditLog.withGrant(details, Optional.empty())).isSameAs(details);
        Map<String, Object> own = new LinkedHashMap<>();
        own.put("tokenId", "the revoked token");
        assertThat(JdbcAuditLog.withGrant(own,
                Optional.of(new BearerGrant(BearerGrant.Kind.PERSONAL_ACCESS_TOKEN, ID, ApiScope.WRITE))))
            .containsEntry("tokenId", "the revoked token");
    }
}
