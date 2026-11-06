package com.flowtivo.tinfirm.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.RecordedEvents;
import com.flowtivo.tinfirm.support.TestCredentials;

import tools.jackson.databind.JsonNode;

/**
 * Accounts and grants over HTTP (the package 1 contract, §3.6, §3.7, §6 to §8, I1 to I5): a member connects and
 * disconnects their own accounts only, checked with the system's "who am I"; admins connect service identities'
 * accounts and lend them to groups; nobody sees another member's credential, and the audit names no token.
 */
class AccountIntegrationTest extends IntegrationTest {

    @Test
    void aMemberConnectsTheirOwnAccountByTokenAndNobodyElseSeesIt() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Support Jira", "JIRA_DC", jira.baseUrl(), false);
        String mine = company.path("/me/accounts/" + systemId);

        jira.myself("test-member-token", "mia", "UTC");
        ApiResponse connected = member.put(mine + "/token", Map.of("token", "test-member-token"));
        assertThat(connected.status()).as("%s", connected).isEqualTo(200);
        JsonNode account = connected.json();
        assertThat(account.get("kind").asString()).isEqualTo("TOKEN");
        assertThat(account.get("status").asString()).isEqualTo("OK");
        assertThat(account.get("login").asString()).isEqualTo("mia");
        // What a conversation of the member binds now, as their accounts page says it.
        assertThat(account.get("binding").asString()).isEqualTo("OWN");
        assertThat(connected.body()).doesNotContain("test-member-token");

