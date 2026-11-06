package com.flowtivo.tinfirm.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * What a check records as its head (the package 6 contract, §3.6, §5.8), without a database: writers lock the chain's
 * head row and the check does not, so an event may be appended while the check verifies the events up to the head it
 * read. A check that passed records that head, never the later event, which it did not verify; a failed check records
 * the chain's last event as found, so that an acknowledgement anchors the next check past the damage.
 */
class AuditCheckServiceTest {

    private static final UUID COMPANY = UUID.fromString("019a0000-0000-7000-8000-000000000001");

    private final AuditEventStore events = mock(AuditEventStore.class);

    private final AuditCheckStore checks = mock(AuditCheckStore.class);

    private final SystemTransactions transactions = mock(SystemTransactions.class);

    private final TimeSource time = new TimeSource(Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneOffset.UTC));

    @SuppressWarnings("unchecked")
    private final ObjectProvider<AuditTools> tools = mock(ObjectProvider.class);

    private final AuditCheckService service = new AuditCheckService(events, checks, transactions, time,
            new TinfirmProperties(URI.create("https://tinfirm.example.com"), Set.of("api")),
            Duration.ofDays(1), tools);

    @Test
    void aPassedCheckRecordsTheHeadItVerifiedNotAnEventAppendedMeanwhile() {
        List<StoredAuditEvent> chain = chain(4);
        StoredAuditEvent e3 = chain.get(2);
        StoredAuditEvent e4 = chain.get(3);
        chainOf(chain.subList(0, 3), e3, e4);

        AuditCheckStore.CheckRow row = service.check(COMPANY).orElseThrow();

        assertThat(row.ok()).isTrue();
        assertThat(row.failure()).isNull();
        assertThat(row.fromSeq()).isZero();
        assertThat(row.headSeq()).isEqualTo(3);
        assertThat(row.headHash()).isEqualTo(e3.hash());
        assertThat(row.eventsChecked()).isEqualTo(3);
        ArgumentCaptor<AuditCheckStore.CheckRow> inserted = ArgumentCaptor.forClass(AuditCheckStore.CheckRow.class);
        verify(checks).insert(inserted.capture());
        assertThat(inserted.getValue()).isSameAs(row);
        verify(events, never()).last(any());
    }

    @Test
    void aFailedCheckRecordsTheChainsLastEventAsFound() {
        List<StoredAuditEvent> chain = chain(4);
        StoredAuditEvent e2 = chain.get(1);
        StoredAuditEvent tampered = new StoredAuditEvent(e2.id(), e2.companyId(), e2.seq(), e2.occurredAt(),
                e2.actorType(), e2.actorId(), e2.action(), e2.targetType(), e2.targetId(), "{\"changed\":[\"forged\"]}",
                e2.prevHash(), e2.hash());
        chainOf(List.of(chain.get(0), tampered, chain.get(2)), chain.get(2), chain.get(3));

        AuditCheckStore.CheckRow row = service.check(COMPANY).orElseThrow();

        assertThat(row.ok()).isFalse();
        assertThat(row.failure()).isEqualTo("BROKEN");
        assertThat(row.firstInvalidSeq()).isEqualTo(2);
        assertThat(row.headSeq()).isEqualTo(4);
        assertThat(row.headHash()).isEqualTo(chain.get(3).hash());
        assertThat(row.eventsChecked()).isEqualTo(3);
        verify(checks).insert(row);
    }

    /** The stores as the check reads them: the head row before the verification, the last event after it. */
    private void chainOf(List<StoredAuditEvent> verified, StoredAuditEvent head, StoredAuditEvent last) {
        when(transactions.inCompany(any(), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        when(checks.tryLock(COMPANY)).thenReturn(true);
        when(checks.anchor(COMPANY)).thenReturn(Optional.empty());
        when(events.head(COMPANY)).thenReturn(Optional.of(new ChainHead(head.seq(), head.hash())));
        when(events.chainSlice(COMPANY, 0, head.seq(), 500)).thenReturn(verified);
        when(events.last(COMPANY)).thenReturn(Optional.of(last));
    }

    /** Events 1 to {@code n} of the company's chain, each hashed over its canonical form and the previous hash. */
    private static List<StoredAuditEvent> chain(int n) {
        List<StoredAuditEvent> chain = new ArrayList<>();
        String prevHash = AuditCanonicalForm.GENESIS_HASH;
        for (long seq = 1; seq <= n; seq++) {
            UUID id = UUID.randomUUID();
            Instant at = Instant.parse("2026-10-26T09:00:00Z").plusSeconds(seq);
            AuditCanonicalForm.Content content = new AuditCanonicalForm.Content(COMPANY, seq, at, "USER", id,
                    "company.renamed", "company", COMPANY.toString(), AuditCanonicalForm.parseDetails("{\"n\":" + seq + "}"));
            String hash = AuditCanonicalForm.hash(prevHash, AuditCanonicalForm.canonical(content));
            chain.add(new StoredAuditEvent(id, COMPANY, seq, at, "USER", id, "company.renamed", "company",
                    COMPANY.toString(), "{\"n\":" + seq + "}", prevHash, hash));
            prevHash = hash;
        }
        return chain;
    }
}
