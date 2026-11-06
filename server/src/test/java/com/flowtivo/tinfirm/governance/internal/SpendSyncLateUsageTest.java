package com.flowtivo.tinfirm.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.model.BudgetAlerts;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.model.ModelEvents;
import com.flowtivo.tinfirm.model.ModelRelay;
import com.flowtivo.tinfirm.model.SpendLogs;
import com.flowtivo.tinfirm.run.RunEvents;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * A request the relay counts after its run ended (CONVENTIONS §15.12, steps 1 and 2): the run's end saw no request,
 * since its only answer was still under way, so the count gives the finished run paid by a budget its settlement, or
 * makes a pending one due again, over the run's span; a run still running, or a subscription's, gets nothing from the
 * count; the end itself still writes nothing for a run without a request; and the sync settles no run with an answer
 * under way, but tries it again, until the relay's hour, and settles it with the cumulative count once none is.
 */
class SpendSyncLateUsageTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID RUN = UUID.randomUUID();

    private static final UUID PAYER = UUID.randomUUID();

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    private static final Instant CREATED = NOW.minus(Duration.ofMinutes(5));

    private static final Instant STARTED = NOW.minus(Duration.ofMinutes(4));

    private static final Instant FINISHED = NOW.minus(Duration.ofSeconds(10));

    private final SystemTransactions transactions = mock(SystemTransactions.class);

    private final SpendStore store = mock(SpendStore.class);

    private final RunReads runs = mock(RunReads.class);

    private final ModelRelay relay = mock(ModelRelay.class);

    private final SpendLogs logs = mock(SpendLogs.class);

    private final SpendProperties properties = new SpendProperties(Duration.ofMinutes(1),
            SpendProperties.Source.LITELLM, Duration.ofMinutes(2), Duration.ofHours(1));

    private final SpendSync sync = new SpendSync(transactions, store, runs, mock(Conversations.class), relay,
            logs, mock(BudgetAlerts.class), mock(TriggerSpend.class), properties,
            new TinfirmProperties(URI.create("https://tinfirm.test"), Set.of("api")),
            new TimeSource(Clock.fixed(NOW, java.time.ZoneOffset.UTC)));

    SpendSyncLateUsageTest() {
        // The listener's own transaction of the company: the work runs, nothing more.
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(transactions).runInCompany(any(), any());
        when(transactions.inCompany(any(), ArgumentMatchers.<Supplier<Object>>any()))
            .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        when(transactions.unscoped(ArgumentMatchers.<Supplier<Object>>any()))
            .thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
    }

    /** The run's pending settlement, due now and claimed by this worker: it ended {@code endedAgo} ago. */
    private SpendStore.Settlement claimed(Duration endedAgo) {
        Instant ended = NOW.minus(endedAgo);
        SpendStore.Settlement settlement = new SpendStore.Settlement(RUN, COMPANY, PAYER, ended.minusSeconds(60),
                ended, "PENDING", 0, NOW.minusSeconds(1), NOW.plus(Duration.ofMinutes(5)), null, null);
        when(store.due(NOW, SpendSync.BATCH)).thenReturn(List.of(new SpendStore.Due(COMPANY, RUN)));
        when(store.claim(eq(COMPANY), eq(List.of(RUN)), eq(NOW), any())).thenReturn(List.of(settlement));
        when(store.lockClaimed(eq(RUN), any())).thenReturn(Optional.of(settlement));
        when(runs.find(COMPANY, RUN))
            .thenReturn(Optional.of(run(new ModelAccessChoice.Budget(), RunStatus.SUCCEEDED, ended)));
        return settlement;
    }

    private static RunInfo run(ModelAccessChoice access, RunStatus status, Instant finishedAt) {
        return new RunInfo(RUN, COMPANY, UUID.randomUUID(), "conv-1", UUID.randomUUID(), RuntimeKind.CLAUDE_CODE,
                TurnReason.FIRST, 1, UUID.randomUUID(), PAYER, access, status, UUID.randomUUID(), null, null, null,
                null, null, null, null, null, 1, CREATED, STARTED, finishedAt);
    }

    @Test
    void aRequestCountedAfterTheRunEndedGivesItsSettlementOnceOverItsSpan() {
        when(runs.find(COMPANY, RUN))
            .thenReturn(Optional.of(run(new ModelAccessChoice.Budget(), RunStatus.SUCCEEDED, FINISHED)));

        sync.relayRequestCounted(new ModelEvents.RelayRequestCounted(COMPANY, RUN));

        verify(store).pendingOrDue(COMPANY, RUN, PAYER, STARTED, FINISHED, NOW.plus(Duration.ofMinutes(2)));
        verify(store, never()).pending(any(), any(), any(), any(), any(), any());
        verify(transactions).runInCompany(eq(COMPANY), any());
    }

    @Test
    void anEndBeforeTheStartSpansFromTheStart() {
        when(runs.find(COMPANY, RUN)).thenReturn(Optional.of(run(new ModelAccessChoice.Budget(), RunStatus.FAILED,
                STARTED.minusSeconds(1))));

        sync.relayRequestCounted(new ModelEvents.RelayRequestCounted(COMPANY, RUN));

        verify(store).pendingOrDue(COMPANY, RUN, PAYER, STARTED, STARTED, NOW.plus(Duration.ofMinutes(2)));
    }

    @Test
    void aRunStillRunningGetsNothingFromTheCountItsEndWill() {
        when(runs.find(COMPANY, RUN))
            .thenReturn(Optional.of(run(new ModelAccessChoice.Budget(), RunStatus.RUNNING, null)));

        sync.relayRequestCounted(new ModelEvents.RelayRequestCounted(COMPANY, RUN));

        verifyNoInteractions(store);
    }

    @Test
    void aSubscriptionsRunAndARunUnknownGetNothingFromTheCount() {
        when(runs.find(COMPANY, RUN)).thenReturn(Optional.of(run(
                new ModelAccessChoice.Subscription(UUID.randomUUID(), "claude"), RunStatus.SUCCEEDED, FINISHED)));
        sync.relayRequestCounted(new ModelEvents.RelayRequestCounted(COMPANY, RUN));

        UUID unknown = UUID.randomUUID();
        when(runs.find(COMPANY, unknown)).thenReturn(Optional.empty());
        sync.relayRequestCounted(new ModelEvents.RelayRequestCounted(COMPANY, unknown));

        verifyNoInteractions(store);
    }

    @Test
    void aStoreThatFailsIsLoggedNotThrown() {
        when(runs.find(COMPANY, RUN))
            .thenReturn(Optional.of(run(new ModelAccessChoice.Budget(), RunStatus.SUCCEEDED, FINISHED)));
        doAnswer(call -> {
            throw new IllegalStateException("the database is away");
        }).when(store).pendingOrDue(any(), any(), any(), any(), any(), any());

        sync.relayRequestCounted(new ModelEvents.RelayRequestCounted(COMPANY, RUN));
    }

    @Test
    void theRunsEndStillWritesNothingWhileTheRelayCountedNoRequest() {
        when(runs.find(COMPANY, RUN))
            .thenReturn(Optional.of(run(new ModelAccessChoice.Budget(), RunStatus.SUCCEEDED, FINISHED)));
        when(relay.usage(COMPANY, RUN)).thenReturn(new ModelRelay.RelayUsage(0, 0, 0));

        sync.runEnded(new RunEvents.RunEnded(COMPANY, RUN, UUID.randomUUID(), RunStatus.SUCCEEDED));

        verifyNoInteractions(store);

        // Once the relay counted one, the end writes the settlement, and moves a pending one's end.
        when(relay.usage(COMPANY, RUN)).thenReturn(new ModelRelay.RelayUsage(1, 10, 20));
        sync.runEnded(new RunEvents.RunEnded(COMPANY, RUN, UUID.randomUUID(), RunStatus.SUCCEEDED));
        verify(store).pending(COMPANY, RUN, PAYER, STARTED, FINISHED, NOW.plus(Duration.ofMinutes(2)));
    }

    @Test
    void aClaimedRunWithAnAnswerUnderWayIsTriedAgainNotSettled() {
        claimed(Duration.ofMinutes(3));
        when(relay.answersUnderWay(COMPANY, RUN)).thenReturn(1L);
        when(relay.usageByModel(COMPANY, RUN))
            .thenReturn(List.of(new ModelRelay.ModelUsage("claude-x", "key", 1, 100, 10, 0, 0)));

        assertThat(sync.syncDue()).isZero();

        verify(store).retry(RUN, 1, NOW.plus(Duration.ofMinutes(2)), null, NOW);
        verify(store, never()).settle(any(), any());
        verify(store, never()).insert(any());
        verifyNoInteractions(logs);
    }

    @Test
    void onceNoAnswerIsUnderWayTheRunSettlesWithTheCumulativeCount() {
        // After the relay's hour: the relay's own count settles it, at LiteLLM's prices.
        claimed(Duration.ofMinutes(61));
        when(relay.usageByModel(COMPANY, RUN))
            .thenReturn(List.of(new ModelRelay.ModelUsage("claude-x", "key", 2, 300, 30, 20, 10)));
        when(logs.prices(COMPANY)).thenReturn(Map.of());

        assertThat(sync.syncDue()).isEqualTo(1);

        verify(relay, never()).answersUnderWay(any(), any());
        verify(store).insert(argThat(row -> row.runId().equals(RUN) && row.model().equals("claude-x")
                && row.requests() == 2 && row.inputTokens() == 300 && row.outputTokens() == 30
                && row.cachedInputTokens() == 20 && row.cacheWriteTokens() == 10 && row.source().equals("RELAY")
                && row.costUsd() == null));
        verify(store).settle(RUN, NOW);
        verify(store, never()).retry(any(), anyInt(), any(), any(), any());
    }

    @Test
    void afterTheRelaysHourAnAnswerStillUnderWayHoldsTheSettlementNoLonger() {
        claimed(Duration.ofMinutes(61));
        when(relay.answersUnderWay(COMPANY, RUN)).thenReturn(1L);
        when(relay.usageByModel(COMPANY, RUN))
            .thenReturn(List.of(new ModelRelay.ModelUsage("claude-x", "key", 1, 100, 10, 0, 0)));
        when(logs.prices(COMPANY)).thenReturn(Map.of());

        assertThat(sync.syncDue()).isEqualTo(1);

        verify(store).insert(argThat(row -> row.requests() == 1 && row.source().equals("RELAY")));
        verify(store).settle(RUN, NOW);
    }
}
