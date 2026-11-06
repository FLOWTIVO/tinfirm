package com.flowtivo.tinfirm.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.flowtivo.tinfirm.agent.AgentMemory;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The compactions queued by the retention sweep (ADR-0063): queuing returns at once while a slow compaction runs, the
 * companies are compacted one at a time in the order queued, a company waiting or being compacted is not queued twice,
 * and a failed compaction leaves the next one.
 */
class MemoryCompactionsTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final AgentMemory memory = mock(AgentMemory.class);

    private final MemoryCompactions compactions = new MemoryCompactions(memory, new TimeSource(Clock.systemUTC()));

    private final UUID slow = UUID.randomUUID();

    private final UUID next = UUID.randomUUID();

    private final CountDownLatch started = new CountDownLatch(1);

    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stop() {
        release.countDown();
        compactions.shutdown();
    }

    /** The slow company's compaction holds its thread until the test releases it. */
    private void slowCompaction() {
        when(memory.compact(eq(slow), any(Instant.class))).thenAnswer(call -> {
            started.countDown();
            assertThat(release.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
            return AgentMemory.Compaction.NONE;
        });
        when(memory.compact(eq(next), any(Instant.class))).thenReturn(AgentMemory.Compaction.NONE);
    }

    @Test
    void queuingReturnsWhileASlowCompactionRunsAndTheNextWaitsItsTurn() throws InterruptedException {
        slowCompaction();

        assertThat(compactions.queue(slow)).isTrue();
        assertThat(started.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        // The sweep's thread is free while the model is slow, and the next company waits for the one before.
        assertThat(compactions.queue(next)).isTrue();
        verify(memory, never()).compact(eq(next), any(Instant.class));

        release.countDown();
        await().atMost(WAIT).untilAsserted(() -> verify(memory).compact(eq(next), any(Instant.class)));
        InOrder order = inOrder(memory);
        order.verify(memory).compact(eq(slow), any(Instant.class));
        order.verify(memory).compact(eq(next), any(Instant.class));
    }

    @Test
    void aCompanyWaitingOrBeingCompactedIsNotQueuedAgain() throws InterruptedException {
        slowCompaction();

        assertThat(compactions.queue(slow)).isTrue();
        assertThat(started.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(compactions.queue(slow)).as("being compacted").isFalse();
        assertThat(compactions.queue(next)).isTrue();
        assertThat(compactions.queue(next)).as("waiting").isFalse();

        release.countDown();
        await().atMost(WAIT).untilAsserted(() -> verify(memory).compact(eq(next), any(Instant.class)));
        verify(memory, times(1)).compact(eq(slow), any(Instant.class));
        // Once its compaction ended, a company's next sweep queues it again.
        await().atMost(WAIT).until(() -> compactions.queue(slow));
    }

    @Test
    void aFailedCompactionLeavesTheNextOne() {
        when(memory.compact(eq(slow), any(Instant.class))).thenThrow(new IllegalStateException("The gateway failed"));
        when(memory.compact(eq(next), any(Instant.class))).thenReturn(AgentMemory.Compaction.NONE);

        assertThat(compactions.queue(slow)).isTrue();
        assertThat(compactions.queue(next)).isTrue();

        await().atMost(WAIT).untilAsserted(() -> verify(memory).compact(eq(next), any(Instant.class)));
    }

    @Test
    void nothingIsQueuedOnceTheServerStops() {
        compactions.shutdown();

        assertThat(compactions.queue(slow)).isFalse();
        verify(memory, never()).compact(any(UUID.class), any(Instant.class));
    }
}
