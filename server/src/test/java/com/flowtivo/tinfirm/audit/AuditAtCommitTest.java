package com.flowtivo.tinfirm.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;
import com.flowtivo.tinfirm.shared.tenant.TenantScope;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;

/**
 * The audit chain's head is the last row a transaction locks (CONVENTIONS §8, §10): {@link AuditLog#record} keeps a
 * transaction's events and appends them when it commits. Two transactions that record an event and lock the same row
 * in opposite orders, which would deadlock ({@code 40P01}) if the head were locked at the first record, both commit,
 * and the chain verifies; a rolled-back transaction appends nothing; an event keeps its order and the time of its
 * call; one recorded after its transaction's commit is appended in a transaction of its own. The events are appended
 * after every synchronization's {@code beforeCommit}, whatever the order it was registered in: one that locks a row
 * waits for it without the head; and an event a synchronization's {@code beforeCommit} records first is appended in its
 * transaction, before the commit. ({@code company.internal.EntitiesBeforeTheAuditHeadTest}: JPA's entities too.) A
 * transaction that enters several companies appends each event under the tenant context it was recorded in, so each
 * goes to its own chain, which its row-level security allows.
 */
@ExtendWith(EnterpriseLicence.class)
class AuditAtCommitTest extends IntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    AuditLog auditLog;

    @Autowired
    SystemTransactions transactions;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TenantScope tenantScope;

    @Autowired
    PlatformTransactionManager transactionManager;

    private AuditRecord event(UUID companyId, String step) {
        return AuditRecord.company(companyId, AuditAction.COMPANY_UPDATED, Actor.SYSTEM)
            .target("company", companyId)
            .detail("step", step)
            .build();
    }

    @Test
    void anEventAndARowLockedInEitherOrderNeverDeadlock() throws Exception {
        TestCompany company = companyWithRoles();
        UUID id = company.id();
        CountDownLatch recorded = new CountDownLatch(1);
        CountDownLatch locked = new CountDownLatch(1);
        // The first records, then locks the company's row; the second locks it first, then records once the first
        // waits for it. Were the head locked at the first record, the second would wait for the head and the first
        // for the row.
        // Each on a thread of its own: each waits for the other, so they must run at the same time, which the common
        // pool that runAsync uses by default does not promise. On Java 25 it has one worker on a machine with two CPUs,
        // such as a small virtual machine, and has no thread-per-task fallback below two workers.
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> first = CompletableFuture.runAsync(() -> transactions.runInCompany(id, () -> {
                auditLog.record(event(id, "first"));
                recorded.countDown();
                awaitLatch(locked);
                jdbc.sql("UPDATE company SET updated_at = updated_at WHERE id = ?").params(id).update();
            }), threads);
            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> transactions.runInCompany(id, () -> {
                awaitLatch(recorded);
                jdbc.sql("UPDATE company SET updated_at = updated_at WHERE id = ?").params(id).update();
                locked.countDown();
                awaitWaiter(id);
                auditLog.record(event(id, "second"));
            }), threads);
            second.get(30, TimeUnit.SECONDS);
            first.get(30, TimeUnit.SECONDS);
        }
        finally {
            threads.shutdownNow();
        }

        ApiClient owner = loginAs(company.email("OWNER"));
        JsonNode verification = owner.get(company.path("/audit-events/verification")).json();
        assertThat(verification.get("valid").asBoolean()).isTrue();
        assertThat(steps(owner, company)).endsWith("second", "first");
    }

    @Test
    void aRolledBackTransactionAppendsNothingAndATransactionsEventsKeepTheirOrderAndTime() throws Exception {
        TestCompany company = companyWithRoles();
        UUID id = company.id();
        try {
            transactions.runInCompany(id, () -> {
                auditLog.record(event(id, "rolled back"));
                throw new IllegalStateException("roll back");
            });
        }
        catch (IllegalStateException expected) {
            // The transaction rolled back.
        }
        transactions.runInCompany(id, () -> {
            auditLog.record(event(id, "one"));
            sleep(20);
            auditLog.record(event(id, "two"));
            // Recorded after the commit, on its thread: appended in a transaction of its own.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    auditLog.record(event(id, "after"));
                }
            });
        });

        ApiClient owner = loginAs(company.email("OWNER"));
        List<String> steps = steps(owner, company);
        assertThat(steps).doesNotContain("rolled back").endsWith("one", "two", "after");
        List<JsonNode> events = new ArrayList<>();
        owner.get(company.path("/audit-events?limit=3")).json().get("items").forEach(events::add);
        // Newest first: after, two, one; each with the time of its call.
        assertThat(events.get(1).get("occurredAt").asString()).isGreaterThan(events.get(2).get("occurredAt")
            .asString());
        assertThat(owner.get(company.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
    }

    @Test
    void aSynchronizationsRowsComeBeforeTheHeadWhateverTheOrderItWasRegisteredIn() throws Exception {
        TestCompany company = companyWithRoles();
        UUID id = company.id();
        try (TenantSql holder = TenantSql.in(id)) {
            holder.rows("SELECT id FROM company WHERE id = ? FOR NO KEY UPDATE", id);
            // The transaction records its event, and then registers a synchronization whose beforeCommit locks a row,
            // as an @TransactionalEventListener of the phase BEFORE_COMMIT would: registered after the log's, and of
            // the same order, it ran after the events were appended, and waited for the row with the head held.
            CompletableFuture<Void> committed = CompletableFuture.runAsync(() -> transactions.runInCompany(id, () -> {
                auditLog.record(event(id, "before the synchronization"));
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        jdbc.sql("UPDATE company SET updated_at = updated_at WHERE id = ?").params(id).update();
                    }
                });
            }));
            await().atMost(WAIT).until(() -> waiting(id, "UPDATE company%") == 1);
            assertThat(headFree(id)).as("the head, while the commit waits for the company's row").isTrue();
            holder.commit();
            committed.get(30, TimeUnit.SECONDS);
        }
        ApiClient owner = loginAs(company.email("OWNER"));
        assertThat(steps(owner, company)).endsWith("before the synchronization");
        assertThat(owner.get(company.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
    }

    @Test
    void anEventRecordedFirstInAnotherSynchronizationsBeforeCommitIsAppendedInItsTransaction() throws Exception {
        TestCompany company = companyWithRoles();
        UUID id = company.id();
        java.time.OffsetDateTime marker = java.time.OffsetDateTime.parse("2001-02-03T04:05:06Z");
        try (TenantSql holder = TenantSql.in(id)) {
            holder.rows("SELECT last_seq FROM audit_chain_head WHERE company_id = ? FOR UPDATE", id);
            // The transaction's first event is recorded in a synchronization's beforeCommit: once the synchronizations
            // were listed for it, so that the log's own came too late for it, and the event waited for the commit to
            // be appended in a transaction of its own.
            CompletableFuture<Void> committed = CompletableFuture.runAsync(() -> transactions.runInCompany(id, () -> {
                jdbc.sql("UPDATE company SET updated_at = ? WHERE id = ?").params(marker, id).update();
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void beforeCommit(boolean readOnly) {
                        auditLog.record(event(id, "in a beforeCommit"));
                    }
                });
            }));
            // It is appended in the transaction, before its commit: the change is not committed while it waits.
            await().atMost(WAIT).until(() -> waiting(id, "%FROM audit_chain_head%FOR UPDATE%") == 1);
            try (TenantSql reader = TenantSql.in(id)) {
                assertThat(reader.count("SELECT count(*) FROM company WHERE id = ? AND updated_at = ?", id, marker))
                    .as("the transaction's change, while its event waits for the head").isZero();
            }
            holder.commit();
            committed.get(30, TimeUnit.SECONDS);
        }
        ApiClient owner = loginAs(company.email("OWNER"));
        assertThat(steps(owner, company)).endsWith("in a beforeCommit");
        assertThat(owner.get(company.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
    }

    @Test
    void aTransactionThatEntersSeveralCompaniesAppendsEachEventInItsOwnChainAndContext() {
        TestCompany a = companyWithRoles();
        TestCompany b = companyWithRoles();
        String instanceStep = unique("instance");
        // As an instance admin's deactivation of a user does: an event in the instance chain, then each company
        // entered in turn for its own event. Appended at the commit under the last company's context, the others'
        // failed its row-level security (the chain's head, its WITH CHECK), and the transaction with them.
        try (TenantContextHolder.Scope admin = TenantContextHolder.open(TenantContext.forUser(UUID.randomUUID(),
                true))) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                auditLog.record(AuditRecord.instance(AuditAction.USER_UPDATED, Actor.SYSTEM)
                    .target("user", UUID.randomUUID())
                    .detail("step", instanceStep)
                    .build());
                tenantScope.enterCompany(a.id());
                auditLog.record(event(a.id(), "in a"));
                tenantScope.enterCompany(b.id());
                auditLog.record(event(b.id(), "in b"));
            });
        }

        // Each event is in its own chain, and in no other; each chain verifies.
        ApiClient ownerOfA = loginAs(a.email("OWNER"));
        ApiClient ownerOfB = loginAs(b.email("OWNER"));
        assertThat(steps(ownerOfA, a)).endsWith("in a");
        assertThat(steps(ownerOfB, b)).endsWith("in b");
        assertThat(anySteps(auditEvents(ownerOfA, a))).doesNotContain("in b", instanceStep);
        assertThat(anySteps(auditEvents(ownerOfB, b))).doesNotContain("in a", instanceStep);
        assertThat(ownerOfA.get(a.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
        assertThat(ownerOfB.get(b.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
        ApiClient instanceAdmin = admin();
        List<JsonNode> instanceEvents = new ArrayList<>();
        instanceAdmin.get("/api/admin/audit-events?limit=50").json().get("items").forEach(instanceEvents::add);
        assertThat(anySteps(instanceEvents)).contains(instanceStep).doesNotContain("in a", "in b");
        assertThat(instanceAdmin.get("/api/admin/audit-events/verification").json().get("valid").asBoolean())
            .isTrue();
    }

    /** The {@code step} details of the events, whatever their action. */
    private static List<String> anySteps(List<JsonNode> events) {
        return events.stream().map(event -> event.path("details").path("step").asString("")).toList();
    }

    /** The backends of this database that wait for a lock in a statement like the pattern. */
    static long waiting(UUID companyId, String statement) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock' AND query ILIKE ?", statement);
        }
    }

    /** Whether another transaction can lock the company's chain head at once. */
    static boolean headFree(UUID companyId) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            sql.rows("SELECT last_seq FROM audit_chain_head WHERE company_id = ? FOR UPDATE NOWAIT", companyId);
            return true;
        }
        catch (java.sql.SQLException ex) {
            if ("55P03".equals(ex.getSQLState())) {
                return false;
            }
            throw ex;
        }
    }

    /** The {@code step} details of the company's {@code company.updated} events, oldest first. */
    private static List<String> steps(ApiClient owner, TestCompany company) {
        List<String> steps = new ArrayList<>();
        owner.get(company.path("/audit-events?limit=50")).json().get("items").forEach(item -> {
            if ("company.updated".equals(item.get("action").asString()) && item.path("details").has("step")) {
                steps.addFirst(item.get("details").get("step").asString());
            }
        });
        return steps;
    }

    /** Until another transaction waits for one this one holds (the first, for the company's row). */
    private static void awaitWaiter(UUID companyId) {
        await().atMost(WAIT).until(() -> {
            try (TenantSql sql = TenantSql.in(companyId)) {
                return sql.count("""
                        SELECT count(*) FROM pg_locks waiting
                          JOIN pg_locks holding ON holding.locktype = 'transactionid'
                                               AND holding.transactionid = waiting.transactionid
                                               AND holding.granted
                         WHERE waiting.locktype = 'transactionid' AND NOT waiting.granted
                           AND holding.pid IN (SELECT pid FROM pg_locks l
                                                WHERE l.locktype = 'relation' AND l.relation = 'company'::regclass
                                                  AND l.mode = 'RowExclusiveLock' AND l.granted)""") > 0;
            }
        });
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
