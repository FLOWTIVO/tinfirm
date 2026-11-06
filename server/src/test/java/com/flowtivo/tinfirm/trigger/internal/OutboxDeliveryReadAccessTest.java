package com.flowtivo.tinfirm.trigger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.company.CompanySwitches;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.ConversationLock;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.trigger.OutboxCause;
import com.flowtivo.tinfirm.trigger.OutboxKind;
import com.flowtivo.tinfirm.trigger.PlaceWriter;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerEventSpec;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerRepeat;
import com.flowtivo.tinfirm.trigger.TriggerResults;
import com.flowtivo.tinfirm.trigger.TriggerSource;
import com.flowtivo.tinfirm.trigger.TriggerStatus;
import com.flowtivo.tinfirm.trigger.TriggerSubject;

/**
 * I9 at the write-back (CONVENTIONS §15.15; ADR-0072, open point 1): a row whose binding's token is lent {@code READ}
 * now is dropped before any writer sees it, with a posted notice that names the credential and the service account and
 * never the token; a {@code WRITE} one is delivered.
 */
class OutboxDeliveryReadAccessTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID SYSTEM = UUID.randomUUID();

    private static final UUID TRIGGER = UUID.randomUUID();

    private static final UUID CONVERSATION = UUID.randomUUID();

    private static final UUID BINDING = UUID.randomUUID();

    private static final UUID ROW = UUID.randomUUID();

    private static final UUID SERVICE = UUID.randomUUID();

    private static final UUID CREDENTIAL = UUID.randomUUID();

    private final OutboxStore store = mock(OutboxStore.class);

    private final TriggerStore triggers = mock(TriggerStore.class);

    private final Conversations conversations = mock(Conversations.class);

    private final TriggerConversations triggerConversations = mock(TriggerConversations.class);

    private final ConversationBindings bindings = mock(ConversationBindings.class);

    private final ConversationLock locks = mock(ConversationLock.class);

    private final Principals principals = mock(Principals.class);

    private final SystemTransactions transactions = mock(SystemTransactions.class);

    private final PlaceWriter tracker = mock(PlaceWriter.class);

    private final OutboxDelivery delivery;

    private final Instant now = Instant.now();

    private final PlaceWriter.Row row = new PlaceWriter.Row(ROW, COMPANY, CONVERSATION, SYSTEM, BINDING, "SHOP-1", 1,
            OutboxKind.COMMENT, OutboxCause.RESULT, null, null, null, null, null, "Done.", null, null, 0, false, now,
            null);

    /** The credential First, lent to svc-ba, bound through the trigger's table. */
    private final AccountInfo first = new AccountInfo(CREDENTIAL, COMPANY, SYSTEM, null, null, "First",
            AccountInfo.Kind.TOKEN, "first-bot", "first-bot", AccountInfo.Status.OK, true);

    private final BindingInfo bound = new BindingInfo(BINDING, COMPANY, CONVERSATION, SYSTEM, BindingInfo.State.BOUND,
            BindingInfo.How.FIXED, CREDENTIAL, SERVICE, "first-bot", null, null, List.of(), null, now);

    OutboxDeliveryReadAccessTest() {
        when(tracker.place()).thenReturn(ConversationInfo.Place.TRACKER_ISSUE);
        when(tracker.writer()).thenReturn(PlaceWriter.Writer.BINDING);
        delivery = new OutboxDelivery(store, triggers, conversations, triggerConversations, bindings, locks,
                mock(Accounts.class), principals, mock(Agents.class), mock(CompanySwitches.class),
                mock(Systems.class), List.of(tracker), transactions, mock(AuditLog.class),
                mock(ApplicationEventPublisher.class), new OutboxProperties(true, Duration.ofMinutes(5), 20, 10, 10,
                        Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofDays(7)),
                new TinfirmProperties(URI.create("https://tinfirm.test"), Set.of("worker")),
                new TimeSource(Clock.systemUTC()));
    }

    @BeforeEach
    void aClaimedRowOfALiveTriggersOpenConversation() {
        when(transactions.inCompany(eq(COMPANY), any())).thenAnswer(invocation -> invocation
            .<Supplier<?>>getArgument(1)
            .get());
        when(store.claim(eq(COMPANY), eq(ROW), any(), any())).thenReturn(Optional.of(new OutboxStore.Stored(row,
                "RESULT", "PENDING", null, null, null)));
        when(conversations.find(COMPANY, CONVERSATION)).thenReturn(Optional.of(new ConversationInfo(CONVERSATION,
                COMPANY, UUID.randomUUID(), "shop-1-ba-1", "SHOP-1", ConversationInfo.Place.TRACKER_ISSUE,
                ConversationInfo.Origin.TRIGGER, null, new ConversationInfo.Subject(SYSTEM, "SHOP-1", null, null),
                RuntimeKind.CLAUDE_CODE, SERVICE, ModelAccessChoice.BUDGET, null, List.of(), List.of(),
                ConversationInfo.State.OPEN, null, null, null, ConversationInfo.WaitingOn.NONE, null, null, 1, null,
                null, null, now, now, TRIGGER, UUID.randomUUID(), 0)));
        when(triggers.find(COMPANY, TRIGGER)).thenReturn(Optional.of(new TriggerInfo(TRIGGER, COMPANY, "Assigned",
                TriggerSource.TRACKER, SYSTEM, List.of(new TriggerEventSpec(TriggerEvent.ASSIGNED, null)),
                TriggerSubject.ISSUE, "SHOP", List.of(), TriggerRepeat.EVERY_TIME, null, UUID.randomUUID(),
                "Prepare it.", UUID.randomUUID(), null, SERVICE, TriggerResults.DEFAULT, 30, TriggerStatus.ACTIVE,
                null, null, null, null, now, 0, 0, null, null, null, now, now)));
        when(principals.find(COMPANY, SERVICE)).thenReturn(Optional.of(new PrincipalInfo(SERVICE, COMPANY,
                PrincipalKind.SERVICE, null, "svc-ba", PrincipalStatus.ACTIVE)));
        when(store.drop(eq(COMPANY), eq(ROW), anyString(), anyInt(), any())).thenReturn(true);
    }

    private void lent(GrantInfo.Access access) {
        when(bindings.use(COMPANY, BINDING, "write-back:" + ROW)).thenReturn(new ConversationBindings.BindingUse.Token(
                bound, first, access, new Accounts.RevealedCredential(new SystemCalls.Credential("first-token", false),
                        null, new CallCredential.Endpoint(SystemKind.JIRA_DC, "https://jira.test"))));
    }

    @Test
    void aReadLendingWritesNothingAndDropsTheRowNamingTheCredentialAndTheServiceAccount() {
        lent(GrantInfo.Access.READ);

        OutboxDelivery.Result result = SystemContext.callInCompany(COMPANY, () -> delivery.deliver(COMPANY, ROW));

        assertThat(result).isEqualTo(OutboxDelivery.Result.DROPPED);
        verify(tracker, never()).write(any());
        verify(tracker, never()).written(any());
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(locks).lock(COMPANY, CONVERSATION);
        verify(store).drop(eq(COMPANY), eq(ROW), reason.capture(), eq(0), any());
        assertThat(reason.getValue()).isEqualTo("its credential First is lent read-only to svc-ba");
        verify(triggerConversations).post(eq(COMPANY), eq(CONVERSATION), contains("Tinfirm did not write the "
                + "result's comment to SHOP-1: its credential First is lent read-only to svc-ba"));
        verify(triggerConversations).post(eq(COMPANY), eq(CONVERSATION), argThat(body -> !body
            .contains("first-token")));
    }

    @Test
    void aWriteLendingIsDelivered() {
        lent(GrantInfo.Access.WRITE);
        when(tracker.write(any())).thenReturn(new PlaceWriter.Outcome.Delivered("10001", "COMMENT"));

        OutboxDelivery.Result result = SystemContext.callInCompany(COMPANY, () -> delivery.deliver(COMPANY, ROW));

        assertThat(result).isEqualTo(OutboxDelivery.Result.DELIVERED);
        verify(tracker).write(argThat(write -> write.row().id().equals(ROW) && write.credential() != null));
        verify(store, never()).drop(any(), any(), anyString(), anyInt(), any());
        verify(triggerConversations, never()).post(any(), any(), anyString());
    }
}
