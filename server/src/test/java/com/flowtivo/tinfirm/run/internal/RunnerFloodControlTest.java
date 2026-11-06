package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RunnerProperties;

import io.github.bucket4j.TimeMeter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The flood controls of runner frames (the security review of 2026-10-27, M4): the buckets per host, company and run
 * with their burst and refill, the tool calls in flight per run, and the sizes of an event.
 */
class RunnerFloodControlTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AtomicLong nanos = new AtomicLong();

    private final TimeMeter clock = new TimeMeter() {

        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    };

    static RunnerProperties properties(int toolCalls, int requests, int companyRequests, int events, int hostEvents,
            int companyEvents) {
        return new RunnerProperties(Duration.ofSeconds(30), Duration.ofSeconds(10), 1_048_576, Duration.ofMinutes(2),
                Duration.ofMinutes(10), toolCalls, DataSize.ofMegabytes(64), requests, companyRequests, events,
                hostEvents, companyEvents);
    }

    @Test
    void theDefaultsAreTheConventionsLimits() {
        RunnerProperties defaults = properties(4, 50, 200, 20, 200, 500);
        assertThat(defaults.eventsMaxBytes()).isEqualTo(DataSize.ofMegabytes(64));
        assertThat(RunnerMessages.MAX_EVENT_PAYLOAD_BYTES).isEqualTo(256 * 1024);
        assertThat(RunnerMessages.MAX_TEXT_CHUNK_BYTES).isEqualTo(32 * 1024);
    }

    @Test
    void aHostsRequestsGetTenSecondsWorthAtOnceThenTheirRate() {
        RunnerFloodControl flood = new RunnerFloodControl(properties(4, 5, 0, 0, 0, 0), clock);
        ConnectedHost host = companyHost(UUID.randomUUID());
        ConnectedHost other = companyHost(UUID.randomUUID());
        for (int i = 0; i < 50; i++) {
            assertThat(flood.admitRequest(host)).as("request %d of the burst", i).isTrue();
        }
        assertThat(flood.admitRequest(host)).isFalse();
        // Another host has its own bucket.
        assertThat(flood.admitRequest(other)).isTrue();
        // A fifth of a second later, one more.
        nanos.addAndGet(Duration.ofMillis(200).toNanos());
        assertThat(flood.admitRequest(host)).isTrue();
        assertThat(flood.admitRequest(host)).isFalse();
    }

    @Test
    void theHostsOfACompanyShareItsBucket() {
        RunnerFloodControl flood = new RunnerFloodControl(properties(4, 0, 2, 0, 0, 0), clock);
        UUID company = UUID.randomUUID();
        ConnectedHost first = new ConnectedHost(UUID.randomUUID(), company);
        ConnectedHost second = new ConnectedHost(UUID.randomUUID(), company);
        for (int i = 0; i < 20; i++) {
            assertThat(flood.admitRequest(i % 2 == 0 ? first : second)).isTrue();
        }
        assertThat(flood.admitRequest(first)).isFalse();
        assertThat(flood.admitRequest(second)).isFalse();
        assertThat(flood.admitRequest(companyHost(UUID.randomUUID()))).isTrue();
    }

    @Test
    void aMachinesPresencesInSeveralCompaniesShareTheHostsBucket() {
        RunnerFloodControl flood = new RunnerFloodControl(properties(4, 1, 0, 0, 0, 0), clock);
        UUID machine = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ConnectedHost authenticated = ConnectedHost.machine(machine,
                Map.of(a, UUID.randomUUID(), b, UUID.randomUUID()));
        ConnectedHost inA = authenticated.in(a).orElseThrow();
        ConnectedHost inB = authenticated.in(b).orElseThrow();
        for (int i = 0; i < 10; i++) {
            assertThat(flood.admitRequest(i % 2 == 0 ? inA : inB)).isTrue();
        }
        assertThat(flood.admitRequest(inA)).isFalse();
        assertThat(flood.admitRequest(inB)).isFalse();
    }

    @Test
    void aRunsEventsAreBoundedBeforeItsHostsAndItsCompanys() {
        RunnerFloodControl flood = new RunnerFloodControl(properties(4, 0, 0, 2, 3, 100), clock);
        ConnectedHost host = companyHost(UUID.randomUUID());
        UUID run = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            assertThat(flood.admitEvent(host, run)).isTrue();
        }
        assertThat(flood.admitEvent(host, run)).as("the run's burst is spent").isFalse();
        // Another run of the host: the host's bucket held 30, the first run took 20.
        UUID second = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            assertThat(flood.admitEvent(host, second)).isTrue();
        }
        assertThat(flood.admitEvent(host, second)).as("the host's burst is spent").isFalse();
        nanos.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(flood.admitEvent(host, run)).isTrue();
    }

    @Test
    void zeroTurnsALimitOff() {
        RunnerFloodControl flood = new RunnerFloodControl(properties(4, 0, 0, 0, 0, 0), clock);
        ConnectedHost host = companyHost(UUID.randomUUID());
        UUID run = UUID.randomUUID();
        for (int i = 0; i < 10_000; i++) {
            assertThat(flood.admitRequest(host)).isTrue();
            assertThat(flood.admitEvent(host, run)).isTrue();
        }
    }

    @Test
    void atMostTheConfiguredToolCallsOfARunWaitAtOnce() {
        RunnerFloodControl flood = new RunnerFloodControl(properties(4, 0, 0, 0, 0, 0), clock);
        UUID run = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            assertThat(flood.toolCallStarted(run)).isTrue();
        }
        assertThat(flood.toolCallStarted(run)).isFalse();
        assertThat(flood.toolCallsInFlight(run)).isEqualTo(4);
        assertThat(flood.toolCallStarted(other)).as("another run's").isTrue();
        flood.toolCallEnded(run);
        assertThat(flood.toolCallStarted(run)).isTrue();
        assertThat(flood.toolCallStarted(run)).isFalse();
        for (int i = 0; i < 4; i++) {
            flood.toolCallEnded(run);
        }
        assertThat(flood.toolCallsInFlight(run)).isZero();
        // Ended more often than started: never below zero.
        flood.toolCallEnded(run);
        assertThat(flood.toolCallsInFlight(run)).isZero();
        assertThat(flood.toolCallStarted(run)).isTrue();
    }

    @Test
    void anEventsPayloadIsAtMost256KiBAndItsTextChunkAtMost32KiB() {
        JsonNode within = JSON.createObjectNode().put("text", "a".repeat(32 * 1024));
        assertThat(oversized(RunnerMessages.EventType.AGENT_MESSAGE, within)).isNull();
        JsonNode longText = JSON.createObjectNode().put("text", "a".repeat(32 * 1024 + 1));
        assertThat(oversized(RunnerMessages.EventType.AGENT_MESSAGE, longText)).contains("text chunk");
        assertThat(oversized(RunnerMessages.EventType.AGENT_THOUGHT, longText)).contains("text chunk");
        // In UTF-8 bytes: 11 000 characters of three bytes each are 33 000 bytes.
        JsonNode wide = JSON.createObjectNode().put("text", "一".repeat(11_000));
        assertThat(oversized(RunnerMessages.EventType.AGENT_MESSAGE, wide)).contains("33000");
        // A tool call's output is not a text chunk, but its payload is bounded.
        JsonNode tool = JSON.createObjectNode().put("text", "a".repeat(100_000));
        assertThat(oversized(RunnerMessages.EventType.TOOL_CALL_UPDATE, tool)).isNull();
        JsonNode huge = JSON.createObjectNode().put("output", "a".repeat(256 * 1024));
        assertThat(oversized(RunnerMessages.EventType.TOOL_CALL_UPDATE, huge)).contains("262144");
        JsonNode exact = JSON.createObjectNode().put("m", "a".repeat(256 * 1024 - "{\"m\":\"\"}".length()));
        assertThat(RunnerFloodControl.utf8Length(exact.toString())).isEqualTo(256 * 1024);
        assertThat(oversized(RunnerMessages.EventType.LOG, exact)).isNull();
    }

    @Test
    void theUtf8LengthCountsSurrogatePairsAsFourBytes() {
        for (String text : new String[] { "", "plain", "été", "一二", "😀 ok" }) {
            assertThat(RunnerFloodControl.utf8Length(text)).as(text)
                .isEqualTo(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        }
    }

    private static String oversized(RunnerMessages.EventType type, JsonNode payload) {
        String json = payload.toString();
        return RunnerFloodControl.oversized(type, payload, RunnerFloodControl.utf8Length(json));
    }

    private static ConnectedHost companyHost(UUID company) {
        return new ConnectedHost(UUID.randomUUID(), company);
    }
}
