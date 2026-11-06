package com.flowtivo.tinfirm.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.flowtivo.tinfirm.runner.RunnerMessages;

/**
 * {@code tinfirm.spend.source} {@code RELAY} (the package 6 contract, §5.1, open point 1): should the pinned LiteLLM
 * not keep the request tags, every run settles from the relay's own count as soon as it is due, without reading
 * LiteLLM's logs.
 */
@TestPropertySource(properties = "tinfirm.spend.source=RELAY")
class SpendRelaySourceTest extends SpendBench {

    @Test
    void everyRunSettlesFromTheRelaysCountWithoutReadingTheLogs() {
        Setup setup = setUp();
        litellm.price(SONNET, 0.000001, 0.000002);
        Relayed run = running(setup, RunnerMessages.ModelAccessFormat.ANTHROPIC);
        answerAnthropicStream(100, 0, 0, 50);
        post(MESSAGES, run.token(), SONNET);
        litellm.logSpend(setup.key().token, SONNET, List.of("run:" + run.runId()), Instant.now(), 9, 100, 50, 0);
        end(setup.companyId(), run.runId());
        due(setup.companyId(), run.runId(), Duration.ofMinutes(3));
        int reads = litellm.requests("/spend/logs?api_key=" + setup.key().token).size();

        settle();

        Map<String, Object> row = spend(setup.companyId(), run.runId()).getFirst();
        assertThat(row.get("source")).isEqualTo("RELAY");
        assertThat((BigDecimal) row.get("cost_usd")).isEqualByComparingTo("0.0002");
        assertThat(litellm.requests("/spend/logs?api_key=" + setup.key().token)).hasSize(reads);
    }
}
