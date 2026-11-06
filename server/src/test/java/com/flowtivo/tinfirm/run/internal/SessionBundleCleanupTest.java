package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.flowtivo.tinfirm.run.ConversationLock;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * The sweeper's delete of a due bundle (ADR-0097, amended), without a database: in the company's transaction it locks
 * the conversation first (CONVENTIONS §10) and deletes the row only while it still names the object the lookup found
 * and the conversation is still due; a conversation that resumed meanwhile keeps its bundle, and no object goes.
 */
class SessionBundleCleanupTest {

    private static final UUID COMPANY = UUID.fromString("019a0000-0000-7000-8000-000000000001");

    private static final UUID CONVERSATION = UUID.fromString("019a0000-0000-7000-8000-000000000002");

    private static final UUID AGENT = UUID.fromString("019a0000-0000-7000-8000-000000000003");

    private static final UUID OBJECT = UUID.fromString("019a0000-0000-7000-8000-000000000004");

    private final SessionBundleProperties properties = new SessionBundleProperties(
            SessionBundleProperties.Store.FILESYSTEM, Path.of("/var/lib/tinfirm/bundles"), null,
            Duration.ofDays(30), Duration.ofHours(1), Duration.ofHours(1));

    private final BundleObjects objects = mock(BundleObjects.class);

    private final SessionBundleStore store = mock(SessionBundleStore.class);

    private final SystemTransactions transactions = mock(SystemTransactions.class);

    private final TimeSource time = new TimeSource(Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneOffset.UTC));

    private final ConversationLock lock = mock(ConversationLock.class);

    private final RunPorts ports = mock(RunPorts.class);

    private final SessionBundleCleanup cleanup = new SessionBundleCleanup(properties, objects, store, transactions,
            time, new TinfirmProperties(URI.create("https://tinfirm.example.com"), Set.of("worker")), ports);

    private final Instant cutoff = time.now().minus(Duration.ofDays(30));

    @Test
    void aConversationThatResumedSinceTheLookupKeepsItsBundle() throws Exception {
        dueBundle();
        when(store.deleteDue(CONVERSATION, AGENT, OBJECT, cutoff)).thenReturn(Optional.empty());

        assertThat(cleanup.sweep()).isZero();

        InOrder order = inOrder(lock, store);
        order.verify(lock).lock(COMPANY, CONVERSATION);
        order.verify(store).deleteDue(CONVERSATION, AGENT, OBJECT, cutoff);
        verify(store, never()).delete(any(), any());
        verify(objects, never()).delete(any());
    }

    @Test
    void aBundleStillDueGoesWithItsObjectAfterItsRow() throws Exception {
        dueBundle();
        when(store.deleteDue(CONVERSATION, AGENT, OBJECT, cutoff)).thenReturn(Optional.of(OBJECT));

        assertThat(cleanup.sweep()).isEqualTo(1);

        InOrder order = inOrder(lock, store, objects);
        order.verify(lock).lock(COMPANY, CONVERSATION);
        order.verify(store).deleteDue(CONVERSATION, AGENT, OBJECT, cutoff);
        order.verify(objects).delete(OBJECT);
    }

    /** The lookup answers the one pair; the transactions run their work in place; the store lists no objects. */
    private void dueBundle() throws Exception {
        when(transactions.unscoped(any())).thenAnswer(call -> call.<Supplier<?>>getArgument(0).get());
        when(transactions.inCompany(any(), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        when(ports.conversationLock()).thenReturn(lock);
        when(store.due(cutoff, 200)).thenReturn(List.of(new SessionBundleStore.Due(COMPANY, CONVERSATION, AGENT,
                OBJECT)));
        when(store.known(any())).thenReturn(Set.of());
        when(objects.list(any(), anyInt())).thenReturn(List.of());
    }
}
