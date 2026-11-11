package com.flowtivo.tinfirm.chat;

import static com.flowtivo.tinfirm.support.TestTriggers.withModel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.FakeSlack;
import com.flowtivo.tinfirm.support.OAuthStubs;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestSettings;

import tools.jackson.databind.JsonNode;

/**
 * Slack's {@code history} tool (the package 5 contract, §2.2, §5.4, open point 13): the Slack app's client on the
 * system ({@code PUT /systems/{id}/app}); a member's or a service identity's {@code OAUTH} account through the app's
 * user scopes {@code channels:history} and {@code groups:history}, which is also the member's link and replaces their
 * {@code LINK} of the same Slack user only ({@code already_linked}); a {@code LINK} binds nothing and a bot never;
 * the tool reads with the conversation's own account, a personal conversation's or a channel trigger's row, never the
 * bot's token (C9). TEST FIXTURE ONLY: every token, code and secret here is a test value.
 */
class SlackHistoryTest extends ChatTestBench {

    private static final String CLIENT_ID = "1234567890.0987654321";

    private static final String CLIENT_SECRET = "test-slack-client-secret";

    @Test
    void aMembersSlackSignInReplacesTheirLinkOfTheSameSlackUserOnly() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        String systemPath = bench.path("/systems/" + slack.systemId());

        // The app: the Slack app's client, its secret required, its user scopes fixed.
        assertThat(bench.owner().put(systemPath + "/app", Map.of("clientId", CLIENT_ID)).problemCode())
            .isEqualTo("VALIDATION_FAILED");
        assertThat(bench.owner().put(systemPath + "/app", Map.of("clientId", CLIENT_ID, "clientSecret",
                CLIENT_SECRET, "scopes", "chat:write")).problemCode()).isEqualTo("VALIDATION_FAILED");
        ApiResponse app = bench.owner().put(systemPath + "/app", Map.of("clientId", CLIENT_ID, "clientSecret",
                CLIENT_SECRET));
        assertThat(app.status()).as("%s", app).isEqualTo(200);
        assertThat(app.toString()).doesNotContain(CLIENT_SECRET);
        // Teams has no app in package 5, and no account by a sign-in; Slack takes no pasted token.
        Teams teams = teams(bench);
        assertThat(bench.owner().put(bench.path("/systems/" + teams.systemId() + "/app"), Map.of("clientId", "x",
                "clientSecret", "y")).problemCode()).isEqualTo("SYSTEM_KIND_INVALID");
        assertThat(bench.member().post(bench.path("/me/accounts/" + teams.systemId() + "/oauth"), Map.of("returnTo",
                "/accounts")).problemCode()).isEqualTo("SYSTEM_KIND_INVALID");
        assertThat(bench.member().put(bench.path("/me/accounts/" + slack.systemId() + "/token"), Map.of("token",
                "xoxp-test-pasted")).problemCode()).isEqualTo("SYSTEM_KIND_INVALID");
        // The bot's credential is signed in to nothing: it changes with the bot (C9).
        assertThat(bench.owner().post(systemPath + "/credentials/" + botAccount(bench, slack) + "/oauth",
                Map.of("returnTo", "/systems")).problemCode()).isEqualTo("BOT_ACCOUNT_IN_USE");

        // The member, linked by the sign-in link: the LINK is no account to act with.
        String user = workspace.member("Max Member");
        linkSlack(bench, slack, bench.member(), user);
        UUID linkId = account(bench, slack.systemId(), bench.memberPrincipal()).id();
        assertThat(myAccount(bench.member(), bench, slack.systemId()).get("binding").asString()).isEqualTo("CONNECT");
        assertThat(myAccount(bench.member(), bench, slack.systemId()).get("oauthAvailable").asBoolean()).isTrue();

