package com.flowtivo.tinfirm.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;

import tools.jackson.databind.JsonNode;

/**
 * ADR-0113, decision 4, from the agent's side: its editor chooses a level its company's hosts report, which makes a
 * version that widens nothing and is audited; a chosen level no host reports refuses the start
 * ({@code NO_HOST_OF_LEVEL}) and readiness says so; removing the last host of a level is audited with it; and a
 * conversation says where its turn ran when its session was on a host of another level.
 */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ConversationIsolationIntegrationTest extends ConversationTestBench {

    /** A company host that reports a level, connected and configured; its name in {@code names}. */
    private FakeRunner leveledHost(Bench bench, String name, String level, String hardened) {
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.id(), name, "COMPANY");
        FakeRunner runner = FakeRunner.connect(port, host.token());
        runner.send(FakeRunner.helloWithIsolation(level, hardened));
        runner.expect("welcome");
        await().atMost(WAIT).until(() -> connections.isConfigured(host.id()));
        return runner;
    }

    private UUID hostId(Bench bench, String name) {
        for (JsonNode host : bench.owner().get(bench.path("/runner-hosts")).json().get("items")) {
            if (host.get("name").asString().equals(name)) {
                return UUID.fromString(host.get("id").asString());
            }
        }
        throw new AssertionError("No host " + name);
    }

    private static List<String> systemNotes(JsonNode conversation) {
        return java.util.stream.StreamSupport.stream(conversation.get("messages").spliterator(), false)
            .filter(message -> "SYSTEM".equals(message.get("kind").asString()))
            .map(message -> message.get("body").asString())
            .toList();
    }

    @Test
    void anEditorChoosesALevelTheHostsReportAndAStartWithoutOneIsRefused() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Analyst", "Do it.", List.of(), "READ", List.of());
        String agentPath = bench.path("/agents/" + agentId);
        String vm = unique("vm");
        try (FakeRunner container = companyHost(bench, 2); FakeRunner machine = leveledHost(bench, vm, "machine",
                "none")) {
            JsonNode isolation = bench.member().get(agentPath).json().get("isolation");
            assertThat(isolation.get("setting").asString()).isEqualTo("AUTOMATIC");
            assertThat(isolation.get("level").asString()).isEqualTo("MACHINE");
            assertThat(isolation.get("offered")).extracting(JsonNode::asString).containsExactly("MACHINE",
                    "CONTAINER");
            assertThat(isolation.get("chosenUnavailable").asBoolean()).isFalse();
            assertThat(isolation.get("outsideStarts").asBoolean()).isFalse();
            assertThat(isolation.get("containerWarning").asBoolean()).isFalse();

            // Only a level the company's hosts report.
            ApiResponse notOffered = bench.member().patch(agentPath, Map.of("isolation", "VM_JAIL"));
            assertThat(notOffered.status()).as("%s", notOffered).isEqualTo(400);
            assertThat(notOffered.problemCode()).isEqualTo("VALIDATION_FAILED");
            assertThat(notOffered.json().get("errors").get(0).get("field").asString()).isEqualTo("isolation");

            int version = bench.member().get(agentPath).json().get("version").asInt();
            ApiResponse chosen = bench.member().patch(agentPath, Map.of("isolation", "MACHINE"));
            assertThat(chosen.status()).as("%s", chosen).isEqualTo(200);
            assertThat(chosen.json().get("isolation").get("setting").asString()).isEqualTo("MACHINE");
            // A version that widens nothing: the people who use it need not accept it.
            assertThat(chosen.json().get("version").asInt()).isEqualTo(version + 1);
            assertThat(chosen.json().get("wideningPending").asBoolean()).isFalse();
            JsonNode changed = auditEvents(bench.owner(), bench.company(), "agent.changed").getFirst();
            assertThat(changed.get("details").get("changed")).extracting(JsonNode::asString)
                .containsExactly("isolation");
            assertThat(changed.get("details").get("isolation").asString()).isEqualTo("MACHINE");
            assertThat(changed.get("details").get("previousIsolation").asString()).isEqualTo("AUTOMATIC");
            // The same level again changes nothing.
            assertThat(bench.member().patch(agentPath, Map.of("isolation", "MACHINE")).json().get("version")
                .asInt()).isEqualTo(version + 1);
        }

        // The last host of the level goes: its removal says so, the agent keeps its level, and no start is accepted.
        assertThat(bench.owner().delete(bench.path("/runner-hosts/" + hostId(bench, vm))).status()).isEqualTo(204);
        JsonNode removed = auditEvents(bench.owner(), bench.company(), "runner_host.removed").getFirst();
        assertThat(removed.get("details").get("isolationLevel").asString()).isEqualTo("MACHINE");
        assertThat(removed.get("details").get("lastOfLevel").asBoolean()).isTrue();
        JsonNode gone = bench.member().get(agentPath).json().get("isolation");
        assertThat(gone.get("setting").asString()).isEqualTo("MACHINE");
        assertThat(gone.get("chosenUnavailable").asBoolean()).isTrue();
        assertThat(gone.has("level")).isFalse();

        ApiResponse refused = bench.member().post(bench.path("/conversations"), Map.of("agentId",
                agentId.toString(), "message", "Hi"));
        assertThat(refused.status()).as("%s", refused).isEqualTo(409);
        assertThat(refused.problemCode()).isEqualTo("NO_HOST_FOR_CONVERSATION");
        assertThat(refused.json().get("details").get("reason").asString()).isEqualTo("NO_HOST_OF_LEVEL");
        JsonNode hosts = bench.member().get(agentPath + "/readiness").json().get("hosts");
        assertThat(hosts.get("runnable").asBoolean()).isFalse();
        assertThat(hosts.get("reason").asString()).contains("report machine");

        // Automatic again: the container host runs it, a weaker host being no reason to refuse.
        assertThat(bench.member().patch(agentPath, Map.of("isolation", "AUTOMATIC")).status()).isEqualTo(200);
        start(bench.member(), bench, agentId, "Hi");
    }

    @Test
    void aConversationSaysWhereItsTurnRanWhenItsSessionWasOnAnotherLevel() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Analyst", "Do it.", List.of(), "READ", List.of());
        try (FakeRunner container = companyHost(bench, 4)) {
            JsonNode started = start(bench.member(), bench, agentId, "First.");
            String id = started.get("id").asString();
            JsonNode assign = container.expect("run.assign");
            String first = assign.get("runId").asString();
            container.accepted(UUID.fromString(first), assign.get("epoch").asLong());
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));
            container.send(finished(first, assign.get("epoch").asLong(), "SUCCEEDED", "One."));
            awaitConversation(bench.member(), bench, id, now -> !"RUNNING".equals(now.path("turnStatus").asString()));

            String vm = unique("vm");
            try (FakeRunner machine = leveledHost(bench, vm, "machine", "none")) {
                // Automatic: the conversation stays on the weaker host of its session, and says so once.
                for (int turn = 2; turn <= 3; turn++) {
                    bench.member().post(bench.path("/conversations/" + id + "/messages"), Map.of("body", "Next."));
                    JsonNode stays = container.expect("run.assign");
                    String runId = stays.get("runId").asString();
                    container.accepted(UUID.fromString(runId), stays.get("epoch").asLong());
                    awaitConversation(bench.member(), bench, id,
                            now -> "RUNNING".equals(now.path("turnStatus").asString()));
                    container.send(finished(runId, stays.get("epoch").asLong(), "SUCCEEDED", "Again."));
                    awaitConversation(bench.member(), bench, id,
                            now -> !"RUNNING".equals(now.path("turnStatus").asString()));
                }
                List<String> notes = systemNotes(conversation(bench.member(), bench, id));
                assertThat(notes).singleElement().satisfies(note -> assertThat(note).startsWith("This turn ran on ")
                    .contains("which reports container and holds this conversation's session"));

                // Chosen machine: the next turn starts a fresh session there, and says so.
                assertThat(bench.member().patch(bench.path("/agents/" + agentId), Map.of("isolation", "MACHINE"))
                    .status()).isEqualTo(200);
                bench.member().post(bench.path("/conversations/" + id + "/messages"), Map.of("body", "Last."));
                JsonNode moved = machine.expect("run.assign");
                machine.accepted(UUID.fromString(moved.get("runId").asString()), moved.get("epoch").asLong());
                JsonNode after = awaitConversation(bench.member(), bench, id,
                        now -> systemNotes(now).size() == 2);
                assertThat(systemNotes(after).getLast()).isEqualTo("This turn started a new session of the runtime on "
                        + vm + " (machine): the agent is set to machine, and the session was on "
                        + lastHostOf(after, 1) + " (container). The conversation and its workspace are kept.");
                machine.assertFramesValid();
            }
            container.assertFramesValid();
        }
    }

    /** The host name of the conversation's turn {@code fromEnd} turns before its latest. */
    private static String lastHostOf(JsonNode conversation, int fromEnd) {
        JsonNode turns = conversation.get("turns");
        return turns.get(turns.size() - 1 - fromEnd).get("runnerHostName").asString();
    }
}
