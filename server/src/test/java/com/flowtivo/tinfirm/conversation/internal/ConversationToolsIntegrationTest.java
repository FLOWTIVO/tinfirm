package com.flowtivo.tinfirm.conversation.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.McpStub;
import com.flowtivo.tinfirm.support.TestCredentials;
import com.flowtivo.tinfirm.support.TestRuns;
import com.flowtivo.tinfirm.support.TestTriggers;

import tools.jackson.databind.JsonNode;

/**
 * The tools of a conversation's run (the package 3 contract, §5.4, §11; V5, V10 and V11's tool half of §9), through
 * the conversation module's {@code RunRequests} and the system stubs: the names a run lists, kept for the
 * conversation and mapped back before the call; whom a tool runs as; a call that binds the person's own account and
 * reaches Jira with its token; a read-only grant that lists no write and refuses a crafted one; an unconfirmed MCP
 * tool; an unlisted tool; a refused credential; an MCP server that needs no account (ADR-0071). TEST FIXTURE ONLY:
 * tokens are test values.
 */
class ConversationToolsIntegrationTest extends ConversationBench {

    private static List<String> names(List<RunnerMessages.ToolSpec> tools) {
        return tools.stream().map(RunnerMessages.ToolSpec::name).toList();
    }

    private static RunnerMessages.ToolSpec tool(List<RunnerMessages.ToolSpec> tools, String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void aRunListsItsToolsByNamesTheConversationKeepsAndACallReachesJiraWithThePersonsToken() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        String handle = jiraHandle(bench);

        List<RunnerMessages.ToolSpec> listed = listed(conversation);
        assertThat(names(listed)).containsExactly(handle + "_comment", handle + "_get", handle + "_search");
        assertThat(tool(listed, handle + "_get").description())
            .endsWith("Not connected yet: the first call uses " + displayName(bench) + "'s account on "
                    + systemName(bench) + ", or asks " + displayName(bench) + " to connect one.");
        assertThat(tool(listed, handle + "_comment").description()).contains("Each call waits for "
                + displayName(bench) + "'s approval.");
        assertThat(rows(bench.companyId(), "SELECT name FROM conversation_tool_name WHERE conversation_id = ?",
                conversation.id())).hasSize(3);

        // The first call binds the person's own account, and reaches Jira with its token.
        connectOwnJira(bench, "test-own-token-1", "ana");
        JiraStub.server().stubFor(get(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-1")))
            .withHeader("Authorization", equalTo("Bearer test-own-token-1"))
            .willReturn(JiraStub.json(200, JiraStub.issue("10001", "SHOP-1", "Checkout fails", "Steps", "ana",
                    List.of(), "Open", "2026-10-01T10:00:00.000+0200", List.of()))));
        RunnerMessages.RunRequestAck read = call(conversation, handle + "_get", Map.of("key", "SHOP-1"));
        assertThat(resultText(read)).contains("SHOP-1: Checkout fails");
        assertThat(bindingRow(conversation, bench.jiraId())).containsEntry("state", "BOUND").containsEntry("how",
                "OWN");
        assertThat(tool(listed(conversation), handle + "_get").description()).endsWith("Runs as "
                + displayName(bench) + "'s own account.");

        // tool.called by the agent, with the account; the token revealed for the run's call.
        JsonNode called = auditEvents(bench.owner(), bench.company(), "tool.called").getFirst();
        assertThat(called.get("actor").get("type").asString()).isEqualTo("AGENT");
        assertThat(called.get("details").get("tool").asString()).isEqualTo("get");
        assertThat(called.get("details").get("mode").asString()).isEqualTo("ALLOW");
        assertThat(called.get("details").get("ok").asBoolean()).isTrue();
        assertThat(called.get("details").get("runId").asString()).isEqualTo(conversation.runId().toString());
        assertThat(auditEvents(bench.owner(), bench.company(), "secret.accessed")).extracting(event -> event
            .get("details").get("purpose").asString()).contains("run-tool:" + conversation.runId());

        // The API never renames a handle (CONVENTIONS §15.2); were it changed in the database, the names would still
        // stay the conversation's.
        ApiResponse renamed = bench.admin().patch(bench.path("/systems/" + bench.jiraId()), Map.of("handle",
                "tracker"));
        assertThat(renamed.problemCode()).as("%s", renamed).isEqualTo("VALIDATION_FAILED");
        assertThat(update(bench.companyId(), "UPDATE external_system SET handle = 'tracker' WHERE id = ?",
                bench.jiraId())).isEqualTo(1);
        assertThat(names(listed(conversation))).containsExactly(handle + "_comment", handle + "_get",
                handle + "_search");
        assertThat(outcome(call(conversation, handle + "_get", Map.of("key", "SHOP-1")))).isEqualTo("DONE");
        // A new conversation lists the new handle.
        assertThat(names(listed(conversation(bench)))).containsExactly("tracker_comment", "tracker_get",
                "tracker_search");
    }

    @Test
    void anUnlistedToolIsUnknownAndOneTheAgentDoesNotListIsDeniedOncePerRun() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        String handle = jiraHandle(bench);

        // V11: a tool no system of the conversation serves is UNKNOWN_TOOL; nothing is bound or audited.
        assertThat(outcome(call(conversation, handle + "_delete_everything", Map.of()))).isEqualTo("UNKNOWN_TOOL");
        assertThat(outcome(call(conversation, "nosuch_get", Map.of("key", "SHOP-1")))).isEqualTo("UNKNOWN_TOOL");
        // A tool Jira serves but the agent does not list: TOOL_DENIED, audited once per run and tool.
        RunnerMessages.RunRequestAck denied = call(conversation, handle + "_transition", Map.of("key", "SHOP-1",
                "transition", "Done"));
        assertThat(outcome(denied)).isEqualTo("TOOL_DENIED");
        assertThat(denied.message()).contains("is not one of " + bench.agentName() + "'s tools");
        assertThat(outcome(call(conversation, handle + "_transition", Map.of("key", "SHOP-1", "transition",
                "Done")))).isEqualTo("TOOL_DENIED");
        assertThat(bindingRow(conversation, bench.jiraId())).isEmpty();
        List<JsonNode> deniedEvents = auditEvents(bench.owner(), bench.company(), "tool.denied");
        assertThat(deniedEvents).singleElement().satisfies(event -> {
            assertThat(event.get("details").get("tool").asString()).isEqualTo("transition");
            assertThat(event.get("details").get("step").asString()).isEqualTo("AGENT");
            assertThat(event.get("actor").get("type").asString()).isEqualTo("AGENT");
        });
        // Arguments that do not fit the tool.
        assertThat(outcome(call(conversation, handle + "_get", Map.of()))).isEqualTo("INVALID_REQUEST");
        // The next turn audits it again.
        Conversation next = nextTurn(conversation, 2);
        assertThat(outcome(call(next, handle + "_transition", Map.of("key", "SHOP-1", "transition", "Done"))))
            .isEqualTo("TOOL_DENIED");
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.denied")).hasSize(2);
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.called")).isEmpty();
    }

