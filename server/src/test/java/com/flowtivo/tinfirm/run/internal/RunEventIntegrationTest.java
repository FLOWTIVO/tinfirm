package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.run.RunEvents;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.RecordedEvents;
import com.flowtivo.tinfirm.support.SseEvent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;

/**
 * Run events: stored once per sequence number, their usage kept, paged, and streamed live as Server-Sent Events (a
 * {@code run-status} first, the events replayed after {@code Last-Event-ID}, live ones, a final {@code run-status}),
 * to the run's person and OWNER only; an event of a run that ended is dropped and counted.
 */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RunEventIntegrationTest extends RunTestBench {

    @Autowired
    MeterRegistry registry;

    @Test
    void eventsAreStoredPagedAndStreamedLiveToWhoSeesTheRun() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, GATEWAY_VERSION, 4)) {
            Turn turn = start(bench);
            expectAssign(runner, turn.runId());
            runner.accepted(turn.runId(), 1);
            awaitRun(bench, turn.runId(), "RUNNING");
            for (int seq = 1; seq <= 3; seq++) {
                runner.event(turn.runId(), 1, seq, "AGENT_MESSAGE", Map.of("text", "chunk " + seq));
            }
            // Sent again after a reconnect: stored once.
            runner.event(turn.runId(), 1, 2, "AGENT_MESSAGE", Map.of("text", "chunk 2"));
            runner.event(turn.runId(), 1, 4, "USAGE", Map.of("inputTokens", 120, "outputTokens", 30));
            await().atMost(WAIT).until(() -> events(bench, turn.runId(), 0).get("items").size() == 4);
            assertThat(events(bench, turn.runId(), 0).get("complete").asBoolean()).isFalse();
            assertThat(getRun(bench, turn.runId()).get("usage").get("inputTokens").asLong()).isEqualTo(120);
            assertThat(bench.person().get(bench.path("/runs/" + turn.runId() + "/events?limit=0")).status())
                .isEqualTo(400);

            String path = bench.path("/runs/" + turn.runId() + "/events/stream");
            ApiClient admin = loginAs(bench.company().email("ADMIN"));
            assertThat(admin.openEventStream(path, Map.of()).statusCode()).isEqualTo(404);
            HttpResponse<Stream<String>> response = bench.person().openEventStream(path, Map.of("Last-Event-ID", "1"));
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
            assertThat(response.headers().firstValue("X-Accel-Buffering")).hasValue("no");
            Iterator<SseEvent> stream = SseEvent.iterate(response.body());
            SseEvent first = SseEvent.next(stream, WAIT);
            assertThat(first.event()).isEqualTo("run-status");
            assertThat(first.id()).isNull();
            assertThat(first.json().get("status").asString()).isEqualTo("RUNNING");
            assertThat(first.json().get("conversationId").asString()).isEqualTo(turn.conversationId().toString());
            assertThat(List.of(SseEvent.next(stream, WAIT), SseEvent.next(stream, WAIT), SseEvent.next(stream, WAIT)))
                .extracting(SseEvent::id)
                .containsExactly("2", "3", "4");

            runner.event(turn.runId(), 1, 5, "AGENT_MESSAGE", Map.of("text", "live"));
            SseEvent live = SseEvent.next(stream, WAIT);
            assertThat(live.id()).isEqualTo("5");
            assertThat(live.json().get("payload").get("text").asString()).isEqualTo("live");

            runner.send(finished(turn.runId(), 1, "SUCCEEDED", 5, "Done.", "session-e"));
            SseEvent last = SseEvent.next(stream, WAIT);
            assertThat(last.event()).isEqualTo("run-status");
            assertThat(last.json().get("status").asString()).isEqualTo("SUCCEEDED");
            assertThat(SseEvent.next(stream, WAIT)).as("the stream ends after the final run-status").isNull();
            assertThat(events(bench, turn.runId(), 0).get("complete").asBoolean()).isTrue();
            assertThat(events(bench, turn.runId(), 3).get("items")).hasSize(2);
            // What the agent said, without the rest: the gap at 4 is the usage between its messages.
            JsonNode said = events(bench, turn.runId(), "?types=AGENT_MESSAGE&limit=3");
            assertThat(said.get("items")).extracting(item -> item.get("seq").asLong()).containsExactly(1L, 2L, 3L);
            assertThat(said.get("complete").asBoolean()).as("a full page").isFalse();
            said = events(bench, turn.runId(), "?types=AGENT_MESSAGE&afterSeq=3");
            assertThat(said.get("items")).extracting(item -> item.get("seq").asLong()).containsExactly(5L);
            assertThat(said.get("complete").asBoolean()).isTrue();
            assertThat(events(bench, turn.runId(), "?types=USAGE,AGENT_MESSAGE").get("items")).hasSize(5);
            assertThat(bench.person().get(bench.path("/runs/" + turn.runId() + "/events?types=NOTHING")).status())
                .isEqualTo(400);
            JsonNode counts = getRun(bench, turn.runId()).get("counts");
            assertThat(counts.get("messages").asInt()).isEqualTo(1);
            // Package 6: the run's end is published for other modules, once, with its final status.
            await().atMost(WAIT).until(() -> !RecordedEvents.of(bench.companyId(), RunEvents.RunEnded.class).stream()
                .filter(ended -> ended.runId().equals(turn.runId())).toList().isEmpty());
            assertThat(RecordedEvents.of(bench.companyId(), RunEvents.RunEnded.class))
                .filteredOn(ended -> ended.runId().equals(turn.runId()))
                .singleElement()
                .satisfies(ended -> {
                    assertThat(ended.status()).isEqualTo(RunStatus.SUCCEEDED);
                    assertThat(ended.conversationId()).isEqualTo(turn.conversationId());
                });
            runner.assertFramesValid();
        }
    }

    @Test
    void aStreamEndsOnceItsViewerNoLongerSeesTheRun() {
        // Two more OWNERs see the person's run (I17): one is removed from the company, the other becomes ADMIN, who
        // sees only their own conversations. Each one's stream ends at its next read; the person's goes on.
        Bench bench = bench(true);
        String removedEmail = uniqueEmail("removed-owner");
        UUID removedId = createUser(admin(), removedEmail, false);
        addMember(bench.owner(), bench.companyId(), removedEmail, "OWNER");
        String demotedEmail = uniqueEmail("demoted-owner");
        UUID demotedId = createUser(admin(), demotedEmail, false);
        addMember(bench.owner(), bench.companyId(), demotedEmail, "OWNER");
        try (FakeRunner runner = companyHost(bench, GATEWAY_VERSION, 4)) {
            Turn turn = start(bench);
            expectAssign(runner, turn.runId());
            runner.accepted(turn.runId(), 1);
            awaitRun(bench, turn.runId(), "RUNNING");
            String path = bench.path("/runs/" + turn.runId() + "/events/stream");
            Iterator<SseEvent> removed = open(loginAs(removedEmail), path);
            Iterator<SseEvent> demoted = open(loginAs(demotedEmail), path);
            Iterator<SseEvent> person = open(bench.person(), path);

            assertThat(bench.owner().delete(bench.path("/members/" + removedId)).status()).isEqualTo(204);
            assertThat(bench.owner().patch(bench.path("/members/" + demotedId), Map.of("role", "ADMIN")).status())
                .isEqualTo(200);
            runner.event(turn.runId(), 1, 1, "AGENT_MESSAGE", Map.of("text", "after the change"));

            assertThat(SseEvent.next(person, WAIT).id()).isEqualTo("1");
            assertThat(SseEvent.next(removed, WAIT)).as("the removed member's stream ends").isNull();
            assertThat(SseEvent.next(demoted, WAIT)).as("the demoted OWNER's stream ends").isNull();
            assertThat(getRun(bench, turn.runId()).get("status").asString()).isEqualTo("RUNNING");
            runner.send(finished(turn.runId(), 1, "SUCCEEDED", 1, "Done.", "session-r"));
            assertThat(SseEvent.next(person, WAIT).event()).isEqualTo("run-status");
            runner.assertFramesValid();
        }
    }

    @Test
    void aStreamEndsWhenTheSessionItWasOpenedWithEnds() {
        // L1: logout deletes the HTTP session; the stream opened with it ends at its next check, with no frame more,
        // while the run goes on and another person's stream (OWNER's, on its own session) is unaffected.
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, GATEWAY_VERSION, 4)) {
            Turn turn = start(bench);
            expectAssign(runner, turn.runId());
            runner.accepted(turn.runId(), 1);
            awaitRun(bench, turn.runId(), "RUNNING");
            String path = bench.path("/runs/" + turn.runId() + "/events/stream");
            ApiClient person = loginAs(bench.company().email("MEMBER"));
            Iterator<SseEvent> signedOut = open(person, path);
            Iterator<SseEvent> owner = open(bench.owner(), path);

            assertThat(person.post("/api/auth/logout", null).status()).isEqualTo(204);
            runner.event(turn.runId(), 1, 1, "AGENT_MESSAGE", Map.of("text", "after the logout"));

            assertThat(SseEvent.next(owner, WAIT).id()).isEqualTo("1");
            assertThat(SseEvent.next(signedOut, WAIT)).as("the signed-out person's stream ends").isNull();
            assertThat(getRun(bench, turn.runId()).get("status").asString()).isEqualTo("RUNNING");
            runner.send(finished(turn.runId(), 1, "SUCCEEDED", 1, "Done.", "session-l1"));
            assertThat(SseEvent.next(owner, WAIT).event()).isEqualTo("run-status");
            runner.assertFramesValid();
        }
    }

    @Test
    void anEventOfARunThatEndedIsDropped() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, GATEWAY_VERSION, 4)) {
            Turn turn = start(bench);
            expectAssign(runner, turn.runId());
            runner.accepted(turn.runId(), 1);
            awaitRun(bench, turn.runId(), "RUNNING");
            runner.event(turn.runId(), 1, 1, "AGENT_MESSAGE", Map.of("text", "Working."));
            runner.send(finished(turn.runId(), 1, "SUCCEEDED", 1, "Done.", "session-late"));
            awaitRun(bench, turn.runId(), "SUCCEEDED");
            double before = lateEvents();

            // An event of the run's epoch after its end (a host that lags, or sends its window again): not stored. A
            // heartbeat behind it is answered once it was handled, since a connection's frames are handled in order.
            runner.event(turn.runId(), 1, 2, "AGENT_MESSAGE", Map.of("text", "Late."));
            runner.heartbeat(turn.runId(), 1);
            assertThat(runner.expect("run.cancel").get("runId").asString()).isEqualTo(turn.runId().toString());
            JsonNode events = events(bench, turn.runId(), 0);
            assertThat(events.get("items")).extracting(item -> item.get("seq").asLong()).containsExactly(1L);
            assertThat(events.get("complete").asBoolean()).isTrue();
            assertThat(query(bench.companyId(), "SELECT event_count FROM run WHERE id = ?", turn.runId()))
                .isEqualTo(1);
            assertThat(lateEvents()).isEqualTo(before + 1);
            runner.assertFramesValid();
        }
    }

    private double lateEvents() {
        Counter counter = registry.find("tinfirm.run.late.events").counter();
        return counter != null ? counter.count() : 0;
    }

    /** Opens the run's stream as the client and reads its first frame, the run's status. */
    private static Iterator<SseEvent> open(ApiClient client, String path) {
        HttpResponse<Stream<String>> response = client.openEventStream(path, Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        Iterator<SseEvent> stream = SseEvent.iterate(response.body());
        assertThat(SseEvent.next(stream, WAIT).event()).isEqualTo("run-status");
        return stream;
    }

    private JsonNode events(Bench bench, UUID runId, long afterSeq) {
        return events(bench, runId, "?afterSeq=" + afterSeq);
    }

    private JsonNode events(Bench bench, UUID runId, String query) {
        ApiResponse response = bench.person().get(bench.path("/runs/" + runId + "/events" + query));
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return response.json();
    }
}
