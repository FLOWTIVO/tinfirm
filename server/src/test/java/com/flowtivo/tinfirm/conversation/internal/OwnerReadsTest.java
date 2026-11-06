package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;

/**
 * {@link OwnerReads} as a unit: who is recorded (OWNER and an instance admin reading another person's conversation;
 * never its person, nor a caller who does not see every conversation), every read with no throttle (two reads in a
 * row, two records), a list once per other person per call, the records of one request in one transaction of their
 * own, and a record that failed failing the read.
 */
class OwnerReadsTest {

    private static final UUID COMPANY = UUID.randomUUID();

    private static final UUID OWNER_USER = UUID.randomUUID();

    private static final UUID OWNER = UUID.randomUUID();

    private static final UUID PERSON = UUID.randomUUID();

    private static final UUID CONVERSATION = UUID.randomUUID();

    private final List<AuditRecord> recorded = new ArrayList<>();

    private final AtomicBoolean failNext = new AtomicBoolean();

    private final AuditLog auditLog = record -> {
        if (failNext.getAndSet(false)) {
            throw new IllegalStateException("The audit log is not reachable");
        }
        recorded.add(record);
    };

    private final CountingTransactions transactions = new CountingTransactions();

    private final OwnerReads reads = new OwnerReads(auditLog, null, transactions);

    private final ConversationsImpl.Caller owner = new ConversationsImpl.Caller(OWNER_USER, OWNER, true);

    private void readConversation(OwnerReads.What what) {
        reads.readAll(COMPANY, owner, Map.of(CONVERSATION, PERSON), what);
    }

    @Test
    void everyReadOfAnotherPersonsConversationIsRecordedWithNoThrottle() {
        readConversation(OwnerReads.What.CONVERSATION);
        readConversation(OwnerReads.What.CONVERSATION);
        readConversation(OwnerReads.What.RUN_EVENTS);
        assertThat(recorded).extracting(record -> record.details().get("what"))
            .containsExactly("conversation", "conversation", "run-events");
        assertThat(transactions.begun).hasValue(3);
        AuditRecord first = recorded.getFirst();
        assertThat(first.action()).isEqualTo(AuditAction.CONVERSATION_READ);
        assertThat(first.companyId()).isEqualTo(COMPANY);
        assertThat(first.actor().id()).isEqualTo(OWNER_USER);
        assertThat(first.targetType()).isEqualTo("conversation");
        assertThat(first.targetId()).isEqualTo(CONVERSATION.toString());
        assertThat(first.details()).containsEntry("conversationId", CONVERSATION.toString())
            .containsEntry("personPrincipalId", PERSON.toString());
    }

    @Test
    void itsPersonAndWhoDoesNotSeeEveryConversationAreNotRecorded() {
        reads.readAll(COMPANY, new ConversationsImpl.Caller(UUID.randomUUID(), PERSON, false),
                Map.of(CONVERSATION, PERSON), OwnerReads.What.CONVERSATION);
        reads.readAll(COMPANY, new ConversationsImpl.Caller(UUID.randomUUID(), PERSON, true),
                Map.of(CONVERSATION, PERSON), OwnerReads.What.CONVERSATION);
        reads.readAll(COMPANY, owner, Map.of(CONVERSATION, OWNER), OwnerReads.What.RUNS);
        reads.listed(COMPANY, owner, List.of(OWNER));
        assertThat(recorded).isEmpty();
        assertThat(transactions.begun).as("no transaction for nothing to record").hasValue(0);
    }

    @Test
    void aTriggersConversationHasNoPersonAndNoReadOfItIsRecorded() {
        Map<UUID, @Nullable UUID> persons = new HashMap<>();
        persons.put(CONVERSATION, null);
        reads.readAll(COMPANY, owner, persons, OwnerReads.What.CONVERSATION);
        reads.readAll(COMPANY, new ConversationsImpl.Caller(UUID.randomUUID(), null, true), persons,
                OwnerReads.What.DECISIONS);
        assertThat(recorded).isEmpty();
        assertThat(transactions.begun).as("no transaction for nothing to record").hasValue(0);
    }

    @Test
    void aPageIsRecordedOncePerOtherPersonOrConversationInOneTransaction() {
        UUID another = UUID.randomUUID();
        reads.listed(COMPANY, owner, List.of(PERSON, PERSON, OWNER, another));
        assertThat(recorded).extracting(record -> record.details().get("personPrincipalId"))
            .containsExactly(PERSON.toString(), another.toString());
        assertThat(recorded).allSatisfy(record -> {
            assertThat(record.targetType()).isEqualTo("principal");
            assertThat(record.details()).containsEntry("what", "conversations").doesNotContainKey("conversationId");
        });
        reads.listed(COMPANY, owner, List.of(PERSON));
        assertThat(recorded).as("the same list again: recorded again").hasSize(3);

        Map<UUID, UUID> decisions = new LinkedHashMap<>();
        UUID other = UUID.randomUUID();
        decisions.put(CONVERSATION, PERSON);
        decisions.put(other, another);
        decisions.put(UUID.randomUUID(), OWNER);
        reads.readAll(COMPANY, owner, decisions, OwnerReads.What.DECISIONS);
        assertThat(recorded.subList(3, recorded.size())).extracting(AuditRecord::targetId)
            .containsExactly(CONVERSATION.toString(), other.toString());
        assertThat(transactions.begun).hasValue(3);
    }

    @Test
    void aRecordThatFailedFailsTheRead() {
        failNext.set(true);
        assertThatThrownBy(() -> readConversation(OwnerReads.What.ATTACHMENT))
            .isInstanceOf(IllegalStateException.class);
        assertThat(recorded).isEmpty();
        readConversation(OwnerReads.What.ATTACHMENT);
        assertThat(recorded).hasSize(1);
    }

    /** Runs the records' own transaction as plain code, counting it: the unit test has no database. */
    private static final class CountingTransactions implements PlatformTransactionManager {

        final AtomicInteger begun = new AtomicInteger();

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            begun.incrementAndGet();
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
