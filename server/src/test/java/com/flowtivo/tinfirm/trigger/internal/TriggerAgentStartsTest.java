package com.flowtivo.tinfirm.trigger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.trigger.TriggerSource;

/**
 * ADR-0113, decision 4: the sources that start an agent's runs from outside the company are those anyone may write to,
 * as {@link TriggerExposures} counts them; a schedule is not one.
 */
class TriggerAgentStartsTest {

    @Test
    void everySourceButAScheduleIsOpenToWriters() {
        assertThat(Arrays.stream(TriggerSource.values()).filter(TriggerSource::openToWriters))
            .containsExactly(TriggerSource.TRACKER, TriggerSource.CHANNEL, TriggerSource.WEBHOOK);
    }
}
