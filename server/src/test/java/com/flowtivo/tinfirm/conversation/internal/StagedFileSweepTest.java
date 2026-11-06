package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;

/** The sweep of held files (ADR-0084): an expired file goes, in every company; one still held stays. */
class StagedFileSweepTest extends IntegrationTest {

    @Autowired
    private StagedFileStore store;

    @Test
    void theSweepDeletesExpiredFilesOnly() throws Exception {
        TestCompany company = companyWithRoles();
        UUID userId = company.userId("MEMBER");
        UUID principal = memberPrincipal(company, userId);
        UUID expired = UUID.randomUUID();
        UUID held = UUID.randomUUID();
        try (TenantSql sql = TenantSql.in(company.id())) {
            for (UUID id : new UUID[] { expired, held }) {
                boolean old = id.equals(expired);
                sql.update("INSERT INTO staged_file (id, company_id, principal_id, user_id, file_name, content_type,"
                        + " size_bytes, sha256, content, created_at, expires_at) VALUES (?, ?, ?, ?, ?, 'text/plain', 1,"
                        + " repeat('0', 64), '\\x41'::bytea, now() - interval '" + (old ? "2 hours" : "1 minute")
                        + "', now() + interval '" + (old ? "-1 hour" : "59 minutes") + "')", id, company.id(), principal,
                        userId, id + ".txt");
            }
            sql.commit();
        }
        assertThat(store.sweep(Instant.now())).isGreaterThanOrEqualTo(1);
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.count("SELECT count(*) FROM staged_file WHERE id = ?", expired)).isZero();
            assertThat(sql.count("SELECT count(*) FROM staged_file WHERE id = ?", held)).isOne();
        }
    }
}
