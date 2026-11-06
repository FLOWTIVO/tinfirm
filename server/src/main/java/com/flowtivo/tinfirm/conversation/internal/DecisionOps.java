package com.flowtivo.tinfirm.conversation.internal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.RepositoryAccess;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.CompanyDirectory;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * The rows of decisions (the package 3 contract, §3.12, §5.8): raised, decided and cancelled here, each audited
 * ({@code decision.created}, {@code decision.decided}, {@code decision.cancelled}; ids, kinds and subjects, never the
 * texts), raised with its card in the conversation's thread. Questions and approvals are decided by the conversation's
 * person ({@code approver_principal_id}); a decision is decided once (I16). Every method runs in the caller's
 * transaction, which locked the conversation's row; the caller asks part 1 for the next turn.
 */
@Component
class DecisionOps implements QuestionAnswers, ChangeRequestApprovals {

    static final String TARGET = "decision";

    /** Open questions and open approvals a conversation holds at most, each (§3.12). */
    static final int MAX_OPEN = 10;

    static final int MAX_QUESTION_LENGTH = 2000;

    static final int MAX_OPTIONS = 10;

    static final int MAX_OPTION_LENGTH = 100;

    /** An answer, and an approved call's result, as the row holds them. */
    static final int MAX_ANSWER_LENGTH = 50_000;

    static final int MAX_NOTE_LENGTH = 20_000;

    /** How much of a question's first line an answer through its card quotes. */
    static final int MAX_QUOTE_LENGTH = 200;

    private final DecisionStore store;

    private final ConversationCore core;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final TriggerPorts triggers;

    private final ApplicationEventPublisher events;

    private final PlaceStore places;

    private final CompanyDirectory roles;

    private final Principals principals;

    private final GroupMembership groups;

    DecisionOps(DecisionStore store, ConversationCore core, AuditLog auditLog, TimeSource time,
            TriggerPorts triggers, ApplicationEventPublisher events, PlaceStore places, CompanyDirectory roles,
            Principals principals, GroupMembership groups) {
        this.roles = roles;
        this.principals = principals;
        this.groups = groups;
        this.store = store;
        this.core = core;
        this.auditLog = auditLog;
        this.time = time;
        this.triggers = triggers;
        this.events = events;
        this.places = places;
    }

    /**
     * Who decides an open decision: a principal, a group, or the company's Owners and Admins; nobody for a trigger's
     * question (anyone in the place answers it, its author and team in Tinfirm).
     */
    record Approver(@Nullable UUID principalId, @Nullable UUID groupId, boolean admins) {

        static final Approver ADMINS = new Approver(null, null, true);

        /** Whether the decision already goes to them. */
        boolean decides(DecisionStore.DecisionRow decision) {
            return admins == decision.approverAdmins() && Objects.equals(principalId, decision.approverPrincipalId())
                    && Objects.equals(groupId, decision.approverGroupId());
        }
    }

    /**
     * Who decides a decision of the conversation (the package 4 contract, §3.8, §5.8, T12): its person; for a
     * trigger's conversation a question names nobody, and an approval its trigger's team, or its author without one.
     * When its only candidates are Viewers, who answer and decide nothing, it goes to the company's Owners and Admins
     * instead (ADR-0077, decision 5).
     */
    private DecisionStore.DecisionRow approver(ConversationInfo conversation, DecisionStore.DecisionRow row) {
        Approver approver = approverOf(conversation, row.kind());
        if (approver.admins()) {
            return row.toAdmins();
        }
        return conversation.triggered() ? row.approvedBy(approver.principalId(), approver.groupId()) : row;
    }

    /**
     * {@link #approver}, as the roles, the groups and the conversation's trigger are now: its team and its author,
     * who may have been reassigned since the conversation started.
     */
    Approver approverOf(ConversationInfo conversation, DecisionStore.Kind kind) {
        if (!conversation.triggered()) {
            return approverOf(conversation, kind, null, null);
        }
        java.util.Optional<com.flowtivo.tinfirm.conversation.TriggerAudience.TriggerView> trigger = triggers
            .trigger(conversation.companyId(), conversation.triggerId());
        return approverOf(conversation, kind,
                trigger.map(com.flowtivo.tinfirm.conversation.TriggerAudience.TriggerView::teamGroupId).orElse(null),
                trigger.map(com.flowtivo.tinfirm.conversation.TriggerAudience.TriggerView::authorPrincipalId)
                    .orElse(conversation.authorPrincipalId()));
    }

