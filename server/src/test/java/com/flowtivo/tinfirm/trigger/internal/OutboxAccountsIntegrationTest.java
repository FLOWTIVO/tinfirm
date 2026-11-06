package com.flowtivo.tinfirm.trigger.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.JiraStub;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The accounts the write-back writes with (the package 4 contract, §5.9, §11 points 6 and 9, T13, V2, V9): the
 * conversation's binding, a service identity's account included; a {@code 401} refuses the account, pauses the whole
 * trigger ({@code ACCOUNT_BROKEN}) and holds its rows until its author resumes it, and the reconnected account writes
 * them; an author who leaves holds the rows, and after an admin's reassignment and the resume the rebound conversation's
 * rows are written by the new table's account. Against the Jira Data Center stub. TEST FIXTURE ONLY: every token is a
 * test value.
 */
class OutboxAccountsIntegrationTest extends OutboxTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aRefusedCredentialPausesTheTriggerItsRowsWaitAndTheReconnectedAccountWritesThemOnceResumed() {
        Jira jira = jira("Writer");
        Bench bench = jira.bench();
        UUID triggerId = trigger(bench.alice(), bench, jira.system(), "SHOP", jira.account(), bench.alicePrincipal(),
                bench.team(), results(true, false, List.of(), List.of()));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
            .withHeader("Authorization", equalTo("Bearer " + jira.token()))
            .willReturn(aResponse().withStatus(401).withHeader("X-Seraph-LoginReason", "AUTHENTICATED_FAILED")));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/remotelink")))
            .willReturn(JiraStub.json(201, Map.of("id", 91))));
        String reconnected = "test-jira-" + unique("again");
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
            .withHeader("Authorization", equalTo("Bearer " + reconnected))
            .willReturn(JiraStub.json(201, Map.of("id", "901"))));

        try (FakeRunner runner = runner(bench)) {
            UUID conversationId = start(bench, triggerId, jira.system(), "10001", KEY, jira.stub().baseUrl()
                    + "/browse/" + KEY).conversation().id();
            Turn turn = take(runner);
            runner.send(completed(turn, "Ready for the team.", List.of()));

            // §11, point 9: the account turns RECONNECT, the whole trigger pauses ACCOUNT_BROKEN, its rows are held,
            // and the refused attempt counts none.
            await().atMost(WAIT).until(() -> "ACCOUNT_BROKEN".equals(bench.alice().get(bench.path("/triggers/"
                    + triggerId)).json().path("pausedReason").asString()));
            List<Map<String, Object>> held = awaitOutbox(bench.id(), conversationId, now -> now.size() == 2
                    && now.stream().allMatch(row -> "HELD".equals(row.get("status"))));
            assertThat(held.getFirst().get("attempts")).isEqualTo(0);
            assertThat(String.valueOf(held.getFirst().get("last_error"))).contains("connected again");
            assertThat(auditEvents(bench.owner(), bench.company(), "account.refused")).hasSize(1);

            // Signed in again: the trigger stays paused (resumed by hand only), and nothing is written meanwhile.
            connectToken(bench.alice(), bench.path("/me/accounts/" + jira.system()), jira.stub(), reconnected,
                    "alice");
            delivery.deliverDue();
            assertThat(outbox(bench.id(), conversationId)).allSatisfy(row -> assertThat(row.get("status"))
                .isEqualTo("HELD"));
            JiraStub.server().verify(0, postRequestedFor(urlEqualTo(jira.issue("/comment")))
                .withHeader("Authorization", equalTo("Bearer " + reconnected)));

            // Its author resumes it: the held rows go, written once with the reconnected account.
            ApiResponse resumed = bench.alice().post(bench.path("/triggers/" + triggerId + "/resume"), Map.of());
            assertThat(resumed.status()).as("%s", resumed).isEqualTo(200);
            List<Map<String, Object>> rows = awaitOutbox(bench.id(), conversationId, now -> allDelivered(now, 2));
            assertThat(rows.getFirst().get("external_id")).isEqualTo("901");
            JiraStub.server().verify(1, postRequestedFor(urlEqualTo(jira.issue("/comment")))
                .withHeader("Authorization", equalTo("Bearer " + reconnected)));
            JiraStub.server().verify(1, postRequestedFor(urlEqualTo(jira.issue("/comment")))
                .withHeader("Authorization", equalTo("Bearer " + jira.token())));
            runner.assertFramesValid();
        }
    }

    @Test
    void afterTheAuthorLeftAndAReassignmentTheWaitingRowsAreWrittenWithTheNewTablesAccount() {
        Bench bench = bench("Mover");
        JiraStub.Instance stub = JiraStub.newInstance();
        UUID system = createSystem(bench.owner(), bench.company(), unique("Jira"), "JIRA_DC", stub.baseUrl(), false);
        UUID ba = serviceIdentity(bench, "svc-ba");
        UUID dev = serviceIdentity(bench, "svc-dev");
        String baToken = "test-jira-" + unique("ba");
        String devToken = "test-jira-" + unique("dev");
        UUID baAccount = connectServiceToken(bench.owner(), bench.company(), ba, system, stub, baToken, "svc-ba");
        UUID devAccount = connectServiceToken(bench.owner(), bench.company(), dev, system, stub, devToken, "svc-dev");
        Jira jira = new Jira(bench, stub, system, baAccount, baToken);
        UUID triggerId = trigger(bench.alice(), bench, system, "SHOP", baAccount, bench.alicePrincipal(),
                bench.team(), results(true, false, List.of(), List.of()));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
            .withHeader("Authorization", equalTo("Bearer " + baToken))
            .willReturn(aResponse().withStatus(503)));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
            .withHeader("Authorization", equalTo("Bearer " + devToken))
            .willReturn(JiraStub.json(201, Map.of("id", "1001"))));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/remotelink")))
            .willReturn(JiraStub.json(201, Map.of("id", 101))));

        try (FakeRunner runner = runner(bench)) {
            UUID conversationId = start(bench, triggerId, system, "10001", KEY, stub.baseUrl() + "/browse/" + KEY)
                .conversation().id();
            Turn turn = take(runner);
            runner.send(completed(turn, "Ready for the team.", List.of()));
            awaitOutbox(bench.id(), conversationId, now -> now.size() == 2
                    && ((Number) now.getFirst().get("attempts")).intValue() == 1);

            // §11, point 6: Alice leaves; her trigger pauses AUTHOR_LEFT, its conversation waits, its rows are held.
            assertThat(bench.owner().delete(bench.path("/members/" + bench.company().userId("MEMBER"))).status())
                .isEqualTo(204);
            awaitOutbox(bench.id(), conversationId, now -> now.stream().allMatch(row -> "HELD".equals(row.get(
                    "status"))));

            // Reassigned to Bob with svc-dev's account and his own budget, and accepted by him: the conversation is
            // rebound, and its rows are written by svc-dev's account, which passed the new table's checks.
            ApiResponse reassigned = bench.admin().post(bench.path("/triggers/" + triggerId + "/reassign"), Map.of(
                    "authorPrincipalId", bench.bobPrincipal().toString(), "payerPrincipalId",
                    bench.bobPrincipal().toString(), "accounts", List.of(Map.of("systemId", system.toString(),
                            "accountId", devAccount.toString()))));
            assertThat(reassigned.status()).as("%s", reassigned).isEqualTo(200);
            // Reassigned, it waits for Bob (TO_ACCEPT, H2): his acceptance of the table, the payer and the open
            // events resumes it.
            int version = bench.bob().get(bench.path("/triggers/" + triggerId)).json().get("currentVersion").asInt();
            ApiResponse resumed = bench.bob().post(bench.path("/triggers/" + triggerId + "/accept"),
                    Map.of("version", version, "acknowledgeOpenEvents", true));
            assertThat(resumed.status()).as("%s", resumed).isEqualTo(200);
            assertThat(resumed.json().get("status").asString()).isEqualTo("ACTIVE");
            List<Map<String, Object>> rows = awaitOutbox(bench.id(), conversationId, now -> allDelivered(now, 2));
            assertThat(rows.getFirst().get("external_id")).isEqualTo("1001");
            JiraStub.server().verify(1, postRequestedFor(urlEqualTo(jira.issue("/comment")))
                .withHeader("Authorization", equalTo("Bearer " + devToken)));
            JiraStub.server().verify(1, postRequestedFor(urlEqualTo(jira.issue("/comment")))
                .withHeader("Authorization", equalTo("Bearer " + baToken)));
            assertThat(auditEvents(bench.owner(), bench.company(), "conversation.rebound")).hasSize(1);
            assertThat(sourceWritten(bench)).allSatisfy(event -> {
                assertThat(event.get("details").get("accountId").asString()).isEqualTo(devAccount.toString());
                assertThat(event.get("details").get("holderPrincipalId").asString()).isEqualTo(dev.toString());
            });
            runner.assertFramesValid();
        }
    }

    @Test
    void v2TheBaAgentsResultIsACommentBySvcBaLabelledWithTheAgent() {
        Bench bench = bench("BA");
        JiraStub.Instance stub = JiraStub.newInstance();
        UUID system = createSystem(bench.owner(), bench.company(), unique("Jira"), "JIRA_DC", stub.baseUrl(), false);
        UUID ba = serviceIdentity(bench, "svc-ba");
        String baToken = "test-jira-" + unique("ba");
        UUID baAccount = connectServiceToken(bench.owner(), bench.company(), ba, system, stub, baToken, "svc-ba");
        Jira jira = new Jira(bench, stub, system, baAccount, baToken);
        // Who pays is the run's half of V2; the write-back's is the table's account.
        UUID triggerId = trigger(bench.alice(), bench, system, "SHOP", baAccount, bench.alicePrincipal(),
                bench.team(), results(true, false, List.of(), List.of()));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
            .withHeader("Authorization", equalTo("Bearer " + baToken))
            .willReturn(JiraStub.json(201, Map.of("id", "1101"))));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/remotelink")))
            .withHeader("Authorization", equalTo("Bearer " + baToken))
            .willReturn(JiraStub.json(201, Map.of("id", 111))));

        try (FakeRunner runner = runner(bench)) {
            UUID conversationId = start(bench, triggerId, system, "10001", KEY, stub.baseUrl() + "/browse/" + KEY)
                .conversation().id();
            Turn turn = take(runner);
            runner.send(completed(turn, "SHOP-1 is ready for the team.", List.of("Estimate it.")));

            // V2: the result is a comment by svc-ba, labelled with the agent; the team sees the conversation.
            awaitOutbox(bench.id(), conversationId, now -> allDelivered(now, 2));
            String body = JSON.readTree(JiraStub.server().findAll(postRequestedFor(urlEqualTo(jira.issue(
                    "/comment")))).getFirst().getBodyAsString()).get("body").asString();
            assertThat(body).startsWith("*BA (Tinfirm agent):* SHOP-1 is ready for the team.");
            assertThat(sourceWritten(bench)).hasSize(2).allSatisfy(event -> assertThat(event.get("details")
                .get("holderPrincipalId").asString()).isEqualTo(ba.toString()));
            JsonNode seen = bench.bob().get(bench.path("/conversations/" + conversationId)).json();
            assertThat(seen.get("place").asString()).isEqualTo("TRACKER_ISSUE");
            runner.assertFramesValid();
        }
    }

    /** A service identity Alice's and Bob's group may use. */
    private UUID serviceIdentity(Bench bench, String name) {
        UUID id = createServiceIdentity(bench.owner(), bench.company(), name);
        assertThat(bench.owner().put(bench.path("/service-identities/" + id + "/users"),
                Map.of("groupIds", List.of(bench.users().toString()))).status()).isEqualTo(200);
        return id;
    }
}
