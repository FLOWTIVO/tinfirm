package com.flowtivo.tinfirm.chat.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.chat.ChatTestBench;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeSlack;
import com.flowtivo.tinfirm.support.SlackSocketStub;
import com.flowtivo.tinfirm.system.ChatSystems;

import tools.jackson.databind.JsonNode;

/**
 * Slack's Socket Mode and the silent subscription (the package 5 contract, §5.1, §5.2, open point 1): the
 * worker's connections open with the app-level token, an envelope is acknowledged once its event is stored and acts
 * once when redelivered, a {@code disconnect} opens a new connection first, a dropped connection comes back, a refused
 * token turns the system {@code ERROR}, a message longer than the limit is dropped unacknowledged; a Slack system
 * answered {@code 503} and silent since turns {@code ERROR} with an item for admins, and its next event clears both.
 */
class SlackSocketModeTest extends ChatTestBench {

    @Autowired
    SlackSockets sockets;

    @Autowired
    ChatStore store;

    @Autowired
    ChatSystems chatSystems;

    @Autowired
    SystemTransactions transactions;

    @Autowired
    TimeSource time;

    @Autowired
    TinfirmProperties tinfirm;

    @Test
    void receivesOverTheServersOwnConnectionsAndAcknowledgesOnceStored() {
        try (SlackSocketStub stub = new SlackSocketStub()) {
            Bench bench = bench(false);
            Slack slack = slack(bench, "SOCKET_MODE");
            FakeSlack.Workspace workspace = slack.workspace();
            workspace.socketMode(stub);
            await().atMost(WAIT).until(() -> stub.open(workspace.teamId).size() == 2);
            assertThat(sockets.connected(slack.systemId())).isTrue();

            // An event, acknowledged once stored; redelivered, acknowledged again and stored once.
            String user = workspace.member("Sol");
            Map<String, Object> envelope = workspace.directMessage(user, "over the socket", null);
            SlackSocketStub.Connection connection = stub.open(workspace.teamId).getFirst();
            connection.send(Map.of("type", "events_api", "envelope_id", "env-1", "payload", envelope,
                    "accepts_response_payload", false, "retry_attempt", 0, "retry_reason", ""));
            await().atMost(WAIT).until(() -> connection.acknowledged().contains("env-1"));
            String eventId = (String) envelope.get("event_id");
            assertThat(events(bench.id(), slack.systemId())).filteredOn(row -> eventId.equals(row.get("event_id")))
                .hasSize(1);
            connection.send(Map.of("type", "events_api", "envelope_id", "env-2", "payload", envelope,
                    "retry_attempt", 1, "retry_reason", "timeout"));
            await().atMost(WAIT).until(() -> connection.acknowledged().contains("env-2"));
            assertThat(events(bench.id(), slack.systemId())).filteredOn(row -> eventId.equals(row.get("event_id")))
                .hasSize(1);
            awaitEvent(bench.id(), slack.systemId(), eventId, "AWAITING_LINK");

            // Another workspace's event is acknowledged and stored nowhere (C2).
            Map<String, Object> foreign = workspace.envelopeOf("TFOREIGN", Map.of("type", "message", "channel",
                    "DX", "channel_type", "im", "user", "UX", "text", "hi", "ts", FakeSlack.ts()));
            connection.send(Map.of("type", "events_api", "envelope_id", "env-3", "payload", foreign));
            await().atMost(WAIT).until(() -> connection.acknowledged().contains("env-3"));
            assertThat(events(bench.id(), slack.systemId())).hasSize(1);

            // Slack refreshes a connection: a new one opens before the old one closes.
            int before = stub.connections(workspace.teamId).size();
            connection.send(Map.of("type", "disconnect", "reason", "refresh_requested"));
            await().atMost(WAIT).until(() -> stub.connections(workspace.teamId).size() == before + 1
                    && !connection.open());
            await().atMost(WAIT).until(() -> stub.open(workspace.teamId).size() == 2);

            // A dropped connection comes back.
            stub.open(workspace.teamId).getFirst().drop();
            await().atMost(WAIT).until(() -> stub.connections(workspace.teamId).size() == before + 2);
            await().atMost(WAIT).until(() -> stub.open(workspace.teamId).size() == 2);

            // Switched to the Events API: the connections close.
            assertThat(bench.owner().patch(bench.path("/systems/" + slack.systemId()), Map.of("slack",
                    Map.of("transport", "EVENTS_API"))).status()).isEqualTo(200);
            await().atMost(WAIT).until(() -> stub.open(workspace.teamId).isEmpty());
            assertThat(sockets.connected(slack.systemId())).isFalse();
        }
    }

