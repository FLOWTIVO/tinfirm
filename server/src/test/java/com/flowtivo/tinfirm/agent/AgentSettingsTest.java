package com.flowtivo.tinfirm.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.runner.EffortLevel;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.McpStub;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.RecordedEvents;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.system.ToolEffect;

import tools.jackson.databind.JsonNode;

/**
 * A person's settings of an agent (the package 3 contract, §3.7 and §5.2, open point 6): the defaults, every check of
 * a save (the model matched as the relay matches it), settings that narrow and never widen (I4) with the person's own
 * MCP servers as the one exception (the database's own check of I4 answered as {@code SETTINGS_WIDEN}), what
 * {@code Settings.effective} answers for a turn, that only the person sees their settings, and the audit.
 */
class AgentSettingsTest extends IntegrationTest {

    @Autowired
    Settings settings;

    @Test
    void settingsAreCheckedAndNarrowTheAgentForTheirPersonOnly() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        ModelGateways.configureBundled(owner, company);
        UUID github = createSystem(owner, company, unique("GitHub"), "GITHUB", "https://api.github.com", false);
        UUID repository = AgentIntegrationTest.addRepository(owner, company, github,
                "https://github.com/acme/" + unique("shop"));
        UUID other = AgentIntegrationTest.addRepository(owner, company, github,
                "https://github.com/acme/" + unique("other"));
        UUID jira = createSystem(owner, company, unique("Jira"), "JIRA_DC", JiraStub.newInstance().baseUrl(), false);
        UUID unlisted = createSystem(owner, company, unique("Git"), "GIT", null, false);
        UUID companyMcp = createSystem(owner, company, unique("CRM"), "MCP", McpStub.newInstance().url(), false);
        McpStub.Instance notes = McpStub.newInstance().serves(List.of(McpStub.tool("find_note", "Finds"),
                McpStub.tool("write_note", "Writes")));
        UUID own = createSystem(alice, company, unique("Notes"), "MCP", notes.url(), true);
        assertThat(alice.post(company.path("/systems/" + own + "/tools/refresh"), Map.of()).status()).isEqualTo(200);
        assertThat(alice.patch(company.path("/systems/" + own + "/tools/find_note"), Map.of("effect", "READ"))
            .status()).isEqualTo(200);
        UUID adminsOwn = createSystem(admin, company, unique("Admin notes"), "MCP", McpStub.newInstance().url(), true);

        UUID agentId = AgentIntegrationTest.createAgent(alice, company, "Analyst");
        String path = company.path("/agents/" + agentId);
        assertThat(alice.put(path + "/systems", Map.of("systems", List.of(Map.of("systemId", github.toString()),
                Map.of("systemId", jira.toString())))).status()).isEqualTo(200);
        assertThat(alice.put(path + "/repositories", Map.of("repositories", List.of(Map.of("repositoryId",
                repository.toString(), "access", "WRITE"), Map.of("repositoryId", other.toString(), "access",
                        "READ")))).status()).isEqualTo(200);
        String settingsPath = path + "/settings/me";

        // The defaults, stored nowhere yet.
        JsonNode defaults = alice.get(settingsPath).json();
        assertThat(defaults.get("stored").asBoolean()).isFalse();
        assertThat(defaults.get("runtime").asString()).isEqualTo("CLAUDE_CODE");
        assertThat(defaults.get("effort").asString()).isEqualTo("DEFAULT");
        assertThat(defaults.get("modelAccess").asString()).isEqualTo("BUDGET");
        assertThat(defaults.get("acceptedVersion").asInt()).isEqualTo(defaults.get("currentVersion").asInt());

