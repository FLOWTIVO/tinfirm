package com.flowtivo.tinfirm.runner.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.runner.EffortLevel;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.support.ProtocolSchema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * CONVENTIONS §14.10 and docs/archive/v1/CONVENTIONS.md §15.1: the hand-written protocol types and the sample messages are kept honest against the
 * schemas: those under {@code src/test/resources/protocol/} against {@code protocol/runner-v1.schema.json}, the runner
 * host's v2 samples under {@code runner/crates/tinfirm-protocol/tests/samples/v2/} against
 * {@code protocol/runner-v2.schema.json}.
 */
class RunnerProtocolSchemaTest {

    private static final Path SAMPLES = Path.of("src/test/resources/protocol");

    /** The runner host's samples of protocol v2: the frames it sends and expects. */
    private static final Path V2_SAMPLES = Path.of("../runner/crates/tinfirm-protocol/tests/samples/v2");

    private static final Set<String> SERVER_TYPES = Set.of("welcome", "run.assign", "run.cancel",
            "run.repository_request.ack", "run.task_create.ack", "run.ask.ack", "run.request.ack", "run.steer",
            "host.config", "psl.update");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final ValidatorFactory VALIDATION = Validation.buildDefaultValidatorFactory();

    private final RunnerMessageCodec codec = new RunnerMessageCodec(VALIDATION.getValidator());

    @AfterAll
    static void closeValidation() {
        VALIDATION.close();
    }

    @Test
    void everySampleMatchesTheSchema() throws IOException {
        List<Path> samples = samples();
        assertThat(samples).hasSizeGreaterThanOrEqualTo(20);
        for (Path sample : samples) {
            assertThat(ProtocolSchema.violations(Files.readString(sample))).as(sample.toString()).isEmpty();
        }
    }

    @Test
    void theServerDecodesEverySampleARunnerHostSends() throws IOException {
        for (Path sample : samples()) {
            String text = Files.readString(sample);
            String type = JSON.readTree(text).get("type").asString();
            if (SERVER_TYPES.contains(type)) {
                assertThatThrownBy(() -> codec.decode(text)).as(sample.toString())
                    .isInstanceOf(RunnerMessageCodec.BadFrame.class);
                continue;
            }
            RunnerMessages.Inbound message = codec.decode(text);
            assertThat(message).as(sample.toString()).isNotNull();
        }
        RunnerMessages.Hello hello = (RunnerMessages.Hello) codec.decode(Files.readString(SAMPLES.resolve("hello.json")));
        assertThat(hello.credentials()).extracting(RunnerMessages.HelloCredential::label)
            .containsExactly("claude-max", "codex-plus");
        assertThat(hello.activeRuns()).singleElement().extracting(RunnerMessages.ActiveRun::lastSeq).isEqualTo(41L);
        RunnerMessages.RunFinished finished = (RunnerMessages.RunFinished) codec
            .decode(Files.readString(SAMPLES.resolve("run.finished.json")));
        assertThat(finished.git()).extracting(RunnerMessages.GitResult::pushed).containsExactly(true, false);
        RunnerMessages.RunRepositoryRequest request = (RunnerMessages.RunRepositoryRequest) codec
            .decode(Files.readString(SAMPLES.resolve("run.repository_request.json")));
        assertThat(request.name()).isEqualTo("docs");
        assertThat(request.componentId()).isNotNull();
        RunnerMessages.RunTaskCreate create = (RunnerMessages.RunTaskCreate) codec
            .decode(Files.readString(SAMPLES.resolve("run.task_create.json")));
        assertThat(create.title()).isEqualTo("Retry webhook deliveries with backoff");
        assertThat(create.repositories()).containsExactly("server");
        RunnerMessages.RunTaskCreate minimal = (RunnerMessages.RunTaskCreate) codec
            .decode(Files.readString(SAMPLES.resolve("run.task_create.minimal.json")));
        assertThat(minimal.description()).isNull();
        assertThat(minimal.repositories()).isNull();
        RunnerMessages.RunAsk ask = (RunnerMessages.RunAsk) codec
            .decode(Files.readString(SAMPLES.resolve("run.ask.json")));
        assertThat(ask.question()).startsWith("Should a delivery");
        assertThat(ask.options()).containsExactly("Only timeouts and 5xx", "Every failure");
        RunnerMessages.RunAsk free = (RunnerMessages.RunAsk) codec
            .decode(Files.readString(SAMPLES.resolve("run.ask.free-answer.json")));
        assertThat(free.options()).isNull();
        RunnerMessages.RunFinished blocked = (RunnerMessages.RunFinished) codec
            .decode(Files.readString(SAMPLES.resolve("run.finished.blocked.json")));
        RunnerMessages.RunResult result = blocked.result();
        assertThat(result).isNotNull();
        assertThat(result.outcome()).isEqualTo(RunnerMessages.ResultOutcome.BLOCKED);
        assertThat(result.nextSteps()).hasSize(2);
        assertThat(result.openQuestions()).containsExactly("Which staging database should the migration test use?");
        assertThat(finished.result()).isNull();
        RunnerMessages.RunRejected rejected = (RunnerMessages.RunRejected) codec
            .decode(Files.readString(SAMPLES.resolve("run.rejected.task-run-active.json")));
        assertThat(rejected.code()).isEqualTo(RunnerMessages.TASK_RUN_ACTIVE);
        assertThat(((RunnerMessages.RunRejected) codec.decode(Files.readString(SAMPLES.resolve("run.rejected.json"))))
            .code()).isNull();
        RunnerMessages.HostConfigApplied applied = (RunnerMessages.HostConfigApplied) codec
            .decode(Files.readString(SAMPLES.resolve("host.config.applied.json")));
        assertThat(applied.version()).isEqualTo(3);
        assertThat(applied.draining()).isFalse();
        assertThat(applied.maxConcurrentRuns()).isEqualTo(2);
        assertThat(applied.credentials()).extracting(RunnerMessages.AppliedCredential::label)
            .containsExactly("claude-max", "codex-plus");
        RunnerMessages.HostConfigApplied draining = (RunnerMessages.HostConfigApplied) codec
            .decode(Files.readString(SAMPLES.resolve("host.config.applied.draining.json")));
        assertThat(draining.draining()).isTrue();
        assertThat(draining.maxConcurrentRuns()).isZero();
    }

