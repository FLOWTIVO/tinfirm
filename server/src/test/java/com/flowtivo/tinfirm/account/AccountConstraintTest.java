package com.flowtivo.tinfirm.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.McpStub;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestCredentials;

/**
 * What the database itself refuses about accounts, credentials, grants and lendings (the package 1 contract, §4 and §9;
 * ADR-0072: a constraint test per invariant I1, I2, I5, I6 and I9, and I3's ownership of a member's secret), as the
 * runtime role, and the row-level security of the tables.
 */
class AccountConstraintTest extends IntegrationTest {

    private static final String INSERT_ACCOUNT = """
            INSERT INTO account (id, company_id, system_id, principal_id, principal_kind, kind, secret_id, external_id,
                                 connected_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'TOKEN', ?, ?, now(), now())""";

    private static final String INSERT_LINK = """
            INSERT INTO account (id, company_id, system_id, principal_id, principal_kind, kind, external_id,
                                 connected_at, updated_at)
            VALUES (?, ?, ?, ?, 'MEMBER', 'LINK', ?, now(), now())""";

    private static final String INSERT_CREDENTIAL = """
            INSERT INTO account (id, company_id, system_id, principal_id, principal_kind, name, kind, secret_id,
                                 external_id, connected_at, updated_at)
            VALUES (?, ?, ?, NULL, 'SYSTEM', ?, 'TOKEN', ?, ?, now(), now())""";

    private static final String INSERT_GRANT = """
            INSERT INTO account_grant (id, company_id, account_id, group_id, access, created_at, updated_at)
            VALUES (?, ?, ?, ?, 'READ', now(), now())""";

    private static final String INSERT_LENDING = """
            INSERT INTO service_account_credential (company_id, principal_id, system_id, account_id, access,
                                                    created_at, updated_at)
            VALUES (?, ?, ?, ?, 'READ', now(), now())""";

    private record Fixture(TestCompany company, UUID system, UUID memberPrincipal, UUID memberAccount,
            UUID memberSecret, UUID service, UUID credential, UUID credentialSecret, UUID group) {
    }

