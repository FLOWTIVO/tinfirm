package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.AgentStatus;
import com.flowtivo.tinfirm.agent.EffectiveAgent;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.ModelAccessSetting;
import com.flowtivo.tinfirm.agent.SettingsOwner;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.run.TurnSource;
import com.flowtivo.tinfirm.runner.EffortLevel;
import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.runner.RunnerHostInfo;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.support.ProtocolSchema;

import tools.jackson.databind.json.JsonMapper;

/**
 * A snapshot of {@code run.assign} (the package 3 contract, §5.7 and §9; I12, I14): the conversation as the work item
 * and the job, the branch {@code agent/{key}} in every repository, each repository's directory, the git tokens only
 * in {@code workspaces[].git}, the relay's token only in {@code modelAccess}, the previous session's id and prompt,
 * the agent's name and the settings' model and effort (left out at the runtime's default), the agent's and the
 * conversation's network hosts; a valid v2 frame that masks its secrets when printed.
 */
class RunAssignmentTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final RunProperties PROPERTIES = new RunProperties(Duration.ofMinutes(60), 200,
            Duration.ofSeconds(10), Duration.ofMinutes(2), Duration.ofSeconds(10), 20000, Duration.ofMinutes(30),
            Duration.ofSeconds(15), Duration.ofSeconds(60));

    private final RunDelivery delivery = new RunDelivery(null, null, null, null, null, null, null, null, null, null,
            null, PROPERTIES, null, null, null);

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID CONVERSATION = UUID.randomUUID();

    private static RunStore.RunRow run(String modelAccess) {
        return new RunStore.RunRow(UUID.randomUUID(), COMPANY, CONVERSATION, "shop-123-dev-2", UUID.randomUUID(),
                RuntimeKind.CODEX, TurnReason.MESSAGE, 3, UUID.randomUUID(), UUID.randomUUID(), modelAccess, null,
                null, null, null, null, RunStatus.QUEUED, 0, null, null, null, null, Instant.now(), null, null, null,
                null, null, null, "agent/shop-123-dev-2", null, null, null, null, null, 0, 0, null, null, null, null,
                null, null, null, null, null, null, null);
    }

    /** The person's settings: the agent Builder with its hosts, a model and an effort. */
    private static EffectiveSettings settings(RunStore.RunRow run, EffortLevel effort) {
        AgentInfo agent = new AgentInfo(run.agentId(), COMPANY, "Builder", "builder", null, "Build.",
                List.of(RuntimeKind.CODEX), AgentStatus.ACTIVE, run.actingPrincipalId(), 3, null);
        EffectiveAgent effective = new EffectiveAgent(agent, 3, new AgentDefinition(List.of(), List.of(),
                List.of("registry.npmjs.org", "api.example.com")), AgentDefinition.Widening.NONE);
        return new EffectiveSettings(run.agentId(), new SettingsOwner.Person(run.actingPrincipalId()), true, effective,
                RuntimeKind.CODEX, null, "gpt-5", effort, new ModelAccessSetting.Budget(), null, List.of(), List.of(),
                Set.of(), List.of(), List.of());
    }

    private static RunDispatcher.Assignment assignment(RunStore.RunRow run, ModelAccessChoice access, String label,
            TurnSource.PreviousRun previous, EffortLevel effort) {
        RunnerHostInfo host = new RunnerHostInfo(UUID.randomUUID(), COMPANY, "builder", HostScope.COMPANY, null, 4, 4,
                null, false, null, "0.2.0", Instant.now());
        return new RunDispatcher.Assignment(run, 5, host, 2, access, label, RunDispatcher.turnRef(run, access),
                previous, settings(run, effort));
    }

    private static TurnSource.Workspace repository(String name, boolean readOnly, String token) {
        return new TurnSource.Workspace(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), name,
                "https://github.com/acme/" + name, "main", readOnly, readOnly ? TurnSource.ReadOnlyCause.GRANT : null,
                new RunnerMessages.GitCredentials("x-access-token", token), "Alice via Tinfirm agent Builder",
                "alice@example.com");
    }

    private static TurnSource.TurnContent.Content content() {
        return new TurnSource.TurnContent.Content("Fix the checkout", "The prompt.", "What is new.",
                List.of(repository("Server", false, "ghs_secret_one"), repository("server", true, "ghs_secret_two")),
                List.of(), List.of(), null, List.of("api.example.com", "*.github.io"), List.of());
    }

    @Test
    void aBudgetsRunNamesTheConversationTheBranchAndTheRelaysTokenOnly() {
        RunStore.RunRow run = run("BUDGET");
        RunnerMessages.ModelAccess relay = new RunnerMessages.ModelAccess(RunnerMessages.ModelAccessFormat.OPENAI,
                "https://tinfirm.example.com/api/runner/v1/model/openai/v1", "tfm_" + "x".repeat(43));
        TurnSource.TurnContent.Content content = content();
        RunnerMessages.RunAssign assign = delivery.build(assignment(run, ModelAccessChoice.BUDGET, null,
                new TurnSource.PreviousRun(UUID.randomUUID(), "session-7", Instant.now(), null), EffortLevel.HIGH),
                content, RunDelivery.dirs(content.workspaces()), relay);

        assertThat(assign.epoch()).isEqualTo(5);
        assertThat(assign.workItem().id()).isEqualTo(CONVERSATION);
        assertThat(assign.job().id()).isEqualTo(CONVERSATION);
        assertThat(assign.workItem().key()).isEqualTo("shop-123-dev-2");
        assertThat(assign.workItem().title()).isEqualTo("Fix the checkout");
        assertThat(assign.workspaces()).extracting(RunnerMessages.Workspace::branch)
            .containsOnly("agent/shop-123-dev-2");
        assertThat(assign.workspaces()).extracting(RunnerMessages.Workspace::dir).containsExactly("server",
                "server-2");
        assertThat(assign.workspaces()).extracting(RunnerMessages.Workspace::readOnly).containsExactly(false, true);
        // The agent as the person's settings have it.
        assertThat(assign.agent().id()).isEqualTo(run.agentId());
        assertThat(assign.agent().name()).isEqualTo("Builder");
        assertThat(assign.agent().runtime()).isEqualTo(RuntimeKind.CODEX);
        assertThat(assign.agent().model()).isEqualTo("gpt-5");
        assertThat(assign.agent().effort()).isEqualTo(EffortLevel.HIGH);
        assertThat(assign.resumeSessionId()).isEqualTo("session-7");
        assertThat(assign.resumePrompt()).isEqualTo("What is new.");
        assertThat(assign.credentialLabel()).isNull();
        assertThat(assign.modelAccess()).isEqualTo(relay);
        // The agent's hosts and the conversation's, sorted and distinct, without a wildcard over shared hosting.
        assertThat(assign.allowedHosts()).containsExactly("api.example.com", "registry.npmjs.org");
        assertThat(assign.limits().maxTurns()).isEqualTo(200);
        // An agent without a time limit of its own takes the deployment's.
        assertThat(assign.limits().maxWallClockSeconds()).isEqualTo(3600);

        String frame = JSON.writeValueAsString(assign);
        assertThat(ProtocolSchema.violations(2, frame)).isEmpty();
        // The secrets: the git tokens only in workspaces[].git, the relay's token only in modelAccess (I14).
        assertThat(frame.indexOf("ghs_secret_one")).isEqualTo(frame.lastIndexOf("ghs_secret_one"));
        assertThat(frame.indexOf("tfm_")).isEqualTo(frame.lastIndexOf("tfm_"));
        assertThat(assign.toString()).doesNotContain("ghs_secret").doesNotContain("tfm_");
        assertThat(content.toString()).doesNotContain("ghs_secret");
    }

    @Test
    void anAgentsTimeLimitOfATurnReplacesTheDeploymentsWallClock() {
        AgentInfo agent = new AgentInfo(UUID.randomUUID(), COMPANY, "Builder", "builder", null, "Build.",
                List.of(RuntimeKind.CODEX), 180, AgentStatus.ACTIVE, null, 1, null);
        assertThat(RunDelivery.maxWallClockSeconds(agent, Duration.ofMinutes(60))).isEqualTo(10800);
        AgentInfo unset = new AgentInfo(UUID.randomUUID(), COMPANY, "Builder", "builder", null, "Build.",
                List.of(RuntimeKind.CODEX), AgentStatus.ACTIVE, null, 1, null);
        assertThat(RunDelivery.maxWallClockSeconds(unset, Duration.ofMinutes(45))).isEqualTo(2700);
    }

    @Test
    void aSubscriptionsRunNamesItsLabelAndAFirstTurnHasNoResume() {
        RunStore.RunRow run = run("SUBSCRIPTION");
        TurnSource.TurnContent.Content content = content();
        RunnerMessages.RunAssign assign = delivery.build(assignment(run, new ModelAccessChoice.Subscription(
                UUID.randomUUID(), "chatgpt-pro"), "chatgpt-pro", null, EffortLevel.DEFAULT), content,
                RunDelivery.dirs(content.workspaces()), null);
        assertThat(assign.credentialLabel()).isEqualTo("chatgpt-pro");
        assertThat(assign.modelAccess()).isNull();
        assertThat(assign.agent().effort()).isNull();
        assertThat(assign.resumeSessionId()).isNull();
        assertThat(assign.resumePrompt()).isNull();
        assertThat(ProtocolSchema.violations(2, JSON.writeValueAsString(assign))).isEmpty();
    }

    @Test
    void theRelaysFormatFollowsTheRuntime() {
        assertThat(RunDelivery.format(RuntimeKind.CODEX)).isEqualTo(RunnerMessages.ModelAccessFormat.OPENAI);
        assertThat(RunDelivery.format(RuntimeKind.CLAUDE_CODE)).isEqualTo(RunnerMessages.ModelAccessFormat.ANTHROPIC);
        assertThat(RunDelivery.format(RuntimeKind.PI)).isEqualTo(RunnerMessages.ModelAccessFormat.ANTHROPIC);
        assertThat(RunDelivery.format(RuntimeKind.OPENCODE)).isEqualTo(RunnerMessages.ModelAccessFormat.ANTHROPIC);
    }

    @Test
    void aFailureToPrepareTheContentGoesBackToTheQueueOnlyWhenItMayPass() {
        // The database, a lock, a timeout, a read: tried again after a back-off.
        assertThat(RunDelivery.transientFailure(new org.springframework.jdbc.CannotGetJdbcConnectionException(
                "no connection"))).isTrue();
        assertThat(RunDelivery.transientFailure(new org.springframework.dao.CannotAcquireLockException("lock")))
            .isTrue();
        assertThat(RunDelivery.transientFailure(new org.springframework.dao.QueryTimeoutException("timeout")))
            .isTrue();
        assertThat(RunDelivery.transientFailure(new org.springframework.transaction.CannotCreateTransactionException(
                "pool"))).isTrue();
        assertThat(RunDelivery.transientFailure(new IllegalStateException("wrapped",
                new java.sql.SQLException("serialization", "40001")))).isTrue();
        assertThat(RunDelivery.transientFailure(new java.io.UncheckedIOException(new java.io.IOException("read"))))
            .isTrue();
        // Anything else fails the same way again: the run fails (OutputPrompt.mountNames threw this one).
        assertThat(RunDelivery.transientFailure(new IndexOutOfBoundsException())).isFalse();
        assertThat(RunDelivery.transientFailure(new IllegalArgumentException("A run.assign holds at most 1000 files")))
            .isFalse();
        assertThat(RunDelivery.transientFailure(new NullPointerException())).isFalse();
        assertThat(RunDelivery.transientFailure(new org.springframework.dao.DataIntegrityViolationException("check",
                new java.sql.SQLException("check", "23514")))).isFalse();
    }

    @Test
    void theTurnsContentKeepsTheProtocolsLimits() {
        List<String> hosts = IntStream.range(0, 101).mapToObj(i -> "h" + i + ".example.com").toList();
        assertThatThrownBy(() -> new TurnSource.TurnContent.Content("T", "P", null, List.of(), List.of(), List.of(),
                null, hosts, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnSource.Workspace(UUID.randomUUID(), UUID.randomUUID(), null, "x", "u", "main",
                true, null, null, "a", "b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TurnSource.TurnContent.NotReady(new TurnSource.TurnPlan.Ready(Set.of())))
            .isInstanceOf(IllegalArgumentException.class);
        // The agent's hundred hosts and the conversation's hundred more fit run.assign's two hundred.
        List<String> agents = IntStream.range(0, 100).mapToObj(i -> "a" + i + ".example.com").toList();
        List<String> granted = IntStream.range(0, 101).mapToObj(i -> "g" + i + ".example.com").toList();
        assertThat(RunDelivery.allowedHosts(agents, granted)).hasSize(200).startsWith("a0.example.com")
            .doesNotHaveDuplicates();
    }
}