        // Each check of a save.
        record Refused(Map<String, Object> body, String code) {
        }
        for (Refused refused : List.of(
                new Refused(Map.of("runtime", "PI"), "RUNTIME_NOT_LISTED"),
                new Refused(Map.of("model", "gpt-5.4"), "MODEL_NOT_ALLOWED"),
                new Refused(Map.of("systemsOff", List.of(unlisted.toString())), "SYSTEM_NOT_ON_AGENT"),
                new Refused(Map.of("toolModes", List.of(Map.of("systemId", unlisted.toString(), "tool", "get",
                        "mode", "OFF"))), "SYSTEM_NOT_ON_AGENT"),
                new Refused(Map.of("toolModes", List.of(Map.of("systemId", jira.toString(), "tool", "label",
                        "mode", "OFF"))), "UNKNOWN_TOOL"),
                // ALLOW is not a mode settings give a tool of the agent.
                new Refused(Map.of("toolModes", List.of(Map.of("systemId", jira.toString(), "tool", "get",
                        "mode", "ALLOW"))), "VALIDATION_FAILED"),
                new Refused(Map.of("defaultRepositoryIds", List.of(UUID.randomUUID().toString())), "SETTINGS_WIDEN"),
                new Refused(Map.of("defaultProjects", List.of(Map.of("systemId", unlisted.toString(), "key", "SHOP"))),
                        "SYSTEM_NOT_ON_AGENT"),
                new Refused(Map.of("ownServers", List.of(Map.of("systemId", companyMcp.toString()))),
                        "SETTINGS_WIDEN"),
                new Refused(Map.of("ownServers", List.of(Map.of("systemId", adminsOwn.toString()))),
                        "SETTINGS_WIDEN"),
                new Refused(Map.of("ownServers", List.of(Map.of("systemId", own.toString(), "tools",
                        List.of(Map.of("name", "delete_all", "mode", "ALLOW"))))), "UNKNOWN_TOOL"),
                new Refused(Map.of("modelAccess", "SUBSCRIPTION"), "VALIDATION_FAILED"))) {
            ApiResponse response = alice.put(settingsPath, refused.body());
            assertThat(response.problemCode()).as("%s -> %s", refused.body(), response).isEqualTo(refused.code());
        }
        // The details name what is refused, for the page to point at: the system, and the tool.
        JsonNode notListed = alice.put(settingsPath, Map.of("systemsOff", List.of(unlisted.toString()))).json();
        assertThat(notListed.get("details").get("systemId").asString()).isEqualTo(unlisted.toString());
        JsonNode unknownTool = alice.put(settingsPath, Map.of("toolModes", List.of(Map.of("systemId",
                jira.toString(), "tool", "label", "mode", "OFF")))).json();
        assertThat(unknownTool.get("details").get("systemId").asString()).isEqualTo(jira.toString());
        assertThat(unknownTool.get("details").get("tool").asString()).isEqualTo("label");
        assertThat(alice.get(settingsPath).json().get("stored").asBoolean()).isFalse();

        // A save that narrows, with the person's own server added: its tools filled, reads ALLOW, the rest ASK.
        Map<String, Object> body = new HashMap<>();
        body.put("runtime", "CODEX");
        body.put("model", "claude-sonnet-4-5");
        body.put("effort", "HIGH");
        body.put("instructions", "Answer in Czech. PRIVATE-ADDITION");
        body.put("defaultRepositoryIds", List.of(repository.toString()));
        body.put("defaultProjects", List.of(Map.of("systemId", jira.toString(), "key", " SHOP ")));
        body.put("toolModes", List.of(Map.of("systemId", github.toString(), "tool", "get", "mode", "ASK"),
                Map.of("systemId", github.toString(), "tool", "search", "mode", "OFF")));
        body.put("systemsOff", List.of(jira.toString()));
        body.put("ownServers", List.of(Map.of("systemId", own.toString())));
        ApiResponse saved = alice.put(settingsPath, body);
        assertThat(saved.status()).as("%s", saved).isEqualTo(200);
        JsonNode view = saved.json();
        assertThat(view.get("stored").asBoolean()).isTrue();
        assertThat(view.get("runtime").asString()).isEqualTo("CODEX");
        assertThat(view.get("model").asString()).isEqualTo("claude-sonnet-4-5");
        assertThat(view.get("defaultProjects").get(0).get("key").asString()).isEqualTo("SHOP");
        assertThat(view.get("ownServers").get(0).get("tools"))
            .extracting(tool -> tool.get("name").asString() + ":" + tool.get("mode").asString())
            .containsExactlyInAnyOrder("find_note:ALLOW", "write_note:ASK");