    /** {@link #approver}, for a trigger's conversation with the given team and author. */
    Approver approverOf(ConversationInfo conversation, DecisionStore.Kind kind, @Nullable UUID team,
            @Nullable UUID author) {
        UUID companyId = conversation.companyId();
        if (!conversation.triggered()) {
            return viewer(companyId, conversation.personPrincipalId()) ? Approver.ADMINS
                    : new Approver(conversation.personPrincipalId(), null, false);
        }
        if (kind == DecisionStore.Kind.QUESTION) {
            boolean onlyViewers = viewer(companyId, author)
                    && (team == null || groups.membersOf(companyId, team).isEmpty() || onlyViewers(companyId, team));
            return onlyViewers ? Approver.ADMINS : new Approver(null, null, false);
        }
        if (team != null) {
            return onlyViewers(companyId, team) ? Approver.ADMINS : new Approver(null, team, false);
        }
        return viewer(companyId, author) ? Approver.ADMINS : new Approver(author, null, false);
    }

    /**
     * The open decision goes to {@code to}, audited as {@code decision.approver.changed}; nothing when it already
     * does. Its conversation and the decision are locked by the caller.
     */
    void reroute(DecisionStore.DecisionRow decision, Approver to) {
        if (to.decides(decision)) {
            return;
        }
        store.route(decision.id(), to.principalId(), to.groupId(), to.admins());
        auditLog.record(AuditRecord.company(decision.companyId(), AuditAction.DECISION_APPROVER_CHANGED,
                CurrentActor.actor())
            .target(TARGET, decision.id())
            .detail("conversationId", decision.conversationId().toString())
            .detail("fromGroupId", decision.approverGroupId() != null ? decision.approverGroupId().toString() : null)
            .detail("fromPrincipalId", decision.approverPrincipalId() != null
                    ? decision.approverPrincipalId().toString() : null)
            .detail("fromAdmins", decision.approverAdmins())
            .detail("toGroupId", to.groupId() != null ? to.groupId().toString() : null)
            .detail("toPrincipalId", to.principalId() != null ? to.principalId().toString() : null)
            .detail("toAdmins", to.admins())
            .build());
    }

    /** Whether the member's principal is a Viewer's. */
    private boolean viewer(UUID companyId, @Nullable UUID principalId) {
        UUID userId = principalId != null
                ? principals.find(companyId, principalId).map(PrincipalInfo::userId).orElse(null) : null;
        return userId != null && roles.roleOf(companyId, userId).orElse(null) == CompanyRole.VIEWER;
    }

    /** Whether the group has members and every one of them is a Viewer. */
    private boolean onlyViewers(UUID companyId, UUID groupId) {
        List<UUID> members = groups.membersOf(companyId, groupId);
        return !members.isEmpty() && members.stream()
            .allMatch(userId -> roles.roleOf(companyId, userId).orElse(null) == CompanyRole.VIEWER);
    }

    /**
     * A decision raised, or the one of the request id raised before.
     *
     * @param created false for a request sent again, which got the decision it raised the first time
     */
    record Raised(DecisionStore.DecisionRow decision, boolean created) {
    }

    // ---- raising ---------------------------------------------------------------------------------------------------

    /**
     * A question for the conversation's person, with its card: the question and its options, by the agent.
     *
     * @param runId     the run that asked, or whose {@code BLOCKED} result asked it
     * @param requestId the {@code ASK} request; null for a result's question
     * @param card      whether it gets a card of its own; a result's questions are listed by the turn's message
     */
    Raised question(ConversationInfo conversation, @Nullable UUID runId, @Nullable UUID requestId, String question,
            List<String> options, boolean card) {
        DecisionStore.DecisionRow row = new DecisionStore.DecisionRow(Ids.newId(), conversation.companyId(),
                conversation.id(), runId, requestId, DecisionStore.Kind.QUESTION, question, options, null, null,
                conversation.personPrincipalId(), DecisionStore.Status.OPEN, null, null, null, null, null, null, null,
                null, null, null, time.now(), null, null, null);
        row = approver(conversation, row);
        return raise(conversation, row, Actor.agent(conversation.agentId()),
                card ? ConversationMessages.Kind.AGENT : null, questionCard(question, options));
    }

