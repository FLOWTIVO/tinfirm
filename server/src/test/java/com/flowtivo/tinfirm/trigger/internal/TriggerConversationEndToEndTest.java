package com.flowtivo.tinfirm.trigger.internal;

import static com.flowtivo.tinfirm.support.TestTriggers.withModel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;

/**
 * A trigger's conversation end to end (the package 4 contract, §5.6 to §5.8, §5.12): part b's firing starts it with
 * the trigger's author, payer and table; its first turn's prompt carries the trigger's instruction, the subject's
 * summary as other people's words and the firing as its request; a firing during a run waits and the run's end starts
 * its {@code EVENT} turn; a question is answered by a comment on the issue ({@code decided_by_name}) and its
 * {@code DECIDED} turn follows; its team sees it, and plain messages are refused ({@code PLACE_IS_EXTERNAL}). Part b's
 * firings are written here as rows, as part b writes them. Driven through the API, a fake runner host and the Jira
 * stub. TEST FIXTURE ONLY: tokens are test values.
 */
class TriggerConversationEndToEndTest extends IntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(20);

    static final Duration NONE_WITHIN = Duration.ofSeconds(2);

    final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Autowired
    RunnerConnections connections;

    @Autowired
    SystemTransactions transactions;

    @Autowired
    FiringConversations firings;

    @Autowired
    TriggerConversations conversations;

    @Test
    void aFiringStartsAConversationItsLaterFiringsAndCommentsContinue() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        String bobEmail = uniqueEmail("bob");
        UUID bobUser = createUser(admin(), bobEmail, false);
        addMember(owner, company.id(), bobEmail, "MEMBER");
        ApiClient bob = loginAs(bobEmail);
        UUID alicePrincipal = memberPrincipal(company, company.userId("MEMBER"));
        ModelGateways.configureBundled(owner, company);
        ModelGateways.awaitKey(litellm, company, alicePrincipal);
        ModelGateways.awaitStatus(owner, company, alicePrincipal, "OK");
        UUID users = createGroup(owner, company, unique("Users"), List.of(company.userId("MEMBER"), bobUser));
        UUID team = createGroup(owner, company, unique("Team"), List.of(bobUser));
        ApiResponse agent = owner.post(company.path("/agents"), Map.of("name", unique("Analyst"), "runtimes",
                List.of("CLAUDE_CODE"), "instructions", "You analyse issues.", "shareGroupIds",
                List.of(users.toString())));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID system = createSystem(owner, company, unique("Jira"), "JIRA_DC", jira.baseUrl(), false);
        UUID account = connectToken(alice, company.path("/me/accounts/" + system), jira, unique("alice"), "alice");
        String name = unique("Prepare");
        ApiResponse created = alice.post(company.path("/triggers"), withModel(Map.ofEntries(Map.entry("name", name),
                Map.entry("source", "TRACKER"), Map.entry("systemId", system.toString()),
                Map.entry("events", List.of(Map.of("event", "ASSIGNED"), Map.of("event", "COMMENTED"))),
                Map.entry("subject", "ISSUE"), Map.entry("scope", "SHOP"),
                Map.entry("agentId", agent.json().get("id").asString()),
                Map.entry("agentVersion", agent.json().get("version").asInt()),
                Map.entry("instruction", "Prepare the issue for the team."),
                Map.entry("accounts", List.of(Map.of("systemId", system.toString(), "accountId",
                        account.toString()))),
                Map.entry("payerPrincipalId", alicePrincipal.toString()), Map.entry("teamGroupId", team.toString()),
                Map.entry("acknowledgeOpenEvents", true))));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        UUID triggerId = UUID.fromString(created.json().get("id").asString());

        RunnerHostRef host = registerRunnerHost(owner, company.id(), unique("host"), "COMPANY");
        try (FakeRunner runner = FakeRunner.connect(port, host.token())) {
            runner.helloV2AndWelcome(List.of(), List.of(), "0.2.0", 4);
            await().atMost(WAIT).until(() -> connections.isConfigured(host.id()));

            // Part b's firing: the issue assigned; it starts the conversation with its first turn.
            UUID first = firing(company.id(), triggerId, null, "ASSIGNED",
                    "SHOP-1 was assigned to alice by Bob Jira at 10:02.", "Type: Story\nStatus: To do\n"
                            + "Description: Checkout fails for EUR.");
            TriggerConversations.Started started = transactions.inCompany(company.id(), () -> firings.start(
                    company.id(), triggerId, new FiringConversations.Place.Subject(new TriggerConversations
                        .TrackerSubject(system, "10001", "SHOP-1", "ISSUE", jira.baseUrl() + "/browse/SHOP-1",
                                "Checkout fails")), 0, first, null));
            assertThat(started.runId()).isPresent();
            String conversationId = started.conversation().id().toString();
            JsonNode assign = runner.expect("run.assign");
            String prompt = assign.get("prompt").asString();
            assertThat(prompt).contains("You analyse issues.", "# Conversation", "Started by the trigger " + name,
                    "# Instruction\nPrepare the issue for the team.", "# Subject", "Checkout fails for EUR.",
                    "# Request", "SHOP-1 was assigned to alice by Bob Jira at 10:02.",
                    "questions are posted on the issue");
            UUID firstRun = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            runner.accepted(firstRun, epoch);

            // The conversation: its trigger's author and payer, the team sees it, and its place is the issue.
            JsonNode conversation = bob.get(company.path("/conversations/" + conversationId)).json();
            assertThat(conversation.get("place").asString()).isEqualTo("TRACKER_ISSUE");
            assertThat(conversation.get("title").asString()).isEqualTo(name + ": SHOP-1 Checkout fails");
            assertThat(conversation.get("trigger").get("subjectKind").asString()).isEqualTo("ISSUE");
            assertThat(conversation.get("bindings").get(0).get("how").asString()).isEqualTo("FIXED");
            assertThat(bob.post(company.path("/conversations/" + conversationId + "/messages"), Map.of("body",
                    "Hello")).problemCode()).isEqualTo("PLACE_IS_EXTERNAL");
            // As the page says it: its place is the issue, and no question waits to be answered here.
            assertThat(conversation.get("mayPost").asBoolean()).isFalse();
            assertThat(conversation.get("mayAnswer").asBoolean()).isFalse();
            assertThat(firingRun(company.id(), first)).isEqualTo(firstRun);

            // A comment during the run: its firing waits, and the run's end starts its EVENT turn.
            UUID second = firing(company.id(), triggerId, UUID.fromString(conversationId), "COMMENTED",
                    "Carol Jira commented: \"Please also check the tax.\"", null);
            assertThat(transactions.inCompany(company.id(), () -> firings.continueWith(company.id(),
                    UUID.fromString(conversationId)))).isEmpty();
            runner.expectNone("run.assign", NONE_WITHIN);
            runner.send(finished(firstRun, epoch, "I looked at the issue."));
            JsonNode event = runner.expect("run.assign");
            assertThat(event.get("prompt").asString() + event.path("resumePrompt").asString(""))
                .contains("Please also check the tax.");
            UUID eventRun = UUID.fromString(event.get("runId").asString());
            long eventEpoch = event.get("epoch").asLong();
            assertThat(firingRun(company.id(), second)).isEqualTo(eventRun);
            JsonNode afterEvent = await().atMost(WAIT).until(() -> alice.get(company.path("/conversations/"
                    + conversationId)).json(), now -> now.get("turnCount").asInt() == 2);
            JsonNode turns = afterEvent.get("turns");
            assertThat(turns.get(turns.size() - 1).get("turnReason").asString()).isEqualTo("EVENT");

            // The agent asks: a question of the trigger's conversation names nobody; its team decides it in Tinfirm,
            // and the next comment on the issue answers it.
            runner.accepted(eventRun, eventEpoch);
            JsonNode asked = runner.requestAndAck(eventRun, eventEpoch, "ASK", Map.of("question", "Which currency?"));
            String questionId = asked.get("payload").get("decisionId").asString();
            JsonNode question = bob.get(company.path("/decisions/" + questionId)).json();
            assertThat(question.path("approverPrincipalId").isMissingNode() || question.get("approverPrincipalId")
                .isNull()).isTrue();
            assertThat(question.get("mayDecide").asBoolean()).isTrue();
            // Its team may answer it here, still not write a plain message.
            JsonNode asking = bob.get(company.path("/conversations/" + conversationId)).json();
            assertThat(asking.get("mayAnswer").asBoolean()).isTrue();
            assertThat(asking.get("mayPost").asBoolean()).isFalse();
            TriggerConversations.External comment = transactions.inCompany(company.id(), () -> conversations.external(
                    company.id(), UUID.fromString(conversationId), new TriggerConversations.ExternalComment("c-77",
                            Map.of("id", "u-9", "login", "carol", "name", "Carol Jira"), null, "EUR, please.",
                            Instant.now(), true)));
            assertThat(comment.created()).isTrue();
            assertThat(comment.answered()).isEqualTo(1);
            assertThat(comment.runId()).isEmpty();
            assertThat(transactions.inCompany(company.id(), () -> conversations.external(company.id(),
                    UUID.fromString(conversationId), new TriggerConversations.ExternalComment("c-77",
                            Map.of("id", "u-9"), null, "EUR, please.", Instant.now(), true))).created()).isFalse();
            JsonNode answered = bob.get(company.path("/decisions/" + questionId)).json();
            assertThat(answered.get("status").asString()).isEqualTo("DECIDED");
            assertThat(answered.get("decidedByName").asString()).isEqualTo("Carol Jira");

            runner.send(finished(eventRun, eventEpoch, "I asked about the currency."));
            JsonNode decided = runner.expect("run.assign");
            assertThat(decided.get("prompt").asString()).contains("# Answers", "EUR, please.");
            JsonNode afterDecided = alice.get(company.path("/conversations/" + conversationId)).json();
            List<String> kinds = new java.util.ArrayList<>();
            afterDecided.get("messages").forEach(message -> kinds.add(message.get("kind").asString()));
            assertThat(kinds).contains("EXTERNAL", "AGENT");

            // Part c raises the review of the turn's result (T14): the team decides it, the author without a seat in
            // the team does not; returned with a note, a REVIEW_RETURNED turn does the work again.
            UUID decidedRun = UUID.fromString(decided.get("runId").asString());
            long decidedEpoch = decided.get("epoch").asLong();
            runner.accepted(decidedRun, decidedEpoch);
            runner.send(finished(decidedRun, decidedEpoch, "The issue is ready."));
            await().atMost(WAIT).until(() -> "SUCCEEDED".equals(alice.get(company.path("/conversations/"
                    + conversationId)).json().path("turnStatus").asString()));
            UUID review = transactions.inCompany(company.id(), () -> conversations.raiseResultReview(company.id(),
                    UUID.fromString(conversationId), decidedRun, "The issue is ready.",
                    List.of(new TriggerConversations.ReviewWrite("COMMENT", "SHOP-1", null,
                            "A comment on SHOP-1: The issue is ready."))));
            JsonNode raised = bob.get(company.path("/decisions/" + review)).json();
            assertThat(raised.get("approverGroupId").asString()).isEqualTo(team.toString());
            assertThat(raised.get("subject").get("type").asString()).isEqualTo("RESULT");
            assertThat(raised.get("subject").get("runId").asString()).isEqualTo(decidedRun.toString());
            assertThat(raised.get("subject").get("summary").asString()).isEqualTo("The issue is ready.");
            assertThat(raised.get("subject").get("writes")).extracting(JsonNode::asString)
                .containsExactly("A comment on SHOP-1: The issue is ready.");
            assertThat(raised.get("subject").get("writeItems")).singleElement().satisfies(item -> {
                assertThat(item.get("kind").asString()).isEqualTo("COMMENT");
                assertThat(item.get("target").asString()).isEqualTo("SHOP-1");
                assertThat(item.get("text").asString()).isEqualTo("A comment on SHOP-1: The issue is ready.");
            });
            assertThat(raised.get("mayDecide").asBoolean()).isTrue();
            // The conversation waits for the team's review: "Waits for <team> · Review".
            JsonNode reviewing = bob.get(company.path("/conversations/" + conversationId)).json();
            assertThat(reviewing.get("waitingOn").asString()).isEqualTo("PERSON");
            assertThat(reviewing.get("waitingReason").asString()).isEqualTo("REVIEW");
            assertThat(reviewing.get("waitingGroupId").asString()).isEqualTo(team.toString());
            assertThat(reviewing.get("waitingGroupName").asString())
                .isEqualTo(raised.get("approverGroupName").asString());
            assertThat(alice.post(company.path("/decisions/" + review + "/decide"), Map.of("outcome", "APPROVED"))
                .problemCode()).isEqualTo("CONVERSATION_BOUND");
            assertThat(bob.post(company.path("/decisions/" + review + "/decide"), Map.of("outcome", "DENIED"))
                .problemCode()).isEqualTo("VALIDATION_FAILED");
            ApiResponse returned = bob.post(company.path("/decisions/" + review + "/decide"), Map.of("outcome",
                    "DENIED", "note", "Name the tax rule too."));
            assertThat(returned.status()).as("%s", returned).isEqualTo(200);
            JsonNode again = runner.expect("run.assign");
            assertThat(again.get("prompt").asString() + again.path("resumePrompt").asString(""))
                .contains("Name the tax rule too.");
            JsonNode afterReview = await().atMost(WAIT).until(() -> alice.get(company.path("/conversations/"
                    + conversationId)).json(), now -> now.get("turnCount").asInt() == 4);
            JsonNode allTurns = afterReview.get("turns");
            assertThat(allTurns.get(allTurns.size() - 1).get("turnReason").asString()).isEqualTo("REVIEW_RETURNED");
            assertThat(auditEvents(owner, company, "conversation.started")).isNotEmpty();

            // OWNER, of the trigger's audience, reads its conversation, its list, its decisions and its run by right
            // (§5.13): the conversation has no person, and none of it is on record as a read of another person's.
            assertThat(owner.get(company.path("/conversations/" + conversationId)).status()).isEqualTo(200);
            assertThat(owner.get(company.path("/conversations?triggerId=" + triggerId)).json().get("items"))
                .hasSize(1);
            assertThat(owner.get(company.path("/decisions/" + review)).status()).isEqualTo(200);
            assertThat(owner.get(company.path("/decisions")).json().get("items")).isNotEmpty();
            assertThat(owner.get(company.path("/runs/" + decidedRun)).status()).isEqualTo(200);
            assertThat(auditEvents(owner, company, "conversation.read")).isEmpty();

            // The commenter's erasure at the issue's system (§15.11, IdentityContent): their comment goes, its author's
            // id, login and name with it, and the question it answered loses its texts and their name; in the open
            // conversation too, and nobody else's words.
            // Its dry run counts what the erasure then removes, by the module's own selection, changing nothing.
            JsonNode preview = owner.post(company.path("/systems/" + system + "/identities/u-9/erase/preview"), null)
                .json();
            assertThat(preview.get("holds")).isEmpty();
            ApiResponse erased = owner.post(company.path("/systems/" + system + "/identities/u-9/erase"), null);
            assertThat(erased.status()).as("%s", erased).isEqualTo(200);
            assertThat(erased.json().get("erased")).isEqualTo(preview.get("erases"));
            assertThat(erased.json().get("erased").get("other").get("messages").asLong()).isEqualTo(1);
            assertThat(erased.json().get("erased").get("other").get("decisions").asLong()).isEqualTo(1);
            JsonNode erasedQuestion = bob.get(company.path("/decisions/" + questionId)).json();
            assertThat(erasedQuestion.path("purgedAt").isNull() || erasedQuestion.path("purgedAt").isMissingNode())
                .isFalse();
            assertThat(erasedQuestion.path("decidedByName").isNull()
                    || erasedQuestion.path("decidedByName").isMissingNode()).isTrue();
            assertThat(erasedQuestion.path("answer").isNull() || erasedQuestion.path("answer").isMissingNode())
                .isTrue();
            List<JsonNode> externals = new java.util.ArrayList<>();
            bob.get(company.path("/conversations/" + conversationId)).json().get("messages").forEach(message -> {
                if ("EXTERNAL".equals(message.get("kind").asString())) {
                    externals.add(message);
                }
            });
            assertThat(externals).singleElement().satisfies(message -> {
                assertThat(message.get("body").asString()).isEqualTo("[This message was removed.]");
                assertThat(message.path("authorName").isNull() || message.path("authorName").isMissingNode())
                    .isTrue();
            });
            assertThat(owner.post(company.path("/systems/" + system + "/identities/u-9/erase"), null).json()
                .get("erased").path("other").path("messages").asLong(0)).as("nothing left of theirs").isZero();
            runner.assertFramesValid();
        }
    }

    /** A firing as part b writes it: a subject's event, waiting for a turn (its conversation, no run yet). */
    private static UUID firing(UUID companyId, UUID triggerId, @Nullable UUID conversationId, String event,
            String request, @Nullable String summary) throws SQLException {
        UUID id = UUID.randomUUID();
        try (TenantSql sql = TenantSql.in(companyId)) {
            sql.accepted("""
                    INSERT INTO trigger_firing (id, company_id, trigger_id, subject_id, subject_key, event, outcome,
                                                request, subject_summary, conversation_id, changed_at, created_at)
                    VALUES (?, ?, ?, '10001', 'SHOP-1', ?, ?, ?, ?, ?, ?, ?)""", id, companyId, triggerId, event,
                    conversationId == null ? "STARTED" : "CONTINUED", request, summary, conversationId,
                    OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC));
            sql.commit();
        }
        return id;
    }

    private static @Nullable UUID firingRun(UUID companyId, UUID firingId) throws SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            List<Object> runs = sql.column("SELECT run_id FROM trigger_firing WHERE id = ?", firingId);
            return (UUID) runs.getFirst();
        }
    }

    private static Map<String, Object> finished(UUID runId, long epoch, String summary) {
        return Map.of("type", "run.finished", "runId", runId.toString(), "epoch", epoch, "outcome", "SUCCEEDED",
                "lastSeq", 0, "summary", summary, "sessionId", "session-" + runId.toString().substring(0, 8), "git",
                List.of());
    }
}
