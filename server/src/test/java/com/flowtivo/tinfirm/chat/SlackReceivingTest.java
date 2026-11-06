package com.flowtivo.tinfirm.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeSlack;

/**
 * Receiving from Slack (the package 5 contract, §5.2, §5.3, C1 to C3, C6, C7, C15): the signature before anything,
 * the acknowledgement at once, one action per event, another workspace's events stored nowhere, what is never handled,
 * who is refused, and the company's rates.
 */
class SlackReceivingTest extends ChatTestBench {

    @Test
    void verifiesTheSignatureBeforeReadingAnything() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        Map<String, Object> challenge = Map.of("type", "url_verification", "token", "unused", "challenge",
                "test-challenge-value");

        FakeSlack.Delivery answered = workspace.deliver(port, slack.systemId(), challenge);
        assertThat(answered.status()).isEqualTo(200);
        assertThat(answered.body()).contains("test-challenge-value");

        FakeSlack.Delivery forged = workspace.deliver(port, slack.systemId(), challenge, "test-not-the-secret",
                Instant.now(), null);
        assertThat(forged.status()).isEqualTo(401);
        assertThat(forged.body()).isEmpty();
        FakeSlack.Delivery old = workspace.deliver(port, slack.systemId(), challenge, workspace.signingSecret,
                Instant.now().minusSeconds(301), null);
        assertThat(old.status()).isEqualTo(401);
        FakeSlack.Delivery early = workspace.deliver(port, slack.systemId(), challenge, workspace.signingSecret,
                Instant.now().plusSeconds(301), null);
        assertThat(early.status()).isEqualTo(401);
        FakeSlack.Delivery withinWindow = workspace.deliver(port, slack.systemId(), challenge,
                workspace.signingSecret, Instant.now().minusSeconds(240), null);
        assertThat(withinWindow.status()).isEqualTo(200);

        // A forged event stores nothing.
        Map<String, Object> message = workspace.directMessage(workspace.member("Forged"), "hello", null);
        assertThat(workspace.deliver(port, slack.systemId(), message, "test-forged", Instant.now(), null).status())
            .isEqualTo(401);
        assertThat(events(bench.id(), slack.systemId())).isEmpty();

        // An unknown system, a Teams system on Slack's path: 404.
        assertThat(workspace.deliver(port, UUID.randomUUID(), challenge).status()).isEqualTo(404);
        Teams teams = teams(bench);
        assertThat(workspace.deliver(port, teams.systemId(), challenge).status()).isEqualTo(404);

        // At most 1 MiB is read.
        byte[] large = ("{\"type\":\"url_verification\",\"challenge\":\"" + "x".repeat(1024 * 1024 + 10) + "\"}")
            .getBytes(StandardCharsets.UTF_8);
        assertThat(FakeSlack.Workspace.send(port, "/api/chat/slack/" + slack.systemId() + "/events", large,
                "application/json", workspace.signingSecret, Instant.now(), null).status()).isEqualTo(413);