    /**
     * An approval for the conversation's person, with its card.
     *
     * @param byAgent whether the agent's request or call raised it (actor AGENT, an AGENT card), or the server itself
     *                (a change request under {@code ASK}: actor SYSTEM, a SYSTEM card)
     * @param card    the card's text: what is asked, in words
     */
    Raised approval(ConversationInfo conversation, @Nullable UUID runId, @Nullable UUID requestId,
            DecisionSubject subject, @Nullable String reason, boolean byAgent, String card) {
        DecisionStore.DecisionRow row = new DecisionStore.DecisionRow(Ids.newId(), conversation.companyId(),
                conversation.id(), runId, requestId, DecisionStore.Kind.APPROVAL, null, List.of(), subject, reason,
                conversation.personPrincipalId(), DecisionStore.Status.OPEN, null, null, null, null, null, null, null,
                null, null, null, time.now(), null, null, null);
        row = approver(conversation, row);
        return raise(conversation, row, byAgent ? Actor.agent(conversation.agentId()) : Actor.SYSTEM,
                byAgent ? ConversationMessages.Kind.AGENT : ConversationMessages.Kind.SYSTEM, card);
    }

    private Raised raise(ConversationInfo conversation, DecisionStore.DecisionRow row, Actor actor,
            ConversationMessages.@Nullable Kind cardKind, String card) {
        if (row.requestId() != null) {
            var repeated = store.byRequest(conversation.id(), row.requestId());
            if (repeated.isPresent()) {
                return new Raised(repeated.get(), false);
            }
        }
        if (!store.insert(row)) {
            return new Raised(store.byRequest(conversation.id(), row.requestId()).orElseThrow(), false);
        }
        AuditRecord.Builder audit = AuditRecord.company(row.companyId(), AuditAction.DECISION_CREATED, actor)
            .target(TARGET, row.id())
            .detail("conversationId", row.conversationId().toString())
            .detail("runId", row.runId() != null ? row.runId().toString() : null)
            .detail("kind", row.kind().name());
        if (row.subject() != null) {
            audit.detail("subject", row.subject().auditDetail());
        }
        auditLog.record(audit.build());
        if (cardKind != null) {
            core.messages().post(row.companyId(), row.conversationId(), cardKind, null, cut(card, 100_000),
                    row.runId(), row.id());
        }
        core.turns().refreshWaiting(row.companyId(), row.conversationId());
        events.publishEvent(new ConversationEvents.DecisionRaised(row.companyId(), row.conversationId(), row.id(),
                row.kind().name()));
        return new Raised(store.find(row.id()).orElseThrow(), true);
    }

    // ---- deciding --------------------------------------------------------------------------------------------------

    /**
     * Decides an OPEN decision once (I16), audited as {@code decision.decided} by the current actor: the person who
     * decided ({@code decidedBy}). {@code 409 DECISION_DECIDED} when it was decided or cancelled meanwhile; the caller's
     * transaction then rolls back whatever it did.
     */
    DecisionStore.DecisionRow decide(DecisionStore.DecisionRow decision, DecisionStore.Outcome outcome,
            @Nullable String answer, @Nullable String option, @Nullable String note,
            DecisionStore.@Nullable Scope scope, @Nullable RepositoryAccess access, UUID decidedBy,
            @Nullable String via) {
        if (!store.decide(decision.id(), outcome, answer, option, note, scope, access, decidedBy, time.now())) {
            throw decided();
        }
        PlaceDeciding.Context place = PlaceDeciding.current();
        AuditRecord.Builder audit = AuditRecord.company(decision.companyId(), AuditAction.DECISION_DECIDED,
                CurrentActor.actor())
            .target(TARGET, decision.id())
            .detail("conversationId", decision.conversationId().toString())
            .detail("kind", decision.kind().name())
            .detail("outcome", outcome.name())
            .detail("scope", scope != null ? scope.name() : null)
            .detail("access", access != null ? access.name() : null)
            .detail("decidedByPrincipalId", decidedBy.toString())
            .detail("via", via);
        if (decision.subject() != null) {
            audit.detail("subject", decision.subject().auditDetail());
        }
        fromPlace(audit, decision.id(), place);
        auditLog.record(audit.build());
        events.publishEvent(new ConversationEvents.DecisionDecided(decision.companyId(), decision.conversationId(),
                decision.id(), decision.kind().name(), outcome.name(), decidedBy, null, null));
        events.publishEvent(new ConversationEvents.DecisionClosed(decision.companyId(), decision.conversationId(),
                decision.id(), outcome.name(), place != null ? place.via() : "APP", null));
        return store.find(decision.id()).orElseThrow();
    }

