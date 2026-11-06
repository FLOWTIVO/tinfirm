package com.flowtivo.tinfirm.trigger.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.RunnerFileRequests;
import com.flowtivo.tinfirm.support.TenantSql;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The write-back to a Jira issue (the package 4 contract, §5.9, T9, T13): a result's comment with the agent's label,
 * its actions (a transition, the turn's document attached), the remote links to the conversation and to a change
 * request it opened, each written with the conversation's binding, in order, recorded in {@code system_write} and every
 * poll's snapshot, audited; an ambiguous attempt that is not written twice; a failure tried again; an ended
 * conversation's rows dropped; another company sees none of it. Against the Jira Data Center stub. TEST FIXTURE ONLY:
 * every token is a test value.
 */
class OutboxJiraIntegrationTest extends OutboxTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static final byte[] REPORT = "# Report\n\nCheckout works for EUR.\n".getBytes(StandardCharsets.UTF_8);

    @Autowired
    ApplicationEventPublisher events;

    @Test
    void aResultIsWrittenWithItsLabelItsActionsItsDocumentAndItsLinks() throws Exception {
        Jira jira = jira("Analyst");
        Bench bench = jira.bench();
        UUID triggerId = trigger(bench.alice(), bench, jira.system(), "SHOP", jira.account(), bench.alicePrincipal(),
                bench.team(), results(true, false, List.of(action("TRANSITION", "In Review"),
                        action("TRANSITION", "Nowhere"), action("ATTACH_DOCUMENTS", null)), List.of()));
        UUID poll = snapshot(bench.id(), jira.system(), jira.account(), bench.alicePrincipal(), "10001", KEY,
                "To Do", "[]");
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
            .withHeader("Authorization", equalTo("Bearer " + jira.token()))
            .willReturn(JiraStub.json(201, Map.of("id", "501"))));
        stubTransitions(jira);
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/attachments")))
            .willReturn(JiraStub.json(200, List.of(Map.of("id", "9001", "filename", "report.md", "size",
                    REPORT.length)))));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/remotelink")))
            .willReturn(JiraStub.json(201, Map.of("id", 77, "self", "http://jira/remotelink/77"))));

        UUID conversationId;
        try (FakeRunner runner = runner(bench)) {
            TriggerConversations.Started started = start(bench, triggerId, jira.system(), "10001", KEY,
                    jira.stub().baseUrl() + "/browse/" + KEY);
            conversationId = started.conversation().id();
            Turn turn = take(runner);
            awaitRunning(bench.id(), turn);
            assertThat(RunnerFileRequests.upload(port, hostToken(runner), turn.runId(), turn.epoch(),
                    "&artifact=true&source=save_artifact", "report.md", "text/markdown", REPORT).statusCode())
                .isEqualTo(200);
            runner.send(completed(turn, "The issue is ready.", List.of("Ask QA to test EUR.")));

            // §5.9: the result's comment, then the actions in order, then the conversation's link; a transition the
            // issue's status does not offer is dropped with the reason, and the rows after it are written.
            List<Map<String, Object>> rows = awaitOutbox(bench.id(), conversationId, now -> now.size() == 5
                    && now.stream().noneMatch(row -> "PENDING".equals(row.get("status"))));
            assertThat(column(rows, "kind")).containsExactly("COMMENT", "TRANSITION", "TRANSITION", "ATTACHMENT",
                    "REMOTE_LINK");
            assertThat(column(rows, "cause")).containsExactly("RESULT", "ON_SUCCESS", "ON_SUCCESS", "ON_SUCCESS",
                    "RESULT");
            assertThat(column(rows, "status")).containsExactly("DELIVERED", "DELIVERED", "DROPPED", "DELIVERED",
                    "DELIVERED");
            assertThat(column(rows, "external_id")).containsExactly("501", "null", "null", "9001", "77");
            assertThat(String.valueOf(rows.get(2).get("dropped_reason"))).contains("offers no transition",
                    "Nowhere");
            List<String> notes = new java.util.ArrayList<>();
            bench.bob().get(bench.path("/conversations/" + conversationId)).json().get("messages")
                .forEach(message -> notes.add(message.get("body").asString()));
            assertThat(notes).anyMatch(note -> note.startsWith("Tinfirm did not write the transition “Nowhere” to "
                    + "SHOP-1"));
            runner.assertFramesValid();
        }

        // Written with the binding's account (Alice's token), the comment labelled with the agent's name.
        List<LoggedRequest> comments = JiraStub.server().findAll(postRequestedFor(urlEqualTo(jira.issue("/comment"))));
        assertThat(comments).hasSize(1);
        String body = JSON.readTree(comments.getFirst().getBodyAsString()).get("body").asString();
        assertThat(body).startsWith("*" + bench.agentName() + " (Tinfirm agent):* The issue is ready.")
            .contains("Ask QA to test EUR.", "report.md", "attached to this issue",
                    "/conversations/" + conversationId);
        JiraStub.server().verify(postRequestedFor(urlEqualTo(jira.issue("/transitions")))
            .withHeader("Authorization", equalTo("Bearer " + jira.token()))
            .withRequestBody(equalToJson("{\"transition\":{\"id\":\"31\"}}")));
        JiraStub.server().verify(postRequestedFor(urlEqualTo(jira.issue("/attachments")))
            .withHeader("X-Atlassian-Token", equalTo("no-check"))
            .withHeader("Content-Type", containing("multipart/form-data"))
            .withRequestBody(containing("filename=\"report.md\""))
            .withRequestBody(containing("Checkout works for EUR.")));
        JiraStub.server().verify(postRequestedFor(urlEqualTo(jira.issue("/remotelink")))
            .withRequestBody(matchingJsonPath("$.object.url", containing("/conversations/" + conversationId)))
            .withRequestBody(matchingJsonPath("$.globalId", containing("tinfirm:"))));

        // T9: every write recorded, and the status it entered in every poll's snapshot, so that no event follows.
        List<Map<String, Object>> writes = systemWrites(bench.id(), conversationId);
        assertThat(column(writes, "kind")).containsExactlyInAnyOrder("COMMENT", "TRANSITION", "ATTACHMENT",
                "REMOTE_LINK");
        assertThat(writes).anySatisfy(write -> {
            assertThat(write.get("kind")).isEqualTo("COMMENT");
            assertThat(write.get("external_id")).isEqualTo("501");
            assertThat(write.get("subject_key")).isEqualTo(KEY);
        });
        assertThat(writes).anySatisfy(write -> assertThat(String.valueOf(write.get("written_values")))
            .contains("\"status\": \"In Review\""));
        assertThat(snapshotOf(bench.id(), poll, "10001").get("status")).isEqualTo("In Review");
        List<JsonNode> audited = sourceWritten(bench);
        assertThat(audited).extracting(event -> event.get("details").get("outcome").asString())
            .containsExactlyInAnyOrder("DELIVERED", "DELIVERED", "DROPPED", "DELIVERED", "DELIVERED");
        assertThat(audited).allSatisfy(event -> {
            assertThat(event.get("details").get("accountId").asString()).isEqualTo(jira.account().toString());
            assertThat(event.get("details").toString()).doesNotContain("The issue is ready.");
        });
        assertThat(auditEvents(bench.owner(), bench.company(), "secret.accessed")).anySatisfy(event ->
                assertThat(event.get("details").get("purpose").asString()).startsWith("write-back:"));

        // A change request the conversation opened is linked to the issue (CHANGE_REQUEST), once.
        String pull = "https://github.example.test/acme/shop/pull/5";
        for (int i = 0; i < 2; i++) {
            UUID conversation = conversationId;
            transactions.runInCompany(bench.id(), () -> events.publishEvent(new ConversationEvents.SystemWritten(
                    bench.id(), conversation, jira.system(), "CHANGE_REQUEST acme/shop#5", "5", "CHANGE_REQUEST",
                    "acme/shop#5", ConversationEvents.WrittenValues.changeRequest(pull, "acme/shop#5 Fix EUR", null))));
        }
        List<Map<String, Object>> linked = awaitOutbox(bench.id(), conversationId, now -> now.size() == 6
                && "DELIVERED".equals(now.get(5).get("status")));
        assertThat(linked.get(5).get("cause")).isEqualTo("CHANGE_REQUEST");
        assertThat(linked.get(5).get("url")).isEqualTo(pull);
        JiraStub.server().verify(1, postRequestedFor(urlEqualTo(jira.issue("/remotelink")))
            .withRequestBody(matchingJsonPath("$.object.url", equalTo(pull))));

        // Tenant isolation: another company sees and changes none of it.
        TestCompany other = companyWithRoles();
        try (TenantSql sql = TenantSql.in(other.id())) {
            assertThat(sql.count("SELECT count(*) FROM outbox WHERE conversation_id = ?", conversationId)).isZero();
            assertThat(sql.count("SELECT count(*) FROM system_write WHERE conversation_id = ?", conversationId))
                .isZero();
            assertThat(sql.count("SELECT count(*) FROM outbox_review")).isZero();
            assertThat(sql.update("UPDATE outbox SET attempts = 3 WHERE conversation_id = ?", conversationId))
                .isZero();
        }
        assertThat(loginAs(other.email("OWNER")).get(other.path("/conversations/" + conversationId)).status())
            .isEqualTo(404);
    }

    @Test
    void anAmbiguousCommentIsNotWrittenTwiceAFailureIsTriedAgainAndAnEndedConversationsRowsAreDropped()
            throws Exception {
        Jira jira = jira("Analyst");
        Bench bench = jira.bench();
        UUID triggerId = trigger(bench.alice(), bench, jira.system(), "SHOP", jira.account(), bench.alicePrincipal(),
                bench.team(), results(true, false, List.of(), List.of()));
        // The first comment reaches Jira, whose answer comes after the tool timeout: the attempt is ambiguous.
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
            .willReturn(JiraStub.json(201, Map.of("id", "601")).withFixedDelay(6_000)));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/remotelink")))
            .willReturn(JiraStub.json(201, Map.of("id", 78))));

        UUID conversationId;
        try (FakeRunner runner = runner(bench)) {
            conversationId = start(bench, triggerId, jira.system(), "10001", KEY, jira.stub().baseUrl()
                    + "/browse/" + KEY).conversation().id();
            Turn turn = take(runner);
            runner.send(completed(turn, "The issue is ready.", List.of()));
            // The comment, then the conversation's link, which waits behind it (T13: in order per conversation).
            List<Map<String, Object>> failed = awaitOutbox(bench.id(), conversationId, now -> now.size() == 2
                    && ((Number) now.getFirst().get("attempts")).intValue() == 1);
            assertThat(failed.getFirst().get("status")).isEqualTo("PENDING");
            assertThat(failed.getFirst().get("ambiguous")).isEqualTo(true);
            assertThat(failed.get(1).get("kind")).isEqualTo("REMOTE_LINK");
            assertThat(failed.get(1).get("attempts")).isEqualTo(0);

            // T13: before trying again, the issue's comments show the first attempt wrote it: taken, not written again.
            String written = JSON.readTree(JiraStub.server().findAll(postRequestedFor(urlEqualTo(
                    jira.issue("/comment")))).getFirst().getBodyAsString()).get("body").asString();
            String created = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern(
                    "uuuu-MM-dd'T'HH:mm:ss.SSSxx"));
            JiraStub.server().stubFor(get(urlPathEqualTo(jira.issue("")))
                .willReturn(JiraStub.json(200, JiraStub.issue("10001", KEY, "Checkout fails", "", "alice", List.of(),
                        "To Do", created, List.of(JiraStub.comment("590", "carol", "First!", created),
                                JiraStub.comment("601", "alice", written, created))))));
            dueNow(bench.id(), conversationId);
            delivery.deliverDue();
            List<Map<String, Object>> delivered = awaitOutbox(bench.id(), conversationId, now -> allDelivered(now,
                    2));
            assertThat(delivered.getFirst().get("external_id")).isEqualTo("601");
            assertThat(delivered.getFirst().get("attempts")).isEqualTo(2);
            JiraStub.server().verify(1, postRequestedFor(urlEqualTo(jira.issue("/comment"))));
            assertThat(systemWrites(bench.id(), conversationId)).anySatisfy(write -> {
                assertThat(write.get("kind")).isEqualTo("COMMENT");
                assertThat(write.get("external_id")).isEqualTo("601");
            });

            // A failure that may pass (503) is tried again later, and written once it passes.
            JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
                .willReturn(aResponse().withStatus(503).withBody("{\"errorMessages\":[\"Down\"]}")));
            UUID next = event(bench, triggerId, conversationId, "Carol commented: \"Check the tax.\"");
            Turn second = take(runner);
            assertThat(second.runId()).isEqualTo(next);
            runner.send(completed(second, "Checked the tax too.", List.of()));
            List<Map<String, Object>> retrying = awaitOutbox(bench.id(), conversationId, now -> now.size() == 3
                    && ((Number) now.get(2).get("attempts")).intValue() == 1);
            assertThat(retrying.get(2).get("status")).isEqualTo("PENDING");
            assertThat(String.valueOf(retrying.get(2).get("last_error"))).contains("503");
            JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
                .willReturn(JiraStub.json(201, Map.of("id", "602"))));
            dueNow(bench.id(), conversationId);
            delivery.deliverDue();
            awaitOutbox(bench.id(), conversationId, now -> allDelivered(now, 3));

            // An ended conversation's undelivered rows are dropped with the reason (but a closed subject's).
            JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
                .willReturn(aResponse().withStatus(503)));
            event(bench, triggerId, conversationId, "Carol commented: \"And the rounding.\"");
            Turn third = take(runner);
            runner.send(completed(third, "One more thing.", List.of()));
            awaitOutbox(bench.id(), conversationId, now -> now.size() == 4
                    && ((Number) now.get(3).get("attempts")).intValue() == 1);
            assertThat(bench.bob().post(bench.path("/conversations/" + conversationId + "/end"), Map.of()).status())
                .isEqualTo(200);
            List<Map<String, Object>> ended = awaitOutbox(bench.id(), conversationId,
                    now -> "DROPPED".equals(now.get(3).get("status")));
            assertThat(String.valueOf(ended.get(3).get("dropped_reason"))).contains("conversation ended (USER)");
            runner.assertFramesValid();
        }
        assertThat(sourceWritten(bench)).extracting(event -> event.get("details").get("outcome").asString())
            .containsExactlyInAnyOrder("DELIVERED", "DELIVERED", "DELIVERED", "DROPPED");
    }
}
