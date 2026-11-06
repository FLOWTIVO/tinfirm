package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.SseEvent;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;

/**
 * {@code GET /conversations/{id}/stream}: signals only, after each committed change, naming its parts; a change of
 * each part signals; who sees the conversation streams it, nobody else learns of it; a suspended person is cut
 * off at the next check while OWNER's stream goes on; opening it records no {@code conversation.read}; it closes with a
 * final status when the conversation ends.
 */
class ConversationStreamTest extends ConversationBench {

    @Autowired
    ConversationStore conversationStore;

    private static String streamPath(Conversation conversation) {
        return conversation.bench().path("/conversations/" + conversation.id() + "/stream");
    }

    /** Opens the stream as the client, and reads its first event: the conversation's status. */
    private static Iterator<SseEvent> open(ApiClient client, Conversation conversation) {
        HttpResponse<Stream<String>> response = client.openEventStream(streamPath(conversation), Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        Iterator<SseEvent> stream = SseEvent.iterate(response.body());
        SseEvent first = SseEvent.next(stream, WAIT);
        assertThat(first.event()).isEqualTo("conversation-status");
        assertThat(first.id()).as("signals carry no id").isNull();
        assertThat(first.json().get("id").asString()).isEqualTo(conversation.id().toString());
        assertThat(first.json().get("state").asString()).isEqualTo("OPEN");
        return stream;
    }

    /**
     * Reads {@code conversation-changed} events until one names the part (an action may commit more than once): its
     * parts.
     */
    private static List<String> changedWith(Iterator<SseEvent> stream, String part) {
        while (true) {
            List<String> parts = changed(stream);
            if (parts.contains(part)) {
                return parts;
            }
        }
    }

    /** The parts of the next {@code conversation-changed}, past any {@code conversation-status}. */
    private static List<String> changed(Iterator<SseEvent> stream) {
        while (true) {
            SseEvent event = SseEvent.next(stream, WAIT);
            assertThat(event).as("the stream goes on").isNotNull();
            if ("conversation-changed".equals(event.event())) {
                List<String> parts = new ArrayList<>();
                event.json().get("parts").forEach(part -> parts.add(part.asString()));
                return parts;
            }
            assertThat(event.event()).isEqualTo("conversation-status");
        }
    }

    private void grantHost(Conversation conversation, String host) {
        transactions.runInCompany(conversation.bench().companyId(), () -> conversationStore.updateGrantedHosts(
                conversation.bench().companyId(), conversation.id(), List.of(host), Instant.now()));
    }

    @Test
    void eachChangeSignalsItsPartsAndTheStreamClosesWhenTheConversationEnds() {
        Bench bench = bench();
        Conversation first = conversation(bench);
        String path = bench.path("/conversations/" + first.id());
        Iterator<SseEvent> stream = open(bench.person(), first);

        // STATUS: renamed.
        assertThat(bench.person().patch(path, Map.of("title", "Checkout in EUR")).status()).isEqualTo(200);
        changedWith(stream, "STATUS");
        // MESSAGES: the person writes while the turn runs; the message waits for the next turn.
        ApiResponse written = bench.person().post(path + "/messages", Map.of("body", "Also check the tax."));
        assertThat(written.status()).as("%s", written).isEqualTo(201);
        changedWith(stream, "MESSAGES");
        // TURNS: the turn ends, and the next one is asked for: its run is made, and it runs.
        TestRuns.ended(bench.companyId(), first.runId());
        assertThat(bench.person().post(path + "/messages", Map.of("body", "Go on.")).status()).isEqualTo(201);
        changedWith(stream, "TURNS");
        Conversation conversation = nextTurn(first, 2);
        // DECISIONS: the agent asks.
        assertThat(outcome(request(conversation, "ASK", Map.of("question", "Which region?")))).isEqualTo("PENDING");
        changedWith(stream, "DECISIONS");
        // BINDINGS: the agent's first call on Jira, which the person has not connected: the binding waits.
        String handle = bench.admin().get(bench.path("/systems/" + bench.jiraId())).json().get("handle").asString();
        assertThat(outcome(call(conversation, handle + "_get", Map.of("key", "SHOP-1")))).isEqualTo("PENDING");
        changedWith(stream, "BINDINGS");
        // OUTPUTS: the agent saves a link.
        assertThat(outcome(request(conversation, "ARTIFACT", Map.of("url", "https://example.com/report", "title",
                "Report")))).isEqualTo("DONE");
        changedWith(stream, "OUTPUTS");
        // ATTACHMENTS: the person adds a file.
        ApiResponse uploaded = bench.person().upload(path + "/attachments", new ApiClient.Multipart("notes.txt",
                "text/plain", "The release notes".getBytes(StandardCharsets.UTF_8)));
        assertThat(uploaded.status()).as("%s", uploaded).isEqualTo(201);
        changedWith(stream, "ATTACHMENTS");
        // WORKSPACE: a network host granted, as an approved request grants it.
        grantHost(conversation, "example.com");
        changedWith(stream, "WORKSPACE");

        // The conversation ends: a final status, and the stream closes.
        assertThat(bench.person().post(path + "/end", null).status()).isEqualTo(200);
        SseEvent last = null;
        for (SseEvent event = SseEvent.next(stream, WAIT); event != null; event = SseEvent.next(stream, WAIT)) {
            last = event;
        }
        assertThat(last).isNotNull();
        assertThat(last.event()).isEqualTo("conversation-status");
        assertThat(last.json().get("state").asString()).isEqualTo("ENDED");
        assertThat(last.json().get("endedReason").asString()).isEqualTo("USER");
        // An ended conversation's stream is its status alone.
        HttpResponse<Stream<String>> ended = bench.person().openEventStream(streamPath(conversation), Map.of());
        assertThat(ended.statusCode()).isEqualTo(200);
        List<SseEvent> all = SseEvent.readAll(ended.body(), WAIT);
        assertThat(all).singleElement().satisfies(event -> {
            assertThat(event.event()).isEqualTo("conversation-status");
            assertThat(event.json().get("state").asString()).isEqualTo("ENDED");
        });
    }

    @Test
    void whoSeesItStreamsItAndASuspendedPersonIsCutOff() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        // Nobody else learns of it: ADMIN does not see a person's conversation, another company's OWNER neither.
        assertThat(bench.admin().openEventStream(streamPath(conversation), Map.of()).statusCode()).isEqualTo(404);
        TestCompany elsewhere = companyWithRoles();
        assertThat(loginAs(elsewhere.email("OWNER")).openEventStream(streamPath(conversation), Map.of())
            .statusCode()).isEqualTo(404);
        assertThat(loginAs(elsewhere.email("OWNER")).openEventStream(elsewhere.path("/conversations/"
                + conversation.id() + "/stream"), Map.of()).statusCode()).isEqualTo(404);

        // OWNER sees every conversation; opening the stream is not a read on record (the client's reads are).
        int reads = auditEvents(bench.owner(), bench.company(), "conversation.read").size();
        Iterator<SseEvent> owners = open(bench.owner(), conversation);
        Iterator<SseEvent> persons = open(bench.person(), conversation);
        assertThat(auditEvents(bench.owner(), bench.company(), "conversation.read")).hasSize(reads);

        // The person is suspended: their stream ends at its next check, with no event more.
        ApiResponse suspended = bench.owner().post(bench.path("/members/" + bench.company().userId("MEMBER")
                + "/suspend"), Map.of("reason", "Laptop stolen"));
        assertThat(suspended.status()).as("%s", suspended).isEqualTo(200);
        assertThat(SseEvent.next(persons, WAIT)).as("the suspended person's stream ends").isNull();
        // A suspended member opens none, as they read none.
        assertThat(bench.person().openEventStream(streamPath(conversation), Map.of()).statusCode()).isEqualTo(403);
        // OWNER's goes on.
        grantHost(conversation, "example.com");
        changedWith(owners, "WORKSPACE");
    }