    @Test
    void capacityFramesOutsideTheSchemaAreBadMessages() {
        // Ceilings are bounded to 16 ($defs/Capacity): an older host with more slots is refused.
        assertThat(code("""
                {"type":"hello","protocolVersion":1,"runnerVersion":"1","executors":["docker"],
                 "isolationTiers":["container"],"maxConcurrentRuns":17,"runtimes":[],"credentials":[]}"""))
            .isEqualTo(RunnerMessages.BAD_MESSAGE);
        assertThat(code("""
                {"type":"hello","protocolVersion":1,"runnerVersion":"1","executors":["docker"],
                 "isolationTiers":["container"],"maxConcurrentRuns":2,"runtimes":[],
                 "credentials":[{"label":"big","kind":"ANTHROPIC_API_KEY","runtimes":["CLAUDE_CODE"],"poolSize":17}]}"""))
            .isEqualTo(RunnerMessages.BAD_MESSAGE);
        // A draining host has 0 slots, any other at least 1.
        assertThat(code("""
                {"type":"host.config.applied","version":1,"draining":true,"maxConcurrentRuns":2,
                 "maxConcurrentRunsCeiling":2,"credentials":[]}""")).isEqualTo(RunnerMessages.BAD_MESSAGE);
        assertThat(code("""
                {"type":"host.config.applied","version":1,"draining":false,"maxConcurrentRuns":0,
                 "maxConcurrentRunsCeiling":2,"credentials":[]}""")).isEqualTo(RunnerMessages.BAD_MESSAGE);
        assertThat(code("""
                {"type":"host.config.applied","version":-1,"draining":false,"maxConcurrentRuns":1,
                 "maxConcurrentRunsCeiling":2,"credentials":[]}""")).isEqualTo(RunnerMessages.BAD_MESSAGE);
        assertThat(code("""
                {"type":"host.config.applied","version":1,"draining":false,"maxConcurrentRuns":1,
                 "maxConcurrentRunsCeiling":2}""")).isEqualTo(RunnerMessages.BAD_MESSAGE);
        // The server sends host.config; a host does not.
        assertThat(code("""
                {"type":"host.config","version":1,"credentials":[]}""")).isEqualTo(RunnerMessages.BAD_MESSAGE);
    }

    @Test
    void appliedValuesAboveTheirCeilingOrDuplicateLabelsAreProblems() {
        RunnerMessages.HostConfigApplied valid = new RunnerMessages.HostConfigApplied(2L, false, 2, 4,
                List.of(new RunnerMessages.AppliedCredential("claude-max", 1, 3),
                        new RunnerMessages.AppliedCredential("codex-plus", 1, 1)));
        assertThat(RunnerHostSessions.appliedProblem(valid)).isNull();
        assertThat(RunnerHostSessions.appliedProblem(new RunnerMessages.HostConfigApplied(2L, false, 5, 4,
                List.of()))).contains("maxConcurrentRunsCeiling");
        assertThat(RunnerHostSessions.appliedProblem(new RunnerMessages.HostConfigApplied(2L, false, 2, 4,
                List.of(new RunnerMessages.AppliedCredential("claude-max", 4, 3))))).contains("claude-max");
        assertThat(RunnerHostSessions.appliedProblem(new RunnerMessages.HostConfigApplied(2L, false, 2, 4,
                List.of(new RunnerMessages.AppliedCredential("claude-max", 1, 3),
                        new RunnerMessages.AppliedCredential("claude-max", 2, 3))))).contains("Duplicate");
        // A host's ready sandboxes: the count it aims at is at most its ceiling (ADR-0099).
        assertThat(RunnerHostSessions.appliedProblem(new RunnerMessages.HostConfigApplied(2L, false, 2, 4, List.of(),
                new RunnerMessages.AppliedPool(4, 4)))).isNull();
        assertThat(RunnerHostSessions.appliedProblem(new RunnerMessages.HostConfigApplied(2L, false, 2, 4, List.of(),
                new RunnerMessages.AppliedPool(5, 4)))).contains("maxReadyCeiling");
    }

    @Test
    void aRepositoryRequestWithoutNameOrReasonStillDecodesSoItCanBeAnswered() {
        String blank = """
                {"type":"run.repository_request","runId":"%s","epoch":1,"requestId":"%s","name":"  "}"""
            .formatted(UUID.randomUUID(), UUID.randomUUID());
        RunnerMessages.RunRepositoryRequest request = (RunnerMessages.RunRepositoryRequest) codec.decode(blank);
        assertThat(request.name()).isBlank();
        assertThat(request.reason()).isNull();
        // Without a requestId there is nothing to answer: a bad frame.
        assertThat(code("""
                {"type":"run.repository_request","runId":"%s","epoch":1,"name":"docs","reason":"r"}"""
            .formatted(UUID.randomUUID()))).isEqualTo(RunnerMessages.BAD_MESSAGE);
        assertThat(code("""
                {"type":"run.repository_request.ack","runId":"%s","requestId":"%s","status":"PENDING","message":"m"}"""
            .formatted(UUID.randomUUID(), UUID.randomUUID()))).isEqualTo(RunnerMessages.BAD_MESSAGE);
    }

    @Test
    void aTaskCreationWithoutATitleStillDecodesSoItCanBeAnswered() {
        String blank = """
                {"type":"run.task_create","runId":"%s","epoch":1,"requestId":"%s","title":"  ","repositories":[""]}"""
            .formatted(UUID.randomUUID(), UUID.randomUUID());
        RunnerMessages.RunTaskCreate create = (RunnerMessages.RunTaskCreate) codec.decode(blank);
        assertThat(create.title()).isBlank();
        assertThat(create.repositories()).containsExactly("");
        // Without a requestId there is nothing to answer: a bad frame.
        assertThat(code("""
                {"type":"run.task_create","runId":"%s","epoch":1,"title":"t"}""".formatted(UUID.randomUUID())))
            .isEqualTo(RunnerMessages.BAD_MESSAGE);
        assertThat(code("""
                {"type":"run.task_create.ack","runId":"%s","requestId":"%s","status":"CREATED","message":"m"}"""
            .formatted(UUID.randomUUID(), UUID.randomUUID()))).isEqualTo(RunnerMessages.BAD_MESSAGE);
    }

