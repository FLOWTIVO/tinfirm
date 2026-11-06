package com.flowtivo.tinfirm.conversation.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.security.Actor;

/**
 * The audit of OWNER's reads of personal conversations (the security review of the redesign, row 18; CONVENTIONS §8,
 * {@code conversation.read}). OWNER, and an instance admin acting in the company, see every conversation in full
 * (§5.10, I17); every read of one, or of what belongs to it, by such a caller who is not its person is on record, on
 * every read path of the API ({@link What}). Its person's own reads, and every read of OWNER's own conversations, are
 * not. Nor is any read of a trigger's conversation: it has no person, and its trigger's audience, OWNER and ADMIN among
 * them, reads it by right (the package 4 contract, §5.13).
 * <p>
 * <b>Every read.</b> Each request records, with no throttle: two reads in a row are two records, and a page that polls
 * writes one per poll (the owner's decision of 2026-10-12, package 6's open point 9). An event stream is one read,
 * recorded when it opens.
 * <p>
 * <b>When.</b> In a transaction of its own ({@code REQUIRES_NEW}), as the break-glass's {@code admin.company.accessed}
 * is, so the read is on record even when the caller's transaction is read-only or what follows fails, and a record
 * that fails fails the read. The records of one request share that transaction. The caller asks at its access check,
 * before its transaction locks a row: the record's own transaction takes the company's chain head when it commits, at
 * once, while the caller's waits on it where PostgreSQL does not see it (the lock order, CONVENTIONS §10). The
 * caller's own audit records take no head before its commit.
 */
@Component
class OwnerReads {

    /** What of a conversation a read shows: the read paths of the API. */
    enum What {

        /** {@code GET /conversations/{id}}, and the conversation OWNER's cancel, retry and end answer. */
        CONVERSATION("conversation"),

        /** {@code GET /conversations}: one record per person whose conversations the page lists. */
        CONVERSATIONS("conversations"),

        /** {@code GET /runs/{runId}}, and the run {@code POST /runs/{runId}/cancel} answers. */
        RUNS("runs"),

        /** {@code GET /runs/{runId}/events}. */
        RUN_EVENTS("run-events"),

        /** {@code GET /runs/{runId}/events/stream}, when it opens. */
        RUN_STREAM("run-stream"),

        /** {@code GET /conversations/{id}/attachments}. */
        ATTACHMENTS("attachments"),

        /** {@code GET /conversations/{id}/attachments/{attachmentId}}: a file's content, an output's document too. */
        ATTACHMENT("attachment"),

        /** {@code GET /decisions} (one record per conversation listed) and {@code GET /decisions/{decisionId}}. */
        DECISIONS("decisions");

        private final String value;

        What(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    private final AuditLog auditLog;

    private final Principals principals;

    private final TransactionTemplate ownTransaction;

    OwnerReads(AuditLog auditLog, Principals principals, PlatformTransactionManager transactionManager) {
        this.auditLog = auditLog;
        this.principals = principals;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * A read of the conversation, or of what belongs to it, by a caller who sees it. A conversation held elsewhere
     * than in Tinfirm names its place and origin in the details (package 5, §5.13: a direct message's conversation
     * shows its chat messages with their chat authors, and its files from the chat).
     */
    void read(UUID companyId, ConversationsImpl.Caller caller, ConversationInfo conversation, What what) {
        UUID person = conversation.personPrincipalId();
        if (person == null) {
            // A trigger's conversation has no person: its audience (OWNER and ADMIN among them) reads it by right
            // (the package 4 contract, §5.13), and no read of it is on record.
            return;
        }
        if (conversation.place() == ConversationInfo.Place.TINFIRM_THREAD) {
            readAll(companyId, caller, Map.of(conversation.id(), person), what);
            return;
        }
        // A conversation held elsewhere names its place and origin (package 5: a direct message's, §5.13).
        if (caller.readsAsOwner(person)) {
            record(List.of(AuditRecord.company(companyId, AuditAction.CONVERSATION_READ, Actor.user(caller.userId()))
                .target(ConversationService.TARGET, conversation.id())
                .detail("conversationId", conversation.id().toString())
                .detail("personPrincipalId", person.toString())
                .detail("what", what.value())
                .detail("place", conversation.place().name())
                .detail("origin", conversation.origin().name())
                .build()));
        }
    }

    /**
     * {@link #read(UUID, ConversationsImpl.Caller, ConversationInfo, What)} for a service that holds the caller's
     * access only: the caller's principal is looked up for OWNER and an instance admin alone.
     */
    void read(CompanyAccess access, UUID userId, ConversationInfo conversation, What what) {
        if (!ConversationsImpl.seesEvery(access)) {
            return;
        }
        read(access.companyId(), ConversationsImpl.caller(access, userId, principals), conversation, what);
    }

    /**
     * A read of what belongs to several conversations, each of its person, by a caller who sees them (a page of
     * {@code GET /decisions}): one record for each conversation the caller reads as OWNER, or as an instance admin,
     * and is not its person.
     *
     * @param persons each conversation's person, by the conversation's id
     */
    void readAll(UUID companyId, ConversationsImpl.Caller caller, Map<UUID, @Nullable UUID> persons, What what) {
        List<AuditRecord> records = new ArrayList<>();
        persons.forEach((conversationId, person) -> {
            if (caller.readsAsOwner(person)) {
                records.add(AuditRecord.company(companyId, AuditAction.CONVERSATION_READ, Actor.user(caller.userId()))
                    .target(ConversationService.TARGET, conversationId)
                    .detail("conversationId", conversationId.toString())
                    .detail("personPrincipalId", person.toString())
                    .detail("what", what.value())
                    .build());
            }
        });
        record(records);
    }

    /**
     * A page of {@code GET /conversations}: one record per person whose conversations it lists, other than the
     * caller, naming the person and no conversation ({@code what} {@code conversations}). A list shows summaries
     * (a title, a subject, a state); what the person learns from the record is that OWNER looked at their
     * conversations, and the reads of a conversation's content are each recorded on their own.
     */
    void listed(UUID companyId, ConversationsImpl.Caller caller, Collection<UUID> personPrincipalIds) {
        List<AuditRecord> records = new ArrayList<>();
        for (UUID person : new LinkedHashSet<>(personPrincipalIds)) {
            if (caller.readsAsOwner(person)) {
                records.add(AuditRecord.company(companyId, AuditAction.CONVERSATION_READ, Actor.user(caller.userId()))
                    .target("principal", person)
                    .detail("personPrincipalId", person.toString())
                    .detail("what", What.CONVERSATIONS.value())
                    .build());
            }
        }
        record(records);
    }

    /** The request's records, in one transaction of their own; nothing when there are none. */
    private void record(List<AuditRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        ownTransaction.executeWithoutResult(status -> records.forEach(auditLog::record));
    }
}
