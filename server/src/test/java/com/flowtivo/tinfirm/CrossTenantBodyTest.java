package com.flowtivo.tinfirm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.model.ModelRelay;
import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeBotFramework;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.FakeSlack;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.RunnerFileRequests;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;
import com.flowtivo.tinfirm.support.TestSettings;

import tools.jackson.databind.JsonNode;

/**
 * The cross-tenant cases {@link TenantIsolationTest} cannot see (CONVENTIONS §10): it calls every path with
 * {@code {companyId}} with company B in the path. Here are the paths without a company (a runner host's files, the
 * model relay), the MCP endpoint, which is outside {@code api/openapi.yaml}, the chat platforms' paths, which name a
 * system and no company (package 5a), ids of company B in the bodies and queries
 * of company A's own paths, and what an instance admin who is not a member may do. Another company's id is answered
 * {@code 404} or {@code 400}, never {@code 500} and never a success, and nothing of company B changes.
 */
@ExtendWith(EnterpriseLicence.class)
class CrossTenantBodyTest extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    /** The runner version its hosts say in {@code hello}: any, since it decides nothing (ADR-0109). */
    private static final String RUNNER_VERSION = "0.2.0";

    private static final String MESSAGES = "/api/runner/v1/model/anthropic/v1/messages";

    private final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private ModelRelay relay;

    @Autowired
    private SystemTransactions transactions;

    @Autowired
    private RunnerConnections connections;

    /** A company with a member's conversation, a file of it and a run of it on a company host. */
    private record Tenant(TestCompany company, ApiClient owner, ApiClient member, UUID person, UUID agentId,
            UUID conversationId, UUID attachmentId, UUID runId, RunnerHostRef host) {
    }

    private Tenant tenant(String name) {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient member = loginAs(company.email("MEMBER"));
        UUID person = memberPrincipal(company, company.userId("MEMBER"));
        UUID agentId = TestRuns.agent(company.id());
        UUID conversationId = UUID.randomUUID();
        String key = name + "-" + conversationId.toString().substring(0, 8);
        TestConversations.bare(company.id(), conversationId, key, agentId, person, "CLAUDE_CODE");
        UUID attachmentId = upload(member, company, conversationId, name + ".txt");
        RunnerHostRef host = registerRunnerHost(owner, company.id(), unique(name + "-host"), "COMPANY");
        UUID runId = UUID.randomUUID();
        TestRuns.running(company.id(), runId, 1, host.id(), conversationId, key, agentId, person, 1);
        return new Tenant(company, owner, member, person, agentId, conversationId, attachmentId, runId, host);
    }

    private static UUID upload(ApiClient member, TestCompany company, UUID conversationId, String fileName) {
        ApiResponse uploaded = member.upload(company.path("/conversations/" + conversationId + "/attachments"),
                new ApiClient.Multipart(fileName, "text/plain", ("only " + fileName).getBytes(StandardCharsets.UTF_8)));
        assertThat(uploaded.status()).as("upload: %s", uploaded).isEqualTo(201);
        return UUID.fromString(uploaded.json().get("id").asString());
    }

    @Test
    void aRunnerHostsTokenAndARunsRelayTokenReachOnlyTheirOwnCompanysRun() {
        Tenant a = tenant("a");
        Tenant b = tenant("b");
        byte[] file = "agent file".getBytes(StandardCharsets.UTF_8);

        // A's host token: its own run's file, nothing of B's run, and no file of B through its own run.
        assertThat(RunnerFileRequests.download(port, a.host().token(), a.runId(), a.attachmentId(), 1).statusCode())
            .isEqualTo(200);
        assertThat(RunnerFileRequests.download(port, a.host().token(), b.runId(), b.attachmentId(), 1).statusCode())
            .isEqualTo(404);
        assertThat(RunnerFileRequests.download(port, a.host().token(), a.runId(), b.attachmentId(), 1).statusCode())
            .isEqualTo(404);
        assertThat(RunnerFileRequests.upload(port, a.host().token(), b.runId(), 1, "", "a.txt", "text/plain", file)
            .statusCode()).isEqualTo(404);
        // A's host token on the skills' download (ADR-0064): nothing of B's run, and no skill of B through its own.
        UUID skillOfB = UUID.fromString(b.member().upload(b.company().path("/skills"), ApiClient.Multipart.fields(
                Map.of("skillMd", "---\nname: b-skill\ndescription: B's\n---\n"))).json().get("id").asString());
        assertThat(runnerSkill(a.host().token(), b.runId(), skillOfB).statusCode()).isEqualTo(404);
        assertThat(runnerSkill(a.host().token(), a.runId(), skillOfB).statusCode()).isEqualTo(404);

        // A's run's relay token: no runner host's token, and the run it names is A's whatever the request says.
        ModelGateways.configureBundled(a.owner(), a.company());
        FakeLiteLlm.Key key = ModelGateways.awaitKey(litellm, a.company(), a.person());
        ModelGateways.awaitStatus(a.owner(), a.company(), a.person(), "OK");
        String token = transactions.inCompany(a.company().id(), () -> relay.open(new ModelRelay.RelayedRun(
                a.company().id(), a.runId(), 1, a.host().id(), a.person(), a.conversationId(), a.agentId()),
                RunnerMessages.ModelAccessFormat.ANTHROPIC)).apiKey();
        assertThat(RunnerFileRequests.download(port, token, b.runId(), b.attachmentId(), 1).statusCode())
            .isEqualTo(401);
        assertThat(RunnerFileRequests.download(port, token, a.runId(), a.attachmentId(), 1).statusCode())
            .isEqualTo(401);
        assertThat(RunnerFileRequests.upload(port, token, b.runId(), 1, "", "a.txt", "text/plain", file)
            .statusCode()).isEqualTo(401);
        assertThat(relay(a.host().token(), Map.of()).statusCode()).isEqualTo(401);

        int before = litellm.requests("/v1/messages").size();
        HttpResponse<String> relayed = relay(token, Map.of("x-litellm-tags", "run:" + b.runId(),
                "x-tinfirm-run", b.runId().toString(), "X-Tinfirm-Runner-Token", a.host().token()));
        assertThat(relayed.statusCode()).as(relayed.body()).isEqualTo(200);
        assertThat(litellm.requests("/v1/messages")).hasSize(before + 1);
        FakeLiteLlm.Request forwarded = litellm.requests("/v1/messages").getLast();
        assertThat(forwarded.header("authorization")).isEqualTo("Bearer " + key.key);
        assertThat(forwarded.header("x-litellm-tags")).startsWith("run:" + a.runId() + ",")
            .doesNotContain(b.runId().toString());
        assertThat(forwarded.text()).doesNotContain(b.runId().toString());

        // Nothing of B changed.
        assertThat(b.owner().get(b.company().path("/conversations/" + b.conversationId() + "/attachments")).json())
            .hasSize(1);
        assertThat(b.owner().get(b.company().path("/runs/" + b.runId())).json().get("status").asString())
            .isEqualTo("RUNNING");
        assertThat(b.owner().get(b.company().path("/audit-events/verification")).json().get("valid").asBoolean())
            .isTrue();
    }

    private HttpResponse<String> runnerSkill(String token, UUID runId, UUID skillId) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/runner/v1/runs/"
                + runId + "/skills/" + skillId + "/versions/1?epoch=1"))
            .timeout(WAIT)
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private HttpResponse<String> relay(String token, Map<String, String> headers) {
        String body = "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,\"metadata\":{\"user_id\":\"run:x\"},"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + MESSAGES))
            .timeout(WAIT)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void aTokenOfAUserOfAnotherCompanyGetsNothingOfTheCompanyAtItsMcpEndpoint() {
        // The endpoint answers a token of no member of the company with 404 and no code, whatever the message, the
        // transport's own (initialize, ping, tools/list) included (§14.11, §15.13, the security review of 2026-10-27,
        // H1). A user of A sees B's endpoint exactly as an endpoint of no company, and B's audit log stays as it was.
        Tenant a = tenant("a");
        Tenant b = tenant("b");
        ApiResponse created = a.member().post("/api/me/tokens", Map.of("currentPassword", PASSWORD,
                "name", "cross tenant", "scope", "WRITE"));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        Map<String, String> bearer = Map.of("Authorization", "Bearer " + created.json().get("token").asString());
        List<JsonNode> auditBefore = auditEvents(b.owner(), b.company());
        UUID nobody = UUID.randomUUID();

        List<Map<String, Object>> messages = List.of(
                Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize", "params", Map.of("protocolVersion",
                        "2025-06-18", "capabilities", Map.of(), "clientInfo", Map.of("name", "test", "version", "1"))),
                Map.of("jsonrpc", "2.0", "id", 2, "method", "ping"),
                Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/list"),
                Map.of("jsonrpc", "2.0", "id", 4, "method", "tools/call", "params", Map.of("name", "get_inbox",
                        "arguments", Map.of())),
                Map.of("jsonrpc", "2.0", "id", 5, "method", "resources/list"),
                Map.of("jsonrpc", "2.0", "id", 6, "result", Map.of()));
        for (Map<String, Object> message : messages) {
            ApiResponse atB = client().send("POST", "/api/companies/" + b.company().id() + "/mcp", message, bearer);
            ApiResponse atNobody = client().send("POST", "/api/companies/" + nobody + "/mcp", message, bearer);
            assertThat(atB.status()).as("%s at B: %s", message, atB).isEqualTo(atNobody.status())
                .isLessThan(500);
            assertThat(atB.body().replace(b.company().id().toString(), "ID"))
                .isEqualTo(atNobody.body().replace(nobody.toString(), "ID"));
            assertThat(atB.body()).doesNotContain(b.company().id().toString())
                .doesNotContain(b.conversationId().toString())
                .doesNotContain("stackTrace");
            assertThat(atB.status()).as("%s at B: %s", message, atB).isEqualTo(404);
            assertThat(atB.json().get("error").get("message").asString()).isEqualTo("Company not found");
            assertThat(atB.json().get("error").get("data").has("code")).as("no code: %s", atB).isFalse();
        }
        assertThat(auditEvents(b.owner(), b.company())).hasSameSizeAs(auditBefore);
    }

    @Test
    void idsOfAnotherCompanyInBodiesAndQueriesAreNotFoundOrRefused() {
        Tenant a = tenant("a");
        Tenant b = tenant("b");
        // B: a group, a code host, a service identity's account on a Jira, an agent and an open question.
        UUID groupOfB = createGroup(b.owner(), b.company(), "B group", List.of(b.company().userId("MEMBER")));
        UUID systemOfB = createSystem(b.owner(), b.company(), "B GitHub", "GITHUB", "https://api.github.com", false);
        UUID accountOfB = serviceAccount(b, "b");
        UUID agentOfB = agent(b.member(), b.company(), "B agent");
        UUID decisionOfB = TestConversations.question(b.company().id(), b.conversationId(), b.person(), "B only?");
        // A: the same, and what a conversation needs to start (the gateway, a host on protocol v2).
        UUID groupOfA = createGroup(a.owner(), a.company(), "A group", List.of(a.company().userId("MEMBER")));
        UUID accountOfA = serviceAccount(a, "a");
        UUID agentOfA = agent(a.member(), a.company(), "A agent");
        ModelGateways.configureBundled(a.owner(), a.company());
        ModelGateways.awaitKey(litellm, a.company(), a.person());
        ModelGateways.awaitStatus(a.owner(), a.company(), a.person(), "OK");
        try (FakeRunner runner = FakeRunner.connect(port, a.host().token())) {
            runner.helloV2AndWelcome(List.of(), List.of(), RUNNER_VERSION, 2);
            await().atMost(WAIT).until(() -> connections.isConfigured(a.host().id()));

            List<String> answers = new ArrayList<>();
            refused(answers, "POST /grants {accountId: B's}", a.owner().post(a.company().path("/grants"),
                    Map.of("credentialId", accountOfB.toString(), "groupId", groupOfA.toString(), "access", "READ")));
            refused(answers, "POST /grants {groupId: B's}", a.owner().post(a.company().path("/grants"),
                    Map.of("credentialId", accountOfA.toString(), "groupId", groupOfB.toString(), "access", "READ")));
            refused(answers, "PUT /agents/{a}/shares {groupIds: [B's]}", a.member().put(a.company().path("/agents/"
                    + agentOfA + "/shares"), Map.of("groupIds", List.of(groupOfB.toString()))));
            refused(answers, "PUT /agents/{a}/systems {systemId: B's}", a.member().put(a.company().path("/agents/"
                    + agentOfA + "/systems"), Map.of("systems", List.of(Map.of("systemId", systemOfB.toString())))));
            UUID skillOfB = UUID.fromString(b.member().upload(b.company().path("/skills"), ApiClient.Multipart
                .fields(Map.of("skillMd", "---\nname: b-only\ndescription: B's\n---\n"))).json().get("id")
                .asString());
            UUID skillOfA = UUID.fromString(a.member().upload(a.company().path("/skills"), ApiClient.Multipart
                .fields(Map.of("skillMd", "---\nname: a-only\ndescription: A's\n---\n"))).json().get("id")
                .asString());
            refused(answers, "PUT /agents/{a}/skills {skillId: B's}", a.member().put(a.company().path("/agents/"
                    + agentOfA + "/skills"), Map.of("skills", List.of(Map.of("skillId", skillOfB.toString())))));
            refused(answers, "PUT /skills/{a}/shares {groupIds: [B's]}", a.member().put(a.company().path("/skills/"
                    + skillOfA + "/shares"), Map.of("groupIds", List.of(groupOfB.toString()))));
            refused(answers, "POST /conversations {agentId: B's}", a.member().post(a.company().path(
                    "/conversations"), Map.of("agentId", agentOfB.toString(), "message", "Hello")));
            refused(answers, "POST /conversations {attachmentIds: [B's]}", a.member().post(a.company().path(
                    "/conversations"), Map.of("agentId", agentOfA.toString(), "message", "Hello", "attachmentIds",
                            List.of(b.attachmentId().toString()))));
            refused(answers, "GET /conversations?agentId=B's", a.member().get(a.company().path(
                    "/conversations?agentId=" + agentOfB)));
            refused(answers, "GET /conversations?agentId=B's as OWNER", a.owner().get(a.company().path(
                    "/conversations?agentId=" + agentOfB)));
            assertThat(answers).as("the answers").isEmpty();

            // The decisions one decides are one's own company's only.
            ApiResponse mine = a.member().get(a.company().path("/decisions?mine=true"));
            assertThat(mine.status()).as("%s", mine).isEqualTo(200);
            assertThat(mine.body()).doesNotContain(decisionOfB.toString())
                .doesNotContain(b.conversationId().toString());
            ApiResponse every = a.owner().get(a.company().path("/decisions?mine=true&open=true"));
            assertThat(every.status()).isEqualTo(200);
            assertThat(every.body()).doesNotContain(decisionOfB.toString());

            // The same requests with A's own ids succeed: the refusals above are for B's ids.
            ApiResponse own = a.member().post(a.company().path("/conversations"), Map.of("agentId",
                    agentOfA.toString(), "message", "Hello", "attachmentIds", List.of(a.attachmentId().toString())));
            assertThat(own.status()).as("%s", own).isEqualTo(201);
            assertThat(a.member().get(a.company().path("/conversations?agentId=" + agentOfA)).json().get("items"))
                .hasSize(1);
            assertThat(a.member().put(a.company().path("/agents/" + agentOfA + "/shares"),
                    Map.of("groupIds", List.of(groupOfA.toString()))).status()).isEqualTo(200);
            assertThat(a.owner().post(a.company().path("/grants"), Map.of("credentialId", accountOfA.toString(),
                    "groupId", groupOfA.toString(), "access", "READ")).status()).isEqualTo(201);
            runner.assertFramesValid();
        }

        // Nothing of B changed.
        assertThat(b.owner().get(b.company().path("/grants")).json().get("items")).isEmpty();
        JsonNode agentOfBNow = b.member().get(b.company().path("/agents/" + agentOfB)).json();
        assertThat(agentOfBNow.get("version").asInt()).isEqualTo(1);
        assertThat(b.owner().get(b.company().path("/conversations/" + b.conversationId() + "/attachments")).json())
            .hasSize(1);
        assertThat(b.owner().get(b.company().path("/decisions/" + decisionOfB)).json().get("status").asString())
            .isEqualTo("OPEN");
        assertThat(b.owner().get(b.company().path("/audit-events/verification")).json().get("valid").asBoolean())
            .isTrue();
    }

    /** A cross-tenant id is 404 or 400: never a success, never a 500. */
    private static void refused(List<String> answers, String request, ApiResponse response) {
        if (response.status() != 404 && response.status() != 400) {
            answers.add(request + " -> " + response);
        }
    }

    private UUID serviceAccount(Tenant tenant, String name) {
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID system = createSystem(tenant.owner(), tenant.company(), name.toUpperCase() + " Jira", "JIRA_DC",
                jira.baseUrl(), false);
        UUID identity = createServiceIdentity(tenant.owner(), tenant.company(), name.toUpperCase() + " bot");
        return connectServiceToken(tenant.owner(), tenant.company(), identity, system, jira,
                "test-" + name + "-only-token", name + "-bot");
    }

    /** An agent of the member, whose settings name a model (a budget's run names one). */
    private UUID agent(ApiClient member, TestCompany company, String name) {
        ApiResponse agent = member.post(company.path("/agents"), Map.of("name", name, "runtimes",
                List.of("CLAUDE_CODE")));
        assertThat(agent.status()).as("agent: %s", agent).isEqualTo(201);
        UUID agentId = UUID.fromString(agent.json().get("id").asString());
        TestSettings.nameModel(company.id(), agentId, memberPrincipal(company, company.userId("MEMBER")));
        return agentId;
    }

    /** A Slack system of the Events API with its signing secret and its bot, on the workspace. */
    private UUID slackSystem(ApiClient owner, TestCompany company, FakeSlack.Workspace workspace) {
        UUID system = createSystem(owner, company, Map.of("name", unique("Slack"), "kind", "SLACK", "slack",
                Map.of("transport", "EVENTS_API")));
        assertThat(owner.put(company.path("/systems/" + system + "/chat/secrets"), Map.of("signingSecret",
                workspace.signingSecret)).status()).isEqualTo(200);
        UUID bot = createServiceIdentity(owner, company, unique("Slack bot"));
        assertThat(owner.put(company.path("/systems/" + system + "/bot"), Map.of("principalId", bot.toString(),
                "token", workspace.botToken)).status()).isEqualTo(200);
        return system;
    }

    /** A Teams system with its bot, of the tenant. */
    private UUID teamsSystem(ApiClient owner, TestCompany company, FakeBotFramework.Tenant tenant) {
        UUID system = createSystem(owner, company, Map.of("name", unique("Teams"), "kind", "TEAMS", "teams",
                Map.of("tenantId", tenant.tenantId, "appId", tenant.appId)));
        UUID bot = createServiceIdentity(owner, company, unique("Teams bot"));
        assertThat(owner.put(company.path("/systems/" + system + "/bot"), Map.of("principalId", bot.toString(),
                "token", tenant.password)).status()).isEqualTo(200);
        return system;
    }

    private static long chatEvents(UUID companyId, UUID systemId) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("SELECT count(*) FROM chat_event WHERE system_id = ?", systemId);
        }
        catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void theChatPlatformsPathsReachOnlyTheirOwnSystemsCompany() {
        // The platforms' paths (package 5a, §15.16) name a system, never a company: company A's delivery reaches
        // nothing of company B, and A's events are not B's to see.
        TestCompany a = companyWithRoles();
        TestCompany b = companyWithRoles();
        ApiClient ownerOfA = loginAs(a.email("OWNER"));
        ApiClient ownerOfB = loginAs(b.email("OWNER"));
        FakeSlack.Workspace workspaceOfA = FakeSlack.workspace();
        FakeSlack.Workspace workspaceOfB = FakeSlack.workspace();
        UUID slackOfA = slackSystem(ownerOfA, a, workspaceOfA);
        UUID slackOfB = slackSystem(ownerOfB, b, workspaceOfB);
        Map<String, Object> envelope = workspaceOfA.directMessage(workspaceOfA.member("Ann"), "hello", null);

        // A's delivery, signed with A's secret, to B's system: 401, stored nowhere.
        assertThat(workspaceOfA.deliver(port, slackOfB, envelope).status()).isEqualTo(401);
        // Signed with B's secret but naming A's workspace: answered so that Slack does not retry, stored nowhere (C2).
        FakeSlack.Delivery foreign = workspaceOfA.deliver(port, slackOfB, envelope, workspaceOfB.signingSecret,
                java.time.Instant.now(), null);
        assertThat(foreign.status()).isEqualTo(200);
        assertThat(foreign.noRetry()).isEqualTo("1");
        assertThat(chatEvents(b.id(), slackOfB)).isZero();
        // A's own delivery is stored, and invisible in B's context.
        assertThat(workspaceOfA.deliver(port, slackOfA, envelope).status()).isEqualTo(200);
        assertThat(chatEvents(a.id(), slackOfA)).isEqualTo(1);
        assertThat(chatEvents(b.id(), slackOfA)).isZero();
        try (TenantSql sql = TenantSql.in(b.id())) {
            assertThat(sql.count("SELECT count(*) FROM chat_user WHERE system_id = ?", slackOfA)).isZero();
            assertThat(sql.refused("INSERT INTO chat_event (id, company_id, system_id, event_id, kind, thread_key,"
                    + " platform_ts, next_attempt_at, received_at) VALUES (?, ?, ?, 'EvX', 'MESSAGE', 'D:1', 1,"
                    + " now(), now())", UUID.randomUUID(), a.id(), slackOfA)).contains("row-level security");
        }
        catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }

        // A's Teams app's token on B's Teams system: 401, stored nowhere.
        FakeBotFramework.Tenant tenantOfA = FakeBotFramework.tenant();
        teamsSystem(ownerOfA, a, tenantOfA);
        UUID teamsOfB = teamsSystem(ownerOfB, b, FakeBotFramework.tenant());
        FakeBotFramework.TeamsUser user = tenantOfA.user("Ann", "user");
        assertThat(tenantOfA.deliver(port, teamsOfB, tenantOfA.personalMessage(user, "hello")).status())
            .isEqualTo(401);
        assertThat(chatEvents(b.id(), teamsOfB)).isZero();

        // Ids of B in A's own chat routes: B's service identity as A's bot, B's agent as A's default.
        UUID botOfB = createServiceIdentity(ownerOfB, b, unique("B bot"));
        List<String> answers = new ArrayList<>();
        refused(answers, "PUT A's bot with B's service identity", ownerOfA.put(a.path("/systems/" + slackOfA
                + "/bot"), Map.of("principalId", botOfB.toString(), "token", workspaceOfA.botToken)));
        UUID agentOfB = agent(loginAs(b.email("MEMBER")), b, unique("B agent"));
        refused(answers, "PUT /me/chat with B's agent", loginAs(a.email("MEMBER")).put(a.path("/me/chat"),
                Map.of("defaultAgentId", agentOfB.toString())));
        refused(answers, "DELETE a member of B's chat account through A", ownerOfA.delete(a.path("/members/"
                + b.userId("MEMBER") + "/accounts/" + slackOfA)));
        assertThat(answers).isEmpty();
        // B's admin routes of A's system: 404 in either company's path.
        assertThat(ownerOfB.get(a.path("/systems/" + slackOfA + "/chat/manifest")).status()).isEqualTo(404);
        assertThat(ownerOfB.put(b.path("/systems/" + slackOfA + "/bot"), Map.of("principalId", botOfB.toString(),
                "token", workspaceOfB.botToken)).status()).isEqualTo(404);
    }

    @Test
    void aWebhookDeliveryWithAnotherCompanysSecretReachesNothingOfTheSystemsCompany() throws Exception {
        // POST /api/hooks/{systemId} names a system, never a company (package 5, C2): company A's secret on company
        // B's system is a forged delivery to B, refused before B's tenant is read, and stored in neither company.
        TestCompany a = companyWithRoles();
        TestCompany b = companyWithRoles();
        ApiClient ownerOfA = loginAs(a.email("OWNER"));
        ApiClient ownerOfB = loginAs(b.email("OWNER"));
        Map<String, Object> webhook = Map.of("scheme", "SECRET_HEADER", "mapping", Map.of("deliveryId", "/id"));
        ApiResponse ofA = ownerOfA.post(a.path("/systems"), Map.of("name", unique("A hook"), "kind", "WEBHOOK",
                "webhook", webhook));
        ApiResponse ofB = ownerOfB.post(b.path("/systems"), Map.of("name", unique("B hook"), "kind", "WEBHOOK",
                "webhook", webhook));
        assertThat(ofA.status()).as("%s", ofA).isEqualTo(201);
        assertThat(ofB.status()).as("%s", ofB).isEqualTo(201);
        UUID hookOfA = UUID.fromString(ofA.json().get("id").asString());
        UUID hookOfB = UUID.fromString(ofB.json().get("id").asString());
        String secretOfA = ofA.json().get("webhook").get("secret").asString();

        HttpResponse<String> forged = hook(hookOfB, secretOfA, "{\"id\":\"cross-tenant-d9q\"}");
        assertThat(forged.statusCode()).isEqualTo(401);
        assertThat(forged.body()).isEmpty();
        // Nothing stored in B, nor in A; B's audit holds only its own refusal (§8), without the delivery.
        try (TenantSql sql = TenantSql.in(b.id())) {
            assertThat(sql.count("SELECT count(*) FROM webhook_delivery WHERE system_id = ?", hookOfB)).isZero();
            assertThat(sql.count("SELECT count(*) FROM webhook_delivery")).isZero();
        }
        try (TenantSql sql = TenantSql.in(a.id())) {
            assertThat(sql.count("SELECT count(*) FROM webhook_delivery")).isZero();
        }
        assertThat(auditEvents(ownerOfB, b).toString()).doesNotContain("cross-tenant-d9q")
            .doesNotContain(hookOfA.toString()).doesNotContain(a.id().toString());
        assertThat(auditEvents(ownerOfB, b, "trigger.fired")).isEmpty();
        assertThat(auditEvents(ownerOfB, b, "webhook.refused"))
            .allSatisfy(event -> assertThat(event.toString()).contains(hookOfB.toString(), "SIGNATURE"));
        assertThat(auditEvents(ownerOfA, a, "webhook.refused")).isEmpty();
        // A's own delivery, with its own secret, is A's.
        assertThat(hook(hookOfA, secretOfA, "{\"id\":\"own-1\"}").statusCode()).isEqualTo(202);
        try (TenantSql sql = TenantSql.in(b.id())) {
            assertThat(sql.count("SELECT count(*) FROM webhook_delivery WHERE system_id = ?", hookOfA)).isZero();
        }
    }

    private HttpResponse<String> hook(UUID systemId, String secret, String body) throws IOException,
            InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/hooks/" + systemId))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("X-Tinfirm-Secret", secret)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anInstanceAdminWhoIsNotAMemberReadsUnderTheAuditedBreakGlassAndActsAsNobody() {
        // CONVENTIONS §6: every read is the break-glass, audited as admin.company.accessed, with ADMIN's view; what needs
        // a principal in the company is 404 (one's own model access, an agent, a conversation), a personal runner host
        // 403; another person's personal conversation and its run are 404 (package 6, §5.10).
        Tenant b = tenant("b");
        UUID agentOfB = agent(b.member(), b.company(), "B agent");
        ApiClient admin = admin();
        assertThat(admin.get("/api/me").json().get("memberships").toString())
            .doesNotContain(b.company().id().toString());

        for (String read : List.of("", "/members", "/groups", "/service-identities", "/systems", "/grants",
                "/secrets", "/audit-events", "/runner-hosts", "/agents", "/agents/" + agentOfB, "/conversations",
                "/decisions", "/inbox")) {
            int before = auditEvents(b.owner(), b.company(), "admin.company.accessed").size();
            ApiResponse response = admin.get(b.company().path(read));
            assertThat(response.status()).as("GET %s: %s", read, response).isEqualTo(200);
            List<JsonNode> after = auditEvents(b.owner(), b.company(), "admin.company.accessed");
            assertThat(after).as("GET %s is audited", read).hasSizeGreaterThan(before);
            assertThat(after.getFirst().get("actor").get("id").asString())
                .isEqualTo(admin.get("/api/me").json().get("id").asString());
        }

        assertThat(admin.get(b.company().path("/conversations/" + b.conversationId())).status()).isEqualTo(404);
        assertThat(admin.get(b.company().path("/runs/" + b.runId())).status()).isEqualTo(404);
        assertThat(admin.get(b.company().path("/conversations")).json().get("items")).isEmpty();
        assertThat(admin.get(b.company().path("/me/model-access")).status()).isEqualTo(404);
        assertThat(admin.post(b.company().path("/agents"), Map.of("name", "Admin agent", "runtimes",
                List.of("CLAUDE_CODE"))).status()).isEqualTo(404);
        assertThat(admin.post(b.company().path("/conversations"), Map.of("agentId", agentOfB.toString(),
                "message", "Hello")).status()).isEqualTo(404);
        ApiResponse personalHost = admin.post(b.company().path("/runner-hosts"), Map.of("name", unique("admin-host"),
                "scope", "PERSONAL"));
        assertThat(personalHost.status()).as("%s", personalHost).isEqualTo(403);
        assertThat(b.owner().get(b.company().path("/agents")).body()).doesNotContain("Admin agent");
        assertThat(b.owner().get(b.company().path("/conversations")).json().get("items")).hasSize(1);
        assertThat(b.owner().get(b.company().path("/audit-events/verification")).json().get("valid").asBoolean())
            .isTrue();
    }
}