    @Test
    void anAskWithoutAQuestionStillDecodesSoItCanBeAnswered() {
        String blank = """
                {"type":"run.ask","runId":"%s","epoch":1,"requestId":"%s","question":"  ","options":[""]}"""
            .formatted(UUID.randomUUID(), UUID.randomUUID());
        RunnerMessages.RunAsk ask = (RunnerMessages.RunAsk) codec.decode(blank);
        assertThat(ask.question()).isBlank();
        assertThat(ask.options()).containsExactly("");
        // Without a requestId there is nothing to answer: a bad frame.
        assertThat(code("""
                {"type":"run.ask","runId":"%s","epoch":1,"question":"q"}""".formatted(UUID.randomUUID())))
            .isEqualTo(RunnerMessages.BAD_MESSAGE);
        assertThat(code("""
                {"type":"run.ask.ack","runId":"%s","requestId":"%s","status":"PENDING","message":"m"}"""
            .formatted(UUID.randomUUID(), UUID.randomUUID()))).isEqualTo(RunnerMessages.BAD_MESSAGE);
    }

    @Test
    void aResultNeedsItsOutcomeAndSummary() {
        String base = """
                {"type":"run.finished","runId":"%s","epoch":1,"outcome":"SUCCEEDED","lastSeq":0,"result":%s}""";
        assertThat(code(base.formatted(UUID.randomUUID(), "{\"summary\":\"s\"}")))
            .isEqualTo(RunnerMessages.BAD_MESSAGE);
        assertThat(code(base.formatted(UUID.randomUUID(), "{\"outcome\":\"BLOCKED\"}")))
            .isEqualTo(RunnerMessages.BAD_MESSAGE);
        assertThat(code(base.formatted(UUID.randomUUID(), "{\"outcome\":\"DONE\",\"summary\":\"s\"}")))
            .isEqualTo(RunnerMessages.BAD_MESSAGE);
        RunnerMessages.RunFinished bare = (RunnerMessages.RunFinished) codec
            .decode(base.formatted(UUID.randomUUID(), "{\"outcome\":\"COMPLETED\",\"summary\":\"s\"}"));
        assertThat(bare.result()).isNotNull();
        assertThat(bare.result().nextSteps()).isNull();
        RunnerMessages.RunFinished none = (RunnerMessages.RunFinished) codec
            .decode(base.formatted(UUID.randomUUID(), "null"));
        assertThat(none.result()).isNull();
    }

