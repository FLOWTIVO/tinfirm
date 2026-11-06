package com.flowtivo.tinfirm.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.OAuthStubs;
import com.flowtivo.tinfirm.support.RecordedEvents;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestDatabase;

import tools.jackson.databind.JsonNode;

/**
 * An account's credential for a call (the package 1 contract, §3.6 and I3): revealed for a call that acts as its member
 * only, audited with the purpose; an OAuth access token about to expire is refreshed through the system's app first,
 * and a refusal turns the account {@code RECONNECT}, never another account (I10). Two calls never refresh one grant at
 * once, and a refresh that succeeds makes an account that turned {@code RECONNECT} meanwhile {@code OK} again. The
 * reveal runs outside any transaction, since the refresh waits on the system (CONVENTIONS §10): inside one it is
 * refused.
 */
class CredentialRevealTest extends IntegrationTest {

    @Autowired
    private Accounts accounts;

    @Autowired
    private SystemTransactions transactions;

    @Test
    void anExpiringGrantIsRefreshedAndAMembersCredentialActsForThemOnly() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        OAuthStubs.jiraClient(admin, company.path("/systems/" + systemId));
        OAuthStubs.connectJira(member, company.path("/me/accounts/" + systemId), jira, "test-access-r1",
                "test-refresh-r1", 30, "mia");
        UUID principal = memberPrincipal(company, company.userId("MEMBER"));
        UUID accountId = transactions.inCompany(company.id(),
                () -> accounts.of(company.id(), systemId, principal).orElseThrow().id());

        // Expires within a minute: refreshed first, the new grant kept.
        OAuthStubs.jiraTokens(jira, "refresh_token=test-refresh-r1", "test-access-r2", "test-refresh-r2", 3600);
        Optional<Accounts.RevealedCredential> first = reveal(company, accountId, principal, "test-call:1");
        assertThat(first).hasValueSatisfying(credential -> {
            assertThat(credential.credential().token()).isEqualTo("test-access-r2");
            assertThat(credential.credential().oauth()).isTrue();
            assertThat(credential.toString()).doesNotContain("test-access");
        });
        assertThat(OAuthStubs.jiraRefreshes(jira)).hasSize(1);
        assertThat(reveal(company, accountId, principal, "test-call:2")).hasValueSatisfying(
                credential -> assertThat(credential.credential().token()).isEqualTo("test-access-r2"));
        assertThat(OAuthStubs.jiraRefreshes(jira)).hasSize(1);

        // I3: never for a call that acts as someone else; each reveal is audited with its purpose.
        UUID ownerPrincipal = memberPrincipal(company, company.userId("OWNER"));
        assertThatThrownBy(() -> reveal(company, accountId, ownerPrincipal, "test-call:3"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(auditEvents(owner, company, "secret.accessed")).extracting(event -> event.get("details")
            .get("purpose")
            .asString()).contains("test-call:1", "test-call:2").doesNotContain("test-call:3");
        auditEvents(owner, company).forEach(event -> assertThat(event.toString()).doesNotContain("test-access-r"));
    }

    @Test
    void aRefusedRefreshAsksToReconnect() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        OAuthStubs.jiraClient(admin, company.path("/systems/" + systemId));
        OAuthStubs.connectJira(member, company.path("/me/accounts/" + systemId), jira, "test-access-x1",
                "test-refresh-x1", 1, "max");
        UUID principal = memberPrincipal(company, company.userId("MEMBER"));
        UUID accountId = transactions.inCompany(company.id(),
                () -> accounts.of(company.id(), systemId, principal).orElseThrow().id());

        OAuthStubs.jiraRefuses(jira, "refresh_token=test-refresh-x1", "invalid_grant");
        assertThat(reveal(company, accountId, principal, "test-call:x")).isEmpty();
        JsonNode account = member.get(company.path("/me/accounts")).json().get("items").get(0);
        assertThat(account.get("account").get("status").asString()).isEqualTo("RECONNECT");
        assertThat(account.get("binding").asString()).isEqualTo("RECONNECT_YOURS");
        assertThat(auditEvents(owner, company, "account.refused")).singleElement()
            .satisfies(event -> assertThat(event.get("targetId").asString()).isEqualTo(accountId.toString()));
        assertThat(RecordedEvents.of(company.id(), AccountEvents.AccountNeedsReconnect.class))
            .containsExactly(new AccountEvents.AccountNeedsReconnect(company.id(), accountId, systemId, principal));
        // Not OK: nothing is revealed until the member signs in again.
        assertThat(reveal(company, accountId, principal, "test-call:y")).isEmpty();
    }

