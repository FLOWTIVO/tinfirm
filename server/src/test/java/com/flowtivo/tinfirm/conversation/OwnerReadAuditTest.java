package com.flowtivo.tinfirm.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.TestConversations;

import tools.jackson.databind.JsonNode;

/**
 * OWNER's reads of personal conversations are audited (the security review of the redesign, row 18; CONVENTIONS §8,
 * {@code conversation.read}): one record per read path of another person's conversation, a list once per person it
 * lists, a decision list once per conversation; none for the person's own reads, none for OWNER's own conversations;
 * and every read again, with no throttle (the owner's decision, package 6's open point 9): two reads in a row are two
 * records, a stream one when it opens. An instance admin acting in the company is recorded as OWNER is.
 */
class OwnerReadAuditTest extends ConversationTestBench {

    private static final String READ = "conversation.read";

    @Test
    void everyReadByOwnerOfAnotherPersonsConversationIsAuditedOnEveryPath() {
        Bench bench = bench(true);
        UUID membersAgent = agent(bench.member(), bench, "Helper", "", List.<UUID[]>of(), "READ", List.of());
        UUID adminsAgent = agent(bench.admin(), bench, "Admin's", "", List.<UUID[]>of(), "READ", List.of());
        UUID ownersAgent = agent(bench.owner(), bench, "Owner's", "", List.<UUID[]>of(), "READ", List.of());
        try (FakeRunner runner = companyHost(bench, 8)) {
            JsonNode a = start(bench.member(), bench, membersAgent, "Plan the release");
            String id = a.get("id").asString();
            String runId = lastTurn(a).get("id").asString();
            String files = bench.path("/conversations/" + id + "/attachments");
            ApiResponse uploaded = bench.member().upload(files, new ApiClient.Multipart("notes.txt", "text/plain",
                    "The release notes".getBytes(StandardCharsets.UTF_8)));
            assertThat(uploaded.status()).as("%s", uploaded).isEqualTo(201);
            String file = files + "/" + uploaded.json().get("id").asString();
            UUID question = TestConversations.question(bench.id(), UUID.fromString(id), bench.memberPrincipal(),
                    "Which release?");
            JsonNode own = start(bench.owner(), bench, ownersAgent, "My own");
            String ownRun = lastTurn(own).get("id").asString();

            // The person reads everything of their own; OWNER reads their own conversation: nothing on record.
            for (String path : List.of("/conversations/" + id, "/conversations", "/runs/" + runId,
                    "/runs/" + runId + "/events", "/conversations/" + id + "/attachments", "/decisions",
                    "/decisions/" + question)) {
                assertThat(bench.member().get(bench.path(path)).status()).as(path).isEqualTo(200);
            }
            assertThat(bench.member().download(file).statusCode()).isEqualTo(200);
            stream(bench.member(), bench, runId);
            assertThat(bench.owner().get(bench.path("/conversations/" + own.get("id").asString())).status())
                .isEqualTo(200);
            assertThat(bench.owner().get(bench.path("/runs/" + ownRun)).status()).isEqualTo(200);
            assertThat(bench.owner().get(bench.path("/conversations?personId=" + bench.ownerPrincipal())).status())
                .isEqualTo(200);
            assertThat(reads(bench)).isEmpty();

            // OWNER reads the member's conversation on every path: one record each.
            assertThat(conversation(bench.owner(), bench, id).get("seenByOwner").asBoolean()).isTrue();
            assertThat(bench.owner().get(bench.path("/conversations?personId=" + bench.memberPrincipal()))
                .json().get("items")).hasSize(1);
            assertThat(bench.owner().get(bench.path("/runs/" + runId)).status()).isEqualTo(200);
            assertThat(bench.owner().get(bench.path("/runs/" + runId + "/events")).status()).isEqualTo(200);
            stream(bench.owner(), bench, runId);
            assertThat(bench.owner().get(files).status()).isEqualTo(200);
            assertThat(bench.owner().download(file).statusCode()).isEqualTo(200);
            assertThat(bench.owner().get(bench.path("/decisions/" + question)).status()).isEqualTo(200);

            // The whole list names each other person it lists once: the member and the admin, not OWNER.
            JsonNode d = start(bench.admin(), bench, adminsAgent, "The admin's");
            assertThat(bench.admin().get(bench.path("/conversations/" + d.get("id").asString())).status())
                .isEqualTo(200);
            assertThat(bench.owner().get(bench.path("/conversations")).json().get("items")).hasSize(3);
            // The decision list names each conversation of another person it lists: A and B.
            String b = start(bench.member(), bench, membersAgent, "Second").get("id").asString();
            TestConversations.question(bench.id(), UUID.fromString(b), bench.memberPrincipal(), "Which branch?");
            assertThat(bench.owner().get(bench.path("/decisions")).json().get("items")).hasSize(2);
            // OWNER's end answers the conversation in full: a read of it.
            String c = start(bench.member(), bench, membersAgent, "Third").get("id").asString();
            assertThat(bench.owner().post(bench.path("/conversations/" + c + "/end"), null).status()).isEqualTo(200);

            UUID owner = bench.company().userId("OWNER");
            String member = bench.memberPrincipal().toString();
            List<JsonNode> reads = reads(bench);
            assertThat(reads).allSatisfy(event -> {
                assertThat(event.get("actor").get("type").asString()).isEqualTo("USER");
                assertThat(event.get("actor").get("id").asString()).isEqualTo(owner.toString());
            });
            assertThat(reads).extracting(event -> event.get("details").get("what").asString(),
                    event -> event.get("details").has("conversationId")
                            ? event.get("details").get("conversationId").asString() : null,
                    event -> event.get("details").get("personPrincipalId").asString(),
                    event -> event.get("targetType").asString(), event -> event.get("targetId").asString())
                .containsExactlyInAnyOrder(
                        tuple("conversation", id, member, "conversation", id),
                        tuple("conversations", null, member, "principal", member),
                        tuple("runs", id, member, "conversation", id),
                        tuple("run-events", id, member, "conversation", id),
                        tuple("run-stream", id, member, "conversation", id),
                        tuple("attachments", id, member, "conversation", id),
                        tuple("attachment", id, member, "conversation", id),
                        tuple("decisions", id, member, "conversation", id),
                        tuple("conversations", null, member, "principal", member),
                        tuple("conversations", null, bench.adminPrincipal().toString(), "principal",
                                bench.adminPrincipal().toString()),
                        tuple("decisions", id, member, "conversation", id),
                        tuple("decisions", b, member, "conversation", b),
                        tuple("conversation", c, member, "conversation", c));

            // Every read is recorded, with no throttle: two reads in a row are two records, and so on.
            conversation(bench.owner(), bench, id);
            conversation(bench.owner(), bench, id);
            assertThat(bench.owner().get(bench.path("/runs/" + runId + "/events")).status()).isEqualTo(200);
            // A stream is one read, recorded when it opens: its polls check access again and record nothing.
            streamAcrossPolls(bench.owner(), bench, runId);
            assertThat(bench.owner().download(file).statusCode()).isEqualTo(200);
            assertThat(bench.owner().get(bench.path("/conversations?personId=" + bench.memberPrincipal()))
                .status()).isEqualTo(200);
            assertThat(bench.owner().get(bench.path("/decisions")).status()).isEqualTo(200);
            List<JsonNode> again = reads(bench);
            assertThat(again).hasSize(reads.size() + 8);
            assertThat(again.subList(0, 8)).extracting(event -> event.get("details").get("what").asString(),
                    event -> event.get("targetId").asString())
                .containsExactlyInAnyOrder(tuple("conversation", id), tuple("conversation", id),
                        tuple("run-events", id), tuple("run-stream", id), tuple("attachment", id),
                        tuple("conversations", member), tuple("decisions", id), tuple("decisions", b));

            // An instance admin who is not a member has ADMIN's view (package 6, §5.10): the conversation is 404 to
            // them, and nothing is read or recorded.
            assertThat(admin().get(bench.path("/conversations/" + id)).status()).isEqualTo(404);
            assertThat(reads(bench)).hasSize(again.size());
            runner.assertFramesValid();
        }
    }

