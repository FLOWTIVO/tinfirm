package com.flowtivo.tinfirm.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.FakeBotFramework;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.FakeSlack;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Direct messages as personal conversations (the package 5 contract, §5.5, §5.6, §5.8, C5, C8, C10, row 18): end to
 * end with the Slack and Bot Framework stubs and the fake runner host. The member's own conversation, paid by their own
 * budget; the agent by a leading handle, the member's default, or the picker, which only the sender presses; the answer
 * in the thread; the Owner notice once; a question decided by its card's button by the linked person only; End; on
 * Teams one open conversation per member, which "New conversation" ends {@code SUPERSEDED}.
 */
class DirectMessagesTest extends ChatTestBench {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    @SuppressWarnings("unchecked")
    void aSlackDirectMessageIsTheMembersOwnConversationAnsweredInItsThread() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 1)) {
            String[] helper = agent(bench.member(), bench, "Helper", List.of());
            Slack slack = slack(bench);
            FakeSlack.Workspace workspace = slack.workspace();
            String user = workspace.member("Max Member");
            String other = workspace.member("Olga Other");
            String helloEvent = linkSlack(bench, slack, bench.member(), user);
            linkSlack(bench, slack, bench.admin(), other);

            // The held message, without a handle or a default: the picker, which only its sender presses.
            await().atMost(WAIT).until(() -> said(workspace.posts("D" + user)).contains("Which agent should answer"));
            awaitEvent(bench.id(), slack.systemId(), helloEvent, "AWAITING_AGENT");
            Map<String, Object> pick = buttons(workspace.posts("D" + user)).stream()
                .filter(value -> "pick".equals(value.get("a")) && !value.containsKey("d"))
                .findFirst()
                .orElseThrow();
            assertThat(pick.get("g")).isEqualTo(helper[0]);
            String channel = "D" + user;
            workspace.deliverPress(port, slack.systemId(), workspace.press(other, channel, FakeSlack.ts(), null,
                    JSON.writeValueAsString(pick)));
            workspace.deliverPress(port, slack.systemId(), workspace.press(user, channel, FakeSlack.ts(), null,
                    JSON.writeValueAsString(pick)));

            // The member's personal conversation, held in the chat thread, paid by the member.
            JsonNode assign = runner.expect("run.assign");
            Map<String, Object> conversation = await().atMost(WAIT).until(() -> rows(bench.id(),
                    "SELECT id, person_principal_id, place, origin, place_ref::text AS ref FROM conversation"
                            + " WHERE place = 'CHAT_THREAD' AND agent_id = ?", UUID.fromString(helper[0])),
                    found -> found.size() == 1).getFirst();
            assertThat(conversation).containsEntry("place", "CHAT_THREAD").containsEntry("origin", "DIRECT_MESSAGE")
                .containsEntry("person_principal_id", bench.memberPrincipal());
            assertThat((String) conversation.get("ref")).contains(slack.systemId().toString()).contains(channel);
            String conversationId = conversation.get("id").toString();
            assertThat(assign.get("modelAccess").get("apiKey").asString()).startsWith("tfm_");
            assertThat(assign.get("prompt").asString()).contains("hello");
            JsonNode inApp = bench.member().get(bench.path("/conversations/" + conversationId)).json();
            assertThat(inApp.get("messages").get(0).get("kind").asString()).isEqualTo("PERSON");
            // Its place in the chat, and a link that opens it there.
            JsonNode chat = inApp.get("chat");
            assertThat(chat.get("systemId").asString()).isEqualTo(slack.systemId().toString());
            assertThat(chat.get("systemKind").asString()).isEqualTo("SLACK");
            assertThat(chat.get("channelId").asString()).isEqualTo(channel);
            assertThat(chat.get("threadId").asString()).isNotBlank();
            assertThat(chat.get("permalink").asString()).isEqualTo("https://slack.com/app_redirect?team="
                    + workspace.teamId + "&channel=" + channel);
            assertThat(bench.admin().get(bench.path("/conversations/" + conversationId)).status()).isEqualTo(404);
            // The Owner reads it in full, on record with its place (row 18, §5.13).
            assertThat(bench.owner().get(bench.path("/conversations/" + conversationId)).status()).isEqualTo(200);
            assertThat(auditEvents(bench.owner(), bench.company(), "conversation.read"))
                .anySatisfy(event -> assertThat(event.toString()).contains(conversationId).contains("CHAT_THREAD")
                    .contains("DIRECT_MESSAGE"));
            // The first conversation carries the Owner notice.
            await().atMost(WAIT).until(() -> said(workspace.posts(channel)).contains("This is a personal conversation"));

            // The answer, in the message's thread, headed by the agent.
            String runId = assign.get("runId").asString();
            long epoch = assign.get("epoch").asLong();
            runner.accepted(UUID.fromString(runId), epoch);
            runner.send(Map.of("type", "run.finished", "runId", runId, "epoch", epoch, "outcome", "SUCCEEDED",
                    "lastSeq", 0, "summary", "Here is **the answer**.", "sessionId", "session-dm", "git", List.of(),
                    "result", Map.of("outcome", "COMPLETED", "summary", "Here is **the answer**.", "nextSteps",
                            List.of(), "openQuestions", List.of())));
            await().atMost(WAIT).until(() -> said(workspace.posts(channel)).contains("the answer"));
            JsonNode answer = workspace.posts(channel).stream()
                .filter(post -> post.toString().contains("the answer"))
                .findFirst()
                .orElseThrow();
            assertThat(answer.get("thread_ts").asString()).isNotBlank();
            assertThat(answer.toString()).contains("*the answer*").contains("Tinfirm agent")
                .doesNotContain("**the answer**");
            assertThat(answer.get("parse").asString()).isEqualTo("none");
            assertThat(answer.get("unfurl_links").asBoolean()).isFalse();
            String thread = answer.get("thread_ts").asString();

            // A reply in the thread continues the conversation: the next turn.
            workspace.deliver(port, slack.systemId(), workspace.directMessage(user, "And the tests?", thread));
            JsonNode next = runner.expect("run.assign");
            assertThat(next.get("prompt").asString()).contains("And the tests?");
            runner.send(Map.of("type", "run.finished", "runId", next.get("runId").asString(), "epoch",
                    next.get("epoch").asLong(), "outcome", "SUCCEEDED", "lastSeq", 0, "summary", "Done.",
                    "sessionId", "session-dm", "git", List.of()));

            // Another member's reply in the thread is not theirs to continue.
            Map<String, Object> intruder = workspace.directMessage(other, "Me too", thread);
            ((Map<String, Object>) envelopeEvent(intruder)).put("channel", channel);
            workspace.deliver(port, slack.systemId(), intruder);
            assertThat(awaitEvent(bench.id(), slack.systemId(), (String) intruder.get("event_id"), "REFUSED"))
                .containsEntry("reason", "NOT_YOURS");

            // End: by its person only.
            Map<String, Object> end = buttons(workspace.posts(channel)).stream()
                .filter(value -> "end".equals(value.get("a")))
                .findFirst()
                .orElseThrow();
            workspace.deliverPress(port, slack.systemId(), workspace.press(other, channel, FakeSlack.ts(), thread,
                    JSON.writeValueAsString(end)));
            workspace.deliverPress(port, slack.systemId(), workspace.press(user, channel, FakeSlack.ts(), thread,
                    JSON.writeValueAsString(end)));
            await().atMost(WAIT).until(() -> "ENDED".equals(bench.member().get(bench.path("/conversations/"
                    + conversationId)).json().get("state").asString()));
            assertThat(bench.member().get(bench.path("/conversations/" + conversationId)).json().get("endedReason")
                .asString()).isEqualTo("USER");

            // A leading @handle starts a new conversation at once, without the picker or the notice again.
            int pickers = (int) workspace.posts(channel).stream()
                .filter(post -> post.toString().contains("Which agent should answer"))
                .count();
            Map<String, Object> withFile = workspace.directMessage(user, "@" + helper[1] + " one more", null);
            Map<String, Object> event = (Map<String, Object>) withFile.get("event");
            event.put("subtype", "file_share");
            event.put("files", List.of(Map.of("id", "F1", "name", "notes.txt", "mimetype", "text/plain", "size", 11,
                    "url_private_download", workspace.file("notes.txt", "hello notes".getBytes()))));
            workspace.deliver(port, slack.systemId(), withFile);
            JsonNode third = runner.expect("run.assign");
            assertThat(third.get("prompt").asString()).contains("one more").doesNotContain("@" + helper[1]);
            assertThat(workspace.posts(channel)).filteredOn(post -> post.toString().contains("Which agent should"))
                .hasSize(pickers);
            assertThat(workspace.posts(channel)).filteredOn(post -> post.toString()
                .contains("This is a personal conversation")).hasSize(1);
            // The message's file, downloaded with the bot's token, is the conversation's attachment.
            String thirdId = rows(bench.id(), "SELECT id FROM conversation WHERE place = 'CHAT_THREAD' AND agent_id = ?"
                    + " AND state = 'OPEN'", UUID.fromString(helper[0])).getFirst().get("id").toString();
            JsonNode attachments = bench.member().get(bench.path("/conversations/" + thirdId + "/attachments")).json();
            assertThat(attachments.toString()).contains("notes.txt");
            runner.assertFramesValid();
        }
    }

    @Test
    void aQuestionIsDecidedByItsCardsButtonByTheLinkedPersonOnly() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 1)) {
            String[] helper = agent(bench.member(), bench, "Asker", List.of());
            ApiResponseCheck.ok(bench.member().put(bench.path("/me/chat"), Map.of("defaultAgentId", helper[0])));
            Slack slack = slack(bench);
            FakeSlack.Workspace workspace = slack.workspace();
            String user = workspace.member("Quinn");
            String other = workspace.member("Oscar");
            linkSlack(bench, slack, bench.member(), user);
            linkSlack(bench, slack, bench.admin(), other);
            String channel = "D" + user;

            // The held message goes to the member's default agent.
            JsonNode assign = runner.expect("run.assign");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            runner.accepted(runId, epoch);
            JsonNode asked = runner.requestAndAck(runId, epoch, "ASK", Map.of("question", "Which format?",
                    "options", List.of("CSV", "XLSX")));
            UUID decisionId = UUID.fromString(asked.get("payload").get("decisionId").asString());
            await().atMost(WAIT).until(() -> said(workspace.posts(channel)).contains("Which format?"));
            Map<String, Object> csv = buttons(workspace.posts(channel)).stream()
                .filter(value -> "decide".equals(value.get("a")) && "CSV".equals(value.get("o")))
                .findFirst()
                .orElseThrow();
            Map<String, Object> card = await().atMost(WAIT).until(() -> rows(bench.id(),
                    "SELECT external_id, channel_id, thread_id FROM chat_post WHERE decision_id = ?", decisionId),
                    found -> found.size() == 1 && found.getFirst().get("external_id") != null).getFirst();
            String cardTs = (String) card.get("external_id");
            String thread = (String) card.get("thread_id");

            // A press on another message than the card does nothing; another member's press is told and changes
            // nothing; typed words decide nothing.
            workspace.deliverPress(port, slack.systemId(), workspace.press(user, channel, FakeSlack.ts(), thread,
                    JSON.writeValueAsString(csv)));
            workspace.deliverPress(port, slack.systemId(), workspace.press(other, channel, cardTs, thread,
                    JSON.writeValueAsString(csv)));
            await().atMost(WAIT).until(() -> workspace.posts(channel).size() + workspace.ephemerals().size() > 0
                    && said(workspace.posts(channel)).contains("Which format?"));
            assertThat(bench.member().get(bench.path("/decisions/" + decisionId)).json().get("status").asString())
                .isEqualTo("OPEN");

            workspace.deliverPress(port, slack.systemId(), workspace.press(user, channel, cardTs, thread,
                    JSON.writeValueAsString(csv)));
            await().atMost(WAIT).until(() -> !"OPEN".equals(bench.member().get(bench.path("/decisions/"
                    + decisionId)).json().get("status").asString()));
            JsonNode decision = bench.member().get(bench.path("/decisions/" + decisionId)).json();
            assertThat(decision.toString()).contains("CSV");
            assertThat(rows(bench.id(), "SELECT decided_via, decided_by_external_id FROM decision WHERE id = ?",
                    decisionId).getFirst()).containsEntry("decided_via", "CHAT")
                .containsEntry("decided_by_external_id", user);
            // The card is updated without its buttons.
            await().atMost(WAIT).until(() -> !workspace.updates().isEmpty());
            assertThat(workspace.updates().getLast().get("ts").asString()).isEqualTo(cardTs);
            // A second press: decided already.
            workspace.deliverPress(port, slack.systemId(), workspace.press(user, channel, cardTs, thread,
                    JSON.writeValueAsString(csv)));
            runner.assertFramesValid();
        }
    }

    @Test
    void aTeamsMemberHasOneOpenDirectConversationThatNewConversationEnds() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 2)) {
            String[] helper = agent(bench.member(), bench, "Teamster", List.of());
            Teams teams = teams(bench);
            FakeBotFramework.Tenant tenant = teams.tenant();
            FakeBotFramework.TeamsUser user = tenant.user("Tara", "user");
            asOwner("UPDATE app_user SET auth_source = 'OIDC', password_hash = NULL, oidc_issuer = ?,"
                    + " oidc_subject = ?, oidc_tenant_id = ?, oidc_object_id = ? WHERE id = ?",
                    "https://login.microsoftonline.com/" + tenant.tenantId + "/v2.0", "subject-" + user.aadObjectId(),
                    tenant.tenantId, user.aadObjectId(), bench.company().userId("MEMBER"));
            String chat = "a:dm-" + user.id();

            tenant.deliver(port, teams.systemId(), tenant.personalMessage(user, "@" + helper[1] + " first"));
            JsonNode first = runner.expect("run.assign");
            assertThat(first.get("prompt").asString()).contains("first");
            tenant.deliver(port, teams.systemId(), tenant.personalMessage(user, "more of the first"));
            List<Map<String, Object>> open = await().atMost(WAIT).until(() -> rows(bench.id(),
                    "SELECT id, state FROM conversation WHERE place = 'CHAT_THREAD' AND agent_id = ?",
                    UUID.fromString(helper[0])), found -> found.size() == 1);
            String firstId = open.getFirst().get("id").toString();
            await().atMost(WAIT).until(() -> bench.member().get(bench.path("/conversations/" + firstId)).json()
                .get("messages").size() >= 2);
            // The Owner notice in the first direct message.
            await().atMost(WAIT).until(() -> said(tenant.posts(chat)).contains("This is a personal conversation"));

            // "New conversation" ends it SUPERSEDED; the next message starts another.
            tenant.deliver(port, teams.systemId(), tenant.submit(user, chat, "1:card", Map.of("a", "new", "c",
                    firstId)));
            await().atMost(WAIT).until(() -> "ENDED".equals(bench.member().get(bench.path("/conversations/"
                    + firstId)).json().get("state").asString()));
            assertThat(bench.member().get(bench.path("/conversations/" + firstId)).json().get("endedReason")
                .asString()).isEqualTo("SUPERSEDED");
            tenant.deliver(port, teams.systemId(), tenant.personalMessage(user, "@" + helper[1] + " second"));
            await().atMost(WAIT).until(() -> rows(bench.id(), "SELECT id FROM conversation WHERE place ="
                    + " 'CHAT_THREAD' AND agent_id = ?", UUID.fromString(helper[0])).size() == 2);
            // Files sent in Teams are not read: the bot points to the app.
            Map<String, Object> withFile = tenant.personalMessage(user, "see the file");
            withFile.put("attachments", List.of(Map.of("contentType", "application/vnd.microsoft.teams.file.download"
                    + ".info", "name", "report.pdf", "content", Map.of("downloadUrl",
                            "https://tenant-my.sharepoint.com/x"))));
            tenant.deliver(port, teams.systemId(), withFile);
            await().atMost(WAIT).until(() -> said(tenant.posts(chat)).contains("not read"));
        }
    }

    /** Every button value the posts carry: Slack's JSON values, Teams' Action.Submit data. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> buttons(List<JsonNode> posts) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (JsonNode post : posts) {
            collect(post, values);
        }
        return values;
    }

    @SuppressWarnings("unchecked")
    private static void collect(JsonNode node, List<Map<String, Object>> values) {
        if (node.isObject()) {
            JsonNode value = node.get("value");
            if (value != null && value.isString() && value.asString().startsWith("{")) {
                values.add(JSON.readValue(value.asString(), Map.class));
            }
            JsonNode data = node.get("data");
            if (data != null && data.isObject() && "Action.Submit".equals(node.path("type").asString())) {
                values.add(JSON.convertValue(data, Map.class));
            }
            node.properties().forEach(entry -> collect(entry.getValue(), values));
        }
        else if (node.isArray()) {
            node.forEach(child -> collect(child, values));
        }
    }

    private static Object envelopeEvent(Map<String, Object> envelope) {
        return envelope.get("event");
    }

    /** A response that must be 2xx. */
    private static final class ApiResponseCheck {

        static void ok(com.flowtivo.tinfirm.support.ApiResponse response) {
            assertThat(response.status()).as("%s", response).isBetween(200, 299);
        }
    }
}