    @Test
    void concurrentRefreshesOfARotatingGrantSpendItOnceAndTheAccountStaysOk() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        OAuthStubs.jiraClient(admin, company.path("/systems/" + systemId));
        OAuthStubs.connectJira(member, company.path("/me/accounts/" + systemId), jira, "test-access-rot-a",
                "test-refresh-rot-a", 30, "rosa");
        UUID principal = memberPrincipal(company, company.userId("MEMBER"));
        UUID accountId = transactions.inCompany(company.id(),
                () -> accounts.of(company.id(), systemId, principal).orElseThrow().id());

        // Jira rotates: the first refresh spends the refresh token (and answers late), a second with it is refused.
        OAuthStubs.jiraRotates(jira, "test-refresh-rot-a", "test-access-rot-b", "test-refresh-rot-b", 3600, 2000);
        Optional<Accounts.RevealedCredential> first;
        Optional<Accounts.RevealedCredential> second;
        try (ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Optional<Accounts.RevealedCredential>> one = calls
                .submit(() -> reveal(company, accountId, principal, "test-call:rot-1"));
            await().atMost(Duration.ofSeconds(10)).until(() -> OAuthStubs.jiraRotationSpent(jira));
            // A second call while the first one's refresh is under way: it read the spent refresh token too.
            Future<Optional<Accounts.RevealedCredential>> two = calls
                .submit(() -> reveal(company, accountId, principal, "test-call:rot-2"));
            first = one.get(20, TimeUnit.SECONDS);
            second = two.get(20, TimeUnit.SECONDS);
        }

