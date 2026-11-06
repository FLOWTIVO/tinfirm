package com.flowtivo.tinfirm.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;

/**
 * The binding of a conversation (the package 3 contract, §5.3, I6 to I8, §9): a table over each outcome of
 * {@code Accounts.bind} as {@code ConversationBindings.resolve} stores it, and over {@code use} after each change a
 * binding meets (a suspended holder, an account to reconnect, an account replaced with the same and with another
 * identity, a grant removed, the person out of the group, a grant lowered to {@code READ}, a system removed); the
 * person's choices; package 1's events resolving waiting bindings again; and through the API and a fake host, the
 * dispatcher keeping a turn queued with {@code ACCOUNT_REQUIRED} until the person connects (V6), and a read-only grant
 * making the workspace read-only (V5).
 */
class ConversationBindingTest extends ConversationTestBench {

    @Autowired
    ConversationBindings bindings;

    @Autowired
    SystemTransactions transactions;

    /** A bare conversation of the member, to bind accounts in without a turn. */
    private UUID conversation(Bench bench) {
        UUID id = UUID.randomUUID();
        TestConversations.bare(bench.id(), id, "bind-" + id.toString().substring(0, 8), TestRuns.agent(bench.id()),
                bench.memberPrincipal(), "CLAUDE_CODE");
        return id;
    }

    private BindingInfo resolve(Bench bench, UUID conversation, UUID system) {
        return transactions.inCompany(bench.id(), () -> bindings.resolve(bench.id(), conversation, system));
    }

    private BindingInfo find(Bench bench, UUID bindingId) {
        return transactions.inCompany(bench.id(), () -> bindings.find(bench.id(), bindingId)).orElseThrow();
    }

    private ConversationBindings.BindingUse use(Bench bench, BindingInfo binding) {
        return SystemContext.callInCompany(bench.id(), () -> bindings.use(bench.id(), binding.id(), "test"));
    }

    private static String token(ConversationBindings.BindingUse use) {
        assertThat(use).as("%s", use).isInstanceOf(ConversationBindings.BindingUse.Token.class);
        return ((ConversationBindings.BindingUse.Token) use).credential().credential().token();
    }