    @Test
    void aStreamEndsWhenTheSessionItWasOpenedWithEnds() {
        // L1: the check at every keep-alive covers the HTTP session too. Logout deletes the session; the stream of
        // the signed-out person ends with no event more, while another person's session goes on.
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        Iterator<SseEvent> owners = open(bench.owner(), conversation);
        ApiClient person = loginAs(bench.company().email("MEMBER"));
        Iterator<SseEvent> persons = open(person, conversation);

        assertThat(person.post("/api/auth/logout", null).status()).isEqualTo(204);
        assertThat(SseEvent.next(persons, WAIT)).as("the signed-out person's stream ends").isNull();
        // OWNER's stream, on its own session, goes on.
        grantHost(conversation, "example.com");
        changedWith(owners, "WORKSPACE");
    }

    @Test
    void aChangeRolledBackSignalsNothing() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        Iterator<SseEvent> stream = open(bench.person(), conversation);
        UUID companyId = bench.companyId();
        try {
            transactions.runInCompany(companyId, () -> {
                conversationStore.rename(companyId, conversation.id(), "Never saved", Instant.now());
                throw new IllegalStateException("rolled back");
            });
        }
        catch (IllegalStateException expected) {
            // The change is undone, and so is its signal.
        }
        grantHost(conversation, "kept.example");
        // Only the committed change's part: the rename rolled back signalled nothing.
        assertThat(changed(stream)).containsExactly("WORKSPACE");
        JsonNode read = bench.person().get(bench.path("/conversations/" + conversation.id())).json();
        assertThat(read.get("title").asString()).isNotEqualTo("Never saved");
    }
}