        // A new signing secret works at once.
        ApiResponse rotated = bench.owner().put(bench.path("/systems/" + slack.systemId() + "/chat/secrets"),
                Map.of("signingSecret", "test-rotated-signing-secret"));
        assertThat(rotated.status()).isEqualTo(200);
        assertThat(workspace.deliver(port, slack.systemId(), challenge, "test-rotated-signing-secret", Instant.now(),
                null).status()).isEqualTo(200);
        assertThat(workspace.deliver(port, slack.systemId(), challenge).status()).isEqualTo(401);
    }

    @Test
    void acknowledgesAtOnceAndActsOnceOnARetry() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        String user = workspace.member("Slow Sam");
        workspace.slowUser(user, Duration.ofSeconds(4));
        Map<String, Object> message = workspace.directMessage(user, "hello", null);
        String eventId = (String) message.get("event_id");

        FakeSlack.Delivery first = workspace.deliver(port, slack.systemId(), message);
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.took()).isLessThan(Duration.ofSeconds(3));
        FakeSlack.Delivery retry = workspace.deliver(port, slack.systemId(), message, workspace.signingSecret,
                Instant.now(), 1);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.took()).isLessThan(Duration.ofSeconds(3));
        FakeSlack.Delivery again = workspace.deliver(port, slack.systemId(), message, workspace.signingSecret,
                Instant.now(), 2);
        assertThat(again.status()).isEqualTo(200);

        assertThat(events(bench.id(), slack.systemId())).filteredOn(row -> eventId.equals(row.get("event_id")))
            .hasSize(1);
        // Handled once: one sign-in link for the unlinked member, whatever the retries.
        awaitEvent(bench.id(), slack.systemId(), eventId, "AWAITING_LINK");
        // The link is posted after the commit that holds the event: wait for it, and for no second one to follow.
        await().during(Duration.ofSeconds(1))
            .atMost(Duration.ofSeconds(10))
            .until(() -> workspace.posts("D" + user).size() == 1);
    }

    @Test
    void dropsAnotherWorkspacesEventsWithoutARetry() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "message");
        event.put("channel", "DOTHER");
        event.put("channel_type", "im");
        event.put("user", "UOTHER");
        event.put("text", "hello");
        event.put("ts", FakeSlack.ts());
        FakeSlack.Delivery delivery = workspace.deliver(port, slack.systemId(), workspace.envelopeOf("TANOTHER",
                event));
        assertThat(delivery.status()).isEqualTo(200);
        assertThat(delivery.noRetry()).isEqualTo("1");
        assertThat(events(bench.id(), slack.systemId())).isEmpty();
    }

    @Test
    void ignoresBotsEditsAndChannelMessagesThatDoNotMentionTheBot() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        String user = workspace.member("Ivy");

        Map<String, Object> fromBot = workspace.directMessage(user, "beep", null);
        event(fromBot).put("bot_id", "B123");
        Map<String, Object> edited = workspace.directMessage(user, "edited", null);
        event(edited).put("subtype", "message_changed");
        Map<String, Object> joined = workspace.directMessage(user, "joined", null);
        event(joined).put("subtype", "channel_join");
        Map<String, Object> inChannel = workspace.directMessage(user, "no mention", null);
        event(inChannel).put("channel_type", "channel");
        event(inChannel).put("channel", "C123");
        for (Map<String, Object> envelope : List.of(fromBot, edited, joined, inChannel)) {
            assertThat(workspace.deliver(port, slack.systemId(), envelope).status()).isEqualTo(200);
        }
        assertThat(event(bench.id(), slack.systemId(), (String) fromBot.get("event_id")).orElseThrow())
            .containsEntry("status", "IGNORED").containsEntry("reason", "BOT");
        assertThat(event(bench.id(), slack.systemId(), (String) edited.get("event_id")).orElseThrow())
            .containsEntry("status", "IGNORED").containsEntry("reason", "EDITED");
        assertThat(event(bench.id(), slack.systemId(), (String) joined.get("event_id")).orElseThrow())
            .containsEntry("status", "IGNORED").containsEntry("reason", "NOT_ADDRESSED");
        assertThat(event(bench.id(), slack.systemId(), (String) inChannel.get("event_id")).orElseThrow())
            .containsEntry("status", "IGNORED").containsEntry("reason", "NOT_ADDRESSED");
        assertThat(workspace.posts()).isEmpty();
    }

    @Test
    void refusesGuestsAndOtherOrganisationsAndTellsThemOnceAnHour() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        String guest = workspace.user("Guest Gil", workspace.teamId, true, false, false);
        String stranger = workspace.user("Stranger Sue", "TSTRANGER", false, true, false);

        Map<String, Object> first = workspace.directMessage(guest, "hello", null);
        workspace.deliver(port, slack.systemId(), first);
        Map<String, Object> refused = awaitEvent(bench.id(), slack.systemId(), (String) first.get("event_id"),
                "REFUSED");
        assertThat(refused).containsEntry("reason", "GUEST");
        Map<String, Object> second = workspace.directMessage(guest, "hello again", null);
        workspace.deliver(port, slack.systemId(), second);
        awaitEvent(bench.id(), slack.systemId(), (String) second.get("event_id"), "REFUSED");
        Map<String, Object> fromStranger = workspace.directMessage(stranger, "hi", null);
        workspace.deliver(port, slack.systemId(), fromStranger);
        assertThat(awaitEvent(bench.id(), slack.systemId(), (String) fromStranger.get("event_id"), "REFUSED"))
            .containsEntry("reason", "EXTERNAL");

        // Told once an hour each, never offered a link, audited once each.
        assertThat(workspace.posts("D" + guest)).hasSize(1);
        assertThat(said(workspace.posts("D" + guest))).contains("members of this workspace only")
            .doesNotContain("/link/");
        assertThat(workspace.posts("D" + stranger)).hasSize(1);
        assertThat(auditEvents(bench.owner(), bench.company(), "chat.refused")).hasSize(2);
        assertThat(rows(bench.id(), "SELECT count(*) AS n FROM chat_link_request WHERE system_id = ?",
                slack.systemId()).getFirst().get("n")).isEqualTo(0L);
    }

    @Test
    void refusesWhatIsOverTheCompanysRatesAndSaysSoOnceAMinute() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        int version = bench.owner().get(bench.path("/chat-settings")).json().get("version").asInt();
        ApiResponse rates = bench.owner().put(bench.path("/chat-settings"), Map.of("messagesPerUserPerMinute", 2,
                "messagesPerUserPerDay", 100, "eventsPerSystemPerMinute", 100, "version", version));
        assertThat(rates.status()).isEqualTo(200);
        String user = workspace.member("Chatty Cat");
        for (int i = 0; i < 6; i++) {
            assertThat(workspace.deliver(port, slack.systemId(), workspace.directMessage(user, "message " + i, null))
                .status()).isEqualTo(200);
        }
        await().atMost(WAIT).until(() -> events(bench.id(), slack.systemId()).stream()
            .filter(row -> "RATE_LIMITED".equals(row.get("reason")) && "REFUSED".equals(row.get("status")))
            .count() >= 2);
        await().atMost(WAIT).until(() -> said(workspace.posts("D" + user)).contains("more messages than"));
        assertThat(workspace.posts("D" + user)).filteredOn(post -> post.toString().contains("more messages than"))
            .hasSizeLessThanOrEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> event(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("event");
    }
}