        // The system refuses a token: 400, audited even so, with the system's reason and never the token.
        ApiResponse refused = owner.put(mine + "/token", Map.of("token", "test-wrong-token"));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.problemCode()).isEqualTo("ACCOUNT_REFUSED");
        // The reason names the system by its name, never its kind's enum.
        assertThat(refused.json().get("detail").asString()).contains("Support Jira answered ")
            .doesNotContain("JIRA_DC");
        assertThat(auditEvents(owner, company, "account.refused")).singleElement()
            .satisfies(event -> assertThat(event.toString()).doesNotContain("test-wrong-token"));

        // I2: one principal per identity on a system. Another member's is said as a member's, never by who they are.
        ApiResponse same = owner.put(mine + "/token", Map.of("token", "test-member-token"));
        assertThat(same.status()).isEqualTo(409);
        assertThat(same.problemCode()).isEqualTo("IDENTITY_IN_USE");
        assertThat(same.json().get("details").get("holder").asString()).isEqualTo("MEMBER");
        assertThat(same.json().get("details").has("serviceIdentityName")).isFalse();
        assertThat(same.json().get("detail").asString())
            .isEqualTo("The Jira Data Center user mia is another member's own account on Support Jira")
            .doesNotContain(company.email("MEMBER"));

        // The member's page: their own account, bound as their own; the owner's: nothing to bind but their own.
        JsonNode page = member.get(company.path("/me/accounts")).json().get("items").get(0);
        assertThat(page.get("account").get("login").asString()).isEqualTo("mia");
        assertThat(page.get("binding").asString()).isEqualTo("OWN");
        JsonNode ownersPage = owner.get(company.path("/me/accounts")).json().get("items").get(0);
        assertThat(ownersPage.has("account")).isFalse();
        assertThat(ownersPage.get("binding").asString()).isEqualTo("CONNECT");

        // I3: the member's secret is theirs: no Secrets page lists it, and the audit names the member, not the token.
        assertThat(owner.get(company.path("/secrets")).json().get("items")).noneSatisfy(secret -> assertThat(
                secret.get("name").asString()).startsWith("MEMBER_ACCOUNT"));
        assertThat(auditEvents(owner, company, "account.connected")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("userId").asString())
                .isEqualTo(company.userId("MEMBER").toString()));
        auditEvents(owner, company).forEach(event -> assertThat(event.toString()).doesNotContain("test-member-token"));

        // Stored again: reconnected; then disconnected, and gone.
        assertThat(member.put(mine + "/token", Map.of("token", "test-member-token")).status()).isEqualTo(200);
        assertThat(auditEvents(owner, company, "account.reconnected")).hasSize(1);
        assertThat(member.delete(mine).status()).isEqualTo(204);
        assertThat(member.delete(mine).status()).isEqualTo(404);
        assertThat(member.get(company.path("/me/accounts")).json().get("items").get(0).has("account")).isFalse();
        UUID accountId = UUID.fromString(account.get("id").asString());
        UUID principal = memberPrincipal(company, company.userId("MEMBER"));
        assertThat(RecordedEvents.of(company.id(), AccountEvents.AccountConnected.class))
            .containsExactly(new AccountEvents.AccountConnected(company.id(), accountId, systemId, principal));
        assertThat(RecordedEvents.of(company.id(), AccountEvents.AccountReconnected.class)).hasSize(1);
        assertThat(RecordedEvents.of(company.id(), AccountEvents.AccountDisconnected.class))
            .containsExactly(new AccountEvents.AccountDisconnected(company.id(), accountId, systemId, principal));
    }

    @Test
    void aMembersAccountsPageLeavesOutAWebhookSystem() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        UUID jiraId = createSystem(admin, company, "Support Jira", "JIRA_DC", JiraStub.newInstance().baseUrl(), false);
        UUID hookId = createSystem(admin, company, Map.of("name", unique("Ops alerts"), "kind", "WEBHOOK", "webhook",
                Map.of("scheme", "STANDARD", "mapping", Map.of("event", "/type"))));

        // A webhook takes deliveries under its signing secret: no one's account, so nothing on the page to connect.
        JsonNode items = member.get(company.path("/me/accounts")).json().get("items");
        assertThat(items).singleElement()
            .satisfies(item -> assertThat(item.get("system").get("id").asString()).isEqualTo(jiraId.toString()));
        assertThat(items.toString()).doesNotContain(hookId.toString());
        ApiResponse token = member.put(company.path("/me/accounts/" + hookId + "/token"), Map.of("token", "t"));
        assertThat(token.problemCode()).isEqualTo("SYSTEM_KIND_INVALID");
    }

    @Test
    void clientCredentialsAreACredentialOfAJiraCloudSystemOnly() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        String credentials = company.path("/systems/" + systemId + "/credentials");
        Map<String, Object> client = Map.of("name", "Service account", "clientId", "test-client", "clientSecret",
                "test-client-secret");

        // Only OWNER and ADMIN add one, and only Jira Cloud takes one: nothing is sent to Atlassian for another kind.
        assertThat(member.post(credentials + "/client", client).status()).isEqualTo(403);
        ApiResponse onDataCenter = admin.post(credentials + "/client", client);
        assertThat(onDataCenter.status()).isEqualTo(400);
        assertThat(onDataCenter.problemCode()).isEqualTo("SYSTEM_KIND_INVALID");
        assertThat(admin.get(credentials).json().get("items")).isEmpty();

        // Nor does a credential of another kind take client credentials in place of its token.
        jira.myself("test-bot-token", "client-bot-" + systemId, "UTC");
        UUID credentialId = TestCredentials.add(admin, company.path(""), systemId, "Bot", "test-bot-token");
        ApiResponse replaced = admin.put(credentials + "/" + credentialId + "/client", Map.of("clientId",
                "test-client", "clientSecret", "test-client-secret"));
        assertThat(replaced.status()).isEqualTo(400);
        assertThat(replaced.problemCode()).isEqualTo("SYSTEM_KIND_INVALID");
        assertThat(admin.get(credentials).json().get("items")).singleElement()
            .satisfies(item -> assertThat(item.get("kind").asString()).isEqualTo("TOKEN"));
    }

    @Test
    void adminsAddCredentialsToASystemAndLendThemToGroupsAndServiceAccounts() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        String credentials = company.path("/systems/" + systemId + "/credentials");

        // I4: only OWNER and ADMIN add a system's credential; its name is unique on the system, ignoring case.
        jira.myself("test-bot-token", "support-bot", "UTC");
        assertThat(member.post(credentials, Map.of("name", "Support bot", "token", "test-bot-token")).status())
            .isEqualTo(403);
        UUID credentialId = TestCredentials.add(admin, company.path(""), systemId, "Support bot", "test-bot-token");
        assertThat(admin.get(credentials).json().get("items")).singleElement().satisfies(item -> {
            assertThat(item.get("name").asString()).isEqualTo("Support bot");
            assertThat(item.get("login").asString()).isEqualTo("support-bot");
            assertThat(item.get("enabled").asBoolean()).isTrue();
            assertThat(item.get("bot").asBoolean()).isFalse();
        });
        assertThat(member.get(credentials).status()).isEqualTo(403);
        ApiResponse sameName = admin.post(credentials, Map.of("name", "support BOT", "token", "test-bot-token"));
        assertThat(sameName.status()).as("%s", sameName).isEqualTo(409);
        assertThat(sameName.problemCode()).isEqualTo("DUPLICATE_CREDENTIAL_NAME");
        assertThat(owner.get(company.path("/secrets")).json().get("items")).anySatisfy(secret -> assertThat(
                secret.get("name").asString()).startsWith("SYSTEM_CREDENTIAL"));
        assertThat(auditEvents(owner, company, "account.connected")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("credential").asString()).isEqualTo("Support bot"));

        // The credential keeps its token in a managed secret: the Secrets page does not delete it, which would take
        // the credential and its grants without their audit and events; it is removed on its system's page.
        String managedSecret = secretId(owner, company, "SYSTEM_CREDENTIAL");
        ApiResponse kept = owner.delete(company.path("/secrets/" + managedSecret));
        assertThat(kept.status()).as("%s", kept).isEqualTo(409);
        assertThat(kept.problemCode()).isEqualTo("SECRET_MANAGED");
        assertThat(kept.body()).contains("Support bot").doesNotContain("test-bot-token");
        assertThat(admin.get(credentials).json().get("items")).hasSize(1);
        assertThat(auditEvents(owner, company, "secret.deleted")).isEmpty();
        // A secret that only looks managed, which no credential keeps, is deleted as any other.
        UUID stray = createManagedNamedSecret(owner, company.id(), "SYSTEM_CREDENTIAL_STRAY", "test-stray-value");
        assertThat(owner.delete(company.path("/secrets/" + stray)).status()).isEqualTo(204);

        // Lent to a group READ; members may not lend; a member's account is never lent (I5); one grant per group.
        UUID analysts = createGroup(admin, company, "Analysts", List.of(company.userId("MEMBER")));
        assertThat(member.post(company.path("/grants"), Map.of("credentialId", credentialId.toString(), "groupId",
                analysts.toString(), "access", "READ")).status()).isEqualTo(403);
        assertThat(member.get(company.path("/grants")).status()).isEqualTo(403);
        UUID grantId = createGrant(admin, company, credentialId, analysts, "READ");
        ApiResponse twice = admin.post(company.path("/grants"), Map.of("credentialId", credentialId.toString(),
                "groupId", analysts.toString(), "access", "WRITE"));
        assertThat(twice.problemCode()).isEqualTo("DUPLICATE_GRANT");
        UUID ownAccount = connectToken(owner, company.path("/me/accounts/" + systemId), jira, "test-owner-token",
                "olga");
        ApiResponse notService = admin.post(company.path("/grants"), Map.of("credentialId", ownAccount.toString(),
                "groupId", analysts.toString(), "access", "READ"));
        assertThat(notService.status()).isEqualTo(400);
        assertThat(notService.problemCode()).isEqualTo("GRANT_NOT_SERVICE");
        JsonNode onSystem = admin.get(credentials).json();
        assertThat(onSystem.get("memberAccounts").get("ok").asInt()).isEqualTo(1);
        assertThat(onSystem.get("memberAccounts").get("notOk").asInt()).isZero();
        assertThat(onSystem.get("items").get(0).get("grants").get(0).get("createdAt").asString()).isNotBlank();
        JsonNode listed = admin.get(company.path("/grants")).json().get("items").get(0);
        assertThat(listed.get("credentialId").asString()).isEqualTo(credentialId.toString());
        assertThat(listed.get("credentialName").asString()).isEqualTo("Support bot");
        assertThat(listed.get("groupName").asString()).isEqualTo("Analysts");
        assertThat(listed.get("systemName").asString()).isEqualTo("Jira");

        // The member now acts through the grant, and sees it.
        JsonNode page = member.get(company.path("/me/accounts")).json().get("items").get(0);
        assertThat(page.get("binding").asString()).isEqualTo("GRANTED");
        assertThat(page.get("grants")).singleElement().satisfies(choice -> {
            assertThat(choice.get("credentialName").asString()).isEqualTo("Support bot");
            assertThat(choice.get("access").asString()).isEqualTo("READ");
        });

        // Lent to a service account too: it borrows one credential per system, and its page shows it.
        UUID bot = createServiceIdentity(admin, company, "Support agent");
        TestCredentials.lend(admin, company.path(""), systemId, credentialId, bot, "WRITE");
        assertThat(admin.get(company.path("/service-identities/" + bot + "/credentials")).json().get("items"))
            .singleElement()
            .satisfies(item -> {
                assertThat(item.get("credential").get("name").asString()).isEqualTo("Support bot");
                assertThat(item.get("access").asString()).isEqualTo("WRITE");
            });
        assertThat(admin.get(credentials).json().get("items").get(0).get("serviceAccounts")).singleElement()
            .satisfies(lending -> assertThat(lending.get("name").asString()).isEqualTo("Support agent"));
        assertThat(auditEvents(owner, company, "credential.lent")).hasSize(1);

        // I2 says whom to ask: a member whose own user is the shared credential learns its name; an admin storing a
        // member's user as a credential learns it is a member's.
        ApiResponse shared = member.put(company.path("/me/accounts/" + systemId) + "/token",
                Map.of("token", "test-bot-token"));
        assertThat(shared.status()).isEqualTo(409);
        assertThat(shared.problemCode()).isEqualTo("IDENTITY_IN_USE");
        assertThat(shared.json().get("details").get("holder").asString()).isEqualTo("CREDENTIAL");
        assertThat(shared.json().get("details").get("credentialName").asString()).isEqualTo("Support bot");
        assertThat(shared.json().get("detail").asString()).isEqualTo("The Jira Data Center user support-bot is the "
                + "credential Support bot of Jira, which groups and service accounts use as a shared account. Ask an "
                + "owner or admin to store Support bot with another user, such as a machine user; then connect yours");
        assertThat(shared.body()).doesNotContain("test-bot-token");
        ApiResponse membersUser = admin.put(credentials + "/" + credentialId + "/token",
                Map.of("token", "test-owner-token"));
        assertThat(membersUser.status()).isEqualTo(409);
        assertThat(membersUser.json().get("details").get("holder").asString()).isEqualTo("MEMBER");
        assertThat(membersUser.json().get("detail").asString()).isEqualTo("The Jira Data Center user olga is a "
                + "member's own account on Jira: add the credential with a user of its own, such as a machine user");
        // A token the system refuses for the credential is audited with the credential's name, never the token.
        ApiResponse refusedToken = admin.put(credentials + "/" + credentialId + "/token",
                Map.of("token", "test-refused-token"));
        assertThat(refusedToken.problemCode()).as("%s", refusedToken).isEqualTo("ACCOUNT_REFUSED");
        assertThat(auditEvents(owner, company, "account.refused")).singleElement().satisfies(event -> {
            assertThat(event.get("details").get("credential").asString()).isEqualTo("Support bot");
            assertThat(event.toString()).doesNotContain("test-refused-token");
        });
        // Two credentials may be one user: two tokens of one bot user.
        UUID second = TestCredentials.add(admin, company.path(""), systemId, "Support bot 2", "test-bot-token");

        // WRITE; then disabled: the grant binds nobody, an admin's to enable again.
        assertThat(admin.patch(company.path("/grants/" + grantId), Map.of("access", "WRITE")).json().get("access")
            .asString()).isEqualTo("WRITE");
        ApiResponse disabled = admin.patch(credentials + "/" + credentialId, Map.of("enabled", false));
        assertThat(disabled.status()).as("%s", disabled).isEqualTo(200);
        assertThat(disabled.json().get("enabled").asBoolean()).isFalse();
        assertThat(member.get(company.path("/me/accounts")).json().get("items").get(0).get("binding").asString())
            .isEqualTo("RECONNECT_COMPANY");
        assertThat(admin.patch(credentials + "/" + credentialId, Map.of("enabled", true, "name", "Support"))
            .json().get("name").asString()).isEqualTo("Support");
        assertThat(auditEvents(owner, company, "credential.changed")).hasSize(2);
        assertThat(RecordedEvents.of(company.id(), AccountEvents.CredentialChanged.class)).isNotEmpty();

        // Withdrawn, then lent again and its group deleted: each grant goes with an audit and an event.
        assertThat(admin.delete(company.path("/grants/" + grantId)).status()).isEqualTo(204);
        UUID again = createGrant(admin, company, credentialId, analysts, "READ");
        assertThat(admin.delete(company.path("/groups/" + analysts)).status()).isEqualTo(204);
        assertThat(auditEvents(owner, company, "grant.deleted")).extracting(event -> event.get("targetId").asString())
            .containsExactly(again.toString(), grantId.toString());
        assertThat(auditEvents(owner, company, "grant.deleted").getFirst().get("details").get("cause").asString())
            .isEqualTo("group.deleted");
        assertThat(auditEvents(owner, company, "grant.changed")).hasSize(1);
        assertThat(RecordedEvents.of(company.id(), AccountEvents.GrantCreated.class)).hasSize(2);
        assertThat(RecordedEvents.of(company.id(), AccountEvents.GrantChanged.class)).hasSize(1);
        assertThat(RecordedEvents.of(company.id(), AccountEvents.GrantDeleted.class)).hasSize(2);

        // Deleting the service account withdraws what it borrowed; the credential stays the system's.
        assertThat(admin.delete(company.path("/service-identities/" + bot)).status()).isEqualTo(204);
        assertThat(auditEvents(owner, company, "credential.withdrawn")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("cause").asString())
                .isEqualTo("principal.deleted"));
        assertThat(admin.get(credentials).json().get("items")).hasSize(2);

        // Removing the credential takes its grants and its secret.
        createGrant(admin, company, credentialId, createGroup(admin, company, "Ops", List.of()), "READ");
        assertThat(member.delete(credentials + "/" + credentialId).status()).isEqualTo(403);
        assertThat(admin.delete(credentials + "/" + credentialId).status()).isEqualTo(204);
        assertThat(admin.delete(credentials + "/" + second).status()).isEqualTo(204);
        assertThat(auditEvents(owner, company, "account.disconnected")).hasSize(2);
        assertThat(owner.get(company.path("/secrets")).json().get("items")).noneSatisfy(secret -> assertThat(
                secret.get("name").asString()).startsWith("SYSTEM_CREDENTIAL"));
        assertThat(admin.get(company.path("/grants")).json().get("items")).isEmpty();
    }

    @Test
    void adminsSeeWhetherAMemberHoldsAnAccountAndItsStatusNeverMore() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID jiraSystem = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        createSystem(admin, company, "Another Jira", "JIRA_DC", JiraStub.newInstance().baseUrl(), false);
        UUID personal = createSystem(member, company, "Notes", "MCP",
                com.flowtivo.tinfirm.support.McpStub.newInstance().serves(List.of()).url(), true);
        connectToken(member, company.path("/me/accounts/" + jiraSystem), jira, "test-seen-token", "sam");
        assertThat(member.put(company.path("/me/accounts/" + personal + "/token"), Map.of("token", "test-notes"))
            .status()).isEqualTo(200);
        String path = company.path("/members/" + company.userId("MEMBER") + "/accounts");

        // I4: OWNER and ADMIN see on which company system the member holds an account, and its status; nothing of a
        // personal server, nor who the account is there.
        for (ApiClient reader : List.of(owner, admin)) {
            ApiResponse seen = reader.get(path);
            assertThat(seen.status()).as("%s", seen).isEqualTo(200);
            assertThat(seen.json().get("items")).singleElement().satisfies(item -> {
                assertThat(item.get("systemId").asString()).isEqualTo(jiraSystem.toString());
                assertThat(item.get("systemName").asString()).isEqualTo("Jira");
                assertThat(item.get("status").asString()).isEqualTo("OK");
                assertThat(item.get("systemKind").asString()).isEqualTo("JIRA_DC");
                assertThat(item.size()).isEqualTo(4);
            });
            assertThat(seen.body()).doesNotContain("sam").doesNotContain("test-seen-token");
        }
        assertThat(owner.get(company.path("/members/" + company.userId("ADMIN") + "/accounts")).json()
            .get("items")).isEmpty();

        // A MEMBER sees nobody's, their own neither (they have /me/accounts); a user who is not a member is 404.
        assertThat(member.get(path).status()).isEqualTo(403);
        assertThat(member.get(company.path("/members/" + company.userId("OWNER") + "/accounts")).status())
            .isEqualTo(403);
        TestCompany elsewhere = companyWithRoles();
        assertThat(owner.get(company.path("/members/" + elsewhere.userId("MEMBER") + "/accounts")).status())
            .isEqualTo(404);
        assertThat(loginAs(elsewhere.email("OWNER")).get(path).status()).isEqualTo(404);
    }

    @Test
    void personalServersHoldTheirOwnersAccountsOnly() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        UUID personal = createSystem(admin, company, "Admin's notes", "MCP",
                com.flowtivo.tinfirm.support.McpStub.newInstance().serves(List.of()).url(), true);

        // I6: a personal server holds no credential; another member does not even see it.
        ApiResponse onPersonal = admin.post(company.path("/systems/" + personal + "/credentials"),
                Map.of("name", "Shared", "token", "test-token"));
        assertThat(onPersonal.status()).isEqualTo(400);
        assertThat(onPersonal.problemCode()).isEqualTo("PERSONAL_SYSTEM");
        assertThat(member.put(company.path("/me/accounts/" + personal + "/token"), Map.of("token", "test-token"))
            .status()).isEqualTo(404);

        // The owner connects their own (an MCP server answers its initialize with the token).
        ApiResponse own = admin.put(company.path("/me/accounts/" + personal + "/token"), Map.of("token",
                "test-token"));
        assertThat(own.status()).as("%s", own).isEqualTo(200);
        assertThat(own.json().has("login")).isFalse();
    }

    /** The id of the company's secret whose name starts with the prefix. */
    private static String secretId(ApiClient reader, TestCompany company, String prefix) {
        for (JsonNode secret : reader.get(company.path("/secrets")).json().get("items")) {
            if (secret.get("name").asString().startsWith(prefix)) {
                return secret.get("id").asString();
            }
        }
        throw new AssertionError("no secret named " + prefix);
    }
}