    private void accountStatus(Bench bench, UUID accountId, String status) throws SQLException {
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update("UPDATE account SET status = ? WHERE id = ?", status, accountId);
            sql.commit();
        }
    }

    private UUID grant(Bench bench, UUID account, UUID group, String access) {
        return createGrant(bench.owner(), bench.company(), account, group, access);
    }

    @Test
    void eachOutcomeOfTheBindingRuleIsStoredAndEveryUseChecksItAgain() throws SQLException {
        Bench bench = bench(false);
        UUID bot = createServiceIdentity(bench.owner(), bench.company(), unique("Bot"));
        UUID otherBot = createServiceIdentity(bench.owner(), bench.company(), unique("Bot"));
        String groupName = unique("Analysts");
        UUID analysts = createGroup(bench.owner(), bench.company(), groupName,
                List.of(bench.company().userId("MEMBER")));
        UUID conversation = conversation(bench);

        // Own: the person's own account, WRITE.
        UUID own = gitHost(bench, "Own")[0];
        connectOwn(bench.member(), bench, own, "test-alice-own");
        BindingInfo ownBinding = resolve(bench, conversation, own);
        assertThat(ownBinding.state()).isEqualTo(BindingInfo.State.BOUND);
        assertThat(ownBinding.how()).isEqualTo(BindingInfo.How.OWN);
        assertThat(ownBinding.holderPrincipalId()).isEqualTo(bench.memberPrincipal());
        assertThat(token(use(bench, ownBinding))).isEqualTo("test-alice-own");
        assertThat(((ConversationBindings.BindingUse.Token) use(bench, ownBinding)).access())
            .isEqualTo(GrantInfo.Access.WRITE);
        // Resolved again, the binding is the same: never re-bound (I7).
        assertThat(resolve(bench, conversation, own).id()).isEqualTo(ownBinding.id());
        // Through the API: whose account it is, the person's own too, for another reader's words; no run asked here.
        JsonNode page = bench.member().get(bench.path("/conversations/" + conversation)).json();
        JsonNode ownView = page.get("bindings").get(0);
        assertThat(ownView.get("holderPrincipalId").asString()).isEqualTo(bench.memberPrincipal().toString());
        assertThat(ownView.get("holderName").asString()).isEqualTo(page.get("personName").asString());
        assertThat(ownView.get("holderKind").asString()).isEqualTo("MEMBER");
        assertThat(ownView.get("text").asString()).endsWith(": your own account");
        assertThat(ownView.path("runId").isMissingNode() || ownView.get("runId").isNull()).isTrue();

        // Granted: the one usable grant, its access read at every use (open point 9).
        UUID shared = gitHost(bench, "Shared")[0];
        UUID botAccount = connectService(bench, bot, shared, "test-bot-shared");
        UUID readGrant = grant(bench, botAccount, analysts, "READ");
        BindingInfo granted = resolve(bench, conversation, shared);
        assertThat(granted.how()).isEqualTo(BindingInfo.How.GRANTED);
        assertThat(granted.grantId()).isEqualTo(readGrant);
        ConversationBindings.BindingUse readUse = use(bench, granted);
        assertThat(token(readUse)).isEqualTo("test-bot-shared");
        assertThat(((ConversationBindings.BindingUse.Token) readUse).access()).isEqualTo(GrantInfo.Access.READ);
        assertThat(bench.owner().patch(bench.path("/grants/" + readGrant), Map.of("access", "WRITE")).status())
            .isEqualTo(200);
        assertThat(((ConversationBindings.BindingUse.Token) use(bench, granted)).access())
            .isEqualTo(GrantInfo.Access.WRITE);
        assertThat(bench.owner().patch(bench.path("/grants/" + readGrant), Map.of("access", "READ")).status())
            .isEqualTo(200);
        assertThat(((ConversationBindings.BindingUse.Token) use(bench, granted)).access())
            .isEqualTo(GrantInfo.Access.READ);

        // A grant lends the system's credential: a suspended service account that borrows it changes nothing here
        // (ADR-0072). Disabled, the binding waits for an admin; enabled again, it is bound again (package 1's event).
        assertThat(bench.owner().post(bench.path("/service-identities/" + bot + "/suspend"), null).status())
            .isEqualTo(200);
        assertThat(use(bench, granted)).isInstanceOf(ConversationBindings.BindingUse.Token.class);
        assertThat(bench.owner().post(bench.path("/service-identities/" + bot + "/resume"), null).status())
            .isEqualTo(200);
        String credential = bench.path("/systems/" + shared + "/credentials/" + botAccount);
        assertThat(bench.owner().patch(credential, Map.of("enabled", false)).status()).isEqualTo(200);
        assertThat(use(bench, granted)).isInstanceOf(ConversationBindings.BindingUse.Unavailable.class);
        assertThat(find(bench, granted.id()).reason()).isEqualTo(BindingInfo.Reason.RECONNECT_COMPANY);
        // The person reads what an admin must do: enable it, by its name.
        JsonNode waiting = java.util.stream.StreamSupport.stream(bench.member().get(bench.path("/conversations/"
                + conversation)).json().get("bindings").spliterator(), false)
            .filter(binding -> binding.get("id").asString().equals(granted.id().toString()))
            .findFirst()
            .orElseThrow();
        assertThat(waiting.get("credentialEnabled").asBoolean()).isFalse();
        assertThat(waiting.get("credentialKind").asString()).isEqualTo("TOKEN");
        assertThat(waiting.get("text").asString()).contains("an admin must enable the shared credential");
        assertThat(bench.owner().patch(credential, Map.of("enabled", true)).status()).isEqualTo(200);
        await().atMost(WAIT).until(() -> find(bench, granted.id()).bound());
        // The person leaves the group: gone for good (I7).
        assertThat(bench.owner().put(bench.path("/groups/" + analysts), Map.of("name", groupName, "memberIds",
                List.of())).status()).isEqualTo(200);
        await().atMost(WAIT).until(() -> find(bench, granted.id()).gone());
        assertThat(find(bench, granted.id()).reason()).isEqualTo(BindingInfo.Reason.LEFT_GROUP);
        // No continue helps a gone binding.
        assertThat(bench.member().post(bench.path("/conversations/" + conversation + "/bindings/" + shared
                + "/continue"), null).json().get("code").asString()).isEqualTo("BINDING_NOT_WAITING");
        assertThat(bench.owner().put(bench.path("/groups/" + analysts), Map.of("name", groupName, "memberIds",
                List.of(bench.company().userId("MEMBER").toString()))).status()).isEqualTo(200);
        assertThat(use(bench, granted)).isInstanceOf(ConversationBindings.BindingUse.Unavailable.class);
        assertThat(find(bench, granted.id()).gone()).isTrue();

        // A grant removed: gone.
        UUID removable = gitHost(bench, "Removable")[0];
        UUID removableGrant = grant(bench, connectService(bench, bot, removable, "test-bot-removable"), analysts,
                "WRITE");
        BindingInfo toRemove = resolve(bench, conversation(bench), removable);
        assertThat(toRemove.bound()).isTrue();
        assertThat(bench.owner().delete(bench.path("/grants/" + removableGrant)).status()).isEqualTo(204);
        await().atMost(WAIT).until(() -> find(bench, toRemove.id()).gone());
        assertThat(find(bench, toRemove.id()).reason()).isEqualTo(BindingInfo.Reason.GRANT_REMOVED);

        // Choose: several usable grants; the person picks one (CHOSEN), and only one offered.
        UUID many = gitHost(bench, "Many")[0];
        UUID first = grant(bench, connectService(bench, bot, many, "test-bot-many"), analysts, "WRITE");
        UUID second = grant(bench, connectService(bench, otherBot, many, "test-other-many"), analysts, "WRITE");
        UUID choosing = conversation(bench);
        BindingInfo pick = resolve(bench, choosing, many);
        assertThat(pick.reason()).isEqualTo(BindingInfo.Reason.PICK_GRANT);
        assertThat(pick.choices()).containsExactlyInAnyOrder(first, second);
        ApiResponse notOffered = bench.member().post(bench.path("/conversations/" + choosing + "/bindings/" + many
                + "/choose"), Map.of("grantId", readGrant.toString()));
        assertThat(notOffered.json().get("code").asString()).isEqualTo("GRANT_NOT_OFFERED");
        ApiResponse ownerChooses = bench.owner().post(bench.path("/conversations/" + choosing + "/bindings/" + many
                + "/choose"), Map.of("grantId", second.toString()));
        assertThat(ownerChooses.json().get("code").asString()).isEqualTo("CONVERSATION_BOUND");
        ApiResponse chosen = bench.member().post(bench.path("/conversations/" + choosing + "/bindings/" + many
                + "/choose"), Map.of("grantId", second.toString()));
        assertThat(chosen.status()).as("%s", chosen).isEqualTo(200);
        assertThat(chosen.json().get("how").asString()).isEqualTo("CHOSEN");
        assertThat(chosen.json().get("grantId").asString()).isEqualTo(second.toString());
        assertThat(token(use(bench, find(bench, pick.id())))).isEqualTo("test-other-many");
        // A continue of a binding settled a moment earlier is answered as it is, idempotently: 200, nothing changes.
        int boundEvents = auditEvents(bench.owner(), bench.company(), "conversation.binding.bound").size();
        ApiResponse settled = bench.member().post(bench.path("/conversations/" + choosing + "/bindings/" + many
                + "/continue"), null);
        assertThat(settled.status()).as("%s", settled).isEqualTo(200);
        assertThat(settled.json().get("state").asString()).isEqualTo("BOUND");
        assertThat(settled.json().get("how").asString()).isEqualTo("CHOSEN");
        assertThat(auditEvents(bench.owner(), bench.company(), "conversation.binding.bound")).hasSize(boundEvents);

        // ConnectRequired: NONE, then RECONNECT_YOURS, CHOOSE and RECONNECT_COMPANY.
        UUID none = gitHost(bench, "None")[0];
        assertThat(resolve(bench, conversation(bench), none).reason()).isEqualTo(BindingInfo.Reason.NONE);
        UUID broken = gitHost(bench, "Broken")[0];
        UUID brokenOwn = connectOwn(bench.member(), bench, broken, "test-alice-broken");
        accountStatus(bench, brokenOwn, "RECONNECT");
        assertThat(resolve(bench, conversation(bench), broken).reason())
            .isEqualTo(BindingInfo.Reason.RECONNECT_YOURS);
        UUID brokenOrGrant = gitHost(bench, "Either")[0];
        accountStatus(bench, connectOwn(bench.member(), bench, brokenOrGrant, "test-alice-either"), "RECONNECT");
        grant(bench, connectService(bench, bot, brokenOrGrant, "test-bot-either"), analysts, "READ");
        UUID eitherConversation = conversation(bench);
        BindingInfo either = resolve(bench, eitherConversation, brokenOrGrant);
        assertThat(either.reason()).isEqualTo(BindingInfo.Reason.CHOOSE);
        assertThat(either.choices()).hasSize(1);
        // "Reconnect" turns CHOOSE into RECONNECT_YOURS; signing in again binds the person's own account.
        assertThat(bench.member().post(bench.path("/conversations/" + eitherConversation + "/bindings/"
                + brokenOrGrant + "/choose"), Map.of("reconnect", true)).json().get("reason").asString())
            .isEqualTo("RECONNECT_YOURS");
        connectOwn(bench.member(), bench, brokenOrGrant, "test-alice-either-again");
        await().atMost(WAIT).until(() -> find(bench, either.id()).bound());
        assertThat(find(bench, either.id()).how()).isEqualTo(BindingInfo.How.OWN);
        UUID companyBroken = gitHost(bench, "Company")[0];
        UUID companyAccount = connectService(bench, bot, companyBroken, "test-bot-company");
        grant(bench, companyAccount, analysts, "WRITE");
        accountStatus(bench, companyAccount, "RECONNECT");
        BindingInfo company = resolve(bench, conversation(bench), companyBroken);
        assertThat(company.reason()).isEqualTo(BindingInfo.Reason.RECONNECT_COMPANY);
        // An admin stores the credential's token again: the waiting binding is resolved again.
        assertThat(bench.owner().put(bench.path("/systems/" + companyBroken + "/credentials/" + companyAccount
                + "/token"), Map.of("token", "test-bot-company-again")).status()).isEqualTo(200);
        await().atMost(WAIT).until(() -> find(bench, company.id()).bound());
        assertThat(token(use(bench, find(bench, company.id())))).isEqualTo("test-bot-company-again");

        // An account to reconnect after binding: the use waits; the same identity connected again is the account.
        UUID ownAccount = find(bench, ownBinding.id()).accountId();
        accountStatus(bench, ownAccount, "RECONNECT");
        assertThat(use(bench, ownBinding)).isInstanceOf(ConversationBindings.BindingUse.Unavailable.class);
        assertThat(find(bench, ownBinding.id()).reason()).isEqualTo(BindingInfo.Reason.RECONNECT_YOURS);
        assertThat(bench.member().delete(bench.path("/me/accounts/" + own)).status()).isEqualTo(204);
        connectOwn(bench.member(), bench, own, "test-alice-own-again");
        await().atMost(WAIT).until(() -> find(bench, ownBinding.id()).bound());
        assertThat(find(bench, ownBinding.id()).accountId()).isNotEqualTo(ownAccount);
        assertThat(token(use(bench, ownBinding))).isEqualTo("test-alice-own-again");

        // A system removed: gone.
        assertThat(bench.owner().delete(bench.path("/systems/" + own)).status()).isEqualTo(204);
        await().atMost(WAIT).until(() -> find(bench, ownBinding.id()).gone());
        assertThat(find(bench, ownBinding.id()).reason()).isEqualTo(BindingInfo.Reason.SYSTEM_REMOVED);
        assertThat(auditEvents(bench.owner(), bench.company(), "conversation.binding.gone")).isNotEmpty();
    }

    @Test
    void anAccountReplacedByAnotherIdentityIsGone() {
        Bench bench = bench(false);
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID tracker = createSystem(bench.owner(), bench.company(), unique("Jira"), "JIRA_DC", jira.baseUrl(), false);
        String accounts = bench.path("/me/accounts/" + tracker);
        connectToken(bench.member(), accounts, jira, "test-alice-jira", "alice");
        UUID conversation = conversation(bench);
        BindingInfo binding = resolve(bench, conversation, tracker);
        assertThat(binding.externalId()).isNotNull();
        assertThat(token(use(bench, binding))).isEqualTo("test-alice-jira");
        // Connected again as another person on Jira: the conversation's account is gone, never re-bound (I6, I7).
        connectToken(bench.member(), accounts, jira, "test-mallory-jira", "mallory");
        assertThat(use(bench, binding)).isInstanceOf(ConversationBindings.BindingUse.Unavailable.class);
        assertThat(find(bench, binding.id()).reason()).isEqualTo(BindingInfo.Reason.IDENTITY_CHANGED);
        // A new conversation binds the account as it is now.
        assertThat(resolve(bench, conversation(bench), tracker).externalId()).isNotEqualTo(binding.externalId());
    }

    @Test
    void aWorkspaceAccountToConnectKeepsTheTurnQueuedUntilThePersonConnectsIt() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = companyHost(bench, 4)) {
            JsonNode conversation = start(bench.member(), bench, agentId, "Fix the cart");
            String id = conversation.get("id").asString();
            assertThat(conversation.get("bindings")).singleElement().satisfies(binding -> {
                assertThat(binding.get("state").asString()).isEqualTo("WAITING");
                assertThat(binding.get("reason").asString()).isEqualTo("NONE");
                assertThat(binding.get("text").asString()).contains("not connected for this conversation yet");
            });
            String runId = lastTurn(conversation).get("id").asString();
            // The workspace's binding was asked for by the turn's run, made just after it: its card goes in turn 1.
            assertThat(conversation.get("bindings").get(0).get("runId").asString()).isEqualTo(runId);
            assertThat(conversation.get("bindings").get(0).get("turnNumber").asInt()).isEqualTo(1);
            // The dispatcher's step 2 (§5.7): the run waits for the account, the conversation for its person.
            await().atMost(WAIT).untilAsserted(() -> {
                JsonNode run = bench.member().get(bench.path("/runs/" + runId)).json();
                assertThat(run.path("queueReason").path("code").asString()).isEqualTo("ACCOUNT_REQUIRED");
            });
            JsonNode waiting = awaitConversation(bench.member(), bench, id,
                    now -> "PERSON".equals(now.get("waitingOn").asString()));
            assertThat(waiting.get("waitingReason").asString()).isEqualTo("ACCOUNT");
            runner.expectNone("run.assign", java.time.Duration.ofSeconds(1));
            // The dispatcher's order (§5.7): a paused agent comes before the account to connect.
            assertThat(bench.member().post(bench.path("/agents/" + agentId + "/pause"), Map.of()).status())
                .isEqualTo(200);
            await().atMost(WAIT).untilAsserted(() -> assertThat(bench.member().get(bench.path("/runs/" + runId))
                .json().path("queueReason").path("code").asString()).isEqualTo("AGENT_PAUSED"));
            assertThat(bench.member().post(bench.path("/agents/" + agentId + "/resume"), null).status())
                .isEqualTo(200);
            await().atMost(WAIT).untilAsserted(() -> assertThat(bench.member().get(bench.path("/runs/" + runId))
                .json().path("queueReason").path("code").asString()).isEqualTo("ACCOUNT_REQUIRED"));

            // V6: the person connects their account; the binding is resolved again and the turn runs with it.
            connectOwn(bench.member(), bench, git[0], "test-alice-connected");
            JsonNode assign = runner.expect("run.assign");
            assertThat(assign.get("runId").asString()).isEqualTo(runId);
            assertThat(assign.get("workspaces").get(0).get("git").get("token").asString())
                .isEqualTo("test-alice-connected");
            JsonNode bound = conversation(bench.member(), bench, id);
            assertThat(bound.get("bindings").get(0).get("state").asString()).isEqualTo("BOUND");
            assertThat(auditEvents(bench.owner(), bench.company(), "conversation.binding.waiting")).hasSize(1);
            assertThat(auditEvents(bench.owner(), bench.company(), "conversation.binding.bound")).hasSize(1);
        }
    }

    @Test
    void aReadOnlyGrantMakesTheWorkspaceReadOnlyAndKeepsItsAccount() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "GitHub");
        String systemName = bench.owner().get(bench.path("/systems/" + git[0])).json().get("name").asString();
        UUID bot = createServiceIdentity(bench.owner(), bench.company(), unique("Bot"));
        String analystsName = unique("Analysts");
        UUID analysts = createGroup(bench.owner(), bench.company(), analystsName,
                List.of(bench.company().userId("MEMBER")));
        grant(bench, connectService(bench, bot, git[0], "test-bot-read"), analysts, "READ");
        UUID agentId = agent(bench.member(), bench, "Analyst", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = companyHost(bench, 4)) {
            // V5: bound through the READ grant, said in the thread; the workspace is read-only, cause GRANT.
            JsonNode conversation = start(bench.member(), bench, agentId, "Read the code");
            assertThat(conversation.get("bindings")).singleElement().satisfies(binding -> {
                assertThat(binding.get("how").asString()).isEqualTo("GRANTED");
                assertThat(binding.get("access").asString()).isEqualTo("READ");
                assertThat(binding.get("groupName").asString()).isEqualTo(analystsName);
                assertThat(binding.get("text").asString()).isEqualTo(systemName + ": via " + analystsName
                        + ", read-only");
            });
            assertThat(conversation.get("messages")).anySatisfy(message -> assertThat(message.get("body")
                .asString()).isEqualTo(systemName + ": via " + analystsName + ", read-only."));
            assertThat(conversation.get("workspace")).singleElement().satisfies(repository -> {
                assertThat(repository.get("readOnly").asBoolean()).isTrue();
                assertThat(repository.get("readOnlyCause").asString()).isEqualTo("GRANT");
            });
            JsonNode assign = runner.expect("run.assign");
            assertThat(assign.get("workspaces").get(0).get("readOnly").asBoolean()).isTrue();
            assertThat(assign.get("workspaces").get(0).get("git").get("token").asString()).isEqualTo("test-bot-read");
            assertThat(assign.get("prompt").asString()).contains("read-only");

            // V6: the person connects their own account; this conversation keeps the grant, a new one binds it.
            connectOwn(bench.member(), bench, git[0], "test-alice-own");
            JsonNode kept = conversation(bench.member(), bench, conversation.get("id").asString());
            assertThat(kept.get("bindings").get(0).get("how").asString()).isEqualTo("GRANTED");
            JsonNode next = start(bench.member(), bench, agentId, "Now with my account");
            assertThat(next.get("bindings").get(0).get("how").asString()).isEqualTo("OWN");
            assertThat(next.get("workspace").get(0).get("readOnly").asBoolean()).isFalse();
        }
    }
}
