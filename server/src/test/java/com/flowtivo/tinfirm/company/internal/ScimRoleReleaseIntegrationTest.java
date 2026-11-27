package com.flowtivo.tinfirm.company.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.shared.licence.LicenceChanged;
import com.flowtivo.tinfirm.support.ConnectionWatch;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;

/**
 * A key applied in a transaction releases, after its commit, the roles SCIM no longer decides (ADR-0087): in
 * transactions of their own once the key's transaction has released its connection, never in the finished one.
 */
class ScimRoleReleaseIntegrationTest extends IntegrationTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ApplicationEventPublisher events;

    @Test
    void aKeyAppliedInATransactionReleasesTheRolesAfterItsCommitWithOneConnectionAtATime() throws Exception {
        TestCompany company = companyWithRoles();
        UUID member = company.userId("MEMBER");
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.update("UPDATE company_membership SET role_source = 'SCIM' WHERE company_id = ? AND user_id = ?",
                    company.id(), member);
            sql.commit();
        }

        ConnectionWatch.Mark mark = ConnectionWatch.mark();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> events
            .publishEvent(new LicenceChanged()));

        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.column("SELECT role_source FROM company_membership WHERE company_id = ? AND user_id = ?",
                    company.id(), member)).as("released before the call returned").containsExactly("APP");
        }
        assertThat(mark.at(ScimRoleRelease.class.getName())).as("second connections asked for").isZero();
    }
}
