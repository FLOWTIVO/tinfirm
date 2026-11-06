package com.flowtivo.tinfirm.trigger.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TenantSql;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The review of a result and the place's decisions (the package 4 contract, §5.8, §5.9, T14, V12, V13): nothing of a
 * result under review reaches the issue before the team approves it, exactly as it was shown; a return with a note
 * writes nothing and starts a {@code REVIEW_RETURNED} turn; the approved comment and transition are written once, even
 * when the delivery is repeated; a failed turn is not reviewed. A question is a comment on the issue, and an answer the
 * team gives in Tinfirm is written there too, naming who answered, with the agent's label (§11, point 4). Against the
 * Jira Data Center stub. TEST FIXTURE ONLY: every token is a test value.
 */
class OutboxReviewIntegrationTest extends OutboxTestSupport {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    Principals principals;

    @Test
    void aReviewedResultIsWrittenOnlyOnceApprovedExactlyAsShownAndAFailureIsNotReviewed() {
        Jira jira = jira("Reviewer");
        Bench bench = jira.bench();
        UUID triggerId = trigger(bench.alice(), bench, jira.system(), "SHOP", jira.account(), bench.alicePrincipal(),
                bench.team(), results(true, true, List.of(action("TRANSITION", "In Review")),
                        List.of(action("TRANSITION", "In Progress"))));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
            .willReturn(JiraStub.json(201, Map.of("id", "701"))));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/remotelink")))
            .willReturn(JiraStub.json(201, Map.of("id", 71))));
        stubTransitions(jira);

        try (FakeRunner runner = runner(bench)) {
            UUID conversationId = start(bench, triggerId, jira.system(), "10001", KEY, jira.stub().baseUrl()
                    + "/browse/" + KEY).conversation().id();
            Turn first = take(runner);
            runner.send(completed(first, "Draft one.", List.of()));

            // T14: the team's approval instead of the writes, which show exactly what would be written.
            UUID returned = awaitReview(bench.id(), conversationId, null);
            assertThat(outbox(bench.id(), conversationId)).isEmpty();
            assertThat(reviews(bench.id(), conversationId)).isEqualTo(1);
            JsonNode decision = bench.bob().get(bench.path("/decisions/" + returned)).json();
            assertThat(decision.get("approverGroupId").asString()).isEqualTo(bench.team().toString());
            assertThat(decision.get("subject").get("type").asString()).isEqualTo("RESULT");
            List<String> writes = new ArrayList<>();
            JSON.readTree(subjectWrites(bench.id(), returned)).forEach(write -> writes.add(write.asString()));
            assertThat(writes).hasSize(3);
            assertThat(writes.get(0)).startsWith("A comment on SHOP-1:\n\n**" + bench.agentName()
                    + " (Tinfirm agent):** Draft one.");
            assertThat(writes.get(1)).isEqualTo("Move SHOP-1 through “In Review”");
            assertThat(writes.get(2)).startsWith("Link SHOP-1 to this conversation in Tinfirm");
            // The API's subject shows them too, with the turn and its summary, for the card.
            JsonNode subject = decision.get("subject");
            assertThat(subject.get("runId").asString()).isEqualTo(first.runId().toString());
            assertThat(subject.get("summary").asString()).isEqualTo("Draft one.");
            List<String> shown = new ArrayList<>();
            subject.get("writes").forEach(write -> shown.add(write.asString()));
            assertThat(shown).isEqualTo(writes);
            // Each with what it is, as its outbox row's kind, and where it goes, in the same order.
            JsonNode items = subject.get("writeItems");
            assertThat(items).extracting(item -> item.get("kind").asString())
                .containsExactly("COMMENT", "TRANSITION", "REMOTE_LINK");
            assertThat(items).extracting(item -> item.get("target").asString()).containsOnly(KEY);
            assertThat(items).extracting(item -> item.get("text").asString()).isEqualTo(writes);
            assertThat(items.get(0).path("value").isMissingNode() || items.get(0).get("value").isNull()).isTrue();
            assertThat(items.get(1).get("value").asString()).isEqualTo("In Review");
            assertThat(items.get(2).get("value").asString()).contains("/conversations/");
            // The conversation waits for the team, which holds the review: its group and the kind REVIEW.
            JsonNode waiting = bench.bob().get(bench.path("/conversations/" + conversationId)).json();
            assertThat(waiting.get("waitingOn").asString()).isEqualTo("PERSON");
            assertThat(waiting.get("waitingReason").asString()).isEqualTo("REVIEW");
            assertThat(waiting.get("waitingGroupId").asString()).isEqualTo(bench.team().toString());
            assertThat(waiting.get("waitingGroupName").asString())
                .isEqualTo(decision.get("approverGroupName").asString());

            // Returned with a note: nothing is written, and a REVIEW_RETURNED turn does the work again.
            ApiResponse denied = bench.bob().post(bench.path("/decisions/" + returned + "/decide"), Map.of("outcome",
                    "DENIED", "note", "Name the tax rule."));
            assertThat(denied.status()).as("%s", denied).isEqualTo(200);
            Turn second = take(runner);
            runner.send(completed(second, "Draft two, with the tax rule.", List.of()));
            UUID approved = awaitReview(bench.id(), conversationId, returned);
            assertThat(outbox(bench.id(), conversationId)).isEmpty();
            assertThat(reviews(bench.id(), conversationId)).isEqualTo(1);
            JiraStub.server().verify(0, postRequestedFor(urlEqualTo(jira.issue("/comment"))));

            // Approved: written, in order, exactly as shown.
            ApiResponse ok = bench.bob().post(bench.path("/decisions/" + approved + "/decide"), Map.of("outcome",
                    "APPROVED"));
            assertThat(ok.status()).as("%s", ok).isEqualTo(200);
            List<Map<String, Object>> rows = awaitOutbox(bench.id(), conversationId, now -> allDelivered(now, 3));
            assertThat(column(rows, "kind")).containsExactly("COMMENT", "TRANSITION", "REMOTE_LINK");
            assertThat(reviews(bench.id(), conversationId)).isZero();
            List<LoggedRequest> comments = JiraStub.server().findAll(postRequestedFor(urlEqualTo(
                    jira.issue("/comment"))));
            assertThat(comments).hasSize(1);
            assertThat(JSON.readTree(comments.getFirst().getBodyAsString()).get("body").asString())
                .startsWith("*" + bench.agentName() + " (Tinfirm agent):* Draft two, with the tax rule.")
                .doesNotContain("Draft one.");

            // V13: a repeated delivery writes nothing again.
            dueNow(bench.id(), conversationId);
            delivery.deliverDue();
            delivery.deliverDue();
            JiraStub.server().verify(1, postRequestedFor(urlEqualTo(jira.issue("/comment"))));
            JiraStub.server().verify(1, postRequestedFor(urlEqualTo(jira.issue("/transitions"))));

            // A failed turn is not reviewed: its failure's comment and actions are written at once.
            event(bench, triggerId, conversationId, "Carol commented: \"Retry the export.\"");
            Turn third = take(runner);
            Map<String, Object> failed = new LinkedHashMap<>(finished(third, "FAILED", "It crashed."));
            failed.put("failureClass", "INFRA");
            failed.put("failureMessage", "The sandbox stopped.");
            runner.send(failed);
            List<Map<String, Object>> after = awaitOutbox(bench.id(), conversationId, now -> allDelivered(now, 5));
            assertThat(column(after, "cause")).containsExactly("RESULT", "ON_SUCCESS", "RESULT", "FAILURE",
                    "ON_FAILURE");
            assertThat(JSON.readTree(JiraStub.server().findAll(postRequestedFor(urlEqualTo(jira.issue("/comment"))))
                .get(1).getBodyAsString()).get("body").asString()).contains(bench.agentName()
                        + " could not finish this: The sandbox stopped. The team of");
            JiraStub.server().verify(2, postRequestedFor(urlEqualTo(jira.issue("/transitions"))));
            runner.assertFramesValid();
        }
    }

    @Test
    void aQuestionIsWrittenToTheIssueAndTheTeamsAnswerInTinfirmToo() {
        Jira jira = jira("Asker");
        Bench bench = jira.bench();
        UUID triggerId = trigger(bench.alice(), bench, jira.system(), "SHOP", jira.account(), bench.alicePrincipal(),
                bench.team(), results(true, false, List.of(), List.of()));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
            .willReturn(JiraStub.json(201, Map.of("id", "801"))));

        try (FakeRunner runner = runner(bench)) {
            UUID conversationId = start(bench, triggerId, jira.system(), "10001", KEY, jira.stub().baseUrl()
                    + "/browse/" + KEY).conversation().id();
            Turn turn = take(runner);
            JsonNode asked = runner.requestAndAck(turn.runId(), turn.epoch(), "ASK", Map.of("question",
                    "Which currency?", "options", List.of("EUR", "USD")));
            assertThat(asked.get("status").asString()).as("%s", asked).isEqualTo("PENDING");
            runner.send(finished(turn, "SUCCEEDED", "I asked about the currency."));

            // §5.8: the turn's question is a comment on the issue, which says how to answer it; no result is written.
            List<Map<String, Object>> asking = awaitOutbox(bench.id(), conversationId, now -> allDelivered(now, 1));
            assertThat(asking.getFirst().get("cause")).isEqualTo("QUESTION");
            List<LoggedRequest> comments = JiraStub.server().findAll(postRequestedFor(urlEqualTo(
                    jira.issue("/comment"))));
            assertThat(JSON.readTree(comments.getFirst().getBodyAsString()).get("body").asString())
                .startsWith("*" + bench.agentName() + " (Tinfirm agent):* " + bench.agentName() + " has a question:")
                .contains("Which currency? (EUR / USD)", "Reply in a comment on this issue to answer.");

            // §11, point 4: Bob, of the team, answers in Tinfirm; the answer is written to the issue, naming him.
            ApiResponse answered = bench.bob().post(bench.path("/conversations/" + conversationId + "/messages"),
                    Map.of("body", "EUR, please.", "answersOpenQuestions", true));
            assertThat(answered.status()).as("%s", answered).isEqualTo(201);
            List<Map<String, Object>> rows = awaitOutbox(bench.id(), conversationId, now -> allDelivered(now, 2));
            assertThat(rows.get(1).get("cause")).isEqualTo("ANSWER");
            String bob = transactions.inCompany(bench.id(), () -> principals.displayNames(bench.id(),
                    List.of(bench.bobPrincipal()))).get(bench.bobPrincipal());
            String answer = JSON.readTree(JiraStub.server().findAll(postRequestedFor(urlEqualTo(
                    jira.issue("/comment")))).get(1).getBodyAsString()).get("body").asString();
            assertThat(answer).startsWith("*" + bench.agentName() + " (Tinfirm agent):* ")
                .contains("answered in Tinfirm:", "Which currency?", "EUR, please.");
            assertThat(answer).contains(bob + " answered in Tinfirm:");

            // The answer starts its DECIDED turn, as any answer does.
            JsonNode decided = runner.expect("run.assign");
            assertThat(decided.get("prompt").asString()).contains("EUR, please.");
            runner.assertFramesValid();
        }
    }

    /** The open review of a result of the conversation, other than {@code before}. */
    private static UUID awaitReview(UUID companyId, UUID conversationId, UUID before) {
        return await().atMost(WAIT).until(() -> {
            try (TenantSql sql = TenantSql.in(companyId)) {
                List<Object> ids = sql.column("""
                        SELECT id FROM decision WHERE conversation_id = ? AND status = 'OPEN' AND kind = 'APPROVAL'
                           AND subject ->> 'type' = 'RESULT'""", conversationId);
                return ids.isEmpty() || ids.getFirst().equals(before) ? null : (UUID) ids.getFirst();
            }
        }, id -> id != null);
    }

    /** The writes a review shows, as its decision keeps them. */
    private static String subjectWrites(UUID companyId, UUID decisionId) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return String.valueOf(sql.column("SELECT (subject -> 'writes')::text FROM decision WHERE id = ?",
                    decisionId).getFirst());
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static long reviews(UUID companyId, UUID conversationId) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("SELECT count(*) FROM outbox_review WHERE conversation_id = ?", conversationId);
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