    @Test
    void everyFrameTheServerEncodesMatchesTheSchema() {
        UUID runId = UUID.randomUUID();
        RunnerMessages.AssignedAgent agent = new RunnerMessages.AssignedAgent(UUID.randomUUID(), "Backend engineer",
                RuntimeKind.CLAUDE_CODE, null);
        RunnerMessages.AssignedTask task = new RunnerMessages.AssignedTask(UUID.randomUUID(), "MYCO-1", "Title");
        RunnerMessages.Limits limits = new RunnerMessages.Limits(3600, 200);
        RunnerMessages.Workspace server = new RunnerMessages.Workspace(UUID.randomUUID(), "server", "server",
                "https://github.com/acme/w.git", "main", "agent/MYCO-1",
                new RunnerMessages.GitCredentials("x-access-token", "not-a-secret"), "Tinfirm agent Backend engineer",
                "agent+x@tinfirm.invalid", false);
        RunnerMessages.Workspace web = new RunnerMessages.Workspace(UUID.randomUUID(), "web-app", "Web app",
                "https://example.org/r.git", "main", "agent/MYCO-1", null, "Tinfirm agent Backend engineer",
                "agent+x@tinfirm.invalid", true);
        RunnerMessages.RequestableRepository docs = new RunnerMessages.RequestableRepository(UUID.randomUUID(), "docs",
                "https://github.com/acme/docs.git", true);
        List<RunnerMessages.Outbound> frames = List.of(
                new RunnerMessages.Welcome(1, UUID.randomUUID(), UUID.randomUUID(), null, 10, 30),
                new RunnerMessages.Welcome(1, UUID.randomUUID(), UUID.randomUUID(), "0.1.0", 10, 30),
                new RunnerMessages.RunAssign(runId, 1, "claude-max", null, agent, task, "prompt", null, List.of(),
                        List.of(), limits, null, null),
                new RunnerMessages.RunAssign(runId, 2, "claude-max", null, agent, task, "prompt", "session",
                        List.of(server, web), List.of(docs), limits, "resume",
                        List.of(new RunnerMessages.AssignedAttachment(UUID.randomUUID(), "spec v2.pdf", 1234,
                                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"))),
                new RunnerMessages.RunCancel(runId, 4),
                RunnerMessages.RunRepositoryRequestAck.pending(runId, UUID.randomUUID(), UUID.randomUUID(),
                        "Requested the repository `docs`."),
                RunnerMessages.RunRepositoryRequestAck.rejected(runId, UUID.randomUUID(), "NO_ACCESS",
                        "You have no access to the repository `docs`."),
                RunnerMessages.RunTaskCreateAck.created(runId, UUID.randomUUID(), UUID.randomUUID(), "MYCO-2",
                        "Created the task MYCO-2."),
                RunnerMessages.RunTaskCreateAck.rejected(runId, UUID.randomUUID(), "TOO_MANY_TASKS",
                        "This run has already created 10 tasks."),
                // The restart's server answered every run.task_create so (archived v2 CONVENTIONS §14.8).
                RunnerMessages.RunTaskCreateAck.rejected(runId, UUID.randomUUID(), "TASK_CREATE_UNAVAILABLE",
                        "Tinfirm no longer has tasks; put the work into your summary's next steps."),
                RunnerMessages.RunAskAck.pending(runId, UUID.randomUUID(), UUID.randomUUID(),
                        "Your question is on the work item."),
                RunnerMessages.RunAskAck.rejected(runId, UUID.randomUUID(), "TOO_MANY_ASKS",
                        "This run has already asked 10 questions."),
                RunnerMessages.ProtocolError.of(RunnerMessages.STALE_EPOCH, "stale", runId),
                RunnerMessages.ProtocolError.of(RunnerMessages.BAD_MESSAGE, "bad"),
                new RunnerMessages.HostConfig(0, null, List.of()),
                new RunnerMessages.HostConfig(7, 3, List.of(new RunnerMessages.HostConfigCredential("claude-max", 2),
                        new RunnerMessages.HostConfigCredential("anthropic-key", 16))));
        for (RunnerMessages.Outbound frame : frames) {
            String json = codec.encode(frame);
            assertThat(ProtocolSchema.violations(json)).as(json).isEmpty();
        }
        // A run of a company model credential (ADR-0027): modelAccess, and no credentialLabel.
        RunnerMessages.RunAssign modelRun = new RunnerMessages.RunAssign(runId, 3, null,
                new RunnerMessages.ModelAccess(RunnerMessages.ModelAccessFormat.OPENAI,
                        "https://litellm.example.com/v1", "sk-not-a-real-key"),
                new RunnerMessages.AssignedAgent(UUID.randomUUID(), "Codex", RuntimeKind.CODEX, null), task,
                "prompt", null, List.of(server), List.of(), limits, null, null);
        JsonNode modelAssign = JSON.readTree(codec.encode(modelRun));
        assertThat(ProtocolSchema.violations(modelAssign.toString())).isEmpty();
        assertThat(modelAssign.has("credentialLabel")).isFalse();
        assertThat(modelAssign.get("modelAccess").get("format").asString()).isEqualTo("openai");
        assertThat(modelAssign.get("modelAccess").get("baseUrl").asString())
            .isEqualTo("https://litellm.example.com/v1");
        assertThat(modelAssign.get("modelAccess").get("apiKey").asString()).isEqualTo("sk-not-a-real-key");
        // A provider's key (ADR-0042): Bedrock with its region and access key ID, no base URL; a pi agent with its model.
        RunnerMessages.RunAssign bedrockRun = new RunnerMessages.RunAssign(runId, 3, null,
                new RunnerMessages.ModelAccess(RunnerMessages.ModelAccessFormat.BEDROCK, null, "eu-central-1",
                        "AKIAIOSFODNN7EXAMPLE", "not-a-real-secret-access-key"),
                new RunnerMessages.AssignedAgent(UUID.randomUUID(), "Analyst", RuntimeKind.PI,
                        "us.anthropic.claude-sonnet-4-5-v1:0"), task, "prompt", null, List.of(server), List.of(),
                limits, null, null);
        JsonNode bedrockAssign = JSON.readTree(codec.encode(bedrockRun));
        assertThat(ProtocolSchema.violations(bedrockAssign.toString())).isEmpty();
        assertThat(bedrockAssign.get("modelAccess").get("format").asString()).isEqualTo("bedrock");
        assertThat(bedrockAssign.get("modelAccess").has("baseUrl")).isFalse();
        assertThat(bedrockAssign.get("modelAccess").get("region").asString()).isEqualTo("eu-central-1");
        assertThat(bedrockAssign.get("agent").get("runtime").asString()).isEqualTo("PI");
        assertThat(bedrockRun.toString()).doesNotContain("not-a-real-secret-access-key");
        // workspaces and requestableRepositories are required: present even when empty.
        JsonNode withoutRepositories = JSON.readTree(codec.encode(frames.get(2)));
        assertThat(withoutRepositories.get("workspaces").isArray()).isTrue();
        assertThat(withoutRepositories.get("workspaces")).isEmpty();
        assertThat(withoutRepositories.get("requestableRepositories")).isEmpty();
        // git is nullable: present even when null.
        JsonNode assign = JSON.readTree(codec.encode(frames.get(3)));
        assertThat(assign.get("workspaces").get(1).has("git")).isTrue();
        assertThat(assign.get("workspaces").get(1).get("git").isNull()).isTrue();
        // host.config: credentials are required, even when empty; no desired slots leaves the field out.
        JsonNode config = JSON.readTree(codec.encode(new RunnerMessages.HostConfig(0, null, List.of())));
        assertThat(config.get("type").asString()).isEqualTo("host.config");
        assertThat(config.get("version").asLong()).isZero();
        assertThat(config.has("desiredMaxConcurrentRuns")).isFalse();
        assertThat(config.get("credentials")).isEmpty();
    }

    @Test
    void aRunAssignCarriesExactlyOneOfACredentialLabelAndModelAccess() throws IOException {
        JsonNode sample = JSON.readTree(Files.readString(SAMPLES.resolve("run.assign.model-access.json")));
        assertThat(ProtocolSchema.violations(sample.toString())).isEmpty();
        ObjectNode both = (ObjectNode) sample.deepCopy();
        both.put("credentialLabel", "claude-max");
        assertThat(ProtocolSchema.violations(both.toString())).isNotEmpty();
        ObjectNode neither = (ObjectNode) sample.deepCopy();
        neither.remove("modelAccess");
        assertThat(ProtocolSchema.violations(neither.toString())).isNotEmpty();
        ObjectNode plainHttp = (ObjectNode) sample.deepCopy();
        ((ObjectNode) plainHttp.get("modelAccess")).put("baseUrl", "http://litellm");
        assertThat(ProtocolSchema.violations(plainHttp.toString())).isNotEmpty();

        RunnerMessages.AssignedAgent agent = new RunnerMessages.AssignedAgent(UUID.randomUUID(), "A",
                RuntimeKind.CLAUDE_CODE, null);
        RunnerMessages.AssignedTask task = new RunnerMessages.AssignedTask(UUID.randomUUID(), "K-1", "T");
        RunnerMessages.ModelAccess access = new RunnerMessages.ModelAccess(RunnerMessages.ModelAccessFormat.ANTHROPIC,
                "https://litellm.example.com", "sk-not-a-real-key");
        RunnerMessages.Limits limits = new RunnerMessages.Limits(3600, 200);
        assertThatThrownBy(() -> new RunnerMessages.RunAssign(UUID.randomUUID(), 1, "claude-max", access, agent,
                task, "p", null, List.of(), List.of(), limits, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunnerMessages.RunAssign(UUID.randomUUID(), 1, null, null, agent, task, "p",
                null, List.of(), List.of(), limits, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theGitTokenAndTheModelKeyNeverAppearInToString() {
        RunnerMessages.Workspace workspace = new RunnerMessages.Workspace(UUID.randomUUID(), "w", "w",
                "https://github.com/acme/w.git", "main", "agent/MYCO-1",
                new RunnerMessages.GitCredentials("x-access-token", "super-secret-token"), "a", "b", false);
        RunnerMessages.RunAssign assign = new RunnerMessages.RunAssign(UUID.randomUUID(), 1, "claude-max", null,
                new RunnerMessages.AssignedAgent(UUID.randomUUID(), "A", RuntimeKind.CLAUDE_CODE, null),
                new RunnerMessages.AssignedTask(UUID.randomUUID(), "K-1", "T"), "prompt", null, List.of(workspace),
                List.of(), new RunnerMessages.Limits(3600, 200), null, null);
        assertThat(assign.toString()).doesNotContain("super-secret-token");
        assertThat(workspace.toString()).doesNotContain("super-secret-token");
        RunnerMessages.RunAssign modelRun = new RunnerMessages.RunAssign(UUID.randomUUID(), 1, null,
                new RunnerMessages.ModelAccess(RunnerMessages.ModelAccessFormat.ANTHROPIC,
                        "https://litellm.example.com", "sk-super-secret-key"),
                new RunnerMessages.AssignedAgent(UUID.randomUUID(), "A", RuntimeKind.CLAUDE_CODE, null),
                new RunnerMessages.AssignedTask(UUID.randomUUID(), "K-1", "T"), "prompt", null, List.of(workspace),
                List.of(), new RunnerMessages.Limits(3600, 200), null, null);
        assertThat(modelRun.toString()).doesNotContain("sk-super-secret-key").doesNotContain("super-secret-token")
            .contains("https://litellm.example.com");
    }

    @Test
    void badFramesAreRejectedWithACode() {
        assertThat(code("{not json")).isEqualTo(RunnerMessages.BAD_MESSAGE);
        assertThat(code("[]")).isEqualTo(RunnerMessages.BAD_MESSAGE);
        assertThat(code("{\"type\":\"run.teleport\"}")).isEqualTo(RunnerMessages.UNKNOWN_TYPE);
        assertThat(code("{\"type\":\"welcome\"}")).isEqualTo(RunnerMessages.BAD_MESSAGE);
        assertThat(code("{\"type\":\"run.accepted\",\"runId\":\"" + UUID.randomUUID() + "\"}"))
            .isEqualTo(RunnerMessages.BAD_MESSAGE);
        String event = """
                {"type":"run.event","runId":"%s","epoch":1,"seq":1,"eventType":"AGENT_MESSAGE",
                 "at":"2026-09-26T08:00:00Z","payload":{"txt":"typo"}}""".formatted(UUID.randomUUID());
        assertThat(code(event)).isEqualTo(RunnerMessages.BAD_MESSAGE);
        String badTime = """
                {"type":"run.event","runId":"%s","epoch":1,"seq":1,"eventType":"LOG",
                 "at":"yesterday","payload":{"level":"info","message":"m"}}""".formatted(UUID.randomUUID());
        assertThat(code(badTime)).isEqualTo(RunnerMessages.BAD_MESSAGE);
        String badLabel = """
                {"type":"hello","protocolVersion":1,"runnerVersion":"1","executors":["docker"],
                 "isolationTiers":["container"],"maxConcurrentRuns":1,"runtimes":[],
                 "credentials":[{"label":"Not Valid","kind":"CLAUDE_LOGIN","runtimes":["CLAUDE_CODE"],"poolSize":1}]}""";
        assertThat(code(badLabel)).isEqualTo(RunnerMessages.BAD_MESSAGE);
        // Unknown fields are ignored.
        String extra = """
                {"type":"run.accepted","runId":"%s","epoch":1,"future":"field"}""".formatted(UUID.randomUUID());
        assertThat(codec.decode(extra)).isInstanceOf(RunnerMessages.RunAccepted.class);
    }

    // ---- protocol v2 ------------------------------------------------------------------------------------------------

    @Test
    void everyV2SampleMatchesTheV2Schema() throws IOException {
        List<Path> samples = samples(V2_SAMPLES);
        assertThat(samples).hasSizeGreaterThanOrEqualTo(40);
        for (Path sample : samples) {
            assertThat(ProtocolSchema.violations(2, Files.readString(sample))).as(sample.toString()).isEmpty();
        }
    }

    @Test
    void theServerDecodesEveryV2SampleAV2HostSends() throws IOException {
        for (Path sample : samples(V2_SAMPLES)) {
            String text = Files.readString(sample);
            String type = JSON.readTree(text).get("type").asString();
            if (SERVER_TYPES.contains(type)) {
                assertThatThrownBy(() -> codec.decode(text, 2)).as(sample.toString())
                    .isInstanceOf(RunnerMessageCodec.BadFrame.class);
                continue;
            }
            assertThat(codec.decode(text, 2)).as(sample.toString()).isNotNull();
        }
        RunnerMessages.Hello hello = (RunnerMessages.Hello) codec
            .decode(Files.readString(V2_SAMPLES.resolve("hello.json")), RunnerMessageCodec.ANY_VERSION);
        assertThat(hello.protocolVersion()).isEqualTo(2);
        assertThat(hello.isolationTiers()).containsExactly("microvm");
        RunnerMessages.RunRequest tool = (RunnerMessages.RunRequest) codec
            .decode(Files.readString(V2_SAMPLES.resolve("run.request-tool.json")), 2);
        assertThat(tool.kind()).isEqualTo("TOOL");
        assertThat(tool.payload().get("tool").asString()).isEqualTo("jira_search");
        assertThat(tool.payload().get("arguments").get("limit").asInt()).isEqualTo(20);
        RunnerMessages.RunRequest access = (RunnerMessages.RunRequest) codec
            .decode(Files.readString(V2_SAMPLES.resolve("run.request-access-tool.json")), 2);
        assertThat(access.kind()).isEqualTo("ACCESS");
        assertThat(access.payload().get("tool").asString()).isEqualTo("GitHub");
        RunnerMessages.RunFinished finished = (RunnerMessages.RunFinished) codec
            .decode(Files.readString(V2_SAMPLES.resolve("run.finished.json")), 2);
        assertThat(finished.artifacts()).extracting(RunnerMessages.ArtifactResult::name)
            .containsExactly("reports/q3-profitability.md", "q3-profitability.xlsx");
        assertThat(finished.artifacts().getFirst().sizeBytes()).isEqualTo(18234L);
        assertThat(finished.resumed()).isTrue();
        assertThat(finished.resumeOutcome()).isNull();
        assertThat(finished.usage()).isNotNull();
        assertThat(finished.usage().inputTokens()).isEqualTo(1200L);
        assertThat(finished.usage().cacheWriteTokens()).isEqualTo(300L);
        RunnerMessages.RunFinished failed = (RunnerMessages.RunFinished) codec
            .decode(Files.readString(V2_SAMPLES.resolve("run.finished-failed.json")), 2);
        assertThat(failed.resumed()).isFalse();
        assertThat(failed.resumeOutcome()).isEqualTo(RunnerMessages.ResumeOutcome.NO_SESSION);
    }

    @Test
    void framesOfTheOtherVersionAreUnknownTypes() throws IOException {
        String ask = Files.readString(SAMPLES.resolve("run.ask.json"));
        String request = Files.readString(V2_SAMPLES.resolve("run.request-ask.json"));
        assertThat(codec.decode(ask, 1)).isInstanceOf(RunnerMessages.RunAsk.class);
        assertThat(codec.decode(request, 2)).isInstanceOf(RunnerMessages.RunRequest.class);
        assertThat(code(ask, 2)).isEqualTo(RunnerMessages.UNKNOWN_TYPE);
        assertThat(code(Files.readString(SAMPLES.resolve("run.repository_request.json")), 2))
            .isEqualTo(RunnerMessages.UNKNOWN_TYPE);
        assertThat(code(Files.readString(SAMPLES.resolve("run.task_create.json")), 2))
            .isEqualTo(RunnerMessages.UNKNOWN_TYPE);
        assertThat(code(request, 1)).isEqualTo(RunnerMessages.UNKNOWN_TYPE);
        // Before hello the version is not known: both decode (and the handler wants a hello first).
        assertThat(codec.decode(ask)).isInstanceOf(RunnerMessages.RunAsk.class);
        assertThat(codec.decode(request)).isInstanceOf(RunnerMessages.RunRequest.class);
        // The acks are the server's in every version.
        assertThat(code(Files.readString(V2_SAMPLES.resolve("run.request.ack-tool.json")), 2))
            .isEqualTo(RunnerMessages.BAD_MESSAGE);
    }

    @Test
    void aRequestWithAnUnknownKindOrNoPayloadStillDecodesSoItCanBeAnswered() {
        RunnerMessages.RunRequest odd = (RunnerMessages.RunRequest) codec.decode("""
                {"type":"run.request","runId":"%s","epoch":1,"requestId":"%s","kind":"TELEPORT"}"""
            .formatted(UUID.randomUUID(), UUID.randomUUID()), 2);
        assertThat(odd.kind()).isEqualTo("TELEPORT");
        assertThat(odd.payload()).isNull();
        // Without a requestId there is nothing to answer: a bad frame.
        assertThat(code("""
                {"type":"run.request","runId":"%s","epoch":1,"kind":"ASK","payload":{"question":"q"}}"""
            .formatted(UUID.randomUUID()), 2)).isEqualTo(RunnerMessages.BAD_MESSAGE);
    }

    @Test
    void everyV2FrameTheServerEncodesMatchesTheV2Schema() throws IOException {
        UUID runId = UUID.randomUUID();
        RunnerMessages.AssignedAgent agent = new RunnerMessages.AssignedAgent(UUID.randomUUID(), "Analyst",
                RuntimeKind.CLAUDE_CODE, null, EffortLevel.HIGH);
        RunnerMessages.Limits limits = new RunnerMessages.Limits(3600, 200);
        JsonNode getSchema = JSON.readTree("""
                {"type":"object","properties":{"key":{"type":"string"}},"required":["key"]}""");
        // jira_get with its replay class (package R), jira_search without one, as a server before it sent.
        List<RunnerMessages.ToolSpec> tools = List.of(new RunnerMessages.ToolSpec("jira_get", "Reads an issue.",
                getSchema, RunnerMessages.ToolReplay.SAFE), new RunnerMessages.ToolSpec("jira_search",
                        "Searches with JQL.", JSON.readTree("""
                        {"type":"object","properties":{"query":{"type":"string"},
                         "limit":{"type":"integer","minimum":1,"maximum":50,"default":20}},"required":["query"]}""")));
        RunnerMessages.RunAssign withTools = RunnerMessages.RunAssign.v2(runId, 1, "claude-max", null, agent,
                new RunnerMessages.AssignedWorkItem(UUID.randomUUID(), "SHOP-1", "Q3 report"),
                new RunnerMessages.AssignedJob(UUID.randomUUID()), "prompt", null, List.of(), List.of(), limits, null,
                List.of(new RunnerMessages.AssignedAttachment(UUID.randomUUID(), "reports_q3.md", 12,
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")), tools,
                List.of("*.example.com", "registry.npmjs.org:8443"));
        RunnerMessages.RunAssign withoutTools = RunnerMessages.RunAssign.v2(runId, 2, null,
                new RunnerMessages.ModelAccess(RunnerMessages.ModelAccessFormat.ANTHROPIC,
                        "https://litellm.example.com", "sk-not-a-real-key"),
                agent, new RunnerMessages.AssignedWorkItem(UUID.randomUUID(), "TF-1", "Chat"),
                new RunnerMessages.AssignedJob(UUID.randomUUID()), "prompt", "session", List.of(), List.of(), limits,
                "resume", null, List.of(), List.of());
        List<RunnerMessages.Outbound> frames = List.of(
                new RunnerMessages.Welcome(2, UUID.randomUUID(), UUID.randomUUID(), "0.1.0", 10, 30),
                withTools, withoutTools, new RunnerMessages.RunCancel(runId, 1),
                RunnerMessages.RunRequestAck.pending(runId, UUID.randomUUID(), UUID.randomUUID(), "posted"),
                RunnerMessages.RunRequestAck.pending(runId, UUID.randomUUID(), UUID.randomUUID(), null),
                RunnerMessages.RunRequestAck.done(runId, UUID.randomUUID(),
                        new RunnerMessages.ArtifactPayload(UUID.randomUUID()), "Saved the link."),
                RunnerMessages.RunRequestAck.done(runId, UUID.randomUUID(), RunnerMessages.ToolResult.text("1 result",
                        JSON.readTree("{\"count\":1}"), false), null),
                RunnerMessages.RunRequestAck.done(runId, UUID.randomUUID(), RunnerMessages.ToolResult.text(
                        "`jira_get` failed: Jira answered 404", null, true), null),
                RunnerMessages.RunRequestAck.rejected(runId, UUID.randomUUID(), "RESULT_TOO_LARGE", "Narrow it."),
                // TOOL_INTERRUPTED (package R): a tool error result with its code and cause.
                RunnerMessages.RunRequestAck.done(runId, UUID.randomUUID(), RunnerMessages.ToolResult.text(
                        "The call of `jira_comment` on Jira was interrupted (TOOL_INTERRUPTED): …",
                        JSON.readTree("{\"code\":\"TOOL_INTERRUPTED\",\"cause\":\"TIMEOUT\"}"), true), null),
                RunnerMessages.ProtocolError.of(RunnerMessages.UNKNOWN_TYPE, "run.ask is not a message of v2"),
                new RunnerMessages.HostConfig(1, 2, List.of()));
        for (RunnerMessages.Outbound frame : frames) {
            String json = codec.encode(frame);
            assertThat(ProtocolSchema.violations(2, json)).as(json).isEmpty();
        }
        JsonNode assign = JSON.readTree(codec.encode(withTools));
        assertThat(assign.has("task")).isFalse();
        assertThat(assign.get("workItem").get("key").asString()).isEqualTo("SHOP-1");
        assertThat(assign.get("tools")).hasSize(2);
        assertThat(assign.get("tools").get(0).get("replay").asString()).isEqualTo("SAFE");
        assertThat(assign.get("tools").get(1).has("replay")).isFalse();
        assertThat(JSON.readTree(codec.encode(withoutTools)).has("tools")).isFalse();
        // allowedHosts is always there under v2, empty when there are none; v1 frames never carry it.
        assertThat(assign.get("allowedHosts")).extracting(JsonNode::asString)
            .containsExactly("*.example.com", "registry.npmjs.org:8443");
        JsonNode noHosts = JSON.readTree(codec.encode(withoutTools)).get("allowedHosts");
        assertThat(noHosts.isArray()).isTrue();
        assertThat(noHosts).isEmpty();
        JsonNode toolAck = JSON.readTree(codec.encode(frames.get(7)));
        assertThat(toolAck.get("payload").get("isError").asBoolean()).isFalse();
        assertThat(toolAck.get("payload").get("content").get(0).get("type").asString()).isEqualTo("text");
        assertThat(toolAck.get("payload").get("structuredContent").get("count").asInt()).isEqualTo(1);
        // A v2 frame is no v1 frame: the welcome's version and the assignment's shape differ.
        assertThat(ProtocolSchema.violations(1, codec.encode(withTools))).isNotEmpty();
        assertThat(ProtocolSchema.violations(1, codec.encode(frames.get(4)))).isNotEmpty();
        // A v1 host is sent protocolVersion 1, which its schema allows (and the host ignores).
        assertThat(ProtocolSchema.violations(1, codec.encode(new RunnerMessages.Welcome(1, UUID.randomUUID(),
                UUID.randomUUID(), null, 10, 30)))).isEmpty();
    }

    @Test
    void anAckCarriesWhatItsStatusNeeds() {
        UUID runId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        assertThatThrownBy(() -> new RunnerMessages.RunRequestAck(runId, requestId,
                RunnerMessages.RequestStatus.REJECTED, null, "X", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunnerMessages.RunRequestAck(runId, requestId,
                RunnerMessages.RequestStatus.DONE, null, null, "m")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunnerMessages.RunRequestAck(runId, requestId,
                RunnerMessages.RequestStatus.PENDING, new RunnerMessages.DecisionPayload(UUID.randomUUID()), "X",
                "m")).isInstanceOf(IllegalArgumentException.class);
        RunnerMessages.AssignedAgent agent = new RunnerMessages.AssignedAgent(UUID.randomUUID(), "A",
                RuntimeKind.CLAUDE_CODE, null);
        RunnerMessages.Limits limits = new RunnerMessages.Limits(3600, 200);
        // A run names its task (v1) or its work item and job (v2), never both.
        assertThatThrownBy(() -> new RunnerMessages.RunAssign(runId, 1, "c", null, agent,
                new RunnerMessages.AssignedTask(UUID.randomUUID(), "K-1", "T"), "p", null, List.of(), List.of(),
                limits, null, null, new RunnerMessages.AssignedWorkItem(UUID.randomUUID(), "K-1", "T"),
                new RunnerMessages.AssignedJob(UUID.randomUUID()), null, null))
            .isInstanceOf(IllegalArgumentException.class);
        // Protocol v1 has no allowed hosts; a v2 assignment always lists them.
        assertThatThrownBy(() -> new RunnerMessages.RunAssign(runId, 1, "c", null, agent,
                new RunnerMessages.AssignedTask(UUID.randomUUID(), "K-1", "T"), "p", null, List.of(), List.of(),
                limits, null, null, null, null, null, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunnerMessages.RunAssign(runId, 1, "c", null, agent, null, "p", null,
                List.of(), List.of(), limits, null, null,
                new RunnerMessages.AssignedWorkItem(UUID.randomUUID(), "K-1", "T"),
                new RunnerMessages.AssignedJob(UUID.randomUUID()), null, null))
            .isInstanceOf(IllegalArgumentException.class);
        // Protocol v1 has no agent effort, and the runtime's default is no effort at all.
        RunnerMessages.AssignedAgent thinking = new RunnerMessages.AssignedAgent(UUID.randomUUID(), "A",
                RuntimeKind.CLAUDE_CODE, null, EffortLevel.MAX);
        assertThatThrownBy(() -> new RunnerMessages.RunAssign(runId, 1, "c", null, thinking,
                new RunnerMessages.AssignedTask(UUID.randomUUID(), "K-1", "T"), "p", null, List.of(), List.of(),
                limits, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RunnerMessages.AssignedAgent(UUID.randomUUID(), "A", RuntimeKind.CLAUDE_CODE,
                null, EffortLevel.DEFAULT)).isInstanceOf(IllegalArgumentException.class);
    }

    /** The allowed hosts of a run.assign: at most 200 (the server cuts its union there), each a short rule. */
    @Test
    void anAssignmentListsAtMostTwoHundredAllowedHosts() {
        RunnerMessages.AssignedAgent agent = new RunnerMessages.AssignedAgent(UUID.randomUUID(), "A",
                RuntimeKind.CLAUDE_CODE, null);
        java.util.function.IntFunction<RunnerMessages.RunAssign> assign = count -> RunnerMessages.RunAssign.v2(
                UUID.randomUUID(), 1, "claude-max", null, agent,
                new RunnerMessages.AssignedWorkItem(UUID.randomUUID(), "K-1", "T"),
                new RunnerMessages.AssignedJob(UUID.randomUUID()), "p", null, List.of(), List.of(),
                new RunnerMessages.Limits(3600, 200), null, null, List.of(),
                java.util.stream.IntStream.range(0, count).mapToObj(i -> "host-" + i + ".example.test").toList());
        assertThat(ProtocolSchema.violations(2, codec.encode(assign.apply(200)))).isEmpty();
        assertThat(ProtocolSchema.violations(2, codec.encode(assign.apply(201)))).isNotEmpty();
    }

    private String code(String frame, int version) {
        try {
            codec.decode(frame, version);
            throw new AssertionError("Accepted: " + frame);
        }
        catch (RunnerMessageCodec.BadFrame ex) {
            return ex.toError().code();
        }
    }

    private String code(String frame) {
        try {
            codec.decode(frame);
            throw new AssertionError("Accepted: " + frame);
        }
        catch (RunnerMessageCodec.BadFrame ex) {
            return ex.toError().code();
        }
    }

    private static List<Path> samples() throws IOException {
        return samples(SAMPLES);
    }

    private static List<Path> samples(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void theSteeringFramesMatchTheV2Schema() throws IOException {
        UUID runId = UUID.randomUUID();
        UUID steerId = UUID.randomUUID();
        RunnerMessages.RunSteer steer = new RunnerMessages.RunSteer(runId, 3, steerId,
                "# Message from Alice while you work\n\n> Use staging.");
        String frame = codec.encode(steer);
        assertThat(ProtocolSchema.violations(2, frame)).isEmpty();
        assertThat(JSON.readTree(frame).propertyNames()).containsExactlyInAnyOrder("type", "runId", "epoch",
                "steerId", "text");
        assertThat(steer.toString()).doesNotContain("staging");
        // A host does not send it, and v1 has no steering.
        assertThat(code(frame)).isEqualTo(RunnerMessages.BAD_MESSAGE);
        assertThat(ProtocolSchema.violations(1, frame)).isNotEmpty();

        RunnerMessages.RunSteerAck delivered = (RunnerMessages.RunSteerAck) codec
            .decode(Files.readString(V2_SAMPLES.resolve("run.steer.ack.json")), 2);
        assertThat(delivered.status()).isEqualTo(RunnerMessages.SteerStatus.DELIVERED);
        assertThat(delivered.code()).isNull();
        RunnerMessages.RunSteerAck refused = (RunnerMessages.RunSteerAck) codec
            .decode(Files.readString(V2_SAMPLES.resolve("run.steer.ack-refused.json")), 2);
        assertThat(refused.status()).isEqualTo(RunnerMessages.SteerStatus.REFUSED);
        assertThat(refused.code()).isEqualTo("NOT_SUPPORTED");
        assertThat(code(Files.readString(V2_SAMPLES.resolve("run.steer.ack.json")), 1))
            .isEqualTo(RunnerMessages.UNKNOWN_TYPE);
        String ack = """
                {"type":"run.steer.ack","runId":"%s","epoch":3,"steerId":"%s","status":"%s"}""";
        assertThat(code(ack.formatted(runId, steerId, "QUEUED"), 2)).isEqualTo(RunnerMessages.BAD_MESSAGE);
        assertThat(code(ack.formatted(runId, "not-a-uuid", "DELIVERED"), 2)).isEqualTo(RunnerMessages.BAD_MESSAGE);

        // hello: the features a host lists, none when it lists none.
        RunnerMessages.Hello hello = (RunnerMessages.Hello) codec
            .decode(Files.readString(V2_SAMPLES.resolve("hello.json")), RunnerMessageCodec.ANY_VERSION);
        assertThat(hello.featureSet()).containsExactly(RunnerMessages.FEATURE_RUN_STEER);
        String plain = """
                {"type":"hello","protocolVersion":2,"runnerVersion":"1","executors":["docker"],
                 "isolationTiers":["microvm"],"maxConcurrentRuns":2,"runtimes":[],"credentials":[]%s}""";
        assertThat(((RunnerMessages.Hello) codec.decode(plain.formatted(""))).featureSet()).isEmpty();
        assertThat(((RunnerMessages.Hello) codec.decode(plain.formatted(",\"features\":[\"run.steer\",\"later.x\"]")))
            .featureSet()).containsExactlyInAnyOrder("run.steer", "later.x");
        assertThat(code(plain.formatted(",\"features\":[\"Run Steer\"]"))).isEqualTo(RunnerMessages.BAD_MESSAGE);
    }

    @Test
    void thePublicSuffixListFramesMatchTheV2Schema() {
        String sha = "a".repeat(64);
        RunnerMessages.PslUpdate update = new RunnerMessages.PslUpdate(sha, "2026-10-07T08:00:00Z", 9000,
                java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}));
        String frame = codec.encode(update);
        assertThat(JSON.readTree(frame).get("type").asString()).isEqualTo("psl.update");
        assertThat(JSON.readTree(frame).propertyNames()).containsExactlyInAnyOrder("type", "sha256", "fetchedAt",
                "ruleCount", "content");
        assertThat(ProtocolSchema.violations(2, frame)).isEmpty();
        // A host does not send it.
        assertThat(code(frame)).isEqualTo(RunnerMessages.BAD_MESSAGE);

        String hello = """
                {"type":"hello","protocolVersion":2,"runnerVersion":"1","executors":["docker"],
                 "isolationTiers":["microvm"],"maxConcurrentRuns":2,"runtimes":[],"credentials":[],
                 "publicSuffixList":{"sha256":"%s"}}""".formatted(sha);
        assertThat(ProtocolSchema.violations(2, hello)).isEmpty();
        RunnerMessages.Hello decoded = (RunnerMessages.Hello) codec.decode(hello);
        assertThat(decoded.publicSuffixList().sha256()).isEqualTo(sha);
        assertThat(((RunnerMessages.Hello) codec.decode("""
                {"type":"hello","protocolVersion":2,"runnerVersion":"1","executors":["docker"],
                 "isolationTiers":["microvm"],"maxConcurrentRuns":2,"runtimes":[],"credentials":[]}""")).publicSuffixList())
            .isNull();
        assertThat(code(hello.replace(sha, "XYZ"))).isEqualTo(RunnerMessages.BAD_MESSAGE);
    }
}
