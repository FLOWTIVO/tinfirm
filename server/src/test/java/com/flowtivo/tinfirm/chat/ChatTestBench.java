package com.flowtivo.tinfirm.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeBotFramework;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.FakeSlack;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestDatabase;
import com.flowtivo.tinfirm.support.TestSettings;

import tools.jackson.databind.JsonNode;

/**
 * The chat module's integration tests' bench (TEST FIXTURE ONLY): a company with its OWNER, ADMIN and MEMBER; a Slack
 * system on {@link FakeSlack} or a Teams system on {@link FakeBotFramework}, with its bot held by a service identity;
 * the chat events as the database holds them; links made through the sign-in link's flow; and, for the end-to-end
 * tests, the bundled model gateway and a company runner host, so that a direct message's turn runs on the fake host
 * with the member's own budget.
 */
public abstract class ChatTestBench extends IntegrationTest {

    public static final Duration WAIT = Duration.ofSeconds(20);

    static final String GATEWAY_VERSION = "0.2.0";

    private static final Pattern LINK = Pattern.compile("/link/([0-9a-f-]{36})#([A-Za-z0-9_-]+)");

    @Autowired
    protected RunnerConnections connections;

    /** A company, its clients and principals by role. */
    public record Bench(TestCompany company, ApiClient owner, ApiClient admin, ApiClient member, UUID ownerPrincipal,
            UUID adminPrincipal, UUID memberPrincipal) {

        public UUID id() {
            return company.id();
        }

        public String path(String suffix) {
            return company.path(suffix);
        }
    }

    /** A Slack system of a bench, its workspace and its bot's service identity. */
    public record Slack(UUID systemId, FakeSlack.Workspace workspace, UUID botPrincipal) {
    }

    /** A Teams system of a bench, its tenant and its bot's service identity. */
    public record Teams(UUID systemId, FakeBotFramework.Tenant tenant, UUID botPrincipal) {
    }

    protected Bench bench(boolean gateway) {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        Bench bench = new Bench(company, owner, loginAs(company.email("ADMIN")), loginAs(company.email("MEMBER")),
                memberPrincipal(company, company.userId("OWNER")), memberPrincipal(company, company.userId("ADMIN")),
                memberPrincipal(company, company.userId("MEMBER")));
        if (gateway) {
            FakeLiteLlm litellm = FakeLiteLlm.bundled();
            ModelGateways.configureBundled(owner, company);
            for (UUID principal : List.of(bench.ownerPrincipal(), bench.adminPrincipal(), bench.memberPrincipal())) {
                ModelGateways.awaitKey(litellm, company, principal);
                ModelGateways.awaitStatus(owner, company, principal, "OK");
            }
        }
        return bench;
    }