    private Fixture fixture() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID system = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        UUID memberAccount = connectToken(member, company.path("/me/accounts/" + system), jira, "test-c-member",
                "c-member-" + system);
        jira.myself("test-c-bot", "c-bot-" + system, "UTC");
        UUID credential = TestCredentials.add(admin, company.path(""), system, "Bot", "test-c-bot");
        UUID service = createServiceIdentity(admin, company, "Bot");
        UUID group = createGroup(admin, company, "Ops", List.of());
        try (TenantSql sql = TenantSql.in(company.id())) {
            UUID memberSecret = (UUID) sql.column("SELECT secret_id FROM account WHERE id = ?", memberAccount)
                .getFirst();
            UUID credentialSecret = (UUID) sql.column("SELECT secret_id FROM account WHERE id = ?", credential)
                .getFirst();
            return new Fixture(company, system, memberPrincipal(company, company.userId("MEMBER")), memberAccount,
                    memberSecret, service, credential, credentialSecret, group);
        }
    }

    @Test
    void oneAccountPerMemberAndAnIdentityIsMembersOrCredentialsNeverBoth() throws SQLException {
        Fixture f = fixture();
        UUID company = f.company().id();
        try (TenantSql sql = TenantSql.in(company)) {
            // I1
            assertThat(sql.refused(INSERT_ACCOUNT, UUID.randomUUID(), company, f.system(), f.memberPrincipal(),
                    "MEMBER", f.memberSecret(), "another-identity")).isEqualTo("account_principal_uq");
            // I2: the member's identity, for another member, and for a credential; the credential's, for a member.
            UUID adminPrincipal = memberPrincipal(f.company(), f.company().userId("ADMIN"));
            assertThat(sql.refused(INSERT_LINK, UUID.randomUUID(), company, f.system(), adminPrincipal,
                    "key-c-member-" + f.system())).isEqualTo("account_identity_uq");
            assertThat(sql.refused(INSERT_CREDENTIAL, UUID.randomUUID(), company, f.system(), "Other",
                    f.credentialSecret(), "key-c-member-" + f.system())).isEqualTo("account_identity_uq");
            assertThat(sql.refused(INSERT_LINK, UUID.randomUUID(), company, f.system(), adminPrincipal,
                    "key-c-bot-" + f.system())).isEqualTo("account_identity_uq");
            // Two credentials may be one user; never two of one name on a system, whatever its case.
            sql.accepted(INSERT_CREDENTIAL, UUID.randomUUID(), company, f.system(), "Bot 2", f.credentialSecret(),
                    "key-c-bot-" + f.system());
            assertThat(sql.refused(INSERT_CREDENTIAL, UUID.randomUUID(), company, f.system(), "BOT",
                    f.credentialSecret(), null)).isEqualTo("account_name_uq");
            // A credential has a name and no principal, a member's own account the reverse; only a credential is
            // disabled, and it is never a LINK; a service account holds no account.
            assertThat(sql.refused("UPDATE account SET name = NULL WHERE id = ?", f.credential()))
                .isEqualTo("account_holder_ck");
            assertThat(sql.refused("UPDATE account SET name = 'Mine' WHERE id = ?", f.memberAccount()))
                .isEqualTo("account_holder_ck");
            assertThat(sql.refused("UPDATE account SET enabled = false WHERE id = ?", f.memberAccount()))
                .isEqualTo("account_holder_ck");
            assertThat(sql.refused("UPDATE account SET kind = 'LINK', secret_id = NULL WHERE id = ?",
                    f.credential())).isEqualTo("account_holder_ck");
            // An OAuth client's own credentials (CLIENT) are a credential's only, never a member's own account.
            assertThat(sql.refused("UPDATE account SET kind = 'CLIENT' WHERE id = ?", f.memberAccount()))
                .isEqualTo("account_holder_ck");
            sql.accepted("UPDATE account SET kind = 'CLIENT' WHERE id = ?", f.credential());
            sql.accepted("UPDATE account SET kind = 'TOKEN' WHERE id = ?", f.credential());
            assertThat(sql.refused("UPDATE account SET kind = 'OTHER' WHERE id = ?", f.memberAccount()))
                .isEqualTo("account_kind_ck");
            sql.accepted("UPDATE account SET enabled = false WHERE id = ?", f.credential());
            assertThat(sql.refused(INSERT_ACCOUNT, UUID.randomUUID(), company, f.system(), f.service(), "SERVICE",
                    f.credentialSecret(), null)).isEqualTo("account_holder_ck");
            // A principal's kind is its own.
            assertThat(sql.refused(INSERT_ACCOUNT, UUID.randomUUID(), company, f.system(), f.service(), "MEMBER",
                    f.credentialSecret(), null)).isEqualTo("account_principal_fk");
        }
    }

    @Test
    void aMembersAccountKeepsItsTokenInASecretTheMemberOwns() throws SQLException {
        Fixture f = fixture();
        try (TenantSql sql = TenantSql.in(f.company().id())) {
            // I3: the member's account with a company secret, the credential with the member's secret.
            assertThat(sql.refused("UPDATE account SET secret_id = ? WHERE id = ?", f.credentialSecret(),
                    f.memberAccount())).isEqualTo("account_secret_owner_ck");
            assertThat(sql.refused("UPDATE account SET secret_id = ? WHERE id = ?", f.memberSecret(),
                    f.credential())).isEqualTo("account_secret_owner_ck");
            assertThat(sql.refused("UPDATE account SET secret_id = NULL WHERE id = ?", f.credential()))
                .isEqualTo("account_secret_ck");
        }
    }

    @Test
    void onlyACredentialIsLentToAGroupOrAServiceAccountWhichBorrowsOnePerSystem() throws SQLException {
        Fixture f = fixture();
        UUID company = f.company().id();
        try (TenantSql sql = TenantSql.in(company)) {
            // I5
            assertThat(sql.refused(INSERT_GRANT, UUID.randomUUID(), company, f.memberAccount(), f.group()))
                .isEqualTo("account_grant_credential_fk");
            assertThat(sql.refused("""
                    INSERT INTO account_grant (id, company_id, account_id, account_principal_kind, group_id, access,
                                               created_at, updated_at)
                    VALUES (?, ?, ?, 'MEMBER', ?, 'READ', now(), now())""", UUID.randomUUID(), company,
                    f.memberAccount(), f.group())).isEqualTo("account_grant_credential_ck");
            sql.accepted(INSERT_GRANT, UUID.randomUUID(), company, f.credential(), f.group());
            assertThat(sql.refused(INSERT_GRANT, UUID.randomUUID(), company, f.credential(), f.group()))
                .isEqualTo("account_grant_account_group_uq");

            // A service account borrows a credential of the system, one per system; a member borrows nothing.
            assertThat(sql.refused(INSERT_LENDING, company, f.service(), f.system(), f.memberAccount()))
                .isEqualTo("service_account_credential_account_fk");
            assertThat(sql.refused(INSERT_LENDING, company, f.memberPrincipal(), f.system(), f.credential()))
                .isEqualTo("service_account_credential_principal_fk");
            sql.accepted(INSERT_LENDING, company, f.service(), f.system(), f.credential());
            UUID second = UUID.randomUUID();
            sql.accepted(INSERT_CREDENTIAL, second, company, f.system(), "Bot 2", f.credentialSecret(), null);
            assertThat(sql.refused(INSERT_LENDING, company, f.service(), f.system(), second))
                .isEqualTo("service_account_credential_pk");
            assertThat(sql.refused("UPDATE service_account_credential SET access = 'ADMIN' WHERE principal_id = ?",
                    f.service())).isEqualTo("service_account_credential_access_ck");
            // It goes with its credential.
            sql.accepted("DELETE FROM account WHERE id = ?", f.credential());
            assertThat(sql.count("SELECT count(*) FROM service_account_credential WHERE principal_id = ?",
                    f.service())).isZero();
        }
    }

    @Test
    void onlyItsOwnerHoldsAnAccountOnAPersonalServerAndNoCredentialIsThere() throws SQLException {
        Fixture f = fixture();
        ApiClient member = loginAs(f.company().email("MEMBER"));
        UUID personal = createSystem(member, f.company(), "Notes", "MCP",
                McpStub.newInstance().serves(List.of()).url(), true);
        try (TenantSql sql = TenantSql.in(f.company().id())) {
            // I6: a credential, and another member's account, on the member's personal server.
            assertThat(sql.refused(INSERT_CREDENTIAL, UUID.randomUUID(), f.company().id(), personal, "Shared",
                    f.credentialSecret(), null)).isEqualTo("account_personal_ck");
            UUID ownerPrincipal = memberPrincipal(f.company(), f.company().userId("OWNER"));
            assertThat(sql.refused(INSERT_ACCOUNT, UUID.randomUUID(), f.company().id(), personal, ownerPrincipal,
                    "MEMBER", f.memberSecret(), null)).isEqualTo("account_personal_ck");
        }
    }

    @Test
    void aChatSystemsBotIsACredentialOnIt() throws SQLException {
        Fixture f = fixture();
        try (TenantSql sql = TenantSql.in(f.company().id())) {
            UUID slack = UUID.randomUUID();
            sql.accepted("""
                    INSERT INTO external_system (id, company_id, name, handle, kind, base_url, created_at, updated_at)
                    VALUES (?, ?, 'Slack', 'slack', 'SLACK', 'https://slack.example.test', now(), now())""", slack,
                    f.company().id());
            UUID botOnSlack = UUID.randomUUID();
            UUID memberOnSlack = UUID.randomUUID();
            sql.accepted(INSERT_CREDENTIAL, botOnSlack, f.company().id(), slack, "Bot", f.credentialSecret(),
                    "U-BOT");
            sql.accepted(INSERT_ACCOUNT, memberOnSlack, f.company().id(), slack, f.memberPrincipal(), "MEMBER",
                    f.memberSecret(), "U-MEMBER");
            // I9: a member's account, or a credential of another system, is no bot; only a chat system has one.
            assertThat(sql.refused("UPDATE external_system SET bot_account_id = ? WHERE id = ?", memberOnSlack, slack))
                .isEqualTo("external_system_bot_fk");
            assertThat(sql.refused("UPDATE external_system SET bot_account_id = ? WHERE id = ?", f.credential(),
                    slack)).isEqualTo("external_system_bot_fk");
            assertThat(sql.refused("UPDATE external_system SET bot_account_id = ? WHERE id = ?", f.credential(),
                    f.system())).isEqualTo("external_system_bot_ck");
            sql.accepted("UPDATE external_system SET bot_account_id = ? WHERE id = ?", botOnSlack, slack);
        }
    }

    @Test
    void anotherCompanysAccountsGrantsAndLendingsAreInvisible() throws SQLException {
        Fixture a = fixture();
        Fixture b = fixture();
        ApiClient adminOfB = loginAs(b.company().email("ADMIN"));
        adminOfB.post(b.company().path("/grants"), Map.of("credentialId", b.credential().toString(), "groupId",
                b.group().toString(), "access", "READ"));
        TestCredentials.lend(adminOfB, b.company().path(""), b.system(), b.credential(), b.service(), "READ");
        try (TenantSql sql = TenantSql.in(a.company().id())) {
            assertThat(sql.count("SELECT count(*) FROM account WHERE company_id = ?", b.company().id())).isZero();
            assertThat(sql.count("SELECT count(*) FROM account_grant WHERE company_id = ?", b.company().id()))
                .isZero();
            assertThat(sql.count("SELECT count(*) FROM service_account_credential WHERE company_id = ?",
                    b.company().id())).isZero();
            assertThat(sql.update("UPDATE account SET status = 'ERROR' WHERE id = ?", b.credential())).isZero();
            // A grant of A on B's credential is refused, and A's service account borrows none of B's.
            assertThat(sql.refused(INSERT_GRANT, UUID.randomUUID(), a.company().id(), b.credential(), a.group()))
                .isEqualTo("account_grant_credential_fk");
            assertThat(sql.refused(INSERT_LENDING, a.company().id(), a.service(), b.system(), b.credential()))
                .isEqualTo("service_account_credential_account_fk");
            sql.context(null);
            assertThat(sql.count("SELECT count(*) FROM account")).isZero();
            assertThat(sql.count("SELECT count(*) FROM account_grant")).isZero();
            assertThat(sql.count("SELECT count(*) FROM service_account_credential")).isZero();
        }
    }
}