    /** Cancels an OPEN decision, audited as {@code decision.cancelled} with the cause; false when it was not open. */
    boolean cancel(DecisionStore.DecisionRow decision, String cause) {
        if (!store.cancel(decision.id(), cause)) {
            return false;
        }
        AuditRecord.Builder audit = AuditRecord.company(decision.companyId(), AuditAction.DECISION_CANCELLED,
                CurrentActor.actor())
            .target(TARGET, decision.id())
            .detail("conversationId", decision.conversationId().toString())
            .detail("kind", decision.kind().name())
            .detail("cause", cause);
        if (decision.subject() != null) {
            audit.detail("subject", decision.subject().auditDetail());
        }
        auditLog.record(audit.build());
        events.publishEvent(new ConversationEvents.DecisionClosed(decision.companyId(), decision.conversationId(),
                decision.id(), "CANCELLED", "APP", null));
        return true;
    }

    /** The result of an approved call, at most {@value #MAX_ANSWER_LENGTH} characters. */
    void recordResult(UUID decisionId, String result) {
        store.recordResult(decisionId, cut(result.strip().isEmpty() ? "(no result)" : result.strip(),
                MAX_ANSWER_LENGTH));
    }

    /**
     * A decision decided from a place (the package 5 contract, §5.8): {@code decided_via} {@code CHAT}, the chat user
     * and their name on the row, and {@code via} and the chat user's id in the audit (row 7); never their name (C16).
     */
    private void fromPlace(AuditRecord.Builder audit, UUID decisionId, PlaceDeciding.@Nullable Context place) {
        if (place == null) {
            return;
        }
        places.decidedFromPlace(decisionId, place.externalId(), place.name());
        audit.detail("via", place.via()).detail("place", place.place()).detail("chatUserId", place.externalId());
    }

    // ---- QuestionAnswers (ADR-0033) ----------------------------------------------------------------------------------

    /**
     * A person's message that answers every open question: each OPEN question of the conversation is answered with
     * the message's text, by the person, audited as {@code decision.decided}. Approvals stay open.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int answerAll(ConversationInfo conversation, UUID messageId, String body, UUID personPrincipalId) {
        return answerAll(conversation, messageId, body, personPrincipalId, null);
    }

    /**
     * Every open question of the conversation answered by one message: a person's in Tinfirm, or a comment on a
     * trigger's conversation's issue (the package 4 contract, §5.8), whose author is the member with the commenter's
     * identity, if any, and the commenter's name at the system. Approvals are never decided by a message (T12).
     *
     * @param decidedByName the commenter's name at the system; null for a person's message in Tinfirm
     */
    @Override
    public int answerAll(ConversationInfo conversation, UUID messageId, String body, @Nullable UUID principalId,
            @Nullable String decidedByName) {
        store.lockConversation(conversation.id());
        String answer = cut(body.strip(), MAX_ANSWER_LENGTH);
        int answered = 0;
        // Announced once every question is answered, so that a listener sees all the message answered.
        List<ConversationEvents.DecisionDecided> decided = new java.util.ArrayList<>();
        for (DecisionStore.DecisionRow open : store.lockOpen(conversation.id())) {
            if (open.kind() == DecisionStore.Kind.QUESTION
                    && store.decide(open.id(), DecisionStore.Outcome.ANSWERED, answer, null, null, null, null,
                            principalId, time.now())) {
                if (decidedByName != null || conversation.triggered()) {
                    store.answeredBy(open.id(), decidedByName != null ? cut(decidedByName, 255) : null, messageId);
                }
                PlaceDeciding.Context place = PlaceDeciding.current();
                AuditRecord.Builder audit = AuditRecord.company(open.companyId(), AuditAction.DECISION_DECIDED,
                        CurrentActor.actor())
                    .target(TARGET, open.id())
                    .detail("conversationId", open.conversationId().toString())
                    .detail("kind", open.kind().name())
                    .detail("outcome", DecisionStore.Outcome.ANSWERED.name())
                    .detail("decidedByPrincipalId", principalId != null ? principalId.toString() : null)
                    .detail("messageId", messageId.toString());
                fromPlace(audit, open.id(), place);
                auditLog.record(audit.build());
                decided.add(new ConversationEvents.DecisionDecided(open.companyId(), open.conversationId(),
                        open.id(), open.kind().name(), DecisionStore.Outcome.ANSWERED.name(), principalId,
                        decidedByName, messageId));
                events.publishEvent(new ConversationEvents.DecisionClosed(open.companyId(), open.conversationId(),
                        open.id(), DecisionStore.Outcome.ANSWERED.name(), place != null ? place.via() : "APP",
                        messageId));
                answered++;
            }
        }
        decided.forEach(events::publishEvent);
        if (answered > 0) {
            core.turns().refreshWaiting(conversation.companyId(), conversation.id());
        }
        return answered;
    }

