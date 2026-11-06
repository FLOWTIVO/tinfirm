package com.flowtivo.tinfirm.conversation.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TestCredentials;

import tools.jackson.databind.JsonNode;

/**
 * The inbox (the package 3 contract, §5.10): each person sees what they act on in their own open conversations, the
 * longest waiting first: their decisions, their waiting bindings they can act on, the conversations whose latest turn
 * failed, the ones that wait on a runner; admins see the service identities' accounts that keep conversations waiting,
 * with how many and nothing of the conversations. An ended conversation leaves the inbox, its waiting binding
 * included. TEST FIXTURE ONLY: tokens are test values.
 */
class InboxIntegrationTest extends ConversationBench {

    @Test
    void eachPersonSeesWhatTheyActOnAndAdminsTheAccountsThatKeepConversationsWaiting() {
        Bench bench = bench();
        String handle = handle(bench, bench.jiraId());
        JiraStub.Instance other = JiraStub.newInstance();
        UUID otherId = createSystem(bench.admin(), bench.company(), unique("Wiki"), "JIRA_DC", other.baseUrl(), false);
        String otherHandle = handle(bench, otherId);
        ApiResponse systems = bench.person().put(bench.agentPath("/systems"), Map.of("systems", List.of(
                Map.of("systemId", bench.jiraId().toString(), "tools", List.of(Map.of("name", "get", "mode",
                        "ALLOW"))),
                Map.of("systemId", otherId.toString(), "tools", List.of(Map.of("name", "get", "mode", "ALLOW"))))));
        assertThat(systems.status()).as("%s", systems).isEqualTo(200);
        // A credential of the Jira, lent to the person's group.
        String botName = unique("Bot");
        bench.jira().myself("test-inbox-bot", "bot-inbox", "UTC");
        UUID account = TestCredentials.add(bench.admin(), bench.path(""), bench.jiraId(), botName, "test-inbox-bot");
        UUID group = createGroup(bench.admin(), bench.company(), unique("Analysts"),
                List.of(bench.company().userId("MEMBER")));
        createGrant(bench.admin(), bench.company(), account, group, "WRITE");
        JiraStub.server().stubFor(get(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-11")))
            .withHeader("Authorization", equalTo("Bearer test-inbox-bot"))
            .willReturn(JiraStub.json(200, JiraStub.issue("10011", "SHOP-11", "Invoices", "", null, List.of(),
                    "Open", "2026-10-01T10:00:00.000+0200", List.of()))));
        JiraStub.server().stubFor(get(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-12")))
            .willReturn(aResponse().withStatus(401).withBody("{\"errorMessages\":[\"Not authenticated.\"]}")));

        // Two conversations act through the grant; Jira then refuses the bot's token: both wait for an admin.
        Conversation granted = conversation(bench);
        Conversation alsoGranted = conversation(bench);
        assertThat(outcome(call(granted, handle + "_get", Map.of("key", "SHOP-11")))).isEqualTo("DONE");
        assertThat(outcome(call(alsoGranted, handle + "_get", Map.of("key", "SHOP-11")))).isEqualTo("DONE");
        assertThat(outcome(call(granted, handle + "_get", Map.of("key", "SHOP-12")))).isEqualTo("PENDING");
        assertThat(bindingRow(granted, bench.jiraId())).containsEntry("reason", "RECONNECT_COMPANY");
        await().atMost(WAIT).until(() -> "WAITING".equals(bindingRow(alsoGranted, bench.jiraId()).get("state")));
        // The person has no account on the other system: that binding waits for them.
        Conversation unbound = conversation(bench);
        assertThat(outcome(call(unbound, otherHandle + "_get", Map.of("key", "DOC-1")))).isEqualTo("PENDING");
        assertThat(bindingRow(unbound, otherId)).containsEntry("reason", "NONE");
        // A question; a turn that failed three hours ago; a turn waiting on a runner for two.
        Conversation asking = conversation(bench);
        UUID question = pendingId(request(asking, "ASK", Map.of("question", "Which region?")));
        Conversation failed = conversation(bench);
        update(bench.companyId(), """
                UPDATE conversation SET turn_status = 'FAILED', last_run_id = ?, last_turn_at = now() - interval '3 hours',
                       failure_class = 'RUNTIME_ERROR', failure_cause = 'The runtime exited.'
                 WHERE id = ?""", failed.runId(), failed.id());
        Conversation queued = conversation(bench);
        update(bench.companyId(), """
                UPDATE conversation SET turn_status = 'QUEUED', waiting_on = 'RUNNER', waiting_reason = 'NO_HOST',
                       waiting_since = now() - interval '2 hours', active_run_id = ?
                 WHERE id = ?""", queued.runId(), queued.id());
        update(bench.companyId(), """
                UPDATE run SET status = 'QUEUED', queue_reason = 'NO_RUNNER_HOST', queue_reason_since = now()
                 WHERE id = ?""", queued.runId());

        // The person: their own, the longest waiting first; nothing of the bot's account.
        JsonNode mine = inbox(bench.person(), bench);
        assertThat(mine.get("items")).extracting(item -> item.get("kind").asString())
            .containsExactly("FAILED", "WAITING", "BINDING", "DECISION");
        JsonNode items = mine.get("items");
        assertThat(items.get(0).get("conversation").get("id").asString()).isEqualTo(failed.id().toString());
        assertThat(items.get(0).get("turn").get("failureCause").asString()).isEqualTo("The runtime exited.");
        assertThat(items.get(1).get("conversation").get("key").asString()).isEqualTo(queued.key());
        assertThat(items.get(1).get("turn").get("waitingReason").asString()).isEqualTo("NO_HOST");
        // The reason in words, as the run's queue reason says it.
        assertThat(items.get(1).get("turn").get("waitingText").asString())
            .isEqualTo("No connected runner host can run the runtime of the agent.");
        assertThat(items.get(2).get("conversation").get("id").asString()).isEqualTo(unbound.id().toString());
        assertThat(items.get(2).get("binding").get("reason").asString()).isEqualTo("NONE");
        assertThat(items.get(2).get("binding").get("systemId").asString()).isEqualTo(otherId.toString());
        // What the Connect card needs without a second call: the kind, whether to offer a sign-in, the grants.
        assertThat(items.get(2).get("binding").get("systemKind").asString()).isEqualTo("JIRA_DC");
        assertThat(items.get(2).get("binding").get("oauthAvailable").asBoolean()).isFalse();
        assertThat(items.get(2).get("binding").get("grantChoices")).isEmpty();
        assertThat(items.get(3).get("decision").get("id").asString()).isEqualTo(question.toString());
        assertThat(items.get(3).get("decision").get("question").asString()).isEqualTo("Which region?");
        // The decision names its conversation, as the other items do.
        assertThat(items.get(3).get("conversation").get("id").asString()).isEqualTo(asking.id().toString());
        assertThat(items.get(3).get("conversation").get("key").asString()).isEqualTo(asking.key());
        // A binding names the run whose tool call first asked for it, with its turn, and whose account it is.
        JsonNode connect = bench.person().get(bench.path("/conversations/" + unbound.id())).json().get("bindings")
            .get(0);
        assertThat(connect.get("runId").asString()).isEqualTo(unbound.runId().toString());
        assertThat(connect.get("turnNumber").asInt()).isEqualTo(1);
        JsonNode lent = bench.person().get(bench.path("/conversations/" + granted.id())).json().get("bindings")
            .get(0);
        assertThat(lent.get("runId").asString()).isEqualTo(granted.runId().toString());
        assertThat(lent.get("credentialName").asString()).isEqualTo(botName);
        assertThat(lent.path("holderPrincipalId").isNull() || lent.path("holderPrincipalId").isMissingNode())
            .isTrue();
        assertThat(mine.get("counts").get("decisions").asInt()).isEqualTo(1);
        assertThat(mine.get("counts").get("bindings").asInt()).isEqualTo(1);
        assertThat(mine.get("counts").get("failed").asInt()).isEqualTo(1);
        assertThat(mine.get("counts").get("waiting").asInt()).isEqualTo(1);
        assertThat(mine.get("counts").get("accounts").asInt()).isZero();

        // The admins: the account with how many conversations it keeps waiting, and nothing of them.
        for (ApiClient admin : List.of(bench.admin(), bench.owner())) {
            JsonNode theirs = inbox(admin, bench);
            assertThat(theirs.get("items")).singleElement().satisfies(item -> {
                assertThat(item.get("kind").asString()).isEqualTo("ACCOUNT");
                assertThat(item.path("conversation").isMissingNode() || item.get("conversation").isNull()).isTrue();
                JsonNode waiting = item.get("account");
                assertThat(waiting.get("reason").asString()).isEqualTo("RECONNECT_COMPANY");
                assertThat(waiting.get("conversations").asInt()).isEqualTo(2);
                assertThat(waiting.get("credential").get("id").asString()).isEqualTo(account.toString());
                assertThat(waiting.get("credential").get("name").asString()).isEqualTo(botName);
                // What an admin does follows from it: a token to store again, not a sign-in.
                assertThat(waiting.get("credential").get("kind").asString()).isEqualTo("TOKEN");
                assertThat(waiting.get("credential").get("status").asString()).isEqualTo("RECONNECT");
                assertThat(waiting.get("credential").get("enabled").asBoolean()).isTrue();
                assertThat(waiting.get("bot").asBoolean()).isFalse();
            });
            assertThat(theirs.get("counts").get("decisions").asInt()).isZero();
            assertThat(theirs.get("counts").get("accounts").asInt()).isEqualTo(1);
        }
        // Another company sees none of it.
        TestCompany elsewhere = companyWithRoles();
        assertThat(inbox(loginAs(elsewhere.email("OWNER")), elsewhere.path("/inbox")).get("items")).isEmpty();

        // Ended conversations leave the inbox; a waiting binding stays as it is, and nobody is asked for it.
        for (Conversation ended : List.of(asking, unbound, failed, alsoGranted)) {
            ApiResponse end = bench.person().post(bench.path("/conversations/" + ended.id() + "/end"), null);
            assertThat(end.status()).as("%s", end).isEqualTo(200);
        }
        assertThat(bindingRow(unbound, otherId)).containsEntry("state", "WAITING");
        assertThat(inbox(bench.person(), bench).get("items")).extracting(item -> item.get("kind").asString())
            .containsExactly("WAITING");
        assertThat(inbox(bench.admin(), bench).get("items")).singleElement()
            .satisfies(item -> assertThat(item.get("account").get("conversations").asInt()).isEqualTo(1));
    }

    private static JsonNode inbox(ApiClient client, Bench bench) {
        return inbox(client, bench.path("/inbox"));
    }

    private static JsonNode inbox(ApiClient client, String path) {
        ApiResponse response = client.get(path);
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return response.json();
    }

    private static String handle(Bench bench, UUID systemId) {
        return String.valueOf(row(bench.companyId(), "SELECT handle FROM external_system WHERE id = ?", systemId)
            .get("handle"));
    }
}