    @Test
    void settingsSwitchToolsOffOrToAskAndNeverWidenTheAgent() {
        Bench bench = bench();
        String handle = jiraHandle(bench);
        // Settings that try ALLOW on the agent's tool are refused; the tool stays as the agent has it.
        ApiResponse widen = bench.person().put(bench.agentPath("/settings/me"), Map.of("toolModes", List.of(Map.of(
                "systemId", bench.jiraId().toString(), "tool", "comment", "mode", "ALLOW"))));
        assertThat(widen.status()).as("%s", widen).isEqualTo(400);
        Conversation conversation = conversation(bench);
        assertThat(tool(listed(conversation), handle + "_comment").description()).contains("Each call waits for");

        // OFF removes a tool, ASK turns ALLOW into ASK.
        ApiResponse saved = bench.person().put(bench.agentPath("/settings/me"), Map.of("toolModes", List.of(
                Map.of("systemId", bench.jiraId().toString(), "tool", "search", "mode", "OFF"),
                Map.of("systemId", bench.jiraId().toString(), "tool", "get", "mode", "ASK"))));
        assertThat(saved.status()).as("%s", saved).isEqualTo(200);
        List<RunnerMessages.ToolSpec> listed = listed(conversation);
        assertThat(names(listed)).containsExactly(handle + "_comment", handle + "_get");
        assertThat(tool(listed, handle + "_get").description()).contains("Each call waits for");
        RunnerMessages.RunRequestAck off = call(conversation, handle + "_search", Map.of("query", "project = SHOP"));
        assertThat(outcome(off)).isEqualTo("TOOL_DENIED");
        assertThat(off.message()).contains("switched off in");
        // ASK now: the call raises an approval once its system is bound.
        connectOwnJira(bench, "test-own-token-2", "ben");
        UUID decision = pendingId(call(conversation, handle + "_get", Map.of("key", "SHOP-2")));
        assertThat(transactions.inCompany(bench.companyId(), () -> decisionStore.find(decision)).orElseThrow()
            .requireSubject().tool()).isEqualTo("get");
    }