        // In effect, for a turn.
        UUID alicePrincipal = memberPrincipal(company, company.userId("MEMBER"));
        EffectiveSettings effective = effective(company, agentId, alicePrincipal);
        assertThat(effective.runtime()).isEqualTo(RuntimeKind.CODEX);
        assertThat(effective.effort()).isEqualTo(EffortLevel.HIGH);
        assertThat(effective.instructions()).isEqualTo("Answer in Czech. PRIVATE-ADDITION");
        assertThat(effective.modelAccess().choice()).contains(ModelAccessChoice.BUDGET);
        assertThat(effective.modeOf(github, "get", ToolEffect.READ)).isEqualTo(EffectiveSettings.ToolDecision.ASK);
        assertThat(effective.modeOf(github, "search", ToolEffect.READ))
            .isEqualTo(EffectiveSettings.ToolDecision.SWITCHED_OFF);
        assertThat(effective.modeOf(github, "label", ToolEffect.WRITE)).isEqualTo(EffectiveSettings.ToolDecision.ASK);
        assertThat(effective.modeOf(jira, "get", ToolEffect.READ))
            .isEqualTo(EffectiveSettings.ToolDecision.SWITCHED_OFF);
        assertThat(effective.modeOf(own, "find_note", ToolEffect.READ)).isEqualTo(EffectiveSettings.ToolDecision.ALLOW);
        assertThat(effective.modeOf(own, "write_note", ToolEffect.UNKNOWN))
            .isEqualTo(EffectiveSettings.ToolDecision.ASK);
        assertThat(effective.systems()).containsExactlyInAnyOrder(github, own);
        assertThat(effective.startingRepositories()).extracting(AgentDefinition.RepositoryEntry::repositoryId)
            .containsExactly(repository);

        // The person's own server's tools as they set them: ALLOW for a write too (open point 6).
        Map<String, Object> ownWrites = new HashMap<>(body);
        ownWrites.put("ownServers", List.of(Map.of("systemId", own.toString(), "tools", List.of(Map.of("name",
                "write_note", "mode", "ALLOW")))));
        assertThat(alice.put(settingsPath, ownWrites).status()).isEqualTo(200);
        effective = effective(company, agentId, alicePrincipal);
        assertThat(effective.modeOf(own, "write_note", ToolEffect.UNKNOWN))
            .isEqualTo(EffectiveSettings.ToolDecision.ALLOW);
        assertThat(effective.modeOf(own, "find_note", ToolEffect.READ))
            .isEqualTo(EffectiveSettings.ToolDecision.NOT_ON_AGENT);

        // Only the person sees their settings: another user of the agent has their own.
        UUID group = createGroup(owner, company, unique("Analysts"), List.of(company.userId("ADMIN")));
        alice.put(path + "/shares", Map.of("groupIds", List.of(group.toString())));
        JsonNode admins = admin.get(settingsPath).json();
        assertThat(admins.get("stored").asBoolean()).isFalse();
        assertThat(admins.get("runtime").asString()).isEqualTo("CLAUDE_CODE");
        assertThat(admins.has("instructions")).isFalse();

        // Audit: what changed, never the instructions; an unchanged save is not audited.
        List<JsonNode> changes = auditEvents(owner, company, "settings.changed");
        assertThat(changes).hasSize(2);
        JsonNode first = changes.getLast().get("details");
        assertThat(first.get("changed")).extracting(JsonNode::asString).contains("runtime", "model", "effort",
                "instructions", "defaultRepositoryIds", "defaultProjects", "systems", "tools");
        assertThat(first.get("systemsOff")).extracting(JsonNode::asString).containsExactly(jira.toString());
        assertThat(first.get("ownServers")).extracting(JsonNode::asString).containsExactly(own.toString());
        assertThat(first.get("toolsOff")).hasSize(1);
        assertThat(first.get("toolsAsk")).hasSize(1);
        assertThat(auditEvents(owner, company).toString()).doesNotContain("PRIVATE-ADDITION");
        assertThat(alice.put(settingsPath, ownWrites).status()).isEqualTo(200);
        assertThat(auditEvents(owner, company, "settings.changed")).hasSize(2);
        assertThat(RecordedEvents.of(company.id(), AgentEvents.SettingsChanged.class))
            .containsOnly(new AgentEvents.SettingsChanged(company.id(), agentId, alicePrincipal));