    /**
     * One open question answered by one message from the place (the package 5 contract, §5.8): a press of an option in
     * a channel's thread, by whoever the place vouches for (a member of the workspace, linked or not), with
     * {@code decided_by_name} and the message, audited as {@code decision.decided} with the place. False when it was
     * decided already.
     *
     * @param principalId the member linked to the chat user, else null
     */
    boolean answerOne(ConversationInfo conversation, UUID decisionId, UUID messageId, String answer,
            @Nullable String option, @Nullable UUID principalId, String decidedByName) {
        store.lockConversation(conversation.id());
        DecisionStore.DecisionRow open = store.lock(decisionId).orElse(null);
        if (open == null || !open.open() || open.kind() != DecisionStore.Kind.QUESTION
                || !store.decide(open.id(), DecisionStore.Outcome.ANSWERED, cut(answer.strip(), MAX_ANSWER_LENGTH),
                        option, null, null, null, principalId, time.now())) {
            return false;
        }
        store.answeredBy(open.id(), cut(decidedByName, 255), messageId);
        PlaceDeciding.Context place = PlaceDeciding.current();
        AuditRecord.Builder audit = AuditRecord.company(open.companyId(), AuditAction.DECISION_DECIDED,
                CurrentActor.actor())
            .target(TARGET, open.id())
            .detail("conversationId", open.conversationId().toString())
            .detail("kind", open.kind().name())
            .detail("outcome", DecisionStore.Outcome.ANSWERED.name())
            .detail("decidedByPrincipalId", principalId != null ? principalId.toString() : null)
            .detail("messageId", messageId.toString());
        fromPlace(audit, open.id(), place);
        auditLog.record(audit.build());
        events.publishEvent(new ConversationEvents.DecisionClosed(open.companyId(), open.conversationId(), open.id(),
                DecisionStore.Outcome.ANSWERED.name(), place != null ? place.via() : "APP", messageId));
        events.publishEvent(new ConversationEvents.DecisionDecided(open.companyId(), open.conversationId(), open.id(),
                open.kind().name(), DecisionStore.Outcome.ANSWERED.name(), principalId, decidedByName, messageId));
        core.turns().refreshWaiting(conversation.companyId(), conversation.id());
        return true;
    }

    // ---- ChangeRequestApprovals (part 3) ---------------------------------------------------------------------------

    /**
     * A change request under {@code ASK} (§5.4, open point 5): an approval of the subject
     * {@code {type: CHANGE_REQUEST, repositoryId}}, raised by the server; the open one of that repository when there is
     * one.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID raise(UUID companyId, UUID conversationId, @Nullable UUID runId, UUID repositoryId, String what) {
        store.lockConversation(conversationId);
        var open = store.openApproval(conversationId, DecisionSubject.Type.CHANGE_REQUEST, "repositoryId",
                repositoryId.toString());
        if (open.isPresent()) {
            return open.get().id();
        }
        ConversationInfo conversation = core.conversations().find(companyId, conversationId)
            .orElseThrow(() -> new IllegalArgumentException("Conversation " + conversationId + " is not visible"));
        String card = cut(what.strip(), MAX_QUESTION_LENGTH);
        return approval(conversation, runId, null, DecisionSubject.changeRequest(repositoryId), null, false,
                card + (card.endsWith(".") ? "" : ".") + "\n\nIt opens once you approve it.").decision().id();
    }

    // ---- texts -----------------------------------------------------------------------------------------------------

    /** A question's card: the question, then its options as a Markdown list. */
    static String questionCard(String question, List<String> options) {
        StringBuilder body = new StringBuilder(question);
        if (!options.isEmpty()) {
            body.append("\n");
            options.forEach(option -> body.append("\n- ").append(option));
        }
        return body.toString();
    }

    /** An answer through a card (§5.8): the question's first line quoted, at most 200 characters, then the answer. */
    static String quoted(@Nullable String question, String answer) {
        String line = question != null ? question.strip().lines().findFirst().orElse("").strip() : "";
        if (characters(line) > MAX_QUOTE_LENGTH) {
            line = line.substring(0, line.offsetByCodePoints(0, MAX_QUOTE_LENGTH)).strip() + "…";
        }
        return "> " + line + "\n\n" + answer;
    }

    /** Characters as PostgreSQL counts them: code points. */
    static int characters(String text) {
        return text.codePointCount(0, text.length());
    }

    /** The text cut to at most {@code max} characters, with an ellipsis when cut. */
    static String cut(String text, int max) {
        if (characters(text) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max - 1)).stripTrailing() + "…";
    }

    static ApiException decided() {
        return ApiException.conflict(ProblemCode.DECISION_DECIDED, "The decision was already decided or cancelled");
    }
}