        // The sign-in: Slack's OAuth v2 asks for the user scopes only, never a bot scope.
        ApiResponse started = bench.member().post(bench.path("/me/accounts/" + slack.systemId() + "/oauth"),
                Map.of("returnTo", "/accounts"));
        assertThat(started.status()).as("%s", started).isEqualTo(200);
        URI authorize = URI.create(started.json().get("authorizationUrl").asString());
        assertThat(authorize.getPath()).isEqualTo("/oauth/v2/authorize");
        Map<String, String> query = OAuthStubs.query(authorize.getRawQuery());
        assertThat(query).containsEntry("user_scope", "channels:history,groups:history")
            .containsEntry("client_id", CLIENT_ID)
            .doesNotContainKey("scope");
        String token = "xoxp-test-" + UUID.randomUUID();
        String code = "test-slack-code-" + UUID.randomUUID();
        workspace.userSignIn(code, user, token);
        ApiResponse callback = bench.member().get("/api/oauth/callback?code=" + code + "&state=" + query.get("state"));
        assertThat(callback.status()).as("%s", callback).isEqualTo(303);
        assertThat(callback.header("Location")).startsWith("/accounts?account=connected");
        assertThat(FakeSlack.Workspace.codeExchanges(code)).singleElement()
            .satisfies(form -> assertThat(form).contains("client_secret=" + CLIENT_SECRET, "client_id="));

        // The LINK became the OAUTH account: the same row and Slack user, still the member's link.
        Row replaced = account(bench, slack.systemId(), bench.memberPrincipal());
        assertThat(replaced.id()).isEqualTo(linkId);
        assertThat(replaced.kind()).isEqualTo("OAUTH");
        assertThat(replaced.externalId()).isEqualTo(user);
        assertThat(myAccount(bench.member(), bench, slack.systemId()).get("binding").asString()).isEqualTo("OWN");
        assertThat(auditEvents(bench.owner(), bench.company(), "account.reconnected"))
            .anySatisfy(event -> assertThat(event.toString()).contains(linkId.toString()).contains("OAUTH"));
        // Their direct messages are still theirs: the next one waits for no link (they use no agent yet).
        Map<String, Object> message = workspace.directMessage(user, "still me", null);
        workspace.deliver(port, slack.systemId(), message);
        assertThat(awaitEvent(bench.id(), slack.systemId(), (String) message.get("event_id"), "REFUSED"))
            .containsEntry("reason", "NO_AGENT_CHOSEN");

        // The admin, linked to another Slack user: a sign-in as a third is refused, as the member's user too.
        String adminUser = workspace.member("Ada Admin");
        linkSlack(bench, slack, bench.admin(), adminUser);
        String third = workspace.member("Theo Third");
        assertThat(signIn(bench.admin(), bench, slack, third)).startsWith("/accounts?account=already_linked");
        assertThat(signIn(bench.admin(), bench, slack, user)).startsWith("/accounts?account=identity_in_use");
        Row adminLink = account(bench, slack.systemId(), bench.adminPrincipal());
        assertThat(adminLink.kind()).isEqualTo("LINK");
        assertThat(adminLink.externalId()).isEqualTo(adminUser);

        // The owner, never linked: their sign-in links them, told in their inbox with its unlink.
        String ownerUser = workspace.member("Olga Owner");
        assertThat(signIn(bench.owner(), bench, slack, ownerUser)).startsWith("/accounts?account=connected");
        assertThat(account(bench, slack.systemId(), bench.ownerPrincipal()).kind()).isEqualTo("OAUTH");
        await().atMost(WAIT).until(() -> bench.owner().get(bench.path("/inbox")).json().toString()
            .contains("CHAT_LINKED"));
        assertThat(bench.owner().get(bench.path("/inbox")).json().toString()).contains(ownerUser);

        // A Slack credential of the system, for history: no link.
        String serviceUser = workspace.member("Reader Service");
        ApiResponse serviceStart = bench.owner().post(systemPath + "/credentials/oauth", Map.of("name",
                "Slack reader", "returnTo", "/systems"));
        assertThat(serviceStart.status()).as("%s", serviceStart).isEqualTo(200);
        String serviceState = OAuthStubs.query(URI.create(serviceStart.json().get("authorizationUrl").asString())
            .getRawQuery()).get("state");
        String serviceCode = "test-slack-code-" + UUID.randomUUID();
        workspace.userSignIn(serviceCode, serviceUser, "xoxp-test-" + UUID.randomUUID());
        assertThat(bench.owner().get("/api/oauth/callback?code=" + serviceCode + "&state=" + serviceState)
            .header("Location")).startsWith("/systems?account=connected");
        assertThat(rows(bench.id(), "SELECT kind FROM account WHERE system_id = ? AND name = ?", slack.systemId(),
                "Slack reader")).singleElement().satisfies(row -> assertThat(row.get("kind")).isEqualTo("OAUTH"));

