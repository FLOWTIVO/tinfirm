package com.flowtivo.tinfirm.company.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.identity.ProvisionedMemberships;
import com.flowtivo.tinfirm.identity.ProvisionedMemberships.ProviderRole;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.access.RoleSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;

/**
 * Two sign-ins of a known user whose provider's claim newly maps them into a company, at the same moment (ADR-0089):
 * each applies the provider's role and, finding no membership, grants one, in its own transaction, as the sign-in does
 * ({@code ExternalProvisioning.applyClaim}). The second waits for the first under the company's lock and then finds the
 * membership it granted: one membership, one {@code member.added}, and neither sign-in fails on the membership's key.
 */
class ProviderGrantRaceTest extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private static final String PROVIDER = "race-idp";

    @Autowired
    private ProvisionedMemberships memberships;

    @Autowired
    private SystemTransactions transactions;

    @Test
    void twoSignInsNewlyMappedIntoACompanyGrantOneMembership() throws Exception {
        TestCompany company = companyWithRoles();
        UUID userId = createUser(admin(), uniqueEmail("newly-mapped"), false);

        // Released together while the company's row is held, so that each waits for it with the other.
        ExecutorService threads = Executors.newFixedThreadPool(2);
        List<Future<ProviderRole>> signIns = new ArrayList<>();
        try (TenantSql holder = TenantSql.in(company.id())) {
            holder.rows("SELECT id FROM company WHERE id = ? FOR NO KEY UPDATE", company.id());
            CountDownLatch start = new CountDownLatch(1);
            for (int i = 0; i < 2; i++) {
                signIns.add(threads.submit(() -> {
                    start.await();
                    return transactions.instanceWide(() -> {
                        ProviderRole applied = memberships.applyProviderRole(company.id(), userId, CompanyRole.MEMBER,
                                PROVIDER);
                        if (applied == ProviderRole.NOT_A_MEMBER) {
                            memberships.grant(company.id(), userId, CompanyRole.MEMBER, PROVIDER,
                                    RoleSource.provider(PROVIDER));
                        }
                        return applied;
                    });
                }));
            }
            start.countDown();
            await().atMost(WAIT).until(() -> waiting(company.id(), "SELECT id FROM company WHERE id = %") == 2);
            holder.commit();
            List<ProviderRole> outcomes = new ArrayList<>();
            for (Future<ProviderRole> signIn : signIns) {
                outcomes.add(signIn.get(30, TimeUnit.SECONDS));
            }
            assertThat(outcomes).containsExactlyInAnyOrder(ProviderRole.NOT_A_MEMBER, ProviderRole.APPLIED);
        }
        finally {
            threads.shutdownNow();
        }

        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.count("SELECT count(*) FROM company_membership WHERE company_id = ? AND user_id = ?",
                    company.id(), userId)).isEqualTo(1);
        }
        assertThat(auditEvents(loginAs(company.email("OWNER")), company, "member.added").stream()
            .filter(event -> event.get("targetId").asString().equals(userId.toString()))).hasSize(1);
    }

    /** The backends of this database that wait for a lock in a statement like the pattern. */
    private static long waiting(UUID companyId, String statement) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock' AND query ILIKE ?", statement);
        }
    }
}
