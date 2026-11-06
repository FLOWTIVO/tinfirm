package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.runner.RunnerMessages;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The usage a runner host reports, within the server's bounds (the security review of 2026-10-27, I7): token counts
 * clamped to {@code [0, Long.MAX_VALUE / 4]}, including counts beyond a {@code long}, and negative or non-finite costs
 * ignored, in {@code USAGE} events and in {@code run.finished}.
 */
class RunUsageClampTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void anEventsTokenCountsAreClamped() {
        JsonNode usage = JSON.readTree("""
                {"inputTokens": 120, "outputTokens": -5, "cachedInputTokens": 18446744073709551615,
                 "cacheWriteTokens": 9223372036854775807, "contextUsed": "many", "costUsd": 0.25}""");
        assertThat(RunnerSessionHandler.usage(usage, "inputTokens")).isEqualTo(120L);
        assertThat(RunnerSessionHandler.usage(usage, "outputTokens")).isZero();
        assertThat(RunnerSessionHandler.usage(usage, "cachedInputTokens")).isEqualTo(Long.MAX_VALUE / 4);
        assertThat(RunnerSessionHandler.usage(usage, "cacheWriteTokens")).isEqualTo(Long.MAX_VALUE / 4);
        assertThat(RunnerSessionHandler.usage(usage, "contextUsed")).isNull();
        assertThat(RunnerSessionHandler.usage(usage, "missing")).isNull();
        assertThat(RunnerSessionHandler.usage(JSON.readTree("{\"n\": 1.5}"), "n")).isNull();
        assertThat(RunnerSessionHandler.MAX_USAGE).isEqualTo(Long.MAX_VALUE / 4);
    }

    @Test
    void anEventsNegativeOrNonFiniteCostIsIgnored() {
        assertThat(RunnerSessionHandler.cost(JSON.readTree("{\"c\": 0.25}"), "c")).isEqualTo(0.25);
        assertThat(RunnerSessionHandler.cost(JSON.readTree("{\"c\": 0}"), "c")).isZero();
        assertThat(RunnerSessionHandler.cost(JSON.readTree("{\"c\": -0.01}"), "c")).isNull();
        assertThat(RunnerSessionHandler.cost(JSON.readTree("{\"c\": 1e400}"), "c")).isNull();
        assertThat(RunnerSessionHandler.cost(JSON.readTree("{\"c\": \"free\"}"), "c")).isNull();
        assertThat(RunnerSessionHandler.cost(JSON.readTree("{}"), "c")).isNull();
    }

    @Test
    void theFinishedRunsUsageIsClampedToo() {
        RunnerMessages.Usage usage = new RunnerMessages.Usage(Long.MAX_VALUE, -1L, 42L, null, null, null,
                Double.NaN);
        RunStore.Outcome outcome = RunnerSessionHandler.outcome(finished(usage));
        assertThat(outcome.inputTokens()).isEqualTo(Long.MAX_VALUE / 4);
        assertThat(outcome.outputTokens()).isZero();
        assertThat(outcome.cachedInputTokens()).isEqualTo(42L);
        assertThat(outcome.cacheWriteTokens()).isNull();
        assertThat(outcome.costUsd()).isNull();
        assertThat(RunnerSessionHandler.outcome(finished(new RunnerMessages.Usage(1L, 2L, 3L, 4L, null, null,
                Double.POSITIVE_INFINITY))).costUsd()).isNull();
        assertThat(RunnerSessionHandler.outcome(finished(new RunnerMessages.Usage(1L, 2L, 3L, 4L, null, null,
                -3.0))).costUsd()).isNull();
        assertThat(RunnerSessionHandler.outcome(finished(new RunnerMessages.Usage(1L, 2L, 3L, 4L, null, null,
                1.5))).costUsd()).isEqualTo(1.5);
    }

    private static RunnerMessages.RunFinished finished(RunnerMessages.Usage usage) {
        return new RunnerMessages.RunFinished(UUID.randomUUID(), 1L, RunnerMessages.Outcome.SUCCEEDED, 3L, null, null,
                "Done.", null, "session", null, usage, null, null, null, null);
    }
}
