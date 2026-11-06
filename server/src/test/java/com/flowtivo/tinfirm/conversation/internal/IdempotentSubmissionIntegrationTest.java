package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestSettings;
import com.flowtivo.tinfirm.support.TestTriggers;

/**
 * Idempotent submissions (package R, §3.5) through the REST API: for each of {@code startConversation},
 * {@code postConversationMessage}, {@code uploadAttachment}, {@code retryConversation} and {@code decideDecision}, a
 * request sent again with its {@code Idempotency-Key} answers the first one's status and resource as it is now, with
 * {@code Idempotent-Replayed: true}, and makes, audits and charges nothing new; two requests with one key at once make
 * one resource; the key with another request is {@code 422 IDEMPOTENCY_KEY_REUSED}; a request that failed stored no
 * key; a key is the caller's own; and the sweep deletes keys after 24 hours, on a time the test chooses.
 */
class IdempotentSubmissionIntegrationTest extends ConversationBench {

    private static final String KEY = "Idempotency-Key";

    private static final String REPLAYED = "Idempotent-Replayed";

    private final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Autowired
    RunnerConnections connections;

    @Autowired
    SubmissionKeys keys;

    /**
     * The bench with the bundled gateway, its members' keys usable, the person's settings of the agent naming a model
     * (a run paid by a budget needs one: {@code MODEL_REQUIRED}), and a company runner host connected.
     */
    private Bench payingBench(List<FakeRunner> hosts) {
        Bench bench = bench();
        ModelGateways.configureBundled(bench.owner(), bench.company());
        for (UUID principal : List.of(bench.personPrincipalId(), memberPrincipal(bench.company(),
                bench.company().userId("OWNER")))) {
            ModelGateways.awaitKey(litellm, bench.company(), principal);
            ModelGateways.awaitStatus(bench.owner(), bench.company(), principal, "OK");
        }
        TestSettings.nameModel(bench.companyId(), bench.agentId(), bench.personPrincipalId());
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        FakeRunner runner = FakeRunner.connect(port, host.token());
        runner.helloV2AndWelcome(List.of(), List.of(), "0.2.0", 4);
        await().atMost(WAIT).until(() -> connections.isConfigured(host.id()));
        hosts.add(runner);
        return bench;
    }

    private static ApiResponse post(ApiClient client, String path, Object body, String key) {
        return client.send("POST", path, body, Map.of(KEY, key));
    }

