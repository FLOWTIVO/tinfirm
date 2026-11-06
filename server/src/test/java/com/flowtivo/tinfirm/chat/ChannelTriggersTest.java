package com.flowtivo.tinfirm.chat;

import static com.flowtivo.tinfirm.support.TestTriggers.withModel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.conversation.ConversationPurge;
import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeBotFramework;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.FakeSlack;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Channel triggers (the package 5 contract, §5.7, §5.8, §5.9, §5.14, open point 21, C7, C8, C10, C12): end to end with
 * the Slack stub and the fake runner host. The editor's checks for the author (the bot's channel, the author linked and
 * in it, one trigger per agent and one default); the channel list; a mention by a member who is not linked fires the
 * trigger, its conversation held in the thread and answered there through the outbox, written by the bot; a mention in
 * the thread continues it; a paused trigger and the company's stop answer nothing and store nothing; the channel list's
 * triggers, a member's own and their team's, an admin's every one; approvals pressed
 * only by a linked member of the team, questions by any member; the bot's failure and the channels' loss make the
 * trigger wait, and it goes on by itself.
 */
@ExtendWith(EnterpriseLicence.class)
class ChannelTriggersTest extends ChatTestBench {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Duration NONE_WITHIN = Duration.ofSeconds(2);

    @Autowired
    ConversationPurge purge;

    @Autowired
    SystemTransactions transactions;

