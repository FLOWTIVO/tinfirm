package com.flowtivo.tinfirm.company.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;

/**
 * The audit chain's head is the last row a transaction locks (CONVENTIONS §8, §10), JPA's entities included: the
 * commit itself writes a changed entity that nothing flushed, after the transaction's synchronizations, so the audit
 * log flushes the transaction before it appends its events. A rename that waits for the company's row at its commit
 * holds no head meanwhile; it held it while the commit flushed the entity after the events.
 */
class EntitiesBeforeTheAuditHeadTest extends IntegrationTest {

    @Autowired
    CompanyRepository companies;

    @Autowired
    AuditLog auditLog;

    @Autowired
    SystemTransactions transactions;

    @Test
    void anEntityTheCommitWouldWriteLocksItsRowBeforeTheHead() throws Exception {
        TestCompany company = companyWithRoles();
        UUID id = company.id();
        String name = unique("Renamed");
        try (TenantSql holder = TenantSql.in(id)) {
            holder.rows("SELECT id FROM company WHERE id = ? FOR NO KEY UPDATE", id);
            CompletableFuture<Void> renamed = CompletableFuture.runAsync(() -> transactions.runInCompany(id, () -> {
                companies.findById(id).orElseThrow().rename(name, Instant.now());
                auditLog.record(AuditRecord.company(id, AuditAction.COMPANY_UPDATED, Actor.SYSTEM)
                    .target("company", id)
                    .detail("name", name)
                    .build());
            }));
            await().atMost(Duration.ofSeconds(20)).until(() -> waiting(id, "update company%") == 1);
            try (TenantSql other = TenantSql.in(id)) {
                // The head is free while the rename waits for the row (else NOWAIT fails with 55P03).
                assertThat(other.column("SELECT last_seq FROM audit_chain_head WHERE company_id = ? FOR UPDATE NOWAIT",
                        id)).hasSize(1);
            }
            holder.commit();
            renamed.get(30, TimeUnit.SECONDS);
        }
        try (TenantSql sql = TenantSql.in(id)) {
            assertThat(sql.column("SELECT name FROM company WHERE id = ?", id)).containsExactly(name);
            assertThat(sql.count("SELECT count(*) FROM audit_event WHERE company_id = ? AND action = 'company.updated'"
                    + " AND details ->> 'name' = ?", id, name)).isEqualTo(1);
        }
    }

    /** The backends of this database that wait for a lock in a statement like the pattern. */
    private static long waiting(UUID companyId, String statement) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock' AND query ILIKE ?", statement);
        }
    }
}
