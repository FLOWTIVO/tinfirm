package com.flowtivo.tinfirm.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;

/**
 * Skills in runs end to end, through the API and fake runner hosts (ADR-0064; the skills contract, §7 and §8): a turn
 * of an agent with skills is assigned only to a host that lists {@code run.skills}, with each skill at the version the
 * agent pins and less those the person switched off; the host downloads exactly those versions for its run and epoch;
 * on hosts without the feature the run waits with {@code NO_HOST_FOR_SKILLS}.
 */
class ConversationSkillsIntegrationTest extends ConversationTestBench {

    @Test
    void aTurnGetsTheAgentsSkillsAtTheirPinnedVersionsAndItsHostDownloadsThem() throws Exception {
        Bench bench = bench(true);
        String writeStoryName = "write-story-" + UUID.randomUUID().toString().substring(0, 8);
        String writeStory = skill(bench, writeStoryName, "Writes stories");
        String reviewStory = skill(bench, "review-story-" + UUID.randomUUID().toString().substring(0, 8),
                "Reviews stories");
        UUID agentId = agent(bench.member(), bench, "Analyst", "You write stories.", List.of(), "WRITE", List.of());
        String agentPath = bench.path("/agents/" + agentId);
        // A new version after the pin: the turn gets the pinned one.
        assertThat(bench.member().put(agentPath + "/skills", Map.of("skills", List.of(Map.of("skillId", writeStory),
                Map.of("skillId", reviewStory)))).status()).isEqualTo(200);
        assertThat(bench.member().upload(bench.path("/skills/" + writeStory + "/versions"), skillMd(writeStoryName,
                "Writes stories, version two")).status()).isEqualTo(201);
        // The person switches one of the two off.
        assertThat(bench.member().put(agentPath + "/settings/me", Map.of("skillsOff", List.of(reviewStory),
                "model", "claude-sonnet-4-5")).status()).isEqualTo(200);
        JsonNode pinned = bench.member().get(bench.path("/skills/" + writeStory)).json().get("versions").get(1);
        assertThat(pinned.get("version").asInt()).isEqualTo(1);

        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.id(), unique("host"), "COMPANY");
        try (FakeRunner runner = skillsHost(host)) {
            start(bench.member(), bench, agentId, "Write the checkout story.");
            JsonNode assign = runner.expect("run.assign");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            JsonNode skills = assign.get("skills");
            assertThat(skills).hasSize(1);
            JsonNode skill = skills.get(0);
            assertThat(skill.get("skillId").asString()).isEqualTo(writeStory);
            assertThat(skill.get("name").asString()).isEqualTo(writeStoryName);
            assertThat(skill.get("version").asInt()).isEqualTo(1);
            assertThat(skill.get("sha256").asString()).isEqualTo(pinned.get("sha256").asString());
            assertThat(skill.get("sizeBytes").asLong()).isEqualTo(pinned.get("sizeBytes").asLong());
            // No prompt section: the runtime lists the skills itself.
            assertThat(assign.get("prompt").asString()).doesNotContain(writeStoryName);
            runner.assertFramesValid();

            // The host downloads the version its run was assigned, for this epoch only.
            String download = "/api/runner/v1/runs/" + runId + "/skills/" + writeStory + "/versions/1?epoch=" + epoch;
            HttpResponse<byte[]> zip = runnerGet(host.token(), download);
            assertThat(zip.statusCode()).isEqualTo(200);
            assertThat(sha256(zip.body())).isEqualTo(skill.get("sha256").asString());
            assertThat(runnerGet(host.token(), download.replace("/versions/1", "/versions/2")).statusCode())
                .isEqualTo(404);
            assertThat(runnerGet(host.token(), download.replace("epoch=" + epoch, "epoch=" + (epoch + 1)))
                .statusCode()).isEqualTo(404);
            assertThat(runnerGet(host.token(), download.replace(writeStory, reviewStory)).statusCode())
                .isEqualTo(404);
            RunnerHostRef other = registerRunnerHost(bench.owner(), bench.id(), unique("other"), "COMPANY");
            assertThat(runnerGet(other.token(), download).statusCode()).isEqualTo(404);
            assertThat(runnerGet("not-a-token", download).statusCode()).isEqualTo(401);
            try (TenantSql sql = TenantSql.in(bench.id())) {
                assertThat(sql.count("SELECT count(*) FROM run_skill WHERE run_id = ?", runId)).isEqualTo(1);
            }
        }
    }

    @Test
    void aTurnWithSkillsWaitsWhileNoConnectedHostDeliversThem() {
        Bench bench = bench(true);
        String writeStory = skill(bench, "write-story-" + UUID.randomUUID().toString().substring(0, 8),
                "Writes stories");
        UUID agentId = agent(bench.member(), bench, "Analyst", "You write stories.", List.of(), "WRITE", List.of());
        assertThat(bench.member().put(bench.path("/agents/" + agentId + "/skills"), Map.of("skills",
                List.of(Map.of("skillId", writeStory)))).status()).isEqualTo(200);
        try (FakeRunner runner = companyHost(bench, 4)) {
            JsonNode conversation = start(bench.member(), bench, agentId, "Write the checkout story.");
            String runId = lastTurn(conversation).get("id").asString();
            await().atMost(WAIT).untilAsserted(() -> assertThat(bench.member().get(bench.path("/runs/" + runId))
                .json().path("queueReason").path("code").asString()).isEqualTo("NO_HOST_FOR_SKILLS"));
            runner.expectNone("run.assign", Duration.ofSeconds(1));
            // The readiness says so before the next conversation.
            JsonNode hosts = bench.member().get(bench.path("/agents/" + agentId + "/readiness")).json().get("hosts");
            assertThat(hosts.get("runnable").asBoolean()).isFalse();
            assertThat(hosts.get("reason").asString()).contains("skills");
        }
    }

    /** A company host whose {@code hello} lists {@code run.skills}. */
    private FakeRunner skillsHost(RunnerHostRef host) {
        FakeRunner runner = FakeRunner.connect(port, host.token());
        Map<String, Object> hello = FakeRunner.helloV2(List.of(), List.of(), GATEWAY_VERSION, 4);
        hello.put("features", List.of("run.skills"));
        runner.send(hello);
        runner.expect("welcome");
        await().atMost(WAIT).until(() -> connections.isConfigured(host.id()));
        return runner;
    }

    /** A skill of the member's, shared with nobody: its id. */
    private String skill(Bench bench, String name, String description) {
        ApiResponse created = bench.member().upload(bench.path("/skills"), skillMd(name, description));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        return created.json().get("id").asString();
    }

    private static ApiClient.Multipart skillMd(String name, String description) {
        return ApiClient.Multipart.fields(Map.of("skillMd", "---\nname: " + name + "\ndescription: " + description
                + "\n---\n# " + name + "\n"));
    }

    private HttpResponse<byte[]> runnerGet(String token, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
