package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.ToolInfo;

import tools.jackson.databind.json.JsonMapper;

/**
 * Parked calls under the kill switches as a unit (the security review of 2026-10-27, M6): an {@code ALLOW} call stays
 * parked at the bind and at its take while the company's runs are stopped, and the stop refuses every parked call that
 * has not started, then asks for the turn that tells it; a paused trigger's call is refused at the bind; a call the
 * reveal holds after it was taken is refused, never parked again; and with no switch on, the call runs after the
 * commit.
 */
class ParkedCallsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID CONVERSATION = UUID.randomUUID();

    private static final UUID SYSTEM = UUID.randomUUID();

    private static final UUID BINDING = UUID.randomUUID();

    private static final UUID RUN = UUID.randomUUID();

    private static final String NAME = "jira_comment";

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    private static final ConversationHolds.Held STOPPED = new ConversationHolds.Held(
            ConversationBindings.Hold.COMPANY_STOPPED, "the company's runs are stopped");

    private final ParkedCallStore store = mock(ParkedCallStore.class);

    private final ConversationStore conversationStore = mock(ConversationStore.class);

    private final ConversationCore core = mock(ConversationCore.class);

    private final Conversations conversations = mock(Conversations.class);

    private final ConversationTurns turns = mock(ConversationTurns.class);

    private final ConversationBindings bindings = mock(ConversationBindings.class);

    private final ConversationTools tools = mock(ConversationTools.class);

    private final ToolRunner runner = mock(ToolRunner.class);

    private final Settings settings = mock(Settings.class);

    private final ConversationWorkers workers = mock(ConversationWorkers.class);

    private final SystemTransactions transactions = mock(SystemTransactions.class);

    private final ConversationHolds holds = mock(ConversationHolds.class);

    private final TimeSource time = mock(TimeSource.class);

    private final ParkedCalls parked = new ParkedCalls(store, conversationStore, core, tools, mock(DecisionOps.class),
            runner, settings, workers, transactions, holds, mock(DecisionStore.class), time);

    private final ConversationInfo conversation = mock(ConversationInfo.class);

    private final BindingInfo binding = new BindingInfo(BINDING, COMPANY, CONVERSATION, SYSTEM,
            BindingInfo.State.BOUND, BindingInfo.How.OWN, UUID.randomUUID(), UUID.randomUUID(), null, null, null,
            List.of(), null, NOW);

    /** What each write of the binding's calls wrote, in order. */
    private final List<List<ParkedCallStore.ParkedCall>> writes = new ArrayList<>();

    /** The work set going after the commit. */
    private final List<Runnable> afterCommit = new ArrayList<>();

    @BeforeEach
    void wired() {
        when(time.now()).thenReturn(NOW);
        when(core.conversations()).thenReturn(conversations);
        when(core.turns()).thenReturn(turns);
        when(core.bindings()).thenReturn(bindings);
        when(bindings.find(COMPANY, BINDING)).thenReturn(Optional.of(binding));
        when(conversation.id()).thenReturn(CONVERSATION);
        when(conversation.companyId()).thenReturn(COMPANY);
        when(conversation.agentId()).thenReturn(UUID.randomUUID());
        when(conversation.open()).thenReturn(true);
        when(conversations.find(COMPANY, CONVERSATION)).thenReturn(Optional.of(conversation));
        when(settings.effective(any(), any(), any())).thenReturn(mock(EffectiveSettings.class));
        SystemInfo system = mock(SystemInfo.class);
        when(system.id()).thenReturn(SYSTEM);
        ToolInfo served = new ToolInfo("comment", null, null, JSON.createObjectNode().put("type", "object"),
                ToolEffect.WRITE, true, false);
        when(tools.resolve(conversation, NAME)).thenReturn(Optional.of(new ConversationTools.Target(system, "comment",
                served)));
        when(tools.texts(conversation)).thenReturn(new ConversationTools.Texts("Triage", "Ann"));
        when(tools.decide(any(), any(), any(), any(), any())).thenReturn(new ToolRules.Decision.Allow(served,
                ToolEffect.WRITE));
        when(holds.of(conversation)).thenReturn(Optional.empty());
        when(transactions.inCompany(any(), any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1)
            .get());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(transactions).runInCompany(any(), any());
        doAnswer(invocation -> {
            writes.add(invocation.getArgument(1));
            return null;
        }).when(store).write(any(), any());
        doAnswer(invocation -> {
            afterCommit.add(invocation.getArgument(2));
            return null;
        }).when(workers).afterCommit(any(), any(), any());
    }

    @Test
    void anAllowCallStaysParkedAtTheBindWhileTheCompanysRunsAreStopped() {
        when(holds.of(conversation)).thenReturn(Optional.of(STOPPED));
        parkedOn(call(ParkedCallStore.Status.PARKED));

        parked.bound(binding);

        assertThat(writes).singleElement().satisfies(written -> assertThat(written).singleElement()
            .extracting(ParkedCallStore.ParkedCall::status).isEqualTo(ParkedCallStore.Status.PARKED));
        assertThat(afterCommit).isEmpty();
        verify(turns, never()).request(any(), any(), any());
    }

    @Test
    void anAllowCallOfAPausedTriggerIsRefusedAtTheBindAndTheTurnTellsIt() {
        when(holds.of(conversation)).thenReturn(Optional.of(new ConversationHolds.Held(
                ConversationBindings.Hold.TRIGGER_PAUSED, "the trigger Nightly triage is paused")));
        parkedOn(call(ParkedCallStore.Status.PARKED));

        parked.bound(binding);

        assertThat(writes).singleElement().satisfies(written -> assertThat(written).singleElement().satisfies(call -> {
            assertThat(call.status()).isEqualTo(ParkedCallStore.Status.REFUSED);
            assertThat(call.result()).isEqualTo("The call did not run: the trigger Nightly triage is paused.");
        }));
        assertThat(afterCommit).isEmpty();
        verify(turns).request(COMPANY, CONVERSATION, TurnReason.CONNECTED);
    }

    @Test
    void withNoSwitchOnAnAllowCallRunsAfterTheCommit() {
        parkedOn(call(ParkedCallStore.Status.PARKED));

        parked.bound(binding);

        assertThat(writes).singleElement().satisfies(written -> assertThat(written).singleElement()
            .extracting(ParkedCallStore.ParkedCall::status).isEqualTo(ParkedCallStore.Status.ALLOWED));
        assertThat(afterCommit).hasSize(1);
    }

    @Test
    void anAllowedCallIsParkedAgainAtItsTakeWhileTheCompanysRunsAreStopped() {
        when(holds.of(conversation)).thenReturn(Optional.of(STOPPED));
        parkedOn(call(ParkedCallStore.Status.ALLOWED));

        parked.runAllowed(COMPANY, BINDING);

        assertThat(writes.getFirst()).singleElement().extracting(ParkedCallStore.ParkedCall::status)
            .isEqualTo(ParkedCallStore.Status.PARKED);
        verify(runner, never()).run(any(), any());
    }

    @Test
    void aCallTheRevealHoldsAfterItWasTakenIsRefusedNeverParkedAgain() {
        parkedOn(call(ParkedCallStore.Status.ALLOWED));
        when(runner.run(any(), eq("binding-call:" + BINDING))).thenReturn(new ToolRunner.Ran.Held(binding,
                "the company's runs are stopped"));

        parked.runAllowed(COMPANY, BINDING);

        assertThat(writes).hasSize(2);
        assertThat(writes.getFirst()).singleElement().extracting(ParkedCallStore.ParkedCall::status)
            .isEqualTo(ParkedCallStore.Status.RUNNING);
        assertThat(writes.get(1)).singleElement().satisfies(call -> {
            assertThat(call.status()).isEqualTo(ParkedCallStore.Status.REFUSED);
            assertThat(call.result()).isEqualTo("The call did not run: the company's runs are stopped.");
        });
        verify(turns).request(COMPANY, CONVERSATION, TurnReason.CONNECTED);
    }

    @Test
    void theCompanysStopRefusesEveryParkedCallThatHasNotStartedAndAsksForTheTurnThatTellsIt() {
        ParkedCallStore.ParkedCall waiting = call(ParkedCallStore.Status.PARKED);
        ParkedCallStore.ParkedCall allowed = call(ParkedCallStore.Status.ALLOWED);
        ParkedCallStore.ParkedCall running = call(ParkedCallStore.Status.RUNNING);
        ParkedCallStore.ParkedCall done = call(ParkedCallStore.Status.DONE);
        when(store.conversationsWithWaitingCalls(COMPANY)).thenReturn(List.of(CONVERSATION));
        when(store.lockOfConversation(CONVERSATION)).thenReturn(List.of(new ParkedCallStore.Parked(BINDING, COMPANY,
                CONVERSATION, SYSTEM, List.of(waiting, allowed, running, done))));

        assertThat(parked.stopped(COMPANY)).containsExactly(CONVERSATION);

        verify(conversationStore).lock(COMPANY, CONVERSATION);
        assertThat(writes).singleElement().satisfies(written -> {
            assertThat(written).extracting(ParkedCallStore.ParkedCall::status).containsExactly(
                    ParkedCallStore.Status.REFUSED, ParkedCallStore.Status.REFUSED, ParkedCallStore.Status.RUNNING,
                    ParkedCallStore.Status.DONE);
            assertThat(written.get(0).result()).isEqualTo(ParkedCalls.COMPANY_STOPPED);
            assertThat(written.get(1).result()).isEqualTo(ParkedCalls.COMPANY_STOPPED);
        });
        // The turn that tells it is asked for after the commit; it waits in the queue until the lift.
        verify(turns, never()).request(any(), any(), any());
        assertThat(afterCommit).hasSize(1);
        afterCommit.getFirst().run();
        verify(turns).request(COMPANY, CONVERSATION, TurnReason.CONNECTED);
    }

    @Test
    void theStopTouchesNothingWithoutParkedCalls() {
        when(store.conversationsWithWaitingCalls(COMPANY)).thenReturn(List.of());

        assertThat(parked.stopped(COMPANY)).isEmpty();

        assertThat(writes).isEmpty();
        assertThat(afterCommit).isEmpty();
    }

    private void parkedOn(ParkedCallStore.ParkedCall call) {
        when(store.lock(BINDING)).thenReturn(Optional.of(new ParkedCallStore.Parked(BINDING, COMPANY, CONVERSATION,
                SYSTEM, List.of(call))));
    }

    private static ParkedCallStore.ParkedCall call(ParkedCallStore.Status status) {
        return new ParkedCallStore.ParkedCall(RUN, UUID.randomUUID(), NAME, "comment",
                JSON.createObjectNode().put("body", "Done."), null, status, null, NOW, null, null, null);
    }
}