    @Test
    void aMentionFiresTheChannelTriggerWhoseAnswersPostInTheThread() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 2)) {
            JsonNode agent = agent(bench.member(), bench, "Support");
            JsonNode other = agent(bench.member(), bench, "Second");
            Slack slack = slack(bench);
            FakeSlack.Workspace workspace = slack.workspace();
            String support = workspace.channel("support", false);
            String quiet = workspace.channel("quiet", false);
            String secret = workspace.channel("secret", true);
            String author = workspace.member("Max Member");
            String asker = workspace.member("Ann Asker");
            linkSlack(bench, slack, bench.member(), author);
            workspace.inChannels(author, support, secret);

            // The editor's list: the bot's channels, read again from Slack, and a private one the author is in.
            JsonNode listed = bench.member().get(bench.path("/systems/" + slack.systemId() + "/chat/channels")).json();
            assertThat(listed.get("items").toString()).contains("\"support\"", "\"quiet\"", "\"secret\"");
            JsonNode supportItem = item(listed, support);
            assertThat(supportItem.get("available").asBoolean()).isTrue();
            assertThat(supportItem.get("hasDefault").asBoolean()).isFalse();
            // Another member, not linked, sees the public channels only.
            JsonNode admins = bench.admin().get(bench.path("/systems/" + slack.systemId() + "/chat/channels")).json();
            assertThat(admins.get("items").toString()).contains("\"support\"").doesNotContain("\"secret\"");
            assertThat(bench.member().get(bench.path("/systems/" + slack.systemId() + "/chat/channels?query=sup"))
                .json().get("items")).hasSize(1);
            // Only an admin asks for the author's view.
            assertThat(bench.member().get(bench.path("/systems/" + slack.systemId() + "/chat/channels"
                    + "?authorPrincipalId=" + bench.memberPrincipal())).status()).isEqualTo(404);
            assertThat(bench.admin().get(bench.path("/systems/" + slack.systemId() + "/chat/channels"
                    + "?authorPrincipalId=" + bench.memberPrincipal())).json().get("items").toString())
                .contains("\"secret\"");

            // The editor's checks, for the author.
            assertThat(trigger(bench.member(), bench, slack, agent, null, channel(quiet, true)).problemCode())
                .isEqualTo("CHANNEL_NOT_JOINED");
            assertThat(trigger(bench.member(), bench, slack, agent, null, channel("CUNKNOWN1", true)).problemCode())
                .isEqualTo("CHANNEL_UNAVAILABLE");
            ApiResponse created = trigger(bench.member(), bench, slack, agent, null, channel(support, true));
            assertThat(created.status()).as("%s", created).isEqualTo(201);
            String triggerId = created.json().get("id").asString();
            assertThat(created.json().get("source").asString()).isEqualTo("CHANNEL");
            assertThat(created.json().get("channels").get(0).get("channelId").asString()).isEqualTo(support);
            assertThat(created.json().get("channels").get(0).get("name").asString()).isEqualTo("support");
            assertThat(trigger(bench.member(), bench, slack, agent, null, channel(support, false)).problemCode())
                .isEqualTo("CHANNEL_TAKEN");
            assertThat(trigger(bench.member(), bench, slack, other, null, channel(support, true)).problemCode())
                .isEqualTo("CHANNEL_TAKEN");
            JsonNode after = item(bench.member().get(bench.path("/systems/" + slack.systemId() + "/chat/channels"))
                .json(), support);
            assertThat(after.get("hasDefault").asBoolean()).isTrue();
            assertThat(after.get("triggers").toString()).contains(triggerId);
            // The admin sees it on the channel too; a member who is neither its author nor in its team does not.
            assertThat(item(bench.admin().get(bench.path("/systems/" + slack.systemId() + "/chat/channels")).json(),
                    support).get("triggers").toString()).contains(triggerId);

            // A mention by a member who is not linked: the trigger fires, its conversation held in the thread.
            Map<String, Object> mention = workspace.mention(asker, support, "How do I reset my password?", null);
            assertThat(workspace.deliver(port, slack.systemId(), mention).status()).isEqualTo(200);
            String thread = FakeSlack.Workspace.tsOf(mention);
            JsonNode assign = runner.expect("run.assign");
            String prompt = assign.get("prompt").asString();
            assertThat(prompt).contains("How do I reset my password?", "A mention in #support", "Ann Asker",
                    "not a Tinfirm member", "other people's words", "Answer the support channel.")
                .doesNotContain("<@" + workspace.botUserId + ">");
            Map<String, Object> conversation = rows(bench.id(), "SELECT id, place, origin, trigger_id,"
                    + " place_ref::text AS ref, title FROM conversation WHERE trigger_id = ?",
                    UUID.fromString(triggerId)).getFirst();
            assertThat(conversation).containsEntry("place", "CHAT_THREAD").containsEntry("origin", "TRIGGER");
            assertThat((String) conversation.get("ref")).contains("CHANNEL", support, thread);
            assertThat((String) conversation.get("title")).contains("How do I reset my password?");
            UUID conversationId = (UUID) conversation.get("id");
            assertThat(rows(bench.id(), "SELECT event, outcome, actor_principal_id FROM trigger_firing"
                    + " WHERE trigger_id = ?", UUID.fromString(triggerId)).getFirst())
                .containsEntry("event", "MENTIONED").containsEntry("outcome", "STARTED")
                .containsEntry("actor_principal_id", null);
            // The mention was taken: its reaction, through the outbox.
            await().atMost(WAIT).until(() -> workspace.count("reactions.add") >= 1);

            // The answer, in the mention's thread, written by the bot through the outbox.
            String runId = assign.get("runId").asString();
            long epoch = assign.get("epoch").asLong();
            runner.accepted(UUID.fromString(runId), epoch);
            runner.send(finished(runId, epoch, "Use the **reset** link."));
            await().atMost(WAIT).until(() -> said(workspace.posts(support)).contains("reset"));
            JsonNode answer = workspace.posts(support).stream()
                .filter(post -> post.toString().contains("reset"))
                .findFirst()
                .orElseThrow();
            assertThat(answer.get("thread_ts").asString()).isEqualTo(thread);
            assertThat(answer.toString()).contains("*reset*", "Tinfirm agent").doesNotContain("**reset**");
            await().atMost(WAIT).until(() -> rows(bench.id(), "SELECT status FROM outbox WHERE conversation_id = ?"
                    + " AND cause = 'RESULT'", conversationId).stream()
                .anyMatch(row -> "DELIVERED".equals(row.get("status"))));
            assertThat(rows(bench.id(), "SELECT writer, binding_id FROM outbox WHERE conversation_id = ?"
                    + " AND cause = 'RESULT'", conversationId).getFirst()).containsEntry("writer", "BOT")
                .containsEntry("binding_id", null);
            assertThat(rows(bench.id(), "SELECT kind FROM system_write WHERE system_id = ?", slack.systemId()))
                .anySatisfy(row -> assertThat(row).containsEntry("kind", "CHAT_MESSAGE"));

            // The thread is the conversation's place: the app takes answers to open questions only.
            assertThat(bench.member().post(bench.path("/conversations/" + conversationId + "/messages"), Map.of("body",
                    "From the app")).problemCode()).isEqualTo("PLACE_IS_EXTERNAL");

            // A mention in the thread continues the conversation: the next turn, the asker's words marked theirs.
            Map<String, Object> more = workspace.mention(asker, support, "And on mobile?", thread);
            workspace.deliver(port, slack.systemId(), more);
            JsonNode next = runner.expect("run.assign");
            assertThat(next.get("prompt").asString()).contains("And on mobile?", "Ann Asker (in Slack)");
            runner.send(finished(next.get("runId").asString(), next.get("epoch").asLong(), "The same on mobile."));
            await().atMost(WAIT).until(() -> said(workspace.posts(support)).contains("The same on mobile."));
            assertThat(rows(bench.id(), "SELECT id FROM conversation WHERE trigger_id = ?",
                    UUID.fromString(triggerId))).hasSize(1);

            // Paused: a mention in a new message, or in the thread, is told so; nothing is stored or fired.
            ApiResponse paused = bench.member().post(bench.path("/triggers/" + triggerId + "/pause"),
                    Map.of("stopRunning", false));
            assertThat(paused.status()).as("%s", paused).isEqualTo(200);
            Map<String, Object> whilePaused = workspace.mention(asker, support, "Anyone there?", null);
            workspace.deliver(port, slack.systemId(), whilePaused);
            assertThat(awaitEvent(bench.id(), slack.systemId(), (String) whilePaused.get("event_id"), "REFUSED"))
                .containsEntry("reason", "TRIGGER_PAUSED");
            Map<String, Object> inThread = workspace.mention(asker, support, "Hello?", thread);
            workspace.deliver(port, slack.systemId(), inThread);
            assertThat(awaitEvent(bench.id(), slack.systemId(), (String) inThread.get("event_id"), "REFUSED"))
                .containsEntry("reason", "TRIGGER_PAUSED");
            await().atMost(WAIT).until(() -> said(workspace.ephemerals()).contains("is paused here"));
            runner.expectNone("run.assign", NONE_WITHIN);
            assertThat(rows(bench.id(), "SELECT id FROM conversation WHERE trigger_id = ?",
                    UUID.fromString(triggerId))).hasSize(1);
            assertThat(rows(bench.id(), "SELECT count(*) AS n FROM message WHERE conversation_id = ?"
                    + " AND body LIKE '%Hello?%'", conversationId).getFirst().get("n")).isEqualTo(0L);

            // The company's stop: a mention fires nothing and is never answered later.
            assertThat(bench.member().post(bench.path("/triggers/" + triggerId + "/resume"), Map.of()).status())
                .isEqualTo(200);
            assertThat(bench.owner().post(bench.path("/runs-stop"), Map.of("reason", "Incident")).status())
                .isEqualTo(200);
            Map<String, Object> whileStopped = workspace.mention(asker, support, "Stopped?", null);
            workspace.deliver(port, slack.systemId(), whileStopped);
            assertThat(awaitEvent(bench.id(), slack.systemId(), (String) whileStopped.get("event_id"), "REFUSED"))
                .containsEntry("reason", "COMPANY_STOPPED");
            assertThat(bench.owner().delete(bench.path("/runs-stop")).status()).isEqualTo(200);
            runner.expectNone("run.assign", NONE_WITHIN);
            assertThat(rows(bench.id(), "SELECT id FROM conversation WHERE trigger_id = ?",
                    UUID.fromString(triggerId))).hasSize(1);

            // Without a default, a mention that names no agent of the channel gets a hint and starts nothing.
            Map<String, Object> unnamed = workspace.mention(asker, quiet, "Hi", null);
            workspace.deliver(port, slack.systemId(), unnamed);
            assertThat(awaitEvent(bench.id(), slack.systemId(), (String) unnamed.get("event_id"), "REFUSED"))
                .containsEntry("reason", "NO_TRIGGER");
            runner.assertFramesValid();
        }
    }

    @Test
    void aReviewedAnswerShowsItsPostAndItsDocumentAsWritesOfTheirKindsUntilAPurge() throws Exception {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 2)) {
            JsonNode agent = agent(bench.member(), bench, "Reviewed");
            Slack slack = slack(bench);
            FakeSlack.Workspace workspace = slack.workspace();
            String support = workspace.channel("support", false);
            String author = workspace.member("Max Member");
            String asker = workspace.member("Ann Asker");
            linkSlack(bench, slack, bench.member(), author);
            workspace.inChannels(author, support);
            bench.member().get(bench.path("/systems/" + slack.systemId() + "/chat/channels"));
            UUID team = createGroup(bench.owner(), bench.company(), unique("Reviewers"),
                    List.of(bench.company().userId("ADMIN")));
            // The trigger reviews its results (T14): its team approves an answer before the bot posts it.
            Map<String, Object> body = triggerBody(bench, slack.systemId(), agent, team, channel(support, true));
            body.put("results", Map.of("comment", false, "review", true, "onSuccess", List.of(), "onFailure",
                    List.of()));
            ApiResponse created = bench.member().post(bench.path("/triggers"), withModel(body));
            assertThat(created.status()).as("%s", created).isEqualTo(201);
            UUID triggerId = UUID.fromString(created.json().get("id").asString());

            Map<String, Object> mention = workspace.mention(asker, support, "Where is the guide?", null);
            assertThat(workspace.deliver(port, slack.systemId(), mention).status()).isEqualTo(200);
            String thread = FakeSlack.Workspace.tsOf(mention);
            JsonNode assign = runner.expect("run.assign");
            String runId = assign.get("runId").asString();
            long epoch = assign.get("epoch").asLong();
            runner.accepted(UUID.fromString(runId), epoch);
            UUID conversationId = (UUID) rows(bench.id(), "SELECT id FROM conversation WHERE trigger_id = ?",
                    triggerId).getFirst().get("id");
            // The turn saved a document, as its host's upload would have.
            document(bench, conversationId, UUID.fromString(agent.get("id").asString()), UUID.fromString(runId),
                    "guide.md");
            runner.send(finished(runId, epoch, "The guide is attached."));

            // The review: the post and the document as the outbox would write them, each of its kind, in the thread.
            await().atMost(WAIT).until(() -> !rows(bench.id(), "SELECT id FROM decision WHERE conversation_id = ?"
                    + " AND subject ->> 'type' = 'RESULT'", conversationId).isEmpty());
            UUID review = (UUID) rows(bench.id(), "SELECT id FROM decision WHERE conversation_id = ?"
                    + " AND subject ->> 'type' = 'RESULT'", conversationId).getFirst().get("id");
            JsonNode subject = bench.admin().get(bench.path("/decisions/" + review)).json().get("subject");
            assertThat(subject.get("runId").asString()).isEqualTo(runId);
            assertThat(subject.get("writes")).extracting(JsonNode::asString).containsExactly(
                    "The answer, posted in the thread", "The document guide.md, uploaded to the thread");
            JsonNode items = subject.get("writeItems");
            assertThat(items).extracting(item -> item.get("kind").asString()).containsExactly("CHAT_POST",
                    "CHAT_FILE");
            assertThat(items).extracting(item -> item.get("target").asString()).containsOnly(support + ":" + thread);
            assertThat(items).extracting(item -> item.get("text").asString())
                .containsExactlyElementsOf(List.of(subject.get("writes").get(0).asString(),
                        subject.get("writes").get(1).asString()));
            assertThat(items.get(0).path("value").isMissingNode() || items.get(0).get("value").isNull()).isTrue();
            assertThat(items.get(1).get("value").asString()).isEqualTo("guide.md");
            // Nothing reaches the thread before the team approves; then the bot posts the answer.
            assertThat(said(workspace.posts(support))).doesNotContain("The guide is attached.");
            ApiResponse approved = bench.admin().post(bench.path("/decisions/" + review + "/decide"),
                    Map.of("outcome", "APPROVED"));
            assertThat(approved.status()).as("%s", approved).isEqualTo(200);
            await().atMost(WAIT).until(() -> said(workspace.posts(support)).contains("The guide is attached."));

            // The chats' retention purges the decided review's texts: its summary and its writes, words and items.
            transactions.runInCompany(bench.id(), () -> purge.purge(conversationId, ConversationPurge.Kind.CHAT,
                    ConversationPurge.Held.NONE, Instant.now()));
            JsonNode purged = bench.admin().get(bench.path("/decisions/" + review)).json();
            assertThat(purged.hasNonNull("purgedAt")).isTrue();
            assertThat(purged.get("subject").get("type").asString()).isEqualTo("RESULT");
            for (String text : List.of("summary", "writes", "writeItems")) {
                assertThat(purged.get("subject").path(text).isMissingNode() || purged.get("subject").get(text)
                    .isNull()).as(text).isTrue();
            }
            assertThat(rows(bench.id(), "SELECT subject::text AS subject FROM decision WHERE id = ?", review)
                .getFirst().get("subject").toString()).doesNotContain("guide.md", "posted in the thread");
            runner.assertFramesValid();
        }
    }

    /** A document of the run, its file and its output, as the run's host's upload makes them. */
    private static void document(Bench bench, UUID conversationId, UUID agentId, UUID runId, String name)
            throws Exception {
        byte[] content = ("# " + name).getBytes(StandardCharsets.UTF_8);
        String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        UUID attachmentId = UUID.randomUUID();
        inCompany(bench.id(), """
                INSERT INTO attachment (id, company_id, conversation_id, file_name, content_type, size_bytes, sha256,
                                        content, uploaded_by_agent_id, run_id, created_at)
                VALUES (?, ?, ?, ?, 'text/markdown', ?, ?, ?, ?, ?, now())""", attachmentId, bench.id(), conversationId,
                name, (long) content.length, sha256, content, agentId, runId);
        inCompany(bench.id(), """
                INSERT INTO output (id, company_id, conversation_id, run_id, kind, title, attachment_id, created_at)
                VALUES (?, ?, ?, ?, 'DOCUMENT', ?, ?, now())""", UUID.randomUUID(), bench.id(), conversationId, runId,
                name, attachmentId);
    }

    @Test
    void theChannelListShowsAMemberTheirOwnAndTheirTeamsTriggersAndAnAdminEveryOne() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 1)) {
            JsonNode agent = agent(bench.member(), bench, "Desk");
            String teammateEmail = uniqueEmail("teammate");
            String outsiderEmail = uniqueEmail("outsider");
            UUID teammateUser = createUser(admin(), teammateEmail, false);
            UUID outsiderUser = createUser(admin(), outsiderEmail, false);
            addMember(bench.owner(), bench.id(), teammateEmail, "MEMBER");
            addMember(bench.owner(), bench.id(), outsiderEmail, "MEMBER");
            ApiClient teammate = loginAs(teammateEmail);
            ApiClient outsider = loginAs(outsiderEmail);
            UUID outsiderPrincipal = memberPrincipal(bench.company(), outsiderUser);
            UUID team = createGroup(bench.owner(), bench.company(), unique("Team"), List.of(teammateUser));
            Slack slack = slack(bench);
            FakeSlack.Workspace workspace = slack.workspace();
            String desk = workspace.channel("desk", false);
            workspace.channel("hidden-desk", true);
            String hidden = workspace.channel("hidden-room", true);
            String author = workspace.member("Max Member");
            linkSlack(bench, slack, bench.member(), author);
            workspace.inChannels(author, desk, hidden);
            String list = bench.path("/systems/" + slack.systemId() + "/chat/channels");
            bench.member().get(list);
            ApiResponse created = trigger(bench.member(), bench, slack, agent, team, channel(desk, true));
            assertThat(created.status()).as("%s", created).isEqualTo(201);
            String triggerId = created.json().get("id").asString();

            // A member sees the triggers they author or are in the team of; another member none, though the channel
            // has a default.
            assertThat(item(bench.member().get(list).json(), desk).get("triggers").toString()).contains(triggerId);
            assertThat(item(teammate.get(list).json(), desk).get("triggers").toString()).contains(triggerId);
            JsonNode outsiders = item(outsider.get(list).json(), desk);
            assertThat(outsiders.get("hasDefault").asBoolean()).isTrue();
            assertThat(outsiders.get("triggers")).isEmpty();

            // An OWNER or ADMIN sees every trigger, in their own view and in an author's: the author's channels, private
            // ones the author is in included, each with every trigger on it.
            assertThat(item(bench.admin().get(list).json(), desk).get("triggers").toString()).contains(triggerId);
            assertThat(item(bench.owner().get(list).json(), desk).get("triggers").toString()).contains(triggerId);
            JsonNode outsidersView = bench.admin().get(list + "?authorPrincipalId=" + outsiderPrincipal).json();
            assertThat(item(outsidersView, desk).get("triggers").toString()).contains(triggerId);
            assertThat(outsidersView.get("items").toString()).doesNotContain("\"hidden-room\"");
            JsonNode authorsView = bench.owner().get(list + "?authorPrincipalId=" + bench.memberPrincipal()).json();
            assertThat(item(authorsView, desk).get("triggers").toString()).contains(triggerId);
            assertThat(authorsView.get("items").toString()).contains("\"hidden-room\"")
                .doesNotContain("\"hidden-desk\"");
            // Only an admin asks for an author's view.
            assertThat(teammate.get(list + "?authorPrincipalId=" + bench.memberPrincipal()).status())
                .isEqualTo(404);
        }
    }

    @Test
    void onlyALinkedMemberOfTheTeamApprovesInTheChannelAndAnyMemberAnswersAQuestion() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 2)) {
            JsonNode agent = agent(bench.member(), bench, "Builder");
            UUID team = createGroup(bench.owner(), bench.company(), unique("Team"),
                    List.of(bench.company().userId("ADMIN")));
            Slack slack = slack(bench);
            FakeSlack.Workspace workspace = slack.workspace();
            String builds = workspace.channel("builds", false);
            String author = workspace.member("Max Member");
            String teamMember = workspace.member("Tess Team");
            String asker = workspace.member("Ann Asker");
            linkSlack(bench, slack, bench.member(), author);
            linkSlack(bench, slack, bench.admin(), teamMember);
            workspace.inChannels(author, builds);
            bench.member().get(bench.path("/systems/" + slack.systemId() + "/chat/channels"));
            ApiResponse created = trigger(bench.member(), bench, slack, agent, team, channel(builds, true));
            assertThat(created.status()).as("%s", created).isEqualTo(201);

            Map<String, Object> mention = workspace.mention(asker, builds, "Build the shop", null);
            workspace.deliver(port, slack.systemId(), mention);
            String thread = FakeSlack.Workspace.tsOf(mention);
            JsonNode assign = runner.expect("run.assign");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            runner.accepted(runId, epoch);

            // An approval: its card in the thread; the asker, not linked, is told; the team's linked member decides.
            JsonNode requested = runner.requestAndAck(runId, epoch, "NETWORK", Map.of("host",
                    "registry.example.test", "reason", "To fetch the packages."));
            UUID approval = UUID.fromString(requested.get("payload").get("decisionId").asString());
            Map<String, Object> card = await().atMost(WAIT).until(() -> rows(bench.id(),
                    "SELECT external_id FROM chat_post WHERE decision_id = ?", approval),
                    found -> found.size() == 1 && found.getFirst().get("external_id") != null).getFirst();
            String cardTs = (String) card.get("external_id");
            Map<String, Object> approve = DirectMessagesTest.buttons(workspace.posts(builds)).stream()
                .filter(value -> "APPROVED".equals(value.get("r")) && approval.toString().equals(value.get("d")))
                .findFirst()
                .orElseThrow();
            workspace.deliverPress(port, slack.systemId(), workspace.press(asker, builds, cardTs, thread,
                    JSON.writeValueAsString(approve)));
            await().atMost(WAIT).until(() -> said(workspace.ephemerals()).contains("Only members of"));
            assertThat(status(bench, approval)).isEqualTo("OPEN");
            workspace.deliverPress(port, slack.systemId(), workspace.press(teamMember, builds, cardTs, thread,
                    JSON.writeValueAsString(approve)));
            await().atMost(WAIT).until(() -> !"OPEN".equals(status(bench, approval)));
            assertThat(rows(bench.id(), "SELECT decided_via, decided_by_principal_id, decided_by_external_id"
                    + " FROM decision WHERE id = ?", approval).getFirst()).containsEntry("decided_via", "CHAT")
                .containsEntry("decided_by_principal_id", bench.adminPrincipal())
                .containsEntry("decided_by_external_id", teamMember);
            await().atMost(WAIT).until(() -> workspace.updates().stream()
                .anyMatch(update -> cardTs.equals(update.path("ts").asString())));

            // A question: any member's press of an option answers it, linked or not.
            JsonNode asked = runner.requestAndAck(runId, epoch, "ASK", Map.of("question", "Which registry?",
                    "options", List.of("npm", "maven")));
            UUID question = UUID.fromString(asked.get("payload").get("decisionId").asString());
            Map<String, Object> questionCard = await().atMost(WAIT).until(() -> rows(bench.id(),
                    "SELECT external_id FROM chat_post WHERE decision_id = ?", question),
                    found -> found.size() == 1 && found.getFirst().get("external_id") != null).getFirst();
            Map<String, Object> npm = DirectMessagesTest.buttons(workspace.posts(builds)).stream()
                .filter(value -> "npm".equals(value.get("o")) && question.toString().equals(value.get("d")))
                .findFirst()
                .orElseThrow();
            workspace.deliverPress(port, slack.systemId(), workspace.press(asker, builds,
                    (String) questionCard.get("external_id"), thread, JSON.writeValueAsString(npm)));
            await().atMost(WAIT).until(() -> !"OPEN".equals(status(bench, question)));
            assertThat(bench.member().get(bench.path("/decisions/" + question)).json().toString()).contains("npm");
            assertThat(rows(bench.id(), "SELECT decided_via, decided_by_external_id FROM decision WHERE id = ?",
                    question).getFirst()).containsEntry("decided_via", "CHAT")
                .containsEntry("decided_by_external_id", asker);
            runner.send(finished(runId.toString(), epoch, "Built."));
            JsonNode decided = runner.expect("run.assign");
            assertThat(decided.get("prompt").asString()).contains("# Answers");
            runner.assertFramesValid();
        }
    }

    /**
     * Review finding (package 5b): any member's channel list read the bot's channels from Slack again; at most once a
     * minute per system now, the kept list in between, so that members listing do not spend the bot's rate.
     */
    @Test
    void theChannelListReadsTheBotsChannelsFromSlackAtMostOnceAMinute() {
        Bench bench = bench(true);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        String support = workspace.channel("support", false);
        String list = bench.path("/systems/" + slack.systemId() + "/chat/channels");
        int before = workspace.botChannelListings();
        assertThat(bench.member().get(list).json().get("items").toString()).contains(support);
        assertThat(workspace.botChannelListings()).isEqualTo(before + 1);
        // Within the minute: the kept list, for anyone, and Slack is not asked again.
        String later = workspace.channel("later", false);
        assertThat(bench.admin().get(list).json().get("items").toString()).contains(support).doesNotContain(later);
        assertThat(bench.owner().get(list).status()).isEqualTo(200);
        assertThat(workspace.botChannelListings()).isEqualTo(before + 1);
    }

    /**
     * A chat user's erasure (§15.11; the package 6 contract, §5.9) through {@code POST /systems/{systemId}/identities/
     * {externalId}/erase}, in an open channel's conversation: their mention's body goes with its author, the title made
     * from it, the answer their press stored and the question's texts with their id and name, the run events that
     * quoted them, and the chat's own rows
     * of them (their {@code chat_user}, their events, their sign-in link); another chat user's words stay, and the
     * audit chain still verifies.
     */
    @Test
    void aChatUsersErasureReachesTheirWordsInTheThreadAndTheChatsRowsOfThem() throws Exception {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 2)) {
            JsonNode agent = agent(bench.member(), bench, "Eraser");
            Slack slack = slack(bench);
            FakeSlack.Workspace workspace = slack.workspace();
            String builds = workspace.channel("builds", false);
            String author = workspace.member("Max Member");
            String asker = workspace.member("Ann Asker");
            String bystander = workspace.member("Bea Bystander");
            linkSlack(bench, slack, bench.member(), author);
            workspace.inChannels(author, builds);
            bench.member().get(bench.path("/systems/" + slack.systemId() + "/chat/channels"));
            assertThat(trigger(bench.member(), bench, slack, agent, null, channel(builds, true)).status())
                .isEqualTo(201);

            Map<String, Object> mention = workspace.mention(asker, builds, "Ship the order of Ann Asker", null);
            workspace.deliver(port, slack.systemId(), mention);
            String thread = FakeSlack.Workspace.tsOf(mention);
            JsonNode assign = runner.expect("run.assign");
            assertThat(assign.get("prompt").asString()).contains("Ship the order of Ann Asker");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            runner.accepted(runId, epoch);
            UUID conversationId = (UUID) rows(bench.id(), "SELECT conversation_id FROM run WHERE id = ?", runId)
                .getFirst().get("conversation_id");

            // The asker presses an option of a question.
            JsonNode asked = runner.requestAndAck(runId, epoch, "ASK", Map.of("question", "Which carrier?",
                    "options", List.of("post", "courier")));
            UUID question = UUID.fromString(asked.get("payload").get("decisionId").asString());
            Map<String, Object> questionCard = await().atMost(WAIT).until(() -> rows(bench.id(),
                    "SELECT external_id FROM chat_post WHERE decision_id = ?", question),
                    found -> found.size() == 1 && found.getFirst().get("external_id") != null).getFirst();
            Map<String, Object> courier = DirectMessagesTest.buttons(workspace.posts(builds)).stream()
                .filter(value -> "courier".equals(value.get("o")) && question.toString().equals(value.get("d")))
                .findFirst()
                .orElseThrow();
            workspace.deliverPress(port, slack.systemId(), workspace.press(asker, builds,
                    (String) questionCard.get("external_id"), thread, JSON.writeValueAsString(courier)));
            await().atMost(WAIT).until(() -> !"OPEN".equals(status(bench, question)));
            assertThat(rows(bench.id(), "SELECT decided_by_external_id FROM decision WHERE id = ?", question)
                .getFirst()).containsEntry("decided_by_external_id", asker);
            runner.send(finished(runId.toString(), epoch, "Asked."));
            JsonNode decided = runner.expect("run.assign");

            // Another chat user's words in the same thread, and a sign-in link offered to the asker.
            Map<String, Object> other = workspace.mention(bystander, builds, "Mine too, please", thread);
            workspace.deliver(port, slack.systemId(), other);
            await().atMost(WAIT).until(() -> !rows(bench.id(), "SELECT id FROM message WHERE conversation_id = ?"
                    + " AND external_author ->> 'id' = ?", conversationId, bystander).isEmpty());
            try (TenantSql sql = TenantSql.in(bench.id())) {
                sql.accepted("""
                        INSERT INTO chat_link_request (token_hash, company_id, system_id, external_id, display_name,
                                                       created_at, expires_at)
                        VALUES (?, ?, ?, ?, 'Ann Asker', now(), now() + interval '1 hour')""",
                        "e".repeat(64), bench.id(), slack.systemId(), asker);
                sql.commit();
            }
            assertThat(rows(bench.id(), "SELECT external_id FROM chat_user WHERE system_id = ? AND external_id = ?",
                    slack.systemId(), asker)).hasSize(1);
            assertThat(rows(bench.id(), "SELECT id FROM chat_event WHERE system_id = ? AND sender = ?",
                    slack.systemId(), asker)).isNotEmpty();

            // Its dry run counts what the erasure then removes, by the modules' own selection, changing nothing.
            JsonNode preview = bench.owner().post(bench.path("/systems/" + slack.systemId() + "/identities/"
                    + asker + "/erase/preview"), null).json();
            assertThat(preview.get("holds")).isEmpty();
            assertThat(rows(bench.id(), "SELECT external_id FROM chat_user WHERE system_id = ? AND external_id = ?",
                    slack.systemId(), asker)).hasSize(1);
            ApiResponse erased = bench.owner().post(bench.path("/systems/" + slack.systemId() + "/identities/"
                    + asker + "/erase"), null);
            assertThat(erased.status()).as("%s", erased).isEqualTo(200);
            assertThat(erased.json().get("erased")).isEqualTo(preview.get("erases"));
            JsonNode counts = erased.json().get("erased").get("other");
            // The mention, and the answer the press stored as their message.
            assertThat(counts.get("messages").asLong()).isEqualTo(2);
            assertThat(counts.get("decisions").asLong()).isEqualTo(1);
            assertThat(counts.get("titles").asLong()).isEqualTo(1);
            assertThat(counts.get("chatUsers").asLong()).isEqualTo(1);
            assertThat(counts.get("chatEvents").asLong()).isPositive();
            assertThat(counts.get("chatLinks").asLong()).isEqualTo(1);

            // Their words and name are gone from the open conversation; the bystander's stay.
            assertThat(rows(bench.id(), "SELECT body, purged_at IS NOT NULL AS purged, external_author::text AS author"
                    + " FROM message WHERE conversation_id = ? AND kind = 'EXTERNAL' ORDER BY created_at, id",
                    conversationId)).satisfiesExactly(
                        mentioned -> assertThat(mentioned).containsEntry("body", null).containsEntry("purged", true)
                            .containsEntry("author", "{}"),
                        pressed -> assertThat(pressed).containsEntry("body", null).containsEntry("purged", true)
                            .containsEntry("author", "{}"),
                        theirs -> assertThat(theirs).containsEntry("body", "Mine too, please")
                            .containsEntry("purged", false));
            assertThat(rows(bench.id(), "SELECT title, state FROM conversation WHERE id = ?", conversationId)
                .getFirst()).containsEntry("title", null).containsEntry("state", "OPEN");
            // The question their press answered loses its texts, as a purge does, and their id and name there.
            assertThat(rows(bench.id(), "SELECT decided_via, decided_by_external_id, decided_by_name, option,"
                    + " purged_at IS NOT NULL AS purged FROM decision WHERE id = ?", question).getFirst())
                .containsEntry("decided_via", "CHAT").containsEntry("decided_by_external_id", null)
                .containsEntry("decided_by_name", null).containsEntry("option", null).containsEntry("purged", true);
            assertThat(rows(bench.id(), "SELECT events_purged_at FROM run WHERE id = ?", runId).getFirst()
                .get("events_purged_at")).isNotNull();
            assertThat(rows(bench.id(), "SELECT 1 FROM chat_user WHERE system_id = ? AND external_id = ?",
                    slack.systemId(), asker)).isEmpty();
            assertThat(rows(bench.id(), "SELECT 1 FROM chat_event WHERE system_id = ? AND sender = ?",
                    slack.systemId(), asker)).isEmpty();
            assertThat(rows(bench.id(), "SELECT 1 FROM chat_link_request WHERE system_id = ? AND external_id = ?",
                    slack.systemId(), asker)).isEmpty();
            assertThat(rows(bench.id(), "SELECT 1 FROM chat_user WHERE system_id = ? AND external_id = ?",
                    slack.systemId(), bystander)).hasSize(1);

            // The audit holds the identity's id, never their name or words, and its chain is intact.
            List<JsonNode> audit = auditEvents(bench.owner(), bench.company());
            assertThat(audit).filteredOn(event -> "identity.erased".equals(event.get("action").asString()))
                .singleElement()
                .satisfies(event -> assertThat(event.get("details").get("externalId").asString()).isEqualTo(asker));
            assertThat(audit.toString()).doesNotContain("Ann Asker").doesNotContain("Ship the order");
            assertThat(bench.owner().get(bench.path("/audit-events/verification")).json().get("valid").asBoolean())
                .isTrue();

            // Erasing again finds nothing of theirs.
            assertThat(bench.owner().post(bench.path("/systems/" + slack.systemId() + "/identities/" + asker
                    + "/erase"), null).json().get("erased").path("other").path("messages").asLong(0)).isZero();
            runner.send(finished(decided.get("runId").asString(), decided.get("epoch").asLong(), "Done."));
            runner.assertFramesValid();
        }
    }

    /**
     * The throttle of the package 5b review's finding 6: a list asked while the system had no bot claimed the minute,
     * and the list right after the bot was set served the kept, empty one. Without a bot nothing is claimed; a bot set,
     * replaced or removed, and the system's resume, forget the minute, so that the next list asks Slack at once.
     */
    @Test
    void theChannelListAsksSlackAtOnceWhenTheBotIsSetReplacedOrTheSystemResumes() {
        Bench bench = bench(true);
        FakeSlack.Workspace workspace = FakeSlack.workspace();
        UUID systemId = createSystem(bench.owner(), bench.company(), Map.of("name", unique("Slack"), "kind", "SLACK",
                "slack", Map.of("transport", "EVENTS_API")));
        assertThat(bench.owner().put(bench.path("/systems/" + systemId + "/chat/secrets"), Map.of("signingSecret",
                workspace.signingSecret)).status()).isEqualTo(200);
        String support = workspace.channel("support", false);
        String list = bench.path("/systems/" + systemId + "/chat/channels");

        // No bot yet: nothing to ask, nothing claimed.
        assertThat(bench.admin().get(list).json().get("items").toString()).doesNotContain(support);
        UUID bot = createServiceIdentity(bench.owner(), bench.company(), unique("Slack bot"));
        Map<String, Object> setBot = Map.of("principalId", bot.toString(), "token", workspace.botToken);
        assertThat(bench.owner().put(bench.path("/systems/" + systemId + "/bot"), setBot).status()).isEqualTo(200);
        // The bot set: the list right after asks Slack.
        assertThat(bench.admin().get(list).json().get("items").toString()).contains(support);

        // Within the minute, a bot set again (removed, then set): asked again at once.
        String desk = workspace.channel("desk", false);
        assertThat(bench.admin().get(list).json().get("items").toString()).doesNotContain(desk);
        assertThat(bench.owner().delete(bench.path("/systems/" + systemId + "/bot")).status()).isEqualTo(204);
        assertThat(bench.owner().put(bench.path("/systems/" + systemId + "/bot"), setBot).status()).isEqualTo(200);
        assertThat(bench.admin().get(list).json().get("items").toString()).contains(desk);

        // The system paused and resumed within the minute: asked again at once.
        String ops = workspace.channel("ops", false);
        assertThat(bench.owner().post(bench.path("/systems/" + systemId + "/pause"), Map.of("reason", "Noise",
                "stopRunning", false)).status()).isEqualTo(200);
        assertThat(bench.owner().post(bench.path("/systems/" + systemId + "/resume"), Map.of()).status())
            .isEqualTo(200);
        assertThat(bench.admin().get(list).json().get("items").toString()).contains(ops);
    }

    @Test
    void theTriggerWaitsOnItsBotAndOnItsChannelsAndGoesOnByItself() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 2)) {
            JsonNode agent = agent(bench.member(), bench, "Watcher");
            Slack slack = slack(bench);
            FakeSlack.Workspace workspace = slack.workspace();
            String ops = workspace.channel("ops", false);
            String author = workspace.member("Max Member");
            String asker = workspace.member("Ann Asker");
            linkSlack(bench, slack, bench.member(), author);
            workspace.inChannels(author, ops);
            bench.member().get(bench.path("/systems/" + slack.systemId() + "/chat/channels"));
            ApiResponse created = trigger(bench.member(), bench, slack, agent, null, channel(ops, true));
            assertThat(created.status()).as("%s", created).isEqualTo(201);
            String triggerId = created.json().get("id").asString();

            // The bot's service identity suspended: the trigger waits, and goes on by itself when it is resumed.
            assertThat(bench.owner().post(bench.path("/service-identities/" + slack.botPrincipal() + "/suspend"),
                    null).status()).isEqualTo(200);
            awaitWaiting(bench, triggerId, "BOT_SUSPENDED");
            assertThat(bench.owner().post(bench.path("/service-identities/" + slack.botPrincipal() + "/resume"),
                    null).status()).isEqualTo(200);
            awaitWaiting(bench, triggerId, null);

            // The bot removed: it waits; set again, it goes on, active all along.
            assertThat(bench.owner().delete(bench.path("/systems/" + slack.systemId() + "/bot")).status())
                .isEqualTo(204);
            awaitWaiting(bench, triggerId, "BOT_MISSING");
            assertThat(bench.member().get(bench.path("/triggers/" + triggerId)).json().get("status").asString())
                .isEqualTo("ACTIVE");
            ApiResponse set = bench.owner().put(bench.path("/systems/" + slack.systemId() + "/bot"), Map.of(
                    "principalId", slack.botPrincipal().toString(), "token", workspace.botToken));
            assertThat(set.status()).as("%s", set).isEqualTo(200);
            awaitWaiting(bench, triggerId, null);

            // The bot left its only channel: the trigger waits CHANNEL_UNAVAILABLE; back in it, it goes on.
            workspace.deliver(port, slack.systemId(), workspace.botLeft(ops));
            awaitWaiting(bench, triggerId, "CHANNEL_UNAVAILABLE");
            assertThat(rows(bench.id(), "SELECT state FROM trigger_channel WHERE trigger_id = ?",
                    UUID.fromString(triggerId)).getFirst()).containsEntry("state", "UNAVAILABLE");
            workspace.deliver(port, slack.systemId(), workspace.botJoined(ops, false));
            awaitWaiting(bench, triggerId, null);

            // And a mention fires it again.
            workspace.deliver(port, slack.systemId(), workspace.mention(asker, ops, "Status?", null));
            JsonNode assign = runner.expect("run.assign");
            assertThat(assign.get("prompt").asString()).contains("Status?");
        }
    }

    @Test
    void aMentionInATeamsChannelFiresItsTriggerAnsweredInTheChannelsThread() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, 2)) {
            JsonNode agent = agent(bench.member(), bench, "Teams desk");
            Teams teams = teams(bench);
            FakeBotFramework.Tenant tenant = teams.tenant();
            FakeBotFramework.TeamsUser author = tenant.user("Max Member", "user");
            FakeBotFramework.TeamsUser asker = tenant.user("Ann Asker", "user");
            asOwner("UPDATE app_user SET auth_source = 'OIDC', password_hash = NULL, oidc_issuer = ?,"
                    + " oidc_subject = ?, oidc_tenant_id = ?, oidc_object_id = ? WHERE id = ?",
                    "https://login.microsoftonline.com/" + tenant.tenantId + "/v2.0", "subject-"
                            + author.aadObjectId(), tenant.tenantId, author.aadObjectId(),
                    bench.company().userId("MEMBER"));
            // The author's first message links them by their Entra object id.
            tenant.deliver(port, teams.systemId(), tenant.personalMessage(author, "hello"));
            await().atMost(WAIT).until(() -> said(tenant.posts("a:dm-" + author.id())).contains("Linked to"));
            String team = "19:team-" + UUID.randomUUID() + "@thread.tacv2";
            String channel = "19:support-" + UUID.randomUUID() + "@thread.tacv2";

            // A mention before any trigger: a hint, and the bot knows the channel from it.
            Map<String, Object> early = tenant.channelMention(asker, team, channel, null, "Anyone?");
            tenant.deliver(port, teams.systemId(), early);
            String earlyId = channel + ";messageid=" + early.get("id") + ":" + early.get("id");
            assertThat(awaitEvent(bench.id(), teams.systemId(), earlyId, "REFUSED")).containsEntry("reason",
                    "NO_TRIGGER");

            ApiResponse created = trigger(bench.member(), bench, teams.systemId(), agent, null, channel(channel, true));
            assertThat(created.status()).as("%s", created).isEqualTo(201);

            // A mention fires the trigger; its answer goes to the channel's thread of the mention.
            Map<String, Object> mention = tenant.channelMention(asker, team, channel, null, "How do I reset it?");
            tenant.deliver(port, teams.systemId(), mention);
            JsonNode assign = runner.expect("run.assign");
            assertThat(assign.get("prompt").asString()).contains("How do I reset it?", "Ann Asker (in Teams)");
            String thread = channel + ";messageid=" + mention.get("id");
            runner.accepted(UUID.fromString(assign.get("runId").asString()), assign.get("epoch").asLong());
            runner.send(finished(assign.get("runId").asString(), assign.get("epoch").asLong(), "Use the reset page."));
            await().atMost(WAIT).until(() -> said(tenant.posts(thread)).contains("Use the reset page."));

            // A reply in the thread continues the conversation.
            tenant.deliver(port, teams.systemId(), tenant.channelMention(asker, team, channel,
                    String.valueOf(mention.get("id")), "Thanks, and on mobile?"));
            JsonNode next = runner.expect("run.assign");
            assertThat(next.get("prompt").asString()).contains("Thanks, and on mobile?");
            runner.assertFramesValid();
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    private JsonNode agent(ApiClient creator, Bench bench, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique(name));
        body.put("runtimes", List.of("CLAUDE_CODE", "CODEX"));
        body.put("instructions", "Answer briefly.");
        body.put("systems", List.of());
        body.put("repositories", List.of());
        body.put("shareGroupIds", List.of());
        ApiResponse agent = creator.post(bench.path("/agents"), body);
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        return agent.json();
    }

    private static Map<String, Object> channel(String channelId, boolean isDefault) {
        return Map.of("channelId", channelId, "default", isDefault);
    }

    private ApiResponse trigger(ApiClient author, Bench bench, Slack slack, JsonNode agent, @Nullable UUID team,
            Map<String, Object> channel) {
        return trigger(author, bench, slack.systemId(), agent, team, channel);
    }

    private ApiResponse trigger(ApiClient author, Bench bench, UUID systemId, JsonNode agent, @Nullable UUID team,
            Map<String, Object> channel) {
        return author.post(bench.path("/triggers"), withModel(triggerBody(bench, systemId, agent, team, channel)));
    }

    private static Map<String, Object> triggerBody(Bench bench, UUID systemId, JsonNode agent, @Nullable UUID team,
            Map<String, Object> channel) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Desk"));
        body.put("source", "CHANNEL");
        body.put("systemId", systemId.toString());
        body.put("channels", List.of(channel));
        body.put("agentId", agent.get("id").asString());
        body.put("agentVersion", agent.get("version").asInt());
        body.put("instruction", "Answer the support channel.");
        body.put("accounts", List.of());
        body.put("payerPrincipalId", bench.memberPrincipal().toString());
        body.put("acknowledgeOpenEvents", true);
        if (team != null) {
            body.put("teamGroupId", team.toString());
        }
        return body;
    }

    private static JsonNode item(JsonNode list, String channelId) {
        for (JsonNode item : list.get("items")) {
            if (channelId.equals(item.get("channelId").asString())) {
                return item;
            }
        }
        throw new AssertionError("No channel " + channelId + " in " + list);
    }

    private static String status(Bench bench, UUID decisionId) {
        return bench.member().get(bench.path("/decisions/" + decisionId)).json().get("status").asString();
    }

    private static void awaitWaiting(Bench bench, String triggerId, @Nullable String reason) {
        await().atMost(WAIT).until(() -> {
            JsonNode waiting = bench.member().get(bench.path("/triggers/" + triggerId)).json().get("waitingReason");
            String now = waiting == null || waiting.isNull() ? null : waiting.asString();
            return java.util.Objects.equals(now, reason);
        });
    }

    private static Map<String, Object> finished(String runId, long epoch, String summary) {
        return Map.of("type", "run.finished", "runId", runId, "epoch", epoch, "outcome", "SUCCEEDED", "lastSeq", 0,
                "summary", summary, "sessionId", "session-" + runId, "git", List.of(), "result", Map.of("outcome",
                        "COMPLETED", "summary", summary, "nextSteps", List.of(), "openQuestions", List.of()));
    }
}
