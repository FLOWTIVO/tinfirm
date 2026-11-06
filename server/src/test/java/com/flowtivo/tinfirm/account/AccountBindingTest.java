package com.flowtivo.tinfirm.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.account.AccountBinding.Check;
import com.flowtivo.tinfirm.account.AccountBinding.ConnectReason;
import com.flowtivo.tinfirm.account.AccountBinding.Outcome;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.McpStub;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestCredentials;

/**
 * The binding rule as a table (the package 1 contract, §5 and §9): what {@code Accounts.bind} answers for a personal
 * conversation and a trigger in each case the contract lists, and what {@code Accounts.check} answers at a later use
 * (I7, I10). Redesign scenarios V5 (an analyst through a {@code READ} grant) and V6 (connecting one's own account
 * mid-conversation keeps the binding) as binding tests.
 */
class AccountBindingTest extends IntegrationTest {

    @Autowired
    private Accounts accounts;

    @Autowired
    private SystemTransactions transactions;

    /**
     * A company with a Jira, two service accounts, each borrowing a credential of the Jira (ADR-0072), and the member
     * in two groups.
     */
    private record Fixture(TestCompany company, ApiClient admin, ApiClient member, JiraStub.Instance jira, UUID system,
            UUID member1, UUID bot1, UUID bot1Account, UUID bot2, UUID bot2Account, UUID group1, UUID group2) {
    }

