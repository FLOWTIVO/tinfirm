package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.run.QueueReasonCode;

/**
 * The back-off of a run its host rejects again ({@link RunProperties#rejectBackoff(int)}), and the count of rejections
 * in the queue reason's stored details.
 */
class RunRejectBackoffTest {

    private static RunProperties properties(Duration backoff, Duration max) {
        return new RunProperties(Duration.ofMinutes(60), 200, Duration.ofSeconds(10), Duration.ofMinutes(2), backoff,
                max, 20000, Duration.ofMinutes(30), Duration.ofSeconds(15), Duration.ofSeconds(60));
    }

    @Test
    void theWaitDoublesWithEachRejectionUpToTheCap() {
        RunProperties properties = properties(Duration.ofSeconds(10), Duration.ofMinutes(5));
        assertThat(properties.rejectBackoff(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.rejectBackoff(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.rejectBackoff(3)).isEqualTo(Duration.ofSeconds(40));
        assertThat(properties.rejectBackoff(5)).isEqualTo(Duration.ofSeconds(160));
        assertThat(properties.rejectBackoff(6)).isEqualTo(Duration.ofMinutes(5));
        // A long run of rejections stays at the cap and does not overflow.
        assertThat(properties.rejectBackoff(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aCapShorterThanTheBackOffIsRefused() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(10), Duration.ofSeconds(5)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("reject-backoff-max");
        assertThat(properties(Duration.ofSeconds(10), Duration.ofSeconds(10)).rejectBackoff(4))
            .isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void theCountIsStoredWithTheReasonAndBelongsToItsHost() {
        UUID host = UUID.randomUUID();
        QueueReason waits = QueueReason.of(QueueReasonCode.RUNNER_HOST_FULL)
            .withRejections(new QueueReason.Rejections(host, 3));
        QueueReason read = QueueReason.fromColumns(waits.code().name(), waits.detailsJson());
        assertThat(read).isEqualTo(waits);
        assertThat(read.rejectionsBy(host)).isEqualTo(new QueueReason.Rejections(host, 3));
        assertThat(read.rejectionsBy(UUID.randomUUID())).isNull();
        assertThat(read.rejectionsBy(null)).isNull();
        // A reason stored before the count existed reads without one.
        assertThat(QueueReason.fromColumns("RUNNER_HOST_FULL", "{}").rejections()).isNull();
    }
}