    @Test
    void aReadOnlyGrantListsNoWriteAndRefusesACraftedOneNamingTheGrant() {
        // V5's tool half: an analyst acts through a READ grant of a service identity's Jira account.
        Bench bench = bench(List.of(Map.of("name", "get", "mode", "ALLOW"), Map.of("name", "comment", "mode",
                "ALLOW")));
        String handle = jiraHandle(bench);
        UUID bot = createServiceIdentity(bench.admin(), bench.company(), unique("Bot"));
        UUID account = connectServiceToken(bench.admin(), bench.company(), bot, bench.jiraId(), bench.jira(),
                "test-bot-token-v5", "bot-v5");
        String groupName = unique("Analysts");
        UUID group = createGroup(bench.admin(), bench.company(), groupName, List.of(bench.company().userId("MEMBER")));
        createGrant(bench.admin(), bench.company(), account, group, "READ");
        Conversation conversation = conversation(bench);
        JiraStub.server().stubFor(get(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-5")))
            .withHeader("Authorization", equalTo("Bearer test-bot-token-v5"))
            .willReturn(JiraStub.json(200, JiraStub.issue("10005", "SHOP-5", "Slow search", "", null, List.of(),
                    "Open", "2026-10-01T10:00:00.000+0200", List.of()))));

        // Unbound, both are listed; the first call binds the grant.
        assertThat(names(listed(conversation))).containsExactly(handle + "_comment", handle + "_get");
        assertThat(outcome(call(conversation, handle + "_get", Map.of("key", "SHOP-5")))).isEqualTo("DONE");
        assertThat(bindingRow(conversation, bench.jiraId())).containsEntry("how", "GRANTED");

        // Bound through the READ grant: no write is listed; a crafted one is TOOL_DENIED naming the grant.
        List<RunnerMessages.ToolSpec> listed = listed(conversation);
        assertThat(names(listed)).containsExactly(handle + "_get");
        assertThat(tool(listed, handle + "_get").description()).endsWith(" via " + groupName + ", read-only.")
            .contains("Runs through ");
        RunnerMessages.RunRequestAck crafted = call(conversation, handle + "_comment", Map.of("key", "SHOP-5",
                "body", "Done."));
        assertThat(outcome(crafted)).isEqualTo("TOOL_DENIED");
        assertThat(crafted.message()).contains("writes, and you act through the read-only grant of " + groupName
                + " on ");
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.denied")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("step").asString()).isEqualTo("BINDING"));
        JiraStub.server().verify(0, postRequestedFor(urlEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-5/comment"))));
        // The grant raised to WRITE lists the write from the next turn (open point 9).
        UUID grant = UUID.fromString(String.valueOf(row(bench.companyId(), "SELECT grant_id FROM conversation_binding"
                + " WHERE conversation_id = ?", conversation.id()).get("grant_id")));
        assertThat(bench.admin().patch(bench.path("/grants/" + grant), Map.of("access", "WRITE")).status())
            .isEqualTo(200);
        assertThat(names(listed(conversation))).containsExactly(handle + "_comment", handle + "_get");
    }

    @Test
    void anUnconfirmedMcpToolIsAWriteUnderAReadGrantAndAskUnderOnesOwnAccount() {
        // V10: the agent filled with the server's defaults lists an unconfirmed tool as ASK.
        Bench bench = bench();
        McpStub.Instance crm = McpStub.newInstance().serves(List.of(McpStub.tool("lookup", "Looks up a contact"),
                McpStub.tool("merge", "Merges contacts")));
        UUID mcp = createSystem(bench.admin(), bench.company(), unique("CRM"), "MCP", crm.url(), false);
        assertThat(bench.admin().post(bench.path("/systems/" + mcp + "/tools/refresh"), Map.of()).status())
            .isEqualTo(200);
        assertThat(bench.admin().patch(bench.path("/systems/" + mcp + "/tools/lookup"), Map.of("effect", "READ"))
            .status()).isEqualTo(200);
        ApiResponse systems = bench.person().put(bench.agentPath("/systems"), Map.of("systems", List.of(
                Map.of("systemId", bench.jiraId().toString(), "tools", List.of(Map.of("name", "get", "mode",
                        "ALLOW"))),
                Map.of("systemId", mcp.toString()))));
        assertThat(systems.status()).as("%s", systems).isEqualTo(200);
        String mcpHandle = String.valueOf(row(bench.companyId(), "SELECT handle FROM external_system WHERE id = ?",
                mcp).get("handle"));

        // Under a READ grant of the system's credential: the unconfirmed tool is a write, neither listed nor run.
        UUID botAccount = TestCredentials.add(bench.admin(), bench.path(""), mcp, "Bot", "test-crm-bot");
        UUID group = createGroup(bench.admin(), bench.company(), unique("Sales"),
                List.of(bench.company().userId("MEMBER")));
        createGrant(bench.admin(), bench.company(), botAccount, group, "READ");
        Conversation granted = conversation(bench);
        assertThat(outcome(call(granted, mcpHandle + "_lookup", Map.of("query", "ana")))).isEqualTo("DONE");
        assertThat(names(listed(granted))).contains(mcpHandle + "_lookup").doesNotContain(mcpHandle + "_merge");
        RunnerMessages.RunRequestAck write = call(granted, mcpHandle + "_merge", Map.of("query", "ana"));
        assertThat(outcome(write)).isEqualTo("TOOL_DENIED");
        assertThat(write.message()).contains("writes, and you act through the read-only grant");
        assertThat(crm.calls()).hasSize(1);

        // Under the person's own account (a new conversation; the first keeps its grant, I6): listed, as ASK.
        connectMcp(bench.person(), bench.path("/me/accounts/" + mcp), "test-crm-own");
        Conversation own = conversation(bench);
        List<RunnerMessages.ToolSpec> listed = listed(own);
        assertThat(names(listed)).contains(mcpHandle + "_lookup", mcpHandle + "_merge");
        assertThat(tool(listed, mcpHandle + "_merge").description()).contains("Each call waits for");
        assertThat(tool(listed, mcpHandle + "_lookup").description()).doesNotContain("Each call waits for");
        assertThat(resultText(call(own, mcpHandle + "_lookup", Map.of("query", "ana")))).isEqualTo("called lookup");
        UUID approval = pendingId(call(own, mcpHandle + "_merge", Map.of("query", "ana")));
        assertThat(transactions.inCompany(bench.companyId(), () -> decisionStore.find(approval)).orElseThrow()
            .kind()).isEqualTo(DecisionStore.Kind.APPROVAL);
        assertThat(crm.calls()).hasSize(2);
        assertThat(bindingRow(granted, mcp)).containsEntry("how", "GRANTED");
        assertThat(bindingRow(own, mcp)).containsEntry("how", "OWN");
    }

    @Test
    void anMcpServerThatNeedsNoAccountIsCalledWithoutACredentialUntilItNeedsOneAgain() {
        Bench bench = bench();
        McpStub.Instance logs = McpStub.newInstance().serves(List.of(McpStub.tool("query", "Queries the logs")));
        UUID mcp = createSystem(bench.admin(), bench.company(), unique("Logs"), "MCP", logs.url(), false);
        assertThat(bench.admin().post(bench.path("/systems/" + mcp + "/tools/refresh"), Map.of()).status())
            .isEqualTo(200);
        assertThat(bench.admin().patch(bench.path("/systems/" + mcp + "/tools/query"), Map.of("effect", "READ"))
            .status()).isEqualTo(200);
        assertThat(bench.person().put(bench.agentPath("/systems"), Map.of("systems", List.of(Map.of("systemId",
                mcp.toString())))).status()).isEqualTo(200);
        String handle = String.valueOf(row(bench.companyId(), "SELECT handle FROM external_system WHERE id = ?",
                mcp).get("handle"));

        // ADR-0071: the admin sets it to need no account once it answers without a credential; a member needs none.
        ApiResponse set = bench.admin().patch(bench.path("/systems/" + mcp), Map.of("accountsRequired", false));
        assertThat(set.status()).as("%s", set).isEqualTo(200);
        assertThat(set.json().get("accountsRequired").asBoolean()).isFalse();
        assertThat(auditEvents(bench.owner(), bench.company(), "system.changed")).anySatisfy(event -> assertThat(
                event.get("details").get("accountsRequired").asBoolean()).isFalse());
        JsonNode mine = bench.person().get(bench.path("/me/accounts")).json().get("items");
        assertThat(mine).filteredOn(item -> item.get("system").get("id").asString().equals(mcp.toString()))
            .singleElement()
            .satisfies(item -> assertThat(item.get("binding").asString()).isEqualTo("NOT_NEEDED"));

        // The first call binds without an account and reaches the server without a credential.
        Conversation conversation = conversation(bench);
        assertThat(tool(listed(conversation), handle + "_query").description())
            .endsWith(" needs no account: calls run without a credential.");
        assertThat(resultText(call(conversation, handle + "_query", Map.of("query", "error")))).isEqualTo(
                "called query");
        assertThat(bindingRow(conversation, mcp)).containsEntry("state", "BOUND").containsEntry("how", "OPEN");
        assertThat(row(bench.companyId(), "SELECT account_id FROM conversation_binding WHERE conversation_id = ? "
                + "AND system_id = ?", conversation.id(), mcp)).containsEntry("account_id", null);
        assertThat(logs.requests()).isNotEmpty()
            .allSatisfy(request -> assertThat(request.getHeader("Authorization")).isNull());
        JsonNode called = auditEvents(bench.owner(), bench.company(), "tool.called").getFirst();
        assertThat(called.get("details").get("ok").asBoolean()).isTrue();
        assertThat(called.get("details").path("accountId").isMissingNode()
                || called.get("details").get("accountId").isNull()).isTrue();
        assertThat(auditEvents(bench.owner(), bench.company(), "secret.accessed")).extracting(event -> event
            .get("details").get("purpose").asString()).doesNotContain("run-tool:" + conversation.runId());

        // A trigger's conversation binds it so too, though its trigger has no row there; Jira, without one, refuses.
        UUID author = bench.personPrincipalId();
        UUID triggerId = TestTriggers.schedule(bench.companyId(), bench.agentId(), author, author);
        update(bench.companyId(), "UPDATE agent_settings SET accepted_version = (SELECT version FROM agent "
                + "WHERE id = ?) WHERE trigger_id = ?", bench.agentId(), triggerId);
        UUID triggeredId = UUID.randomUUID();
        String key = "trig-" + triggeredId.toString().substring(0, 8);
        TestTriggers.conversation(bench.companyId(), triggeredId, key, triggerId, bench.agentId(), author, author);
        UUID triggeredRun = UUID.randomUUID();
        TestRuns.ofTrigger(bench.companyId(), triggeredRun, triggeredId, key, bench.agentId(), triggerId, author,
                author, "RUNNING");
        Conversation triggered = new Conversation(bench, triggeredId, key, triggeredRun, new ActiveRun(
                bench.companyId(), triggeredRun, 1, UUID.randomUUID(), triggeredId, bench.agentId(), author, author, 1));
        assertThat(resultText(call(triggered, handle + "_query", Map.of("query", "error")))).isEqualTo(
                "called query");
        assertThat(bindingRow(triggered, mcp)).containsEntry("state", "BOUND").containsEntry("how", "OPEN");
        assertThat(outcome(call(triggered, jiraHandle(bench) + "_get", Map.of("key", "SHOP-1"))))
            .isEqualTo("TOOL_DENIED");

        // Needing accounts again: the next call waits for the person's own account; the trigger's has none to wait
        // for.
        assertThat(bench.admin().patch(bench.path("/systems/" + mcp), Map.of("accountsRequired", true)).status())
            .isEqualTo(200);
        RunnerMessages.RunRequestAck parked = call(conversation, handle + "_query", Map.of("query", "error"));
        assertThat(pendingId(parked)).isEqualTo(bindingRow(conversation, mcp).get("id"));
        assertThat(bindingRow(conversation, mcp)).containsEntry("state", "WAITING").containsEntry("reason", "NONE")
            .containsEntry("how", "OPEN");
        assertThat(outcome(call(triggered, handle + "_query", Map.of("query", "error")))).isEqualTo("ACCOUNT_GONE");
        assertThat(bindingRow(triggered, mcp)).containsEntry("state", "GONE").containsEntry("reason",
                "ACCOUNT_REMOVED");
    }

    @Test
    void onlyAnMcpServerThatAnswersWithoutACredentialIsSetToNeedNoAccount() {
        Bench bench = bench();
        McpStub.Instance guarded = McpStub.newInstance().requiring("X-API-Key", "test-key")
            .serves(List.of(McpStub.tool("query", "Queries")));
        UUID mcp = createSystem(bench.admin(), bench.company(), unique("Guarded"), "MCP", guarded.url(), false);
        ApiResponse refused = bench.admin().patch(bench.path("/systems/" + mcp), Map.of("accountsRequired", false));
        assertThat(refused.problemCode()).as("%s", refused).isEqualTo("CREDENTIAL_REQUIRED");
        assertThat(row(bench.companyId(), "SELECT accounts_required FROM external_system WHERE id = ?", mcp))
            .containsEntry("accounts_required", true);
        // Only an MCP server; a member does not change a company system.
        assertThat(bench.admin().patch(bench.path("/systems/" + bench.jiraId()), Map.of("accountsRequired", false))
            .problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(bench.person().patch(bench.path("/systems/" + mcp), Map.of("accountsRequired", false)).status())
            .isIn(403, 404);
    }

    @Test
    void aServerPausedWhileItIsReachedIsNotSetToNeedNoAccount() {
        Bench bench = bench();
        McpStub.Instance logs = McpStub.newInstance().serves(List.of(McpStub.tool("query", "Queries the logs")));
        UUID mcp = createSystem(bench.admin(), bench.company(), unique("Logs"), "MCP", logs.url(), false);
        // The owner pauses the system while it answers the check without a credential, before the change is stored.
        String path = URI.create(logs.url()).getPath();
        AtomicReference<ApiResponse> paused = new AtomicReference<>();
        McpStub.server().addMockServiceRequestListener((request, response) -> {
            if (request.getUrl().startsWith(path) && request.getBodyAsString().contains("tools/list")
                    && paused.get() == null) {
                paused.set(bench.owner().post(bench.path("/systems/" + mcp + "/pause"), Map.of("reason",
                        "Incident")));
            }
        });

        ApiResponse refused = bench.admin().patch(bench.path("/systems/" + mcp), Map.of("accountsRequired", false));

        assertThat(paused.get()).isNotNull();
        assertThat(paused.get().status()).as("%s", paused.get()).isEqualTo(200);
        assertThat(refused.problemCode()).as("%s", refused).isEqualTo("SYSTEM_PAUSED");
        assertThat(row(bench.companyId(), "SELECT accounts_required FROM external_system WHERE id = ?", mcp))
            .containsEntry("accounts_required", true);
        assertThat(auditEvents(bench.owner(), bench.company(), "system.changed")).noneSatisfy(event -> assertThat(
                event.get("details").path("accountsRequired").isMissingNode()).isFalse());
    }

    @Test
    void aCredentialTheSystemRefusesTurnsTheAccountToReconnectAndTheCallWaits() {
        Bench bench = bench();
        String handle = jiraHandle(bench);
        Conversation conversation = conversation(bench);
        UUID account = connectOwnJira(bench, "test-own-token-401", "ida");
        JiraStub.server().stubFor(get(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-401")))
            .willReturn(aResponse().withStatus(401).withBody("{\"errorMessages\":[\"Not authenticated.\"]}")));

        RunnerMessages.RunRequestAck refused = call(conversation, handle + "_get", Map.of("key", "SHOP-401"));

        // The account turns RECONNECT; the binding waits for the person; the call is parked on it.
        UUID binding = pendingId(refused);
        Map<String, Object> row = bindingRow(conversation, bench.jiraId());
        assertThat(row.get("id")).isEqualTo(binding);
        assertThat(row).containsEntry("state", "WAITING").containsEntry("reason", "RECONNECT_YOURS");
        assertThat(String.valueOf(row.get("calls"))).contains("SHOP-401", "\"status\": \"PARKED\"");
        assertThat(refused.message()).endsWith("End your turn now.");
        assertThat(row(bench.companyId(), "SELECT status FROM account WHERE id = ?", account))
            .containsEntry("status", "RECONNECT");
        assertThat(auditEvents(bench.owner(), bench.company(), "account.refused")).hasSize(1);
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.called")).singleElement().satisfies(event -> {
            assertThat(event.get("details").get("ok").asBoolean()).isFalse();
            assertThat(event.get("details").get("code").asString()).isEqualTo("RECONNECT");
        });
    }

    @Test
    void aNameLongerThanTheRuntimesTakeIsCutHashedAndMappedBack() {
        Bench bench = bench();
        String longName = "Search_All_Records_In_The_Archive_Including_Old_Ones_Of_Year";
        McpStub.Instance archive = McpStub.newInstance().serves(List.of(McpStub.tool(longName, "Searches")));
        UUID mcp = createSystem(bench.admin(), bench.company(), unique("Archive"), "MCP", archive.url(), false);
        assertThat(bench.admin().post(bench.path("/systems/" + mcp + "/tools/refresh"), Map.of()).status())
            .isEqualTo(200);
        assertThat(bench.admin().patch(bench.path("/systems/" + mcp + "/tools/" + longName), Map.of("effect",
                "READ")).status()).isEqualTo(200);
        assertThat(bench.person().put(bench.agentPath("/systems"), Map.of("systems", List.of(Map.of("systemId",
                mcp.toString()))))
            .status()).isEqualTo(200);
        connectMcp(bench.person(), bench.path("/me/accounts/" + mcp), "test-archive-own");
        Conversation conversation = conversation(bench);

        RunnerMessages.ToolSpec spec = listed(conversation).getFirst();
        assertThat(spec.name()).hasSize(64).matches("^[a-z0-9][a-z0-9_-]{0,63}$");
        assertThat(resultText(call(conversation, spec.name(), Map.of("query", "1999")))).isEqualTo("called "
                + longName);
        assertThat(listed(conversation).getFirst().name()).isEqualTo(spec.name());
    }

    /** Stores a token as an account on an MCP server, whose initialize accepts any; answers the account's id. */
    private UUID connectMcp(com.flowtivo.tinfirm.support.ApiClient actor, String accountPath, String token) {
        ApiResponse response = actor.put(accountPath + "/token", Map.of("token", token));
        assertThat(response.status()).as("token: %s", response).isEqualTo(200);
        return UUID.fromString(response.json().get("id").asString());
    }

    private String jiraHandle(Bench bench) {
        return String.valueOf(row(bench.companyId(), "SELECT handle FROM external_system WHERE id = ?",
                bench.jiraId()).get("handle"));
    }

    private String systemName(Bench bench) {
        return String.valueOf(row(bench.companyId(), "SELECT name FROM external_system WHERE id = ?",
                bench.jiraId()).get("name"));
    }

    private String displayName(Bench bench) {
        return "User " + bench.company().email("MEMBER");
    }
}