    private Fixture fixture() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID system = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        UUID bot1 = createServiceIdentity(admin, company, "Bot one");
        UUID bot2 = createServiceIdentity(admin, company, "Bot two");
        UUID bot1Account = connectServiceToken(admin, company, bot1, system, jira, "test-bot1-" + system,
                "bot1-" + system);
        UUID bot2Account = connectServiceToken(admin, company, bot2, system, jira, "test-bot2-" + system,
                "bot2-" + system);
        UUID memberId = company.userId("MEMBER");
        UUID group1 = createGroup(admin, company, "Analysts", List.of(memberId));
        UUID group2 = createGroup(admin, company, "Support", List.of(memberId));
        return new Fixture(company, admin, member, jira, system, memberPrincipal(company, memberId), bot1,
                bot1Account, bot2, bot2Account, group1, group2);
    }

    @Test
    void aPersonalConversationBindsOwnThenGrantsAndNeverFallsBack() throws SQLException {
        Fixture f = fixture();
        UUID companyId = f.company().id();

        // No account, no grant: connect your own.
        assertThat(bind(f, f.member1())).isEqualTo(connect(f, ConnectReason.NONE));

        // One grant: Granted; two: Choose, WRITE before READ.
        UUID grant1 = createGrant(f.admin(), f.company(), f.bot1Account(), f.group1(), "READ");
        assertThat(bind(f, f.member1())).isInstanceOfSatisfying(AccountBinding.Granted.class, granted -> {
            assertThat(granted.grant().id()).isEqualTo(grant1);
            assertThat(granted.grant().access()).isEqualTo(GrantInfo.Access.READ);
            assertThat(granted.account().id()).isEqualTo(f.bot1Account());
        });
        UUID grant2 = createGrant(f.admin(), f.company(), f.bot2Account(), f.group2(), "WRITE");
        assertThat(bind(f, f.member1())).isInstanceOfSatisfying(AccountBinding.Choose.class, choose -> assertThat(
                choose.grants()).extracting(GrantInfo::id).containsExactly(grant2, grant1));

        // A disabled credential binds nobody; a suspended service account that borrows it changes nothing of a grant.
        f.admin().post(f.company().path("/service-identities/" + f.bot2() + "/suspend"), Map.of());
        assertThat(bind(f, f.member1())).isInstanceOf(AccountBinding.Choose.class);
        disable(f, f.bot2Account(), true);
        assertThat(bind(f, f.member1())).isInstanceOf(AccountBinding.Granted.class);

        // A person who left the group no longer acts through its grant; the one left, of the disabled credential, is
        // an admin's to enable.
        f.admin().put(f.company().path("/groups/" + f.group1()), Map.of("name", "Analysts", "memberIds", List.of()));
        assertThat(bind(f, f.member1())).isEqualTo(connect(f, ConnectReason.RECONNECT_COMPANY));
        f.admin().put(f.company().path("/groups/" + f.group1()), Map.of("name", "Analysts", "memberIds",
                List.of(f.company().userId("MEMBER").toString())));

        // The granted account needs an admin: RECONNECT_COMPANY, never another account (I10).
        setStatus(companyId, f.bot1Account(), "RECONNECT");
        assertThat(bind(f, f.member1())).isEqualTo(connect(f, ConnectReason.RECONNECT_COMPANY));
        setStatus(companyId, f.bot1Account(), "OK");

        // One's own account wins; broken, it asks to reconnect, or to choose when a grant exists (I10).
        UUID own = connectToken(f.member(), f.company().path("/me/accounts/" + f.system()), f.jira(),
                "test-member-token-" + f.system(), "mia-" + f.system());
        assertThat(bind(f, f.member1())).isInstanceOfSatisfying(AccountBinding.Own.class,
                bound -> assertThat(bound.account().id()).isEqualTo(own));
        setStatus(companyId, own, "RECONNECT");
        assertThat(bind(f, f.member1())).isInstanceOfSatisfying(AccountBinding.ConnectRequired.class, connect -> {
            assertThat(connect.reason()).isEqualTo(ConnectReason.CHOOSE);
            assertThat(connect.choices()).extracting(GrantInfo::id).containsExactly(grant1);
        });
        f.admin().delete(f.company().path("/grants/" + grant1));
        assertThat(bind(f, f.member1())).isEqualTo(connect(f, ConnectReason.RECONNECT_YOURS));
    }

    @Test
    void aPersonalServerBindsForItsOwnerOnlyAndNotWhileSwitchedOff() {
        Fixture f = fixture();
        McpStub.Instance mcp = McpStub.newInstance().serves(List.of());
        UUID personal = createSystem(f.member(), f.company(), "Notes", "MCP", mcp.url(), true);
        UUID ownerPrincipal = memberPrincipal(f.company(), f.company().userId("OWNER"));

        assertThat(bind(f, f.member1(), personal)).isEqualTo(new AccountBinding.ConnectRequired(personal,
                ConnectReason.NONE, List.of()));
        assertThat(f.member().put(f.company().path("/me/accounts/" + personal + "/token"), Map.of("token",
                "test-notes")).status()).isEqualTo(200);
        assertThat(bind(f, f.member1(), personal)).isInstanceOf(AccountBinding.Own.class);
        assertThat(bind(f, ownerPrincipal, personal)).isEqualTo(new AccountBinding.NotEntitled(personal,
                AccountBinding.Refusal.NOT_OWNER));
        f.admin().put(f.company().path("/system-settings"), Map.of("personalMcpServers", false));
        assertThat(bind(f, f.member1(), personal)).isEqualTo(new AccountBinding.NotEntitled(personal,
                AccountBinding.Refusal.PERSONAL_SYSTEMS_OFF));
        f.admin().put(f.company().path("/system-settings"), Map.of("personalMcpServers", true));
    }

    @Test
    void aTriggerBindsItsAuthorsOwnAccountOrAServiceIdentitysTheyMayUse() throws SQLException {
        Fixture f = fixture();
        UUID own = connectToken(f.member(), f.company().path("/me/accounts/" + f.system()), f.jira(),
                "test-member-trigger-" + f.system(), "mia-t-" + f.system());
        assertThat(trigger(f, f.member1(), own)).isInstanceOfSatisfying(AccountBinding.Fixed.class, fixed -> {
            assertThat(fixed.account().id()).isEqualTo(own);
            assertThat(fixed.holderPrincipalId()).isEqualTo(f.member1());
        });

        // A service account's credential needs the use right (a group); OWNER may use every one, ADMIN only by group.
        assertThat(trigger(f, f.bot1(), f.bot1Account())).isEqualTo(new AccountBinding.NotEntitled(f.system(),
                AccountBinding.Refusal.ACCOUNT_NOT_USABLE));
        f.admin().put(f.company().path("/service-identities/" + f.bot1() + "/users"), Map.of("groupIds",
                List.of(f.group1().toString())));
        assertThat(trigger(f, f.bot1(), f.bot1Account())).isInstanceOfSatisfying(AccountBinding.Fixed.class,
                fixed -> assertThat(fixed.holderPrincipalId()).isEqualTo(f.bot1()));
        assertThat(trigger(f, f.bot2(), f.bot2Account())).isEqualTo(new AccountBinding.NotEntitled(f.system(),
                AccountBinding.Refusal.ACCOUNT_NOT_USABLE));
        UUID ownerPrincipal = memberPrincipal(f.company(), f.company().userId("OWNER"));
        assertThat(inCompany(f, () -> accounts.bind(new AccountBinding.Trigger(f.company().id(), ownerPrincipal,
                f.bot2(), f.bot2Account()), f.system()))).isInstanceOf(AccountBinding.Fixed.class);

        // Only through the service account that borrows it (ADR-0072), and never as the author's own.
        assertThat(trigger(f, f.bot1(), f.bot2Account())).isEqualTo(new AccountBinding.NotEntitled(f.system(),
                AccountBinding.Refusal.ACCOUNT_NOT_USABLE));
        assertThat(trigger(f, f.member1(), f.bot1Account())).isEqualTo(new AccountBinding.NotEntitled(f.system(),
                AccountBinding.Refusal.ACCOUNT_NOT_USABLE));

        // A broken or disabled credential asks an admin; another member's own account is never usable.
        setStatus(f.company().id(), f.bot1Account(), "ERROR");
        assertThat(trigger(f, f.bot1(), f.bot1Account())).isEqualTo(connect(f, ConnectReason.RECONNECT_COMPANY));
        setStatus(f.company().id(), f.bot1Account(), "OK");
        disable(f, f.bot1Account(), true);
        assertThat(trigger(f, f.bot1(), f.bot1Account())).isEqualTo(connect(f, ConnectReason.RECONNECT_COMPANY));
        assertThat(inCompany(f, () -> accounts.bind(new AccountBinding.Trigger(f.company().id(), ownerPrincipal,
                ownerPrincipal, own), f.system()))).isEqualTo(new AccountBinding.NotEntitled(f.system(),
                        AccountBinding.Refusal.ACCOUNT_NOT_USABLE));
    }

    @Test
    void aStoredBindingIsCheckedAtEveryUseAndNeverRebinds() throws SQLException {
        Fixture f = fixture();
        UUID companyId = f.company().id();

        // V5: an analyst acts through a READ grant; its access is read at each check.
        UUID grant = createGrant(f.admin(), f.company(), f.bot1Account(), f.group1(), "READ");
        Outcome bound = bind(f, f.member1());
        AccountBinding.Stored stored = AccountBinding.Stored.of(bound, f.member1());
        assertThat(check(f, stored)).isInstanceOfSatisfying(AccountBinding.Usable.class, usable -> {
            assertThat(usable.account().id()).isEqualTo(f.bot1Account());
            assertThat(usable.access()).isEqualTo(GrantInfo.Access.READ);
        });
        f.admin().patch(f.company().path("/grants/" + grant), Map.of("access", "WRITE"));
        assertThat(check(f, stored)).isInstanceOfSatisfying(AccountBinding.Usable.class,
                usable -> assertThat(usable.access()).isEqualTo(GrantInfo.Access.WRITE));

        // V6: connecting one's own account mid-conversation keeps the binding; a new conversation binds the own one.
        connectToken(f.member(), f.company().path("/me/accounts/" + f.system()), f.jira(),
                "test-member-v6-" + f.system(), "mia-v6-" + f.system());
        assertThat(check(f, stored)).isInstanceOfSatisfying(AccountBinding.Usable.class,
                usable -> assertThat(usable.account().id()).isEqualTo(f.bot1Account()));
        assertThat(bind(f, f.member1())).isInstanceOf(AccountBinding.Own.class);

        // Each failure stops the use with its reason; a grant's credential is the company's, not a service account's.
        f.admin().post(f.company().path("/service-identities/" + f.bot1() + "/suspend"), Map.of());
        assertThat(check(f, stored)).isInstanceOf(AccountBinding.Usable.class);
        f.admin().post(f.company().path("/service-identities/" + f.bot1() + "/resume"), Map.of());
        disable(f, f.bot1Account(), true);
        assertThat(check(f, stored)).isEqualTo(stop(AccountBinding.StopReason.RECONNECT_COMPANY));
        disable(f, f.bot1Account(), false);
        setStatus(companyId, f.bot1Account(), "RECONNECT");
        assertThat(check(f, stored)).isEqualTo(stop(AccountBinding.StopReason.RECONNECT_COMPANY));
        setStatus(companyId, f.bot1Account(), "OK");
        f.admin().put(f.company().path("/groups/" + f.group1()), Map.of("name", "Analysts", "memberIds", List.of()));
        assertThat(check(f, stored)).isEqualTo(stop(AccountBinding.StopReason.NOT_IN_GROUP));
        f.admin().delete(f.company().path("/grants/" + grant));
        assertThat(check(f, stored)).isEqualTo(stop(AccountBinding.StopReason.GRANT_GONE));

        // The account stored again as another identity: the bound identity changed.
        UUID ownAccount = accountOf(f, f.member1());
        AccountBinding.Stored own = AccountBinding.Stored.of(bind(f, f.member1()), f.member1());
        assertThat(check(f, own)).isInstanceOfSatisfying(AccountBinding.Usable.class, usable -> {
            assertThat(usable.account().id()).isEqualTo(ownAccount);
            assertThat(usable.access()).isEqualTo(GrantInfo.Access.WRITE);
        });
        connectToken(f.member(), f.company().path("/me/accounts/" + f.system()), f.jira(),
                "test-member-other-" + f.system(), "someone-else-" + f.system());
        assertThat(check(f, own)).isEqualTo(stop(AccountBinding.StopReason.IDENTITY_CHANGED));
        assertThat(f.member().delete(f.company().path("/me/accounts/" + f.system())).status()).isEqualTo(204);
        assertThat(check(f, own)).isEqualTo(stop(AccountBinding.StopReason.RECONNECT_YOURS));

        // A trigger's author who loses the use right.
        f.admin().put(f.company().path("/groups/" + f.group1()), Map.of("name", "Analysts", "memberIds",
                List.of(f.company().userId("MEMBER").toString())));
        f.admin().put(f.company().path("/service-identities/" + f.bot2() + "/users"), Map.of("groupIds",
                List.of(f.group1().toString())));
        AccountBinding.Stored fixed = AccountBinding.Stored.of(trigger(f, f.bot2(), f.bot2Account()), f.member1());
        assertThat(check(f, fixed)).isInstanceOf(AccountBinding.Usable.class);
        f.admin().post(f.company().path("/service-identities/" + f.bot2() + "/suspend"), Map.of());
        assertThat(check(f, fixed)).isEqualTo(stop(AccountBinding.StopReason.PRINCIPAL_INACTIVE));
        f.admin().post(f.company().path("/service-identities/" + f.bot2() + "/resume"), Map.of());
        f.admin().put(f.company().path("/service-identities/" + f.bot2() + "/users"), Map.of("groupIds", List.of()));
        assertThat(check(f, fixed)).isEqualTo(stop(AccountBinding.StopReason.USE_RIGHT_GONE));

        // A service account that borrows another credential now: the stored one stops; the trigger's row and its
        // conversations' bindings follow the service account (ADR-0072), never the check.
        f.admin().put(f.company().path("/service-identities/" + f.bot2() + "/users"), Map.of("groupIds",
                List.of(f.group1().toString())));
        assertThat(check(f, fixed)).isInstanceOf(AccountBinding.Usable.class);
        TestCredentials.lend(f.admin(), f.company().path(""), f.system(), f.bot1Account(), f.bot2(), "READ");
        assertThat(check(f, fixed)).isEqualTo(stop(AccountBinding.StopReason.USE_RIGHT_GONE));
        AccountBinding.Stored lentAgain = AccountBinding.Stored.of(trigger(f, f.bot2(), f.bot1Account()),
                f.member1());
        assertThat(check(f, lentAgain)).isInstanceOfSatisfying(AccountBinding.Usable.class,
                usable -> assertThat(usable.access()).isEqualTo(GrantInfo.Access.READ));

        // The system removed.
        f.admin().delete(f.company().path("/systems/" + f.system()));
        assertThat(check(f, fixed)).isEqualTo(stop(AccountBinding.StopReason.SYSTEM_GONE));
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------------

    private Outcome bind(Fixture f, UUID memberPrincipal) {
        return bind(f, memberPrincipal, f.system());
    }

    private Outcome bind(Fixture f, UUID memberPrincipal, UUID systemId) {
        return inCompany(f, () -> accounts.bind(new AccountBinding.Personal(f.company().id(), memberPrincipal),
                systemId));
    }

    private Outcome trigger(Fixture f, UUID holderPrincipalId, UUID accountId) {
        return inCompany(f, () -> accounts.bind(new AccountBinding.Trigger(f.company().id(), f.member1(),
                holderPrincipalId, accountId), f.system()));
    }

    /** Disables or enables a credential of the fixture's Jira. */
    private static void disable(Fixture f, UUID credentialId, boolean disabled) {
        assertThat(f.admin().patch(f.company().path("/systems/" + f.system() + "/credentials/" + credentialId),
                Map.of("enabled", !disabled)).status()).isEqualTo(200);
    }

    private Check check(Fixture f, AccountBinding.Stored stored) {
        return inCompany(f, () -> accounts.check(stored));
    }

    private UUID accountOf(Fixture f, UUID principalId) {
        return inCompany(f, () -> accounts.of(f.company().id(), f.system(), principalId).orElseThrow().id());
    }

    private <T> T inCompany(Fixture f, Supplier<T> work) {
        return transactions.inCompany(f.company().id(), work);
    }

    private static AccountBinding.ConnectRequired connect(Fixture f, ConnectReason reason) {
        return new AccountBinding.ConnectRequired(f.system(), reason, List.of());
    }

    private static AccountBinding.Stopped stop(AccountBinding.StopReason reason) {
        return new AccountBinding.Stopped(reason);
    }

    private static void setStatus(UUID companyId, UUID accountId, String status) throws SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            sql.accepted("UPDATE account SET status = ? WHERE id = ?", status, accountId);
            sql.commit();
        }
    }
}