    /** A company runner host connected over protocol v2, with this many slots, configured. */
    protected FakeRunner companyHost(Bench bench, int slots) {
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.id(), unique("host"), "COMPANY");
        FakeRunner runner = FakeRunner.connect(port, host.token());
        runner.helloV2AndWelcome(List.of(), List.of(), GATEWAY_VERSION, slots);
        await().atMost(WAIT).until(() -> connections.isConfigured(host.id()));
        return runner;
    }

    /**
     * An agent the creator makes, with no system, shared with the groups, whose settings name a model for the bench's
     * people (a budget's run names one); answers its id and handle.
     */
    protected String[] agent(ApiClient creator, Bench bench, String name, List<UUID> shareGroupIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique(name));
        body.put("runtimes", List.of("CLAUDE_CODE", "CODEX"));
        body.put("instructions", "Answer briefly.");
        body.put("systems", List.of());
        body.put("repositories", List.of());
        body.put("shareGroupIds", shareGroupIds.stream().map(UUID::toString).toList());
        ApiResponse agent = creator.post(bench.path("/agents"), body);
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        UUID agentId = UUID.fromString(agent.json().get("id").asString());
        for (UUID principal : List.of(bench.ownerPrincipal(), bench.adminPrincipal(), bench.memberPrincipal())) {
            TestSettings.nameModel(bench.id(), agentId, principal);
        }
        return new String[] { agentId.toString(), agent.json().get("handle").asString() };
    }

    // ---- chat systems ----------------------------------------------------------------------------------------------

    /** A Slack system of the Events API with its signing secret and its bot. */
    protected Slack slack(Bench bench) {
        return slack(bench, "EVENTS_API");
    }

    protected Slack slack(Bench bench, String transport) {
        FakeSlack.Workspace workspace = FakeSlack.workspace();
        UUID systemId = createSystem(bench.owner(), bench.company(), Map.of("name", unique("Slack"), "kind", "SLACK",
                "slack", Map.of("transport", transport)));
        Map<String, Object> secrets = new HashMap<>();
        secrets.put("signingSecret", workspace.signingSecret);
        if ("SOCKET_MODE".equals(transport)) {
            secrets.put("appToken", workspace.appToken);
        }
        ApiResponse stored = bench.owner().put(bench.path("/systems/" + systemId + "/chat/secrets"), secrets);
        assertThat(stored.status()).as("%s", stored).isEqualTo(200);
        UUID bot = createServiceIdentity(bench.owner(), bench.company(), unique("Slack bot"));
        ApiResponse set = bench.owner().put(bench.path("/systems/" + systemId + "/bot"), Map.of("principalId",
                bot.toString(), "token", workspace.botToken));
        assertThat(set.status()).as("%s", set).isEqualTo(200);
        return new Slack(systemId, workspace, bot);
    }

    /** A Teams system with its bot. */
    protected Teams teams(Bench bench) {
        FakeBotFramework.Tenant tenant = FakeBotFramework.tenant();
        UUID systemId = createSystem(bench.owner(), bench.company(), Map.of("name", unique("Teams"), "kind", "TEAMS",
                "teams", Map.of("tenantId", tenant.tenantId, "appId", tenant.appId)));
        UUID bot = createServiceIdentity(bench.owner(), bench.company(), unique("Teams bot"));
        ApiResponse set = bench.owner().put(bench.path("/systems/" + systemId + "/bot"), Map.of("principalId",
                bot.toString(), "token", tenant.password));
        assertThat(set.status()).as("%s", set).isEqualTo(200);
        return new Teams(systemId, tenant, bot);
    }

    // ---- the events ------------------------------------------------------------------------------------------------

    /** The system's events, oldest first: event_id, kind, sender, status, reason, received_at, handled_at. */
    protected static List<Map<String, Object>> events(UUID companyId, UUID systemId) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.rows("SELECT event_id, kind, sender, status, reason, received_at, handled_at, platform_ts"
                    + " FROM chat_event WHERE system_id = ? ORDER BY received_at, platform_ts", systemId);
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    protected static Optional<Map<String, Object>> event(UUID companyId, UUID systemId, String eventId) {
        return events(companyId, systemId).stream().filter(row -> eventId.equals(row.get("event_id"))).findFirst();
    }

    /** Waits until the event has the status, and answers its row. */
    protected static Map<String, Object> awaitEvent(UUID companyId, UUID systemId, String eventId, String status) {
        await().atMost(WAIT).until(() -> event(companyId, systemId, eventId)
            .map(row -> status.equals(row.get("status")))
            .orElse(false));
        return event(companyId, systemId, eventId).orElseThrow();
    }

    /** One row of the database as the company sees it. */
    protected static List<Map<String, Object>> rows(UUID companyId, String query, Object... params) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.rows(query, params);
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Runs a statement as the app in the company's context, committed. */
    protected static void inCompany(UUID companyId, String statement, Object... params) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            sql.accepted(statement, params);
            sql.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Runs a statement as the schema's owner, committed: the tests' setup of rows the app may not write. */
    protected static void asOwner(String statement, Object... params) {
        try (Connection connection = TestDatabase.connectAsOwner();
                PreparedStatement prepared = connection.prepareStatement(statement)) {
            for (int index = 0; index < params.length; index++) {
                prepared.setObject(index + 1, params[index]);
            }
            prepared.executeUpdate();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---- linking ---------------------------------------------------------------------------------------------------

    /** The company and token of the sign-in link the bot posted last in the channel. */
    protected static String[] linkIn(List<JsonNode> posts) {
        List<String[]> found = new ArrayList<>();
        for (JsonNode post : posts) {
            Matcher matcher = LINK.matcher(post.toString());
            while (matcher.find()) {
                found.add(new String[] { matcher.group(1), matcher.group(2) });
            }
        }
        assertThat(found).as("a sign-in link in %s", posts).isNotEmpty();
        return found.getLast();
    }

    /**
     * Links a Slack member of the workspace to the client's member through the sign-in link: a direct message, the
     * link the bot posts, the confirmation. The message that waited for the link is dropped, not sent (the security
     * review of 2026-10-27, L3), so the chat user sends it again: answers that new event, "hello" as the first was.
     */
    protected String linkSlack(Bench bench, Slack slack, ApiClient member, String userId) {
        Map<String, Object> envelope = slack.workspace().directMessage(userId, "hello", null);
        assertThat(slack.workspace().deliver(port, slack.systemId(), envelope).status()).isEqualTo(200);
        String eventId = (String) envelope.get("event_id");
        awaitEvent(bench.id(), slack.systemId(), eventId, "AWAITING_LINK");
        // The link is posted after the hold commits: wait for it, not only for the hold.
        List<JsonNode> posts = await().atMost(WAIT).until(() -> slack.workspace().posts("D" + userId),
                found -> LINK.matcher(found.toString()).find());
        String[] link = linkIn(posts);
        ApiResponse confirmed = member.post(bench.path("/chat-links/confirm"), Map.of("token", link[1]));
        assertThat(confirmed.status()).as("%s", confirmed).isEqualTo(200);
        awaitEvent(bench.id(), slack.systemId(), eventId, "IGNORED");
        Map<String, Object> again = slack.workspace().directMessage(userId, "hello", null);
        assertThat(slack.workspace().deliver(port, slack.systemId(), again).status()).isEqualTo(200);
        return (String) again.get("event_id");
    }

    /** The posts' texts and blocks, flattened, for assertions on what the bot said. */
    protected static String said(List<JsonNode> posts) {
        StringBuilder text = new StringBuilder();
        for (JsonNode post : posts) {
            text.append(post.toString()).append('\n');
        }
        return text.toString();
    }

    protected static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