        // Back to the defaults: the accepted version stays.
        JsonNode reset = alice.delete(settingsPath).json();
        assertThat(reset.get("stored").asBoolean()).isTrue();
        assertThat(reset.get("runtime").asString()).isEqualTo("CLAUDE_CODE");
        assertThat(reset.get("systemsOff")).isEmpty();
        assertThat(reset.get("ownServers")).isEmpty();
        assertThat(reset.get("acceptedVersion").asInt()).isEqualTo(view.get("acceptedVersion").asInt());

        // The agent drops CODEX: a stored choice it no longer lists gives way to its first runtime.
        alice.put(settingsPath, Map.of("runtime", "CODEX"));
        assertThat(alice.patch(path, Map.of("runtimes", List.of("CLAUDE_CODE"))).status()).isEqualTo(200);
        JsonNode dropped = alice.get(settingsPath).json();
        assertThat(dropped.get("runtime").asString()).isEqualTo("CLAUDE_CODE");
        assertThat(dropped.get("chosenRuntime").asString()).isEqualTo("CODEX");
    }

    @Test
    void theModelIsOneTheKeysModelsAllowAsTheRelayReadsThem() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        ModelGateways.configureBundled(owner, company);
        UUID alicePrincipal = memberPrincipal(company, company.userId("MEMBER"));
        UUID agentId = AgentIntegrationTest.createAgent(alice, company, "Writer");
        String settingsPath = company.path("/agents/" + agentId + "/settings/me");
        String modelAccessPath = company.path("/principals/" + alicePrincipal + "/model-access");

        // The company's default models, by name.
        assertThat(alice.put(settingsPath, Map.of("model", "claude-haiku-4-5")).status()).isEqualTo(200);
        assertThat(alice.put(settingsPath, Map.of("model", "claude-opus-4-1")).problemCode())
            .isEqualTo("MODEL_NOT_ALLOWED");

        // Alice's own models: a pattern, LiteLLM's names for every model, and an empty list, which allows any.
        record Case(List<String> models, String model, boolean allowed) {
        }
        for (Case check : List.of(
                new Case(List.of("claude-*"), "claude-opus-4-1", true),
                new Case(List.of("claude-*"), "gpt-5.4", false),
                new Case(List.of("gpt-5.4", "claude-*"), "claude-sonnet-4-5", true),
                new Case(List.of("*"), "gpt-5.4", true),
                new Case(List.of("all-proxy-models"), "gpt-5.4", true),
                new Case(List.of("all-team-models"), "gpt-5.4", true),
                new Case(List.of(), "gpt-5.4", true),
                new Case(List.of("claude-sonnet-4-5"), "claude-sonnet-4-5-20250929", false))) {
            ApiResponse patched = owner.patch(modelAccessPath, Map.of("models", check.models()));
            assertThat(patched.status()).as("%s", patched).isEqualTo(200);
            ApiResponse saved = alice.put(settingsPath, Map.of("model", check.model()));
            if (check.allowed()) {
                assertThat(saved.status()).as("%s -> %s", check, saved).isEqualTo(200);
                assertThat(saved.json().get("model").asString()).isEqualTo(check.model());
            }
            else {
                assertThat(saved.problemCode()).as("%s -> %s", check, saved).isEqualTo("MODEL_NOT_ALLOWED");
            }
        }
    }

    @Test
    void aSaveTheDatabaseRefusesAsAWideningIsSettingsWidenNotAServerError() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        UUID github = createSystem(owner, company, unique("GitHub"), "GITHUB", "https://api.github.com", false);
        UUID agentId = AgentIntegrationTest.createAgent(alice, company, "Reviewer");
        String path = company.path("/agents/" + agentId);
        assertThat(alice.put(path + "/systems", Map.of("systems", List.of(Map.of("systemId", github.toString()))))
            .status()).isEqualTo(200);
        String settingsPath = path + "/settings/me";
        assertThat(alice.put(settingsPath, Map.of("effort", "HIGH")).status()).isEqualTo(200);

        // GitHub leaves the agent while Alice switches it off: her save passes the service's checks, waits for her
        // settings row, and then meets the database's check of I4 (agent_settings_widen_ck).
        CompletableFuture<ApiResponse> save;
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.column("SELECT id FROM agent_settings WHERE agent_id = ? FOR UPDATE", agentId);
            sql.update("DELETE FROM agent_system WHERE agent_id = ? AND system_id = ?", agentId, github);
            save = CompletableFuture.supplyAsync(() -> alice.put(settingsPath, Map.of("effort", "LOW",
                    "systemsOff", List.of(github.toString()))));
            await().atMost(ModelGateways.WAIT).until(() -> sql.count("""
                    SELECT count(*) FROM pg_locks waiting
                      JOIN pg_locks holding ON holding.locktype = 'transactionid'
                                           AND holding.transactionid = waiting.transactionid
                                           AND holding.granted AND holding.pid = pg_backend_pid()
                     WHERE waiting.locktype = 'transactionid' AND NOT waiting.granted""") > 0);
            sql.commit();
        }
        ApiResponse refused = save.get(30, TimeUnit.SECONDS);
        assertThat(refused.status()).as("%s", refused).isEqualTo(400);
        assertThat(refused.problemCode()).isEqualTo("SETTINGS_WIDEN");
        // Nothing of the save stayed.
        JsonNode stored = alice.get(settingsPath).json();
        assertThat(stored.get("effort").asString()).isEqualTo("HIGH");
        assertThat(stored.get("systemsOff")).isEmpty();
    }

    @Test
    void aSubscriptionIsOneOnThePersonsOwnRunnerHostThatServesTheRuntime() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        RunnerHostRef laptop = registerRunnerHost(alice, company.id(), unique("alice-laptop"));
        connectRunner(laptop, List.of(FakeRunner.claudeCredential("claude-max"))).close();
        RunnerHostRef adminsLaptop = registerRunnerHost(admin, company.id(), unique("admin-laptop"));
        connectRunner(adminsLaptop, List.of(FakeRunner.claudeCredential("claude-max"))).close();
        UUID agentId = AgentIntegrationTest.createAgent(alice, company, "Coder");
        String settingsPath = company.path("/agents/" + agentId + "/settings/me");

        record Refused(Map<String, Object> body, String code) {
        }
        for (Refused refused : List.of(
                new Refused(subscription(laptop.id(), "claude-max", "CODEX"), "SUBSCRIPTION_NOT_SERVING"),
                new Refused(subscription(laptop.id(), "no-such-label", "CLAUDE_CODE"), "SUBSCRIPTION_NOT_SERVING"),
                new Refused(subscription(adminsLaptop.id(), "claude-max", "CLAUDE_CODE"),
                        "SUBSCRIPTION_NOT_SERVING"))) {
            assertThat(alice.put(settingsPath, refused.body()).problemCode()).as("%s", refused.body())
                .isEqualTo(refused.code());
        }
        JsonNode saved = alice.put(settingsPath, subscription(laptop.id(), "claude-max", "CLAUDE_CODE")).json();
        assertThat(saved.get("modelAccess").asString()).isEqualTo("SUBSCRIPTION");
        assertThat(saved.get("subscription").get("runnerHostId").asString()).isEqualTo(laptop.id().toString());
        UUID alicePrincipal = memberPrincipal(company, company.userId("MEMBER"));
        assertThat(effective(company, agentId, alicePrincipal).modelAccess().choice())
            .contains(new ModelAccessChoice.Subscription(laptop.id(), "claude-max"));

        // The host goes: the subscription is gone, and Tinfirm does not switch to the budget by itself.
        assertThat(alice.delete(company.path("/runner-hosts/" + laptop.id())).status()).isEqualTo(204);
        JsonNode gone = alice.get(settingsPath).json();
        assertThat(gone.get("modelAccess").asString()).isEqualTo("SUBSCRIPTION");
        assertThat(gone.get("subscription").has("runnerHostId")).isFalse();
        EffectiveSettings effective = effective(company, agentId, alicePrincipal);
        assertThat(effective.modelAccess()).isEqualTo(new ModelAccessSetting.SubscriptionGone("claude-max"));
        assertThat(effective.modelAccess().choice()).isEmpty();
    }

    @Test
    void readinessSaysWhoPaysHowEachSystemWouldBindAndWhetherAHostCanRunIt() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        UUID alicePrincipal = memberPrincipal(company, company.userId("MEMBER"));
        ModelGateways.configureBundled(owner, company);
        ModelGateways.awaitStatus(owner, company, alicePrincipal, "OK");
        JiraStub.Instance jiraStub = JiraStub.newInstance();
        UUID jira = createSystem(owner, company, unique("Jira"), "JIRA_DC", jiraStub.baseUrl(), false);
        connectToken(alice, company.path("/me/accounts/" + jira), jiraStub, "test-alice-token-" + unique("t"),
                "alice");
        UUID github = createSystem(owner, company, unique("GitHub"), "GITHUB", "https://api.github.com", false);
        UUID agentId = AgentIntegrationTest.createAgent(alice, company, "Analyst");
        String path = company.path("/agents/" + agentId);
        alice.put(path + "/systems", Map.of("systems", List.of(Map.of("systemId", jira.toString()),
                Map.of("systemId", github.toString()))));

        ApiResponse response = alice.get(path + "/readiness");
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        JsonNode readiness = response.json();
        assertThat(readiness.get("settings").get("runtime").asString()).isEqualTo("CLAUDE_CODE");
        assertThat(readiness.get("payer").get("modelAccess").asString()).isEqualTo("BUDGET");
        // The defaults name no model, which a run paid by a budget needs: refused until the settings name one.
        assertThat(readiness.get("payer").get("state").asString()).isEqualTo("REFUSED");
        assertThat(readiness.get("payer").get("reason").asString()).isEqualTo("MODEL_REQUIRED");
        assertThat(readiness.get("payer").get("models")).extracting(JsonNode::asString)
            .containsExactlyElementsOf(ModelGateways.DEFAULT_MODELS);
        Map<String, String> bindings = new HashMap<>();
        readiness.get("systems").forEach(system -> bindings.put(system.get("systemId").asString(),
                system.get("binding").asString()));
        assertThat(bindings).containsEntry(jira.toString(), "OWN").containsEntry(github.toString(), "CONNECT");
        // No runner host yet: nothing can run it, and the reason says so.
        assertThat(readiness.get("hosts").get("runnable").asBoolean()).isFalse();
        assertThat(readiness.get("hosts").get("eligible").asInt()).isZero();

        // Nothing was stored, and only who may use the agent asks.
        assertThat(alice.get(path + "/settings/me").json().get("stored").asBoolean()).isFalse();
        assertThat(admin.get(path + "/readiness").problemCode()).isEqualTo("AGENT_NOT_USABLE");

        // Settings that name a model the key allows: the budget pays.
        assertThat(alice.put(path + "/settings/me", Map.of("model", "claude-haiku-4-5")).status()).isEqualTo(200);
        assertThat(alice.get(path + "/readiness").json().get("payer").get("state").asString()).isEqualTo("READY");
    }

    private static Map<String, Object> subscription(UUID hostId, String label, String runtime) {
        return Map.of("modelAccess", "SUBSCRIPTION", "runtime", runtime, "subscription",
                Map.of("runnerHostId", hostId.toString(), "label", label));
    }

    private EffectiveSettings effective(TestCompany company, UUID agentId, UUID principalId) {
        return SystemContext.callInCompany(company.id(),
                () -> settings.effective(company.id(), agentId, new SettingsOwner.Person(principalId)));
    }
}