        // A refused code: nothing changes.
        String refusedCode = "test-slack-code-" + UUID.randomUUID();
        workspace.refuseCode(refusedCode, "invalid_code");
        ApiResponse again = bench.member().post(bench.path("/me/accounts/" + slack.systemId() + "/oauth"),
                Map.of("returnTo", "/accounts"));
        String againState = OAuthStubs.query(URI.create(again.json().get("authorizationUrl").asString())
            .getRawQuery()).get("state");
        assertThat(bench.member().get("/api/oauth/callback?code=" + refusedCode + "&state=" + againState)
            .header("Location")).startsWith("/accounts?account=refused");
        assertThat(account(bench, slack.systemId(), bench.memberPrincipal()).kind()).isEqualTo("OAUTH");

        // The Slack app's client secret refused: the app's refusal, not the account's, and the audit names Slack's
        // own error. Nothing changes.
        String appCode = "test-slack-code-" + UUID.randomUUID();
        workspace.refuseCode(appCode, "bad_client_secret");
        ApiResponse appStart = bench.member().post(bench.path("/me/accounts/" + slack.systemId() + "/oauth"),
                Map.of("returnTo", "/accounts"));
        String appState = OAuthStubs.query(URI.create(appStart.json().get("authorizationUrl").asString())
            .getRawQuery()).get("state");
        assertThat(bench.member().get("/api/oauth/callback?code=" + appCode + "&state=" + appState)
            .header("Location")).startsWith("/accounts?account=app_refused");
        assertThat(rows(bench.id(), "SELECT details ->> 'reason' AS reason FROM audit_event WHERE company_id = ? "
                + "AND action = 'account.refused' ORDER BY seq DESC LIMIT 1", bench.id()))
            .singleElement()
            .satisfies(row -> assertThat(row.get("reason")).isEqualTo("The token endpoint answered 200 "
                    + "(invalid_client, Slack's bad_client_secret)"));
        assertThat(account(bench, slack.systemId(), bench.memberPrincipal()).kind()).isEqualTo("OAUTH");
    }

    @Test
    void theAgentReadsSlackWithThePersonsOwnSlackAccountAndALinkWaitsForTheirSignIn() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 2)) {
            Slack slack = slack(bench);
            FakeSlack.Workspace workspace = slack.workspace();
            ApiResponse app = bench.owner().put(bench.path("/systems/" + slack.systemId() + "/app"),
                    Map.of("clientId", CLIENT_ID, "clientSecret", CLIENT_SECRET));
            assertThat(app.status()).as("%s", app).isEqualTo(200);
            String handle = bench.owner().get(bench.path("/systems/" + slack.systemId())).json().get("handle")
                .asString();

            // Slack's fixed catalogue: history, a read; an agent lists Slack, never Teams.
            JsonNode tools = bench.member().get(bench.path("/systems/" + slack.systemId() + "/tools")).json();
            assertThat(tools.toString()).contains("\"history\"", "READ").doesNotContain("\"post\"");
            Teams teams = teams(bench);
            assertThat(bench.member().post(bench.path("/agents"), agentBody(teams.systemId())).problemCode())
                .isEqualTo("SYSTEM_KIND_INVALID");
            ApiResponse created = bench.member().post(bench.path("/agents"), agentBody(slack.systemId()));
            assertThat(created.status()).as("%s", created).isEqualTo(201);
            UUID agentId = UUID.fromString(created.json().get("id").asString());
            TestSettings.nameModel(bench.id(), agentId, bench.memberPrincipal());

            String user = workspace.member("Max Member");
            String other = workspace.member("Olga Other");
            linkSlack(bench, slack, bench.member(), user);
            String channel = workspace.channel("general", false);
            String token = "xoxp-test-" + UUID.randomUUID();
            String older = FakeSlack.ts();
            String newer = FakeSlack.ts();
            workspace.history(token, channel, List.of(FakeSlack.Workspace.message(other, newer, "The deploy is done"),
                    FakeSlack.Workspace.message(user, older, "Is the deploy done?")), "dXNlcjpVMDYxTkZUVDI=");

            ApiResponse started = bench.member().post(bench.path("/conversations"), Map.of("agentId",
                    agentId.toString(), "message", "What was said in #general?"));
            assertThat(started.status()).as("%s", started).isEqualTo(201);
            JsonNode assign = runner.expect("run.assign");
            List<String> names = new ArrayList<>();
            assign.get("tools").forEach(tool -> names.add(tool.get("name").asString()));
            assertThat(names).containsExactly(handle + "_history");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            runner.accepted(runId, epoch);

            // The member's LINK acts with nothing: the call waits for their Slack account.
            JsonNode parked = runner.requestAndAck(runId, epoch, "TOOL", Map.of("tool", handle + "_history",
                    "arguments", Map.of("channel", channel, "limit", 2)));
            assertThat(parked.get("status").asString()).as("%s", parked).isEqualTo("PENDING");
            // They sign in to Slack: the parked call runs with their user token, never the bot's.
            assertThat(signIn(bench.member(), bench, slack, user, token)).startsWith("/accounts?account=connected");
            await().atMost(WAIT).until(() -> settledCalls(bench, started.json().get("id").asString()) == 1);
            assertThat(FakeSlack.Workspace.countWith("conversations.history", token)).isEqualTo(1);
            assertThat(FakeSlack.Workspace.countWith("conversations.history", workspace.botToken)).isZero();

            // A call now runs at once: the channel's messages, oldest first, with the next page's cursor.
            JsonNode read = runner.requestAndAck(runId, epoch, "TOOL", Map.of("tool", handle + "_history",
                    "arguments", Map.of("channel", channel, "limit", 2)));
            assertThat(read.get("status").asString()).as("%s", read).isEqualTo("DONE");
            String text = read.get("payload").get("content").get(0).get("text").asString();
            assertThat(text).contains("<@" + user + ">", "Is the deploy done?", "<@" + other + ">",
                "The deploy is done", "dXNlcjpVMDYxTkZUVDI=");
            assertThat(text.indexOf("Is the deploy done?")).isLessThan(text.indexOf("The deploy is done"));
            JsonNode structured = read.get("payload").get("structuredContent");
            assertThat(structured.get("count").asInt()).isEqualTo(2);
            assertThat(structured.get("nextCursor").asString()).isEqualTo("dXNlcjpVMDYxTkZUVDI=");

            // A thread: its messages from its start.
            String reply = FakeSlack.ts();
            workspace.replies(token, channel, older, List.of(FakeSlack.Workspace.message(user, older,
                    "Is the deploy done?"), FakeSlack.Workspace.message(other, reply, "Yes, at noon")));
            JsonNode thread = runner.requestAndAck(runId, epoch, "TOOL", Map.of("tool", handle + "_history",
                    "arguments", Map.of("channel", channel, "thread", older)));
            assertThat(thread.get("status").asString()).as("%s", thread).isEqualTo("DONE");
            assertThat(thread.get("payload").get("content").get(0).get("text").asString())
                .contains("The thread " + older, "Yes, at noon");

            // Arguments out of shape are refused before Slack is called.
            JsonNode invalid = runner.requestAndAck(runId, epoch, "TOOL", Map.of("tool", handle + "_history",
                    "arguments", Map.of("channel", "#general")));
            assertThat(invalid.get("status").asString()).as("%s", invalid).isEqualTo("REJECTED");
            assertThat(invalid.toString()).contains("INVALID_REQUEST");

            // Slack refuses the token: the account must be connected again.
            workspace.refuseHistory(token);
            runner.requestAndAck(runId, epoch, "TOOL", Map.of("tool", handle + "_history", "arguments",
                    Map.of("channel", channel)));
            await().atMost(WAIT).until(() -> "RECONNECT".equals(account(bench, slack.systemId(),
                    bench.memberPrincipal()).status()));
            runner.assertFramesValid();
        }
    }

    @Test
    void aChannelTriggersRowOnItsSlackSystemReadsTheThreadAndNeverNamesTheBotOrALink() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 2)) {
            Slack slack = slack(bench);
            FakeSlack.Workspace workspace = slack.workspace();
            assertThat(bench.owner().put(bench.path("/systems/" + slack.systemId() + "/app"), Map.of("clientId",
                    CLIENT_ID, "clientSecret", CLIENT_SECRET)).status()).isEqualTo(200);
            String handle = bench.owner().get(bench.path("/systems/" + slack.systemId())).json().get("handle")
                .asString();
            ApiResponse created = bench.member().post(bench.path("/agents"), agentBody(slack.systemId()));
            assertThat(created.status()).as("%s", created).isEqualTo(201);
            JsonNode agent = created.json();
            TestSettings.nameModel(bench.id(), UUID.fromString(agent.get("id").asString()), bench.memberPrincipal());
            String support = workspace.channel("support", false);
            String author = workspace.member("Max Member");
            String adminUser = workspace.member("Ada Admin");
            String asker = workspace.member("Ann Asker");
            linkSlack(bench, slack, bench.member(), author);
            linkSlack(bench, slack, bench.admin(), adminUser);
            workspace.inChannels(author, support);
            bench.member().get(bench.path("/systems/" + slack.systemId() + "/chat/channels"));

            // The bot's account is never in a trigger's table (C9); a link acts with nothing.
            UUID botAccount = botAccount(bench, slack);
            ApiResponse withBot = trigger(bench, agent, support, botAccount, slack.systemId());
            assertThat(withBot.problemCode()).as("%s", withBot).isEqualTo("BOT_ACCOUNT");
            UUID memberLink = account(bench, slack.systemId(), bench.memberPrincipal()).id();
            ApiResponse withLink = trigger(bench, agent, support, memberLink, slack.systemId());
            assertThat(withLink.problemCode()).as("%s", withLink).isEqualTo("ACCOUNT_NOT_ENTITLED");
            assertThat(withLink.json().toString()).contains("ACCOUNT_NOT_USABLE");

            // The author's own Slack account, signed in: the trigger's row on its chat system.
            String token = "xoxp-test-" + UUID.randomUUID();
            assertThat(signIn(bench.member(), bench, slack, author, token)).startsWith("/accounts?account=connected");
            ApiResponse made = trigger(bench, agent, support, memberLink, slack.systemId());
            assertThat(made.status()).as("%s", made).isEqualTo(201);

            // A mention: the run lists history, and the prompt names the channel and thread to read.
            Map<String, Object> mention = workspace.mention(asker, support, "Summarise this thread", null);
            assertThat(workspace.deliver(port, slack.systemId(), mention).status()).isEqualTo(200);
            String thread = FakeSlack.Workspace.tsOf(mention);
            JsonNode assign = runner.expect("run.assign");
            List<String> names = new ArrayList<>();
            assign.get("tools").forEach(tool -> names.add(tool.get("name").asString()));
            assertThat(names).containsExactly(handle + "_history");
            assertThat(assign.get("prompt").asString()).contains("A mention in #support (Slack channel " + support
                    + ", thread " + thread + ")");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            runner.accepted(runId, epoch);
            workspace.replies(token, support, thread, List.of(FakeSlack.Workspace.message(asker, thread,
                    "Summarise this thread"), FakeSlack.Workspace.message(adminUser, FakeSlack.ts(), "Context here")));
            JsonNode read = runner.requestAndAck(runId, epoch, "TOOL", Map.of("tool", handle + "_history",
                    "arguments", Map.of("channel", support, "thread", thread)));
            assertThat(read.get("status").asString()).as("%s", read).isEqualTo("DONE");
            assertThat(read.get("payload").get("content").get(0).get("text").asString()).contains("Context here");
            // With the table's account, never the bot's token.
            assertThat(FakeSlack.Workspace.countWith("conversations.replies", token)).isEqualTo(1);
            assertThat(FakeSlack.Workspace.countWith("conversations.replies", workspace.botToken)).isZero();

            // Anyone in #support may ask: another channel, the channel's own messages or another of its threads,
            // though the author's account reads them, are refused before Slack is called.
            String finance = workspace.channel("finance", false);
            workspace.history(token, finance, List.of(FakeSlack.Workspace.message(author, FakeSlack.ts(),
                    "Q3 numbers")), null);
            workspace.history(token, support, List.of(FakeSlack.Workspace.message(author, FakeSlack.ts(),
                    "Another topic")), null);
            for (Map<String, Object> elsewhere : List.of(Map.<String, Object>of("channel", finance),
                    Map.<String, Object>of("channel", support),
                    Map.<String, Object>of("channel", support, "thread", FakeSlack.ts()))) {
                JsonNode outside = runner.requestAndAck(runId, epoch, "TOOL", Map.of("tool", handle + "_history",
                        "arguments", elsewhere));
                assertThat(outside.get("status").asString()).as("%s", outside).isEqualTo("REJECTED");
                assertThat(outside.toString()).contains("TOOL_DENIED", "reads only the thread you were asked in, "
                        + thread + " of " + support).doesNotContain("Q3 numbers", "Another topic");
            }
            assertThat(FakeSlack.Workspace.countWith("conversations.history", token)).isZero();
            assertThat(FakeSlack.Workspace.countWith("conversations.replies", token)).isEqualTo(1);
            runner.assertFramesValid();
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    /** An account row as the company sees it. */
    private record Row(UUID id, String kind, String externalId, String status) {
    }

    private static Row account(Bench bench, UUID systemId, UUID principalId) {
        Map<String, Object> row = rows(bench.id(), "SELECT id, kind, external_id, status FROM account"
                + " WHERE system_id = ? AND principal_id = ?", systemId, principalId).getFirst();
        return new Row((UUID) row.get("id"), (String) row.get("kind"), (String) row.get("external_id"),
                (String) row.get("status"));
    }

    /** The Slack system's bot, a credential of the system (ADR-0072). */
    private static UUID botAccount(Bench bench, Slack slack) {
        return UUID.fromString(bench.owner().get(bench.path("/systems/" + slack.systemId())).json().get("chat")
            .get("botCredentialId").asString());
    }

    private static JsonNode myAccount(ApiClient client, Bench bench, UUID systemId) {
        for (JsonNode item : client.get(bench.path("/me/accounts")).json().get("items")) {
            if (systemId.toString().equals(item.get("system").get("id").asString())) {
                return item;
            }
        }
        throw new AssertionError("No system " + systemId + " in the caller's accounts");
    }

    /** A sign-in to Slack as the user, with a token of its own; answers where the callback sends the browser. */
    private String signIn(ApiClient client, Bench bench, Slack slack, String userId) {
        return signIn(client, bench, slack, userId, "xoxp-test-" + UUID.randomUUID());
    }

    private String signIn(ApiClient client, Bench bench, Slack slack, String userId, String token) {
        String state = OAuthStubs.startSignIn(client, bench.path("/me/accounts/" + slack.systemId()), "/accounts");
        String code = "test-slack-code-" + UUID.randomUUID();
        slack.workspace().userSignIn(code, userId, token);
        ApiResponse callback = client.get("/api/oauth/callback?code=" + code + "&state=" + state);
        assertThat(callback.status()).as("%s", callback).isEqualTo(303);
        return callback.header("Location");
    }

    /** An agent that lists the system with its tools, history allowed. */
    private static Map<String, Object> agentBody(UUID systemId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Reader"));
        body.put("runtimes", List.of("CLAUDE_CODE", "CODEX"));
        body.put("instructions", "Read the channel.");
        body.put("systems", List.of(Map.of("systemId", systemId.toString(), "tools", List.of(Map.of("name",
                "history", "mode", "ALLOW")))));
        body.put("repositories", List.of());
        body.put("shareGroupIds", List.of());
        return body;
    }

    /** The member's channel trigger on the channel, its table's row on the Slack system naming the account. */
    private static ApiResponse trigger(Bench bench, JsonNode agent, String channel, UUID accountId, UUID systemId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Desk"));
        body.put("source", "CHANNEL");
        body.put("systemId", systemId.toString());
        body.put("channels", List.of(Map.of("channelId", channel, "default", true)));
        body.put("agentId", agent.get("id").asString());
        body.put("agentVersion", agent.get("version").asInt());
        body.put("instruction", "Answer in the thread.");
        body.put("accounts", List.of(Map.of("systemId", systemId.toString(), "accountId", accountId.toString())));
        body.put("payerPrincipalId", bench.memberPrincipal().toString());
        body.put("acknowledgeOpenEvents", true);
        return bench.member().post(bench.path("/triggers"), withModel(body));
    }

    /** The calls of the conversation's bindings that ran or were refused. */
    private static long settledCalls(Bench bench, String conversationId) throws SQLException {
        try (TenantSql sql = TenantSql.in(bench.id())) {
            return sql.count("""
                    SELECT count(*) FROM conversation_binding b, jsonb_array_elements(b.waiting_calls) AS call
                     WHERE b.conversation_id = ? AND call ->> 'status' IN ('DONE', 'REFUSED')""",
                    UUID.fromString(conversationId));
        }
    }
}
