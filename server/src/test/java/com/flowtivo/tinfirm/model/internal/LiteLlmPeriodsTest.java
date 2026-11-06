package com.flowtivo.tinfirm.model.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tinfirm's budget period in LiteLLM's terms and back (ADR-0094): the same words, and a duration of LiteLLM's that is
 * no period of Tinfirm's reads as none, so that the sync writes the settings' period again.
 */
class LiteLlmPeriodsTest {

    @Test
    void aPeriodIsLiteLlmsDurationAsItIs() {
        for (String period : new String[] { "30d", "1mo", "7d", "12h", "2w" }) {
            assertThat(LiteLlm.duration(period)).isEqualTo(period);
            assertThat(LiteLlm.period(LiteLlm.duration(period))).isEqualTo(period);
        }
    }

    @Test
    void aDurationInOtherUnitsIsNoPeriod() {
        assertThat(LiteLlm.period("30m")).isNull();
        assertThat(LiteLlm.period("0d")).isNull();
        assertThat(LiteLlm.period("")).isNull();
        assertThat(LiteLlm.period(null)).isNull();
    }
}
