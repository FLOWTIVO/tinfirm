package com.flowtivo.tinfirm.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeSlack;

import tools.jackson.databind.JsonNode;

/**
 * The chat sign-in link's page and confirmation (the security review of 2026-10-27, L3): the page names the chat user
 * as Slack says they are now, with their real name and picture read with the bot's token as the page loads, and the
 * messages that waited for the link are dropped on confirmation, never sent as the member who confirmed; the chat user
 * is asked to send them again.
 */
class ChatLinksIntegrationTest extends ChatTestBench {

    /** The smallest PNG: one transparent pixel. TEST FIXTURE ONLY. */
    private static final byte[] PIXEL = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUl"
            + "EQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @Test
    void thePageNamesTheChatUserAsSlackSaysAndQueuedMessagesAreNotReleased() {
        Bench bench = bench(false);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        String user = workspace.memberWithPicture("Ada Lovelace", "the.ceo", PIXEL);
        Map<String, Object> first = workspace.directMessage(user, "delete the production database", null);
        workspace.deliver(port, slack.systemId(), first);
        awaitEvent(bench.id(), slack.systemId(), (String) first.get("event_id"), "AWAITING_LINK");
        Map<String, Object> second = workspace.directMessage(user, "and the backups", null);
        workspace.deliver(port, slack.systemId(), second);
        awaitEvent(bench.id(), slack.systemId(), (String) second.get("event_id"), "AWAITING_LINK");
        List<JsonNode> posts = await().atMost(WAIT).until(() -> workspace.posts("D" + user),
                found -> found.toString().contains("Link my account"));
        String[] link = linkIn(posts);

        // The page: who Slack says it is now, and what waits.
        ApiResponse read = bench.member().post(bench.path("/chat-links/read"), Map.of("token", link[1]));
        assertThat(read.status()).as("%s", read).isEqualTo(200);
        JsonNode page = read.json();
        assertThat(page.get("platformChecked").asBoolean()).isTrue();
        assertThat(page.get("platformName").asString()).isEqualTo("Ada Lovelace");
        assertThat(page.get("displayName").asString()).isEqualTo("the.ceo");
        assertThat(page.get("avatar").asString()).isEqualTo("data:image/png;base64,"
                + Base64.getEncoder().encodeToString(PIXEL));
        assertThat(page.get("workspaceName").asString()).isEqualTo(workspace.teamName);
        assertThat(page.get("waitingMessages").asInt()).isEqualTo(2);

        // Confirmed: linked, and the two messages dropped rather than run as the member who confirmed.
        ApiResponse confirmed = bench.member().post(bench.path("/chat-links/confirm"), Map.of("token", link[1]));
        assertThat(confirmed.status()).as("%s", confirmed).isEqualTo(200);
        for (Map<String, Object> held : List.of(first, second)) {
            Map<String, Object> row = awaitEvent(bench.id(), slack.systemId(), (String) held.get("event_id"),
                    "IGNORED");
            assertThat(row.get("reason")).isEqualTo("UNLINKED");
        }
        await().atMost(WAIT).until(() -> said(workspace.posts("D" + user))
            .contains("The 2 messages you wrote before linking were not sent: send them again."));
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).until(() -> rows(bench.id(),
                "SELECT id FROM conversation WHERE place = 'CHAT_THREAD'").isEmpty());
        assertThat(said(workspace.posts("D" + user))).doesNotContain("Which agent should answer");
    }
}