    @Test
    void aMessageLongerThanTheLimitIsDroppedUnacknowledgedAndTheConnectionCarriesOn() {
        try (SlackSocketStub stub = new SlackSocketStub()) {
            Bench bench = bench(false);
            Slack slack = slack(bench, "SOCKET_MODE");
            FakeSlack.Workspace workspace = slack.workspace();
            workspace.socketMode(stub);
            await().atMost(WAIT).until(() -> stub.open(workspace.teamId).size() == 2);
            SlackSocketStub.Connection connection = stub.open(workspace.teamId).getFirst();
            String user = workspace.member("Max");

            // Longer than tinfirm.chat.max-body (1 MB), in one frame and in several.
            Map<String, Object> tooLong = workspace.directMessage(user, "too long", null);
            connection.send(Map.of("type", "events_api", "envelope_id", "env-long", "payload", tooLong, "padding",
                    "x".repeat(1_100_000)));
            connection.send(Map.of("type", "events_api", "envelope_id", "env-long-frames", "payload", tooLong,
                    "padding", "x".repeat(1_100_000)), 9);
            // A long message under the limit, in several frames, is put together.
            Map<String, Object> framed = workspace.directMessage(user, "in frames", null);
            connection.send(Map.of("type", "events_api", "envelope_id", "env-frames", "payload", framed, "padding",
                    "x".repeat(500_000)), 7);

            await().atMost(WAIT).until(() -> connection.acknowledged().contains("env-frames"));
            assertThat(connection.acknowledged()).doesNotContain("env-long", "env-long-frames");
            assertThat(event(bench.id(), slack.systemId(), (String) tooLong.get("event_id"))).isEmpty();
            awaitEvent(bench.id(), slack.systemId(), (String) framed.get("event_id"), "AWAITING_LINK");
            assertThat(connection.open()).isTrue();
            assertThat(sockets.connected(slack.systemId())).isTrue();

            // The system's test opens a connection of its own, which puts no message together.
            ApiResponse tested = bench.owner().post(bench.path("/systems/" + slack.systemId() + "/test"), Map.of());
            assertThat(tested.status()).as("%s", tested).isEqualTo(200);
            assertThat(tested.json().get("message").asString()).contains("a Socket Mode connection opens");
        }
    }

    @Test
    void turnsTheSystemErrorWhenSlackRefusesTheAppLevelToken() {
        try (SlackSocketStub stub = new SlackSocketStub()) {
            Bench bench = bench(false);
            Slack slack = slack(bench, "SOCKET_MODE");
            slack.workspace().refuseAppToken();
            await().atMost(WAIT).until(() -> "ERROR".equals(bench.owner().get(bench.path("/systems/"
                    + slack.systemId())).json().get("status").asString()));
            assertThat(stub.connections(slack.workspace().teamId)).isEmpty();
        }
    }

    @Test
    void aSilentSubscriptionTurnsTheSystemErrorAndItsNextEventClearsIt() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        // A watch that waits no time: this replica answered 503, and nothing arrived since.
        ChatSilence silence = new ChatSilence(store, chatSystems, new ChatProperties(Duration.ofSeconds(1),
                Duration.ZERO, org.springframework.util.unit.DataSize.ofMegabytes(1), 20, 200, 600,
                Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofSeconds(5), 2, Duration.ofSeconds(30)),
                transactions, time, tinfirm);
        silence.failed(bench.id(), slack.systemId());
        silence.watch();
        JsonNode system = bench.owner().get(bench.path("/systems/" + slack.systemId())).json();
        assertThat(system.get("status").asString()).isEqualTo("ERROR");
        assertThat(system.get("lastError").asString()).contains("re-enable");
        assertThat(bench.admin().get(bench.path("/inbox")).json().toString()).contains("CHAT_SUBSCRIPTION_SILENT");
        assertThat(bench.member().get(bench.path("/inbox")).json().toString())
            .doesNotContain("CHAT_SUBSCRIPTION_SILENT");

        FakeSlack.Workspace workspace = slack.workspace();
        workspace.deliver(port, slack.systemId(), workspace.directMessage(workspace.member("Back"), "hi", null));
        silence.clear();
        assertThat(bench.owner().get(bench.path("/systems/" + slack.systemId())).json().get("status").asString())
            .isEqualTo("OK");
        assertThat(bench.admin().get(bench.path("/inbox")).json().toString())
            .doesNotContain("CHAT_SUBSCRIPTION_SILENT");
    }
}