    @Test
    void eachSubmissionSentAgainAnswersWhatTheFirstMadeAndMakesNothingNew() {
        List<FakeRunner> hosts = new ArrayList<>();
        try {
            Bench bench = payingBench(hosts);
            ApiClient person = bench.person();

            // startConversation: one conversation, one run, one record.
            String startKey = UUID.randomUUID().toString();
            Map<String, Object> start = Map.of("agentId", bench.agentId().toString(), "message", "Plan the release");
            ApiResponse started = post(person, bench.path("/conversations"), start, startKey);
            assertThat(started.status()).as("%s", started).isEqualTo(201);
            assertThat(started.header(REPLAYED)).isNull();
            String id = started.json().get("id").asString();
            ApiResponse startedAgain = post(person, bench.path("/conversations"), start, startKey);
            assertThat(startedAgain.status()).as("%s", startedAgain).isEqualTo(201);
            assertThat(startedAgain.header(REPLAYED)).isEqualTo("true");
            assertThat(startedAgain.json().get("id").asString()).isEqualTo(id);
            assertThat(person.get(bench.path("/conversations?agentId=" + bench.agentId())).json().get("items"))
                .hasSize(1);
            assertThat(auditEvents(bench.owner(), bench.company(), "conversation.started")).hasSize(1);
            assertThat(auditEvents(bench.owner(), bench.company(), "run.started")).hasSize(1);

            // postConversationMessage: one message.
            String messageKey = UUID.randomUUID().toString();
            String messages = bench.path("/conversations/" + id + "/messages");
            ApiResponse said = post(person, messages, Map.of("body", "Also the notes"), messageKey);
            assertThat(said.status()).as("%s", said).isEqualTo(201);
            ApiResponse saidAgain = post(person, messages, Map.of("body", "Also the notes"), messageKey);
            assertThat(saidAgain.status()).as("%s", saidAgain).isEqualTo(201);
            assertThat(saidAgain.header(REPLAYED)).isEqualTo("true");
            assertThat(saidAgain.json().get("messages").valueStream()
                .filter(message -> "Also the notes".equals(message.get("body").asString()))).hasSize(1);

            // uploadAttachment: one file, one record.
            String uploadKey = UUID.randomUUID().toString();
            String attachments = bench.path("/conversations/" + id + "/attachments");
            ApiClient.Multipart file = new ApiClient.Multipart("notes.txt", "text/plain",
                    "the notes".getBytes(StandardCharsets.UTF_8), Map.of("note", "For the plan"));
            ApiResponse uploaded = post(person, attachments, file, uploadKey);
            assertThat(uploaded.status()).as("%s", uploaded).isEqualTo(201);
            ApiResponse uploadedAgain = post(person, attachments, file, uploadKey);
            assertThat(uploadedAgain.status()).as("%s", uploadedAgain).isEqualTo(201);
            assertThat(uploadedAgain.header(REPLAYED)).isEqualTo("true");
            assertThat(uploadedAgain.json().get("id").asString()).isEqualTo(uploaded.json().get("id").asString());
            assertThat(person.get(attachments).json()).hasSize(1);
            assertThat(auditEvents(bench.owner(), bench.company(), "attachment.uploaded")).hasSize(1);
            // The same file under another name is another request.
            ApiResponse renamed = post(person, attachments, new ApiClient.Multipart("other.txt", "text/plain",
                    "the notes".getBytes(StandardCharsets.UTF_8), Map.of("note", "For the plan")), uploadKey);
            assertThat(renamed.status()).as("%s", renamed).isEqualTo(422);
            assertThat(renamed.problemCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

            // retryConversation: one new run, not TURN_ACTIVE.
            assertThat(person.post(bench.path("/conversations/" + id + "/cancel"), null).status()).isEqualTo(200);
            String retryKey = UUID.randomUUID().toString();
            String retry = bench.path("/conversations/" + id + "/retry");
            ApiResponse retried = post(person, retry, null, retryKey);
            assertThat(retried.status()).as("%s", retried).isEqualTo(200);
            int turns = retried.json().get("turnCount").asInt();
            ApiResponse retriedAgain = post(person, retry, null, retryKey);
            assertThat(retriedAgain.status()).as("%s", retriedAgain).isEqualTo(200);
            assertThat(retriedAgain.header(REPLAYED)).isEqualTo("true");
            assertThat(retriedAgain.json().get("turnCount").asInt()).isEqualTo(turns);
            assertThat(auditEvents(bench.owner(), bench.company(), "run.started")).hasSize(turns);

            // decideDecision: decided once, not DECISION_DECIDED.
            UUID question = TestConversations.question(bench.companyId(), UUID.fromString(id),
                    bench.personPrincipalId(), "Which branch?");
            String decideKey = UUID.randomUUID().toString();
            String decide = bench.path("/decisions/" + question + "/decide");
            ApiResponse decided = post(person, decide, Map.of("answer", "main"), decideKey);
            assertThat(decided.status()).as("%s", decided).isEqualTo(200);
            ApiResponse decidedAgain = post(person, decide, Map.of("answer", "main"), decideKey);
            assertThat(decidedAgain.status()).as("%s", decidedAgain).isEqualTo(200);
            assertThat(decidedAgain.header(REPLAYED)).isEqualTo("true");
            assertThat(decidedAgain.json().get("outcome").asString()).isEqualTo("ANSWERED");
            assertThat(auditEvents(bench.owner(), bench.company(), "decision.decided")).hasSize(1);
            // Without the key, the second is what it ever was.
            assertThat(person.post(decide, Map.of("answer", "main")).problemCode()).isEqualTo("DECISION_DECIDED");

            // The key with another operation or another body: 422, nothing made.
            ApiResponse otherBody = post(person, messages, Map.of("body", "Something else"), messageKey);
            assertThat(otherBody.status()).as("%s", otherBody).isEqualTo(422);
            assertThat(otherBody.problemCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
            ApiResponse otherOperation = post(person, retry, null, messageKey);
            assertThat(otherOperation.problemCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
            // A malformed key is refused.
            assertThat(post(person, messages, Map.of("body", "Bad key"), "has a space").status()).isEqualTo(400);
            assertThat(person.get(bench.path("/conversations/" + id)).json().get("messages").valueStream()
                .filter(message -> "Something else".equals(message.get("body").asString())
                        || "Bad key".equals(message.get("body").asString()))).isEmpty();
        }
        finally {
            hosts.forEach(FakeRunner::close);
        }
    }

    @Test
    void twoRequestsWithOneKeyAtOnceMakeOneResource() throws Exception {
        List<FakeRunner> hosts = new ArrayList<>();
        ExecutorService clients = Executors.newFixedThreadPool(2);
        try {
            Bench bench = payingBench(hosts);
            for (int round = 0; round < 3; round++) {
                String key = UUID.randomUUID().toString();
                Map<String, Object> start = Map.of("agentId", bench.agentId().toString(), "message", "Round " + round);
                CyclicBarrier together = new CyclicBarrier(2);
                List<Future<ApiResponse>> answers = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    answers.add(clients.submit(() -> {
                        together.await();
                        return post(bench.person(), bench.path("/conversations"), start, key);
                    }));
                }
                ApiResponse first = answers.get(0).get(60, TimeUnit.SECONDS);
                ApiResponse second = answers.get(1).get(60, TimeUnit.SECONDS);
                assertThat(first.status()).as("%s", first).isEqualTo(201);
                assertThat(second.status()).as("%s", second).isEqualTo(201);
                assertThat(first.json().get("id").asString()).isEqualTo(second.json().get("id").asString());
                assertThat(List.of(String.valueOf(first.header(REPLAYED)), String.valueOf(second.header(REPLAYED))))
                    .containsExactlyInAnyOrder("true", "null");
            }
            assertThat(auditEvents(bench.owner(), bench.company(), "conversation.started")).hasSize(3);
        }
        finally {
            clients.shutdownNow();
            hosts.forEach(FakeRunner::close);
        }
    }

    @Test
    void inATriggersConversationTheKeyIsTheCallersFromItsAudience() throws SQLException {
        List<FakeRunner> hosts = new ArrayList<>();
        try {
            Bench bench = payingBench(hosts);
            // A trigger's conversation (package 4a) has no person: its author decides its question in the app.
            UUID author = bench.personPrincipalId();
            UUID triggerId = TestTriggers.schedule(bench.companyId(), bench.agentId(), author, author);
            UUID conversationId = UUID.randomUUID();
            TestTriggers.conversation(bench.companyId(), conversationId, "trig-" + conversationId.toString()
                .substring(0, 8), triggerId, bench.agentId(), author, author);
            UUID question = TestConversations.question(bench.companyId(), conversationId, null, "Which branch?");
            String key = "trigger-" + UUID.randomUUID();
            String path = bench.path("/decisions/" + question + "/decide");

            ApiResponse decided = post(bench.person(), path, Map.of("answer", "main"), key);
            assertThat(decided.status()).as("%s", decided).isEqualTo(200);
            assertThat(decided.header(REPLAYED)).isNull();
            ApiResponse again = post(bench.person(), path, Map.of("answer", "main"), key);
            assertThat(again.status()).as("%s", again).isEqualTo(200);
            assertThat(again.header(REPLAYED)).isEqualTo("true");
            assertThat(again.json().get("id").asString()).isEqualTo(question.toString());

            // The key is the caller's, not the conversation's person's, which it has none of.
            try (TenantSql sql = TenantSql.in(bench.companyId())) {
                assertThat(sql.column("SELECT principal_id FROM submission_key WHERE key = ?", key))
                    .containsExactly(author);
            }
            assertThat(auditEvents(bench.owner(), bench.company(), "decision.decided")).hasSize(1);
        }
        finally {
            hosts.forEach(FakeRunner::close);
        }
    }

    @Test
    void aStartRefusedWhileTheCompanysRunsAreStoppedStoresNoKeyAndAStartMadeBeforeReplays() throws SQLException {
        List<FakeRunner> hosts = new ArrayList<>();
        try {
            Bench bench = payingBench(hosts);
            Map<String, Object> start = Map.of("agentId", bench.agentId().toString(), "message", "Plan it");
            String before = "before-stop-" + UUID.randomUUID();
            ApiResponse started = post(bench.person(), bench.path("/conversations"), start, before);
            assertThat(started.status()).as("%s", started).isEqualTo(201);
            ApiResponse stopped = bench.admin().post(bench.path("/runs-stop"), Map.of("reason", "Incident review"));
            assertThat(stopped.status()).as("%s", stopped).isEqualTo(200);

            // A new start is refused (package 6's kill switch), and its key is not kept.
            String during = "during-stop-" + UUID.randomUUID();
            ApiResponse refused = post(bench.person(), bench.path("/conversations"), start, during);
            assertThat(refused.status()).as("%s", refused).isEqualTo(409);
            assertThat(refused.problemCode()).isEqualTo("COMPANY_STOPPED");
            try (TenantSql sql = TenantSql.in(bench.companyId())) {
                assertThat(sql.count("SELECT count(*) FROM submission_key WHERE key = ?", during)).isZero();
            }
            // The start made before the stop is answered again as it is: a replay makes nothing.
            ApiResponse replayed = post(bench.person(), bench.path("/conversations"), start, before);
            assertThat(replayed.status()).as("%s", replayed).isEqualTo(201);
            assertThat(replayed.header(REPLAYED)).isEqualTo("true");
            assertThat(replayed.json().get("id").asString()).isEqualTo(started.json().get("id").asString());

            // Once the stop is lifted, the refused request's key starts its conversation.
            assertThat(bench.owner().delete(bench.path("/runs-stop")).status()).isEqualTo(200);
            ApiResponse anew = post(bench.person(), bench.path("/conversations"), start, during);
            assertThat(anew.status()).as("%s", anew).isEqualTo(201);
            assertThat(anew.header(REPLAYED)).isNull();
        }
        finally {
            hosts.forEach(FakeRunner::close);
        }
    }

    @Test
    void aFailedRequestStoresNoKeyAKeyIsTheCallersOwnAndTheSweepDeletesItAfterADay() throws SQLException {
        List<FakeRunner> hosts = new ArrayList<>();
        try {
            Bench bench = payingBench(hosts);
            String key = "retry-me-" + UUID.randomUUID();
            // The first request fails: no key is stored, so the same key serves the corrected request.
            ApiResponse failed = post(bench.person(), bench.path("/conversations"), Map.of("agentId",
                    bench.agentId().toString(), "message", " "), key);
            assertThat(failed.status()).as("%s", failed).isEqualTo(400);
            ApiResponse started = post(bench.person(), bench.path("/conversations"), Map.of("agentId",
                    bench.agentId().toString(), "message", "Plan it"), key);
            assertThat(started.status()).as("%s", started).isEqualTo(201);
            assertThat(started.header(REPLAYED)).isNull();

            // The OWNER's same key is another key: their own conversation.
            ApiResponse ownersAgentCreated = bench.owner().post(bench.path("/agents"), Map.of("name",
                    unique("Owners"), "runtimes", List.of("CLAUDE_CODE")));
            assertThat(ownersAgentCreated.status()).as("%s", ownersAgentCreated).isEqualTo(201);
            String ownersAgent = ownersAgentCreated.json().get("id").asString();
            TestSettings.nameModel(bench.companyId(), UUID.fromString(ownersAgent),
                    memberPrincipal(bench.company(), bench.company().userId("OWNER")));
            ApiResponse owners = post(bench.owner(), bench.path("/conversations"), Map.of("agentId", ownersAgent,
                    "message", "Plan it"), key);
            assertThat(owners.status()).as("%s", owners).isEqualTo(201);
            assertThat(owners.header(REPLAYED)).isNull();
            assertThat(owners.json().get("id").asString()).isNotEqualTo(started.json().get("id").asString());
            try (TenantSql sql = TenantSql.in(bench.companyId())) {
                assertThat(sql.count("SELECT count(*) FROM submission_key WHERE key = ?", key)).isEqualTo(2);
            }

            // Kept for a day: the sweep leaves it before, and deletes it after.
            keys.sweep(Instant.now().plus(Duration.ofHours(23)));
            ApiResponse replayed = post(bench.person(), bench.path("/conversations"), Map.of("agentId",
                    bench.agentId().toString(), "message", "Plan it"), key);
            assertThat(replayed.header(REPLAYED)).isEqualTo("true");
            keys.sweep(Instant.now().plus(Duration.ofHours(25)));
            try (TenantSql sql = TenantSql.in(bench.companyId())) {
                assertThat(sql.count("SELECT count(*) FROM submission_key WHERE key = ?", key)).isZero();
            }
            ApiResponse anew = post(bench.person(), bench.path("/conversations"), Map.of("agentId",
                    bench.agentId().toString(), "message", "Plan it"), key);
            assertThat(anew.status()).as("%s", anew).isEqualTo(201);
            assertThat(anew.header(REPLAYED)).isNull();
            assertThat(anew.json().get("id").asString()).isNotEqualTo(started.json().get("id").asString());
        }
        finally {
            hosts.forEach(FakeRunner::close);
        }
    }

}
