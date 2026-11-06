package com.flowtivo.tinfirm.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TestCredentials;
import com.flowtivo.tinfirm.support.TestSettings;

import tools.jackson.databind.JsonNode;

/**
 * The conversation module's integration tests' bench (TEST FIXTURE ONLY): a company with its OWNER, ADMIN and MEMBER
 * (the member is the person of most conversations), the bundled model gateway with their keys, a company runner host
 * of protocol v2 that takes runs paid by a budget, code hosts of kind {@code GIT} whose tokens are taken as they are,
 * and agents made through the API. Conversations are started, read and driven through the API, and their turns'
 * runs through the fake host, so the real ports of the conversation module answer the run module.
 */
abstract class ConversationTestBench extends IntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(20);

    /** The runner version its hosts say in {@code hello}: any, since it decides nothing (ADR-0109). */
    static final String RUNNER_VERSION = "0.2.0";

    final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Autowired
    RunnerConnections connections;

    /** A company, its clients and principals by role. */
    record Bench(TestCompany company, ApiClient owner, ApiClient admin, ApiClient member, UUID ownerPrincipal,
            UUID adminPrincipal, UUID memberPrincipal) {

        UUID id() {
            return company.id();
        }

        String path(String suffix) {
            return company.path(suffix);
        }
    }

    /** A company and its members; with the bundled gateway configured and every member's key usable when asked. */
    Bench bench(boolean gateway) {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        Bench bench = new Bench(company, owner, loginAs(company.email("ADMIN")), loginAs(company.email("MEMBER")),
                memberPrincipal(company, company.userId("OWNER")), memberPrincipal(company, company.userId("ADMIN")),
                memberPrincipal(company, company.userId("MEMBER")));
        if (gateway) {
            ModelGateways.configureBundled(owner, company);
            for (UUID principal : List.of(bench.ownerPrincipal(), bench.adminPrincipal(), bench.memberPrincipal())) {
                ModelGateways.awaitKey(litellm, company, principal);
                ModelGateways.awaitStatus(owner, company, principal, "OK");
            }
        }
        return bench;
    }

    /** A company runner host connected over protocol v2, with this many slots, configured. */
    FakeRunner companyHost(Bench bench, int slots) {
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.id(), unique("host"), "COMPANY");
        FakeRunner runner = FakeRunner.connect(port, host.token());
        runner.helloV2AndWelcome(List.of(), List.of(), RUNNER_VERSION, slots);
        await().atMost(WAIT).until(() -> connections.isConfigured(host.id()));
        return runner;
    }

    /**
     * A company runner host as {@link #companyHost}, whose {@code hello} lists the feature {@code run.steer}: the
     * server gives it the person's messages for its running turns.
     */
    FakeRunner steeringHost(Bench bench, int slots) {
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.id(), unique("host"), "COMPANY");
        FakeRunner runner = FakeRunner.connect(port, host.token());
        Map<String, Object> hello = FakeRunner.helloV2(List.of(), List.of(), RUNNER_VERSION, slots);
        hello.put("features", List.of("run.steer"));
        runner.send(hello);
        runner.expect("welcome");
        await().atMost(WAIT).until(() -> connections.isConfigured(host.id()));
        return runner;
    }

    /** A code host of kind GIT, whose tokens are taken as they are, with one repository; answers both ids. */
    UUID[] gitHost(Bench bench, String name) {
        UUID system = createSystem(bench.owner(), bench.company(), unique(name), "GIT", null, false);
        ApiResponse repository = bench.owner().post(bench.path("/systems/" + system + "/repositories"),
                Map.of("url", "https://git.example.test/acme/" + unique("shop").toLowerCase() + ".git"));
        assertThat(repository.status()).as("%s", repository).isEqualTo(201);
        return new UUID[] { system, UUID.fromString(repository.json().get("id").asString()) };
    }

    /**
     * An agent the client creates, with its instructions, the code host and the repository with its access, whose
     * settings name a model for the bench's people (a budget's run names one).
     */
    UUID agent(ApiClient creator, Bench bench, String name, String instructions, List<UUID[]> repositories,
            String access, List<UUID> shareGroupIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique(name));
        body.put("runtimes", List.of("CLAUDE_CODE", "CODEX"));
        body.put("instructions", instructions);
        List<Map<String, Object>> systems = new ArrayList<>();
        List<Map<String, Object>> repos = new ArrayList<>();
        for (UUID[] repository : repositories) {
            systems.add(Map.of("systemId", repository[0].toString(), "tools", List.of()));
            repos.add(Map.of("repositoryId", repository[1].toString(), "access", access));
        }
        body.put("systems", systems);
        body.put("repositories", repos);
        body.put("shareGroupIds", shareGroupIds.stream().map(UUID::toString).toList());
        ApiResponse agent = creator.post(bench.path("/agents"), body);
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        UUID agentId = UUID.fromString(agent.json().get("id").asString());
        for (UUID principal : List.of(bench.ownerPrincipal(), bench.adminPrincipal(), bench.memberPrincipal())) {
            TestSettings.nameModel(bench.id(), agentId, principal);
        }
        return agentId;
    }

    /** The client's own token on a GIT system, taken as it is. */
    UUID connectOwn(ApiClient client, Bench bench, UUID system, String token) {
        ApiResponse response = client.put(bench.path("/me/accounts/" + system + "/token"), Map.of("token", token));
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return UUID.fromString(response.json().get("id").asString());
    }

    /** A token on a GIT system as its credential, added by the OWNER and lent to the service account. */
    UUID connectService(Bench bench, UUID serviceIdentity, UUID system, String token) {
        return TestCredentials.lendToken(bench.owner(), bench.path(""), serviceIdentity, system, token);
    }

    JsonNode start(ApiClient client, Bench bench, UUID agentId, String message) {
        ApiResponse response = client.post(bench.path("/conversations"), Map.of("agentId", agentId.toString(),
                "message", message));
        assertThat(response.status()).as("%s", response).isEqualTo(201);
        return response.json();
    }

    JsonNode conversation(ApiClient client, Bench bench, String conversationId) {
        ApiResponse response = client.get(bench.path("/conversations/" + conversationId));
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return response.json();
    }

    JsonNode awaitConversation(ApiClient client, Bench bench, String conversationId,
            java.util.function.Predicate<JsonNode> condition) {
        await().atMost(WAIT).until(() -> condition.test(conversation(client, bench, conversationId)));
        return conversation(client, bench, conversationId);
    }

    /** The conversation's latest turn, its run as the Runs API shows it. */
    static JsonNode lastTurn(JsonNode conversation) {
        JsonNode turns = conversation.get("turns");
        return turns.get(turns.size() - 1);
    }

    static Map<String, Object> finished(String runId, long epoch, String outcome, String summary) {
        return Map.of("type", "run.finished", "runId", runId, "epoch", epoch, "outcome", outcome, "lastSeq", 0,
                "summary", summary, "sessionId", "session-" + runId.substring(0, 8), "git", List.of(),
                "result", Map.of("outcome", "COMPLETED", "summary", summary, "nextSteps", List.of(),
                        "openQuestions", List.of()));
    }
}