        // One refresh: the second call waited for it and took the grant it stored. The account stays OK.
        assertThat(first).hasValueSatisfying(
                credential -> assertThat(credential.credential().token()).isEqualTo("test-access-rot-b"));
        assertThat(second).hasValueSatisfying(
                credential -> assertThat(credential.credential().token()).isEqualTo("test-access-rot-b"));
        assertThat(OAuthStubs.jiraRefreshes(jira)).hasSize(1);
        assertThat(transactions.inCompany(company.id(), () -> accounts.find(company.id(), accountId)).orElseThrow()
            .status()).isEqualTo(AccountInfo.Status.OK);
        assertThat(auditEvents(owner, company, "account.refused")).isEmpty();
        assertThat(RecordedEvents.of(company.id(), AccountEvents.AccountNeedsReconnect.class)).isEmpty();
    }

    @Test
    void aRefreshThatSucceedsAfterTheAccountTurnedReconnectMakesItUsableAgain() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        OAuthStubs.jiraClient(admin, company.path("/systems/" + systemId));
        OAuthStubs.connectJira(member, company.path("/me/accounts/" + systemId), jira, "test-access-again-a",
                "test-refresh-again-a", 30, "ada");
        UUID principal = memberPrincipal(company, company.userId("MEMBER"));
        UUID accountId = transactions.inCompany(company.id(),
                () -> accounts.of(company.id(), systemId, principal).orElseThrow().id());

        OAuthStubs.jiraRotates(jira, "test-refresh-again-a", "test-access-again-b", "test-refresh-again-b", 3600,
                2000);
        Optional<Accounts.RevealedCredential> refreshed;
        try (ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Optional<Accounts.RevealedCredential>> one = calls
                .submit(() -> reveal(company, accountId, principal, "test-call:again"));
            await().atMost(Duration.ofSeconds(10)).until(() -> OAuthStubs.jiraRotationSpent(jira));
            // Meanwhile the account turned RECONNECT, as another replica's refresh with the spent token, or a call
            // with the old access token, would have it.
            assertThat(transactions.inCompany(company.id(), () -> accounts.refused(company.id(), accountId,
                    "The system refused to refresh the grant: invalid_grant"))).isTrue();
            refreshed = one.get(20, TimeUnit.SECONDS);
        }

        // The refresh proved the grant: OK again, audited and announced as a reconnected account.
        assertThat(refreshed).hasValueSatisfying(
                credential -> assertThat(credential.credential().token()).isEqualTo("test-access-again-b"));
        assertThat(transactions.inCompany(company.id(), () -> accounts.find(company.id(), accountId)).orElseThrow()
            .status()).isEqualTo(AccountInfo.Status.OK);
        JsonNode listed = member.get(company.path("/me/accounts")).json().get("items").get(0);
        assertThat(listed.get("binding").asString()).isEqualTo("OWN");
        assertThat(RecordedEvents.of(company.id(), AccountEvents.AccountReconnected.class))
            .containsExactly(new AccountEvents.AccountReconnected(company.id(), accountId, systemId, principal));
        assertThat(auditEvents(owner, company, "account.reconnected")).singleElement().satisfies(event -> {
            assertThat(event.get("targetId").asString()).isEqualTo(accountId.toString());
            assertThat(event.get("details").get("refreshed").asBoolean()).isTrue();
            assertThat(event.get("details").get("kind").asString()).isEqualTo("OAUTH");
        });
        auditEvents(owner, company).forEach(event -> assertThat(event.toString()).doesNotContain("test-access-again"));
    }

    @Test
    void aCredentialTheSystemRefusedOnACallAsksToReconnectOnce() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        UUID bot = createServiceIdentity(admin, company, "Bot");
        UUID accountId = connectServiceToken(admin, company, bot, systemId, jira, "test-bot-token-401", "bot-401");

        // A tool call met 401 (the package 3 contract, §5.3): the account turns RECONNECT, audited and announced. The
        // system's text is scrubbed (the security review of 2026-10-27, I2): the echoed token masked, the NUL and the
        // newline gone, cut to 300.
        boolean turned = transactions.inCompany(company.id(), () -> accounts.refused(company.id(), accountId,
                "Jira answered 401\u0000\n for ATATT3xFfGF0TestEchoedToken " + "x".repeat(600)));
        assertThat(turned).isTrue();
        assertThat(transactions.inCompany(company.id(), () -> accounts.find(company.id(), accountId)).orElseThrow()
            .status()).isEqualTo(AccountInfo.Status.RECONNECT);
        assertThat(auditEvents(owner, company, "account.refused")).singleElement().satisfies(event -> {
            assertThat(event.get("targetId").asString()).isEqualTo(accountId.toString());
            assertThat(event.get("details").get("reason").asString()).startsWith("Jira answered 401 for <token>")
                .doesNotContain("ATATT3xFfGF0")
                .hasSize(300);
            assertThat(event.get("actor").get("type").asString()).isEqualTo("SYSTEM");
        });
        assertThat(RecordedEvents.of(company.id(), AccountEvents.AccountNeedsReconnect.class))
            .containsExactly(new AccountEvents.AccountNeedsReconnect(company.id(), accountId, systemId, null));

        // Not OK any more, or gone: nothing happens again.
        assertThat(transactions.inCompany(company.id(), () -> accounts.refused(company.id(), accountId, "again")))
            .isFalse();
        assertThat(transactions.inCompany(company.id(), () -> accounts.refused(company.id(), UUID.randomUUID(),
                "unknown"))).isFalse();
        assertThat(auditEvents(owner, company, "account.refused")).hasSize(1);
        assertThat(RecordedEvents.of(company.id(), AccountEvents.AccountNeedsReconnect.class)).hasSize(1);
    }

    @Test
    void aGrantThatExpiredAfterTheAppWasRemovedAsksToReconnect() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        OAuthStubs.jiraClient(admin, company.path("/systems/" + systemId));
        // expires_in 0: expired at once.
        OAuthStubs.connectJira(member, company.path("/me/accounts/" + systemId), jira, "test-access-n1",
                "test-refresh-n1", 0, "nora");
        UUID principal = memberPrincipal(company, company.userId("MEMBER"));
        UUID accountId = transactions.inCompany(company.id(),
                () -> accounts.of(company.id(), systemId, principal).orElseThrow().id());

        // The app goes; the account stays as it is until its grant cannot be refreshed.
        assertThat(admin.delete(company.path("/systems/" + systemId + "/app")).status()).isEqualTo(204);
        JsonNode before = member.get(company.path("/me/accounts")).json().get("items").get(0);
        assertThat(before.get("account").get("status").asString()).isEqualTo("OK");
        assertThat(before.get("oauthAvailable").asBoolean()).isFalse();

        assertThat(reveal(company, accountId, principal, "test-call:n")).isEmpty();
        assertThat(OAuthStubs.jiraRefreshes(jira)).isEmpty();
        JsonNode after = member.get(company.path("/me/accounts")).json().get("items").get(0);
        assertThat(after.get("account").get("status").asString()).isEqualTo("RECONNECT");
        assertThat(auditEvents(owner, company, "account.refused")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("reason").asString()).contains("no app"));
    }

    @Test
    void aRevealNeverHoldsTheAuditChainHeadWhileItWaitsForTheAccountsRow() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        UUID bot = createServiceIdentity(admin, company, "Bot");
        UUID accountId = connectServiceToken(admin, company, bot, systemId, jira, "test-bot-token-order", "bot-order");

        // A transaction that changes the account, as a refusal does (CONVENTIONS §10): its row first, then the audit
        // chain head. A reveal meanwhile must not hold the chain head while it waits for the row, or the two deadlock.
        try (ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Optional<Accounts.RevealedCredential>> revealed;
            try (TenantSql refusal = TenantSql.in(company.id())) {
                assertThat(refusal.column("SELECT id FROM account WHERE id = ? FOR UPDATE", accountId)).hasSize(1);
                revealed = calls.submit(() -> reveal(company, accountId, bot, "test-call:order"));
                await().atMost(Duration.ofSeconds(20)).until(() -> waitsForTheAccountsRow());
                assertThat(refusal.column("SELECT last_seq FROM audit_chain_head WHERE company_id = ? FOR UPDATE",
                        company.id())).hasSize(1);
            }
            assertThat(revealed.get(20, TimeUnit.SECONDS)).hasValueSatisfying(
                    credential -> assertThat(credential.credential().token()).isEqualTo("test-bot-token-order"));
        }
        assertThat(transactions.inCompany(company.id(), () -> accounts.find(company.id(), accountId)).orElseThrow()
            .status()).isEqualTo(AccountInfo.Status.OK);
    }

    /** Whether a transaction of this test database waits for a lock to write an account's last use. */
    private static boolean waitsForTheAccountsRow() throws SQLException {
        try (Connection superuser = TestDatabase.connectAsSuperuser(TestDatabase.jdbcUrl());
                PreparedStatement select = superuser.prepareStatement("""
                        SELECT count(*) FROM pg_stat_activity
                         WHERE datname = current_database() AND wait_event_type = 'Lock'
                           AND query LIKE 'UPDATE account SET last_used_at%'""");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1) > 0;
        }
    }

    @Test
    void aCredentialIsNeverRevealedInsideATransaction() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        UUID bot = createServiceIdentity(admin, company, "Bot");
        UUID accountId = connectServiceToken(admin, company, bot, systemId, jira, "test-bot-token-tx", "bot-tx");

        // A refresh would wait on the system with the transaction's connection held: refused, whatever the account.
        assertThatThrownBy(() -> transactions.inCompany(company.id(),
                () -> accounts.reveal(company.id(), accountId, bot, "test-call:tx")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside a database transaction");
        assertThat(reveal(company, accountId, bot, "test-call:outside")).hasValueSatisfying(
                credential -> assertThat(credential.credential().token()).isEqualTo("test-bot-token-tx"));
    }

    @Test
    void aCredentialIsRevealedThroughAServiceAccountOnlyWhileItIsActiveAndBorrowsIt() {
        // I7 and ADR-0072: a suspended service account, or one that no longer borrows the credential, acts with it for
        // nothing; a poll the credential's triggers share reveals it without one.
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        UUID bot = createServiceIdentity(admin, company, "Bot");
        UUID other = createServiceIdentity(admin, company, "Other");
        UUID accountId = connectServiceToken(admin, company, bot, systemId, jira, "test-bot-token-lent", "bot-lent");

        assertThat(reveal(company, accountId, bot, "test-call:lent")).isPresent();
        assertThat(reveal(company, accountId, other, "test-call:not-lent")).isEmpty();
        assertThat(reveal(company, accountId, null, "test-poll:shared")).isPresent();
        assertThat(admin.post(company.path("/service-identities/" + bot + "/suspend"), null).status())
            .isEqualTo(200);
        assertThat(reveal(company, accountId, bot, "test-call:suspended")).isEmpty();
        assertThat(admin.post(company.path("/service-identities/" + bot + "/resume"), null).status())
            .isEqualTo(200);
        assertThat(admin.delete(company.path("/systems/" + systemId + "/credentials/" + accountId
                + "/service-accounts/" + bot)).status()).isEqualTo(204);
        assertThat(reveal(company, accountId, bot, "test-call:withdrawn")).isEmpty();

        // A member acts with it through a grant, which the caller checked; one who left, never.
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        assertThat(reveal(company, accountId, member, "test-call:member")).isPresent();
        assertThat(loginAs(company.email("OWNER")).delete(company.path("/members/" + company.userId("MEMBER")))
            .status()).isEqualTo(204);
        assertThat(reveal(company, accountId, member, "test-call:left")).isEmpty();
    }

    /** Reveals as the system in the company, outside any transaction, as callers of {@code Accounts.reveal} do. */
    private Optional<Accounts.RevealedCredential> reveal(TestCompany company, UUID accountId,
            @org.jspecify.annotations.Nullable UUID acting, String purpose) {
        return SystemContext.callInCompany(company.id(),
                () -> accounts.reveal(company.id(), accountId, acting, purpose));
    }
}
