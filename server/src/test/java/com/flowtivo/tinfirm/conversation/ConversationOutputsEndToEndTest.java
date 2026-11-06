package com.flowtivo.tinfirm.conversation;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.GitHubStub;
import com.flowtivo.tinfirm.support.RunnerFileRequests;
import com.flowtivo.tinfirm.support.TestSettings;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Part 3 end to end through real conversations (the package 3 contract, §3.13, §5.4, §5.5, §5.7, §5.9): a conversation
 * started with another conversation's file gets it in {@code run.assign.attachments} and the prompt's {@code # Files};
 * its runner host downloads it, saves a document with {@code save_artifact} and leaves one in {@code /output}, which
 * become outputs when the turn ends, in the conversation's detail and the next turn's {@code # Outputs}; and a turn that
 * pushed to a GitHub repository gets its pull request, opened with the person's own account.
 */
class ConversationOutputsEndToEndTest extends ConversationTestBench {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final byte[] BRIEF = "# Brief\n\nThe Q3 numbers.\n".getBytes(StandardCharsets.UTF_8);

    private static final byte[] REPORT = "# Q3\n\nAll good.\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void aTurnGetsTheFilesAndItsDocumentsBecomeOutputsOfTheConversation() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Writer", "You write reports.", List.of(), "WRITE", List.of());
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.id(), unique("host"), "COMPANY");
        try (FakeRunner runner = connect(host)) {
            // A first conversation, with the brief its person added.
            JsonNode first = start(bench.member(), bench, agentId, "Draft the brief");
            JsonNode firstAssign = runner.expect("run.assign");
            runner.accepted(UUID.fromString(firstAssign.get("runId").asString()), firstAssign.get("epoch").asLong());
            runner.send(finished(firstAssign.get("runId").asString(), firstAssign.get("epoch").asLong(), "SUCCEEDED",
                    "Drafted."));
            awaitConversation(bench.member(), bench, first.get("id").asString(),
                    now -> "SUCCEEDED".equals(now.path("turnStatus").asString()));
            ApiResponse brief = bench.member().upload(bench.path("/conversations/" + first.get("id").asString()
                    + "/attachments"), new ApiClient.Multipart("brief.md", "text/markdown", BRIEF));
            assertThat(brief.status()).as("%s", brief).isEqualTo(201);
            UUID briefId = UUID.fromString(brief.json().get("id").asString());

            // The second starts with a copy of it; a file of none of the person's conversations is refused.
            ApiResponse unknown = bench.member().post(bench.path("/conversations"), Map.of("agentId",
                    agentId.toString(), "message", "Write the report", "attachmentIds",
                    List.of(UUID.randomUUID().toString())));
            assertThat(unknown.status()).isEqualTo(400);
            ApiResponse started = bench.member().post(bench.path("/conversations"), Map.of("agentId",
                    agentId.toString(), "message", "Write the report", "attachmentIds", List.of(briefId.toString())));
            assertThat(started.status()).as("%s", started).isEqualTo(201);
            String id = started.json().get("id").asString();
            JsonNode copy = started.json().get("attachments").get(0);
            assertThat(started.json().get("attachments")).hasSize(1);
            assertThat(copy.get("fileName").asString()).isEqualTo("brief.md");
            assertThat(copy.get("id").asString()).isNotEqualTo(briefId.toString());
            assertThat(copy.get("uploadedByUserId").asString())
                .isEqualTo(bench.company().userId("MEMBER").toString());
            UUID copyId = UUID.fromString(copy.get("id").asString());

            JsonNode assign = runner.expect("run.assign");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            assertThat(assign.get("attachments")).singleElement().satisfies(file -> {
                assertThat(file.get("id").asString()).isEqualTo(copyId.toString());
                assertThat(file.get("fileName").asString()).isEqualTo("brief.md");
                assertThat(file.get("sizeBytes").asLong()).isEqualTo(BRIEF.length);
                assertThat(file.get("sha256").asString()).isEqualTo(sha256(BRIEF));
            });
            assertThat(assign.get("prompt").asString()).contains("# Files", "`/attachments/brief.md`",
                    "Put documents you make into `/output`").doesNotContain("# Outputs");
            // The host downloads it before its sandbox starts, while the run awaits run.accepted.
            HttpResponse<byte[]> download = RunnerFileRequests.download(port, host.token(), runId, copyId, epoch);
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.body()).isEqualTo(BRIEF);

            runner.accepted(runId, epoch);
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));
            JsonNode saved = JSON.readTree(RunnerFileRequests.upload(port, host.token(), runId, epoch,
                    "&artifact=true&source=save_artifact", "notes.md", "text/markdown", REPORT).body());
            assertThat(saved.get("status").asString()).isEqualTo("STORED");
            JsonNode left = JSON.readTree(RunnerFileRequests.upload(port, host.token(), runId, epoch,
                    "&artifact=true&source=output", "reports/q3.md", "text/markdown", REPORT).body());
            assertThat(left.get("status").asString()).isEqualTo("STORED");
            String q3 = left.get("attachmentId").asString();

            Map<String, Object> finished = new LinkedHashMap<>(finished(runId.toString(), epoch, "SUCCEEDED",
                    "Wrote the report."));
            finished.put("artifacts", List.of(Map.of("name", "reports/q3.md", "attachmentId", q3, "sizeBytes",
                    REPORT.length)));
            runner.send(finished);
            JsonNode after = awaitConversation(bench.member(), bench, id,
                    now -> "SUCCEEDED".equals(now.path("turnStatus").asString()) && now.get("outputs").size() == 2);
            assertThat(after.get("outputs")).extracting(output -> output.get("kind").asString() + ":"
                    + output.get("title").asString()).containsExactly("DOCUMENT:notes.md", "DOCUMENT:reports/q3.md");
            assertThat(after.get("outputs").get(1).get("attachmentId").asString()).isEqualTo(q3);
            assertThat(after.get("outputs")).allSatisfy(output -> assertThat(output.get("runId").asString())
                .isEqualTo(runId.toString()));
            assertThat(after.get("attachments")).extracting(file -> file.get("fileName").asString())
                .containsExactly("brief.md", "notes.md", "reports/q3.md");
            assertThat(after.get("attachments").get(2).get("outputId").asString())
                .isEqualTo(after.get("outputs").get(1).get("id").asString());
            // OWNER sees them too; ADMIN does not see the conversation.
            assertThat(conversation(bench.owner(), bench, id).get("outputs")).hasSize(2);
            assertThat(bench.admin().get(bench.path("/conversations/" + id + "/attachments")).status())
                .isEqualTo(404);

            // The next turn mounts them all and names what the conversation produced.
            bench.member().post(bench.path("/conversations/" + id + "/messages"), Map.of("body", "Summarize it."));
            JsonNode second = runner.expect("run.assign");
            assertThat(second.get("attachments")).extracting(file -> file.get("fileName").asString())
                .containsExactly("brief.md", "notes.md", "reports_q3.md");
            assertThat(second.get("prompt").asString()).contains("# Outputs\nWhat this conversation produced so far:\n"
                    + "- Document: notes.md (`/attachments/notes.md`)\n"
                    + "- Document: reports/q3.md (`/attachments/reports_q3.md`)");
            runner.assertFramesValid();
        }
        assertThat(auditEvents(bench.owner(), bench.company(), "output.created")).hasSize(2);
    }

    @Test
    void aTurnThatPushedToAGitHubRepositoryGetsItsPullRequestWithThePersonsAccount() {
        Bench bench = bench(true);
        GitHubStub.Issues github = GitHubStub.newIssues();
        UUID system = createSystem(bench.owner(), bench.company(), Map.of("name", unique("GitHub"), "kind", "GITHUB",
                "baseUrl", github.baseUrl()));
        ApiResponse repository = bench.owner().post(bench.path("/systems/" + system + "/repositories"),
                Map.of("url", "https://localhost/acme/shop", "defaultBranch", "main"));
        assertThat(repository.status()).as("%s", repository).isEqualTo(201);
        String repositoryId = repository.json().get("id").asString();
        github.user("test-github-token-of-the-member", "the-member");
        connectOwn(bench.member(), bench, system, "test-github-token-of-the-member");
        ApiResponse agent = bench.member().post(bench.path("/agents"), Map.of("name", unique("Builder"), "runtimes",
                List.of("CLAUDE_CODE"), "systems", List.of(Map.of("systemId", system.toString(), "tools",
                        List.of(Map.of("name", "open_change_request", "mode", "ALLOW")))), "repositories",
                List.of(Map.of("repositoryId", repositoryId, "access", "WRITE"))));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        TestSettings.nameModel(bench.id(), UUID.fromString(agent.json().get("id").asString()), bench.memberPrincipal());
        String pulls = github.path() + "/repos/acme/shop/pulls";
        GitHubStub.server().stubFor(post(urlEqualTo(pulls)).willReturn(GitHubStub.json(201, Map.of("number", 12,
                "html_url", "https://github.com/acme/shop/pull/12", "state", "open", "draft", false))));

        try (FakeRunner runner = companyHost(bench, 2)) {
            JsonNode started = start(bench.member(), bench, UUID.fromString(agent.json().get("id").asString()),
                    "Fix the totals");
            String id = started.get("id").asString();
            String key = started.get("key").asString();
            JsonNode assign = runner.expect("run.assign");
            assertThat(assign.get("workspaces").get(0).get("git").get("token").asString())
                .isEqualTo("test-github-token-of-the-member");
            String runId = assign.get("runId").asString();
            long epoch = assign.get("epoch").asLong();
            runner.accepted(UUID.fromString(runId), epoch);
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));
            Map<String, Object> finished = new LinkedHashMap<>(finished(runId, epoch, "SUCCEEDED", "Fixed the totals."));
            finished.put("git", List.of(Map.of("componentId", repositoryId, "branch", "agent/" + key, "pushed", true,
                    "headSha", "abc123", "commits", 2)));
            runner.send(finished);

            JsonNode after = awaitConversation(bench.member(), bench, id, now -> now.get("outputs").size() == 1);
            assertThat(after.get("outputs")).singleElement().satisfies(output -> {
                assertThat(output.get("kind").asString()).isEqualTo("CHANGE_REQUEST");
                assertThat(output.get("url").asString()).isEqualTo("https://github.com/acme/shop/pull/12");
                assertThat(output.get("externalNumber").asString()).isEqualTo("12");
                assertThat(output.get("changeRequestState").asString()).isEqualTo("OPEN");
                assertThat(output.get("repositoryId").asString()).isEqualTo(repositoryId);
                assertThat(output.get("runId").asString()).isEqualTo(runId);
            });
            GitHubStub.server().verify(1, postRequestedFor(urlEqualTo(pulls))
                .withHeader("Authorization", equalTo("Bearer test-github-token-of-the-member")));
            JsonNode request = JSON.readTree(GitHubStub.server().findAll(postRequestedFor(urlEqualTo(pulls)))
                .getFirst()
                .getBodyAsString());
            assertThat(request.get("head").asString()).isEqualTo("agent/" + key);
            assertThat(request.get("base").asString()).isEqualTo("main");
            assertThat(request.get("title").asString()).isEqualTo("Fix the totals");
            assertThat(request.get("body").asString()).startsWith("Fixed the totals.\n\nhttp")
                .contains("/conversations/" + id + "\n\n").endsWith(" via Tinfirm agent "
                        + agent.json().get("name").asString() + ".");
            await().atMost(WAIT).untilAsserted(() -> assertThat(auditEvents(bench.owner(), bench.company(),
                    "secret.accessed")).anySatisfy(event -> assertThat(event.get("details").get("purpose").asString())
                        .isEqualTo("change-request:" + runId)));
            runner.assertFramesValid();
        }
    }

    private FakeRunner connect(RunnerHostRef host) {
        FakeRunner runner = FakeRunner.connect(port, host.token());
        runner.helloV2AndWelcome(List.of(), List.of(), RUNNER_VERSION, 4);
        await().atMost(WAIT).until(() -> connections.isConfigured(host.id()));
        return runner;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