    /** The company's {@code conversation.read} events, newest first. */
    private List<JsonNode> reads(Bench bench) {
        return auditEvents(bench.owner(), bench.company(), READ);
    }

    /**
     * Opens the run's event stream and keeps it while the server polls the run again: a keep-alive comment comes after
     * each wait (every second in the tests), and a poll after each, so three comments are at least two polls. Then goes
     * away.
     */
    private static void streamAcrossPolls(ApiClient client, Bench bench, String runId) {
        HttpResponse<Stream<String>> response = client.openEventStream(bench.path("/runs/" + runId
                + "/events/stream"), Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        assertTimeoutPreemptively(WAIT, () -> {
            try (Stream<String> lines = response.body()) {
                Iterator<String> frames = lines.iterator();
                int keepAlives = 0;
                while (keepAlives < 3 && frames.hasNext()) {
                    if (frames.next().startsWith(":")) {
                        keepAlives++;
                    }
                }
                assertThat(keepAlives).as("keep-alive comments before the stream ended").isEqualTo(3);
            }
        });
    }

    /** Opens the run's event stream, sees its headers, and goes away: one connection. */
    private static void stream(ApiClient client, Bench bench, String runId) {
        HttpResponse<Stream<String>> response = client.openEventStream(bench.path("/runs/" + runId
                + "/events/stream"), Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        response.body().close();
    }
}
