package com.flowtivo.tinfirm.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.FakeSlack;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Package 6's kill switches in the chats (the package 6 contract, §5.4, G4 to G6; CONVENTIONS §15.16), end to end
 * with the Slack stub and the fake runner host: the company's stop starts no conversation from a direct message, nor
 * from the picker, and takes a message to an open one, whose turn waits for the lift, the chat user told at most once an
 * hour; a suspended member's direct messages are refused as a non-member's, told once an hour and never offered a
 * sign-in link; a paused chat system's events are dropped {@code SYSTEM_PAUSED}, its bot posts nothing and cannot be
 * set, and the answers held meanwhile are posted once it is resumed.
 */
class ChatKillSwitchesTest extends ChatTestBench {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String STOPPED = "Tinfirm is stopped in";

    @Test
    void theCompanysStopStartsNothingFromAChatAndTakesAMessageToAnOpenConversation() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 1)) {
            String[] helper = agent(bench.member(), bench, "Stoppable", List.of());
            Slack slack = slack(bench);
            FakeSlack.Workspace workspace = slack.workspace();
            String user = workspace.member("Sam Stopped");
            String channel = "D" + user;
            // The linking message waits for the picker; a leading handle starts a conversation at once.
            String hello = linkSlack(bench, slack, bench.member(), user);
            awaitEvent(bench.id(), slack.systemId(), hello, "AWAITING_AGENT");
            await().atMost(WAIT).until(() -> said(workspace.posts(channel)).contains("Which agent should answer"));
            workspace.deliver(port, slack.systemId(), workspace.directMessage(user, "@" + helper[1] + " first", null));
            JsonNode assign = runner.expect("run.assign");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            runner.accepted(runId, epoch);
            runner.send(Map.of("type", "run.finished", "runId", runId.toString(), "epoch", epoch, "outcome",
                    "SUCCEEDED", "lastSeq", 0, "summary", "The first answer.", "sessionId", "session-stop", "git",
                    List.of()));
            await().atMost(WAIT).until(() -> said(workspace.posts(channel)).contains("The first answer."));
            String thread = workspace.posts(channel).stream()
                .filter(post -> post.toString().contains("The first answer."))
                .findFirst()
                .orElseThrow()
                .get("thread_ts")
                .asString();
            String conversationId = rows(bench.id(), "SELECT id FROM conversation WHERE place = 'CHAT_THREAD'")
                .getFirst()
                .get("id")
                .toString();

            ApiResponse stopped = bench.owner().post(bench.path("/runs-stop"), Map.of("reason", "Incident"));
            assertThat(stopped.status()).as("%s", stopped).isEqualTo(200);

            // The picker's press starts nothing: its held message is refused, and the chat user told.
            Map<String, Object> pick = DirectMessagesTest.buttons(workspace.posts(channel)).stream()
                .filter(value -> "pick".equals(value.get("a")) && !value.containsKey("d"))
                .findFirst()
                .orElseThrow();
            workspace.deliverPress(port, slack.systemId(), workspace.press(user, channel, FakeSlack.ts(), null,
                    JSON.writeValueAsString(pick)));
            assertThat(awaitEvent(bench.id(), slack.systemId(), hello, "REFUSED"))
                .containsEntry("reason", "COMPANY_STOPPED");
            await().atMost(WAIT).until(() -> said(workspace.posts(channel)).contains("nothing was started"));

            // A message to the open conversation is taken, and its turn waits for the lift; told once an hour.
            inCompany(bench.id(), "UPDATE chat_user SET stop_told_at = NULL WHERE system_id = ?", slack.systemId());
            Map<String, Object> waiting = workspace.directMessage(user, "And meanwhile?", thread);
            workspace.deliver(port, slack.systemId(), waiting);
            awaitEvent(bench.id(), slack.systemId(), (String) waiting.get("event_id"), "HANDLED");
            await().atMost(WAIT).until(() -> said(workspace.posts(channel)).contains("your message waits"));
            assertThat(bench.member().get(bench.path("/conversations/" + conversationId)).json().get("messages")
                .toString()).contains("And meanwhile?");

            // A new direct message starts nothing, and is not told again within the hour.
            Map<String, Object> fresh = workspace.directMessage(user, "@" + helper[1] + " another", null);
            workspace.deliver(port, slack.systemId(), fresh);
            assertThat(awaitEvent(bench.id(), slack.systemId(), (String) fresh.get("event_id"), "REFUSED"))
                .containsEntry("reason", "COMPANY_STOPPED");
            runner.expectNone("run.assign", Duration.ofSeconds(2));
            assertThat(rows(bench.id(), "SELECT id FROM conversation WHERE place = 'CHAT_THREAD'")).hasSize(1);
            assertThat(workspace.posts(channel)).filteredOn(post -> post.toString().contains(STOPPED)).hasSize(2);

            // The lift dispatches the waiting turn.
            ApiResponse lifted = bench.owner().delete(bench.path("/runs-stop"));
            assertThat(lifted.status()).as("%s", lifted).isEqualTo(200);
            JsonNode next = runner.expect("run.assign");
            assertThat(next.get("prompt").asString()).contains("And meanwhile?");
            runner.assertFramesValid();
        }
    }

    @Test
    void aSuspendedMembersDirectMessagesAreRefusedAndToldOnceAnHourWithoutASignInLink() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        String user = workspace.member("Sue Suspended");
        String channel = "D" + user;
        String hello = linkSlack(bench, slack, bench.member(), user);
        awaitEvent(bench.id(), slack.systemId(), hello, "REFUSED");
        int links = (int) workspace.posts(channel).stream()
            .filter(post -> post.toString().contains("Link my account"))
            .count();

        UUID memberUser = bench.company().userId("MEMBER");
        ApiResponse suspended = bench.owner().post(bench.path("/members/" + memberUser + "/suspend"),
                Map.of("reason", "On leave"));
        assertThat(suspended.status()).as("%s", suspended).isEqualTo(200);

        Map<String, Object> first = workspace.directMessage(user, "anyone there?", null);
        workspace.deliver(port, slack.systemId(), first);
        assertThat(awaitEvent(bench.id(), slack.systemId(), (String) first.get("event_id"), "REFUSED"))
            .containsEntry("reason", "SUSPENDED");
        await().atMost(WAIT).until(() -> said(workspace.posts(channel)).contains("Your Tinfirm account is suspended"));
        Map<String, Object> second = workspace.directMessage(user, "still there?", null);
        workspace.deliver(port, slack.systemId(), second);
        assertThat(awaitEvent(bench.id(), slack.systemId(), (String) second.get("event_id"), "REFUSED"))
            .containsEntry("reason", "SUSPENDED");
        assertThat(workspace.posts(channel)).filteredOn(post -> post.toString().contains("is suspended"))
            .hasSize(1);
        assertThat(workspace.posts(channel)).filteredOn(post -> post.toString().contains("Link my account"))
            .hasSize(links);
        assertThat(auditEvents(bench.owner(), bench.company(), "chat.refused"))
            .anySatisfy(event -> assertThat(event.toString()).contains(user).contains("SUSPENDED"));

        // Resumed, the member is served again.
        assertThat(bench.owner().post(bench.path("/members/" + memberUser + "/resume"), Map.of()).status())
            .isEqualTo(200);
        Map<String, Object> back = workspace.directMessage(user, "back again", null);
        workspace.deliver(port, slack.systemId(), back);
        assertThat(awaitEvent(bench.id(), slack.systemId(), (String) back.get("event_id"), "REFUSED"))
            .containsEntry("reason", "NO_AGENT_CHOSEN");
    }

    @Test
    void aPausedChatSystemDropsItsEventsAndHoldsItsAnswersUntilItIsResumed() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 1)) {
            String[] helper = agent(bench.member(), bench, "Pausable", List.of());
            Slack slack = slack(bench);
            FakeSlack.Workspace workspace = slack.workspace();
            String user = workspace.member("Pat Paused");
            String channel = "D" + user;
            linkSlack(bench, slack, bench.member(), user);
            await().atMost(WAIT).until(() -> said(workspace.posts(channel)).contains("Which agent should answer"));
            Map<String, Object> asked = workspace.directMessage(user, "@" + helper[1] + " the question", null);
            workspace.deliver(port, slack.systemId(), asked);
            JsonNode assign = runner.expect("run.assign");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            runner.accepted(runId, epoch);
            // What the start posts (the Owner notice, the taken message's reaction) is done before the pause.
            await().atMost(WAIT).until(() -> workspace.count("reactions.add") > 0);
            String thread = (String) ((Map<?, ?>) asked.get("event")).get("ts");

            ApiResponse paused = bench.owner().post(bench.path("/systems/" + slack.systemId() + "/pause"),
                    Map.of("reason", "Unwanted traffic", "stopRunning", false));
            assertThat(paused.status()).as("%s", paused).isEqualTo(200);
            int posted = workspace.count("chat.postMessage");
            int reacted = workspace.count("reactions.add");

            // The answer of the running turn is held: the bot posts nothing while the system is paused.
            runner.send(Map.of("type", "run.finished", "runId", runId.toString(), "epoch", epoch, "outcome",
                    "SUCCEEDED", "lastSeq", 0, "summary", "The held answer.", "sessionId", "session-pause", "git",
                    List.of()));
            // Its events are verified and dropped, and never answered later.
            Map<String, Object> dropped = workspace.directMessage(user, "Hello while paused", thread);
            assertThat(workspace.deliver(port, slack.systemId(), dropped).status()).isEqualTo(200);
            assertThat(awaitEvent(bench.id(), slack.systemId(), (String) dropped.get("event_id"), "IGNORED"))
                .containsEntry("reason", "SYSTEM_PAUSED");
            await().during(Duration.ofSeconds(2)).atMost(WAIT).until(() -> !said(workspace.posts(channel))
                .contains("The held answer."));
            assertThat(workspace.count("chat.postMessage")).isEqualTo(posted);
            assertThat(workspace.count("reactions.add")).isEqualTo(reacted);
            runner.expectNone("run.assign", Duration.ofSeconds(1));
            // Its bot is not set again while it is paused.
            ApiResponse bot = bench.owner().put(bench.path("/systems/" + slack.systemId() + "/bot"), Map.of(
                    "principalId", slack.botPrincipal().toString(), "token", workspace.botToken));
            assertThat(bot.status()).as("%s", bot).isEqualTo(409);
            assertThat(bot.problemCode()).isEqualTo("SYSTEM_PAUSED");

            // Resumed: the held answer is posted in its thread; the next message is served.
            ApiResponse resumed = bench.owner().post(bench.path("/systems/" + slack.systemId() + "/resume"),
                    Map.of());
            assertThat(resumed.status()).as("%s", resumed).isEqualTo(200);
            await().atMost(WAIT).until(() -> said(workspace.posts(channel)).contains("The held answer."));
            assertThat(said(workspace.posts(channel))).doesNotContain("Hello while paused");
            workspace.deliver(port, slack.systemId(), workspace.directMessage(user, "And now?", thread));
            assertThat(runner.expect("run.assign").get("prompt").asString()).contains("And now?")
                .doesNotContain("Hello while paused");
            runner.assertFramesValid();
        }
    }
}
