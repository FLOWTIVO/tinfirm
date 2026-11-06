package com.flowtivo.tinfirm.conversation.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.RepositoryAccess;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.company.CompanySwitches;
import com.flowtivo.tinfirm.conversation.ConversationCommands.Submitted;
import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.ConversationView;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.DecideRequest;
import com.flowtivo.tinfirm.shared.api.model.Decision;
import com.flowtivo.tinfirm.shared.api.model.DecisionPage;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * The Decisions API (the package 3 contract, §5.8, §6): list, get and decide. A caller sees the decisions of the
 * conversations they see ({@code Conversations.maySee}: their person, OWNER in full); what they may not see is
 * {@code 404}; OWNER's reads of another person's decisions are audited ({@link OwnerReads}). Only the approver decides
 * ({@link DecisionPresenter.Decider}, I15): anyone else who sees it gets {@code 409 CONVERSATION_BOUND}. A decision is
 * decided once (I16, {@code 409 DECISION_DECIDED}). What an approval does is done in the deciding transaction, but a
 * tool call and a change request, which call a system: they follow right after the commit ({@link ApprovedCalls}). The
 * conversation's {@code DECIDED} turn is requested once its decision is decided (after an approved call ran, for a tool
 * call); part 1 starts it once nothing of the conversation is open.
 */
@Service
class DecisionService {

    private final DecisionStore store;

    private final DecisionOps decisions;

    private final DecisionPresenter presenter;

    private final ApprovedCalls approved;

    private final ConversationCore core;

    private final Agents agents;

    private final Settings settings;

    private final Principals principals;

    private final CompanyAccessGuard companyAccess;

    private final OwnerReads ownerReads;

    private final ConversationsImpl conversations;

    private final ApplicationEventPublisher events;

    private final CompanySwitches companySwitches;

    private final SubmissionKeys keys;

    DecisionService(DecisionStore store, DecisionOps decisions, DecisionPresenter presenter, ApprovedCalls approved,
            ConversationCore core, Agents agents, Settings settings, Principals principals,
            CompanyAccessGuard companyAccess, OwnerReads ownerReads, ConversationsImpl conversations,
            ApplicationEventPublisher events, CompanySwitches companySwitches, SubmissionKeys keys) {
        this.conversations = conversations;
        this.events = events;
        this.companySwitches = companySwitches;
        this.keys = keys;
        this.store = store;
        this.decisions = decisions;
        this.presenter = presenter;
        this.approved = approved;
        this.core = core;
        this.agents = agents;
        this.settings = settings;
        this.principals = principals;
        this.companyAccess = companyAccess;
        this.ownerReads = ownerReads;
    }

    /**
     * {@code GET /decisions}: newest first; {@code open}, {@code mine}. OWNER sees every conversation's in the app,
     * and only their own conversations' through a client (G9).
     */
    @Transactional(readOnly = true)
    public DecisionPage list(UUID companyId, ConversationView view, boolean open, boolean mine,
            @Nullable Integer limit, @Nullable String cursor) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
        UUID userId = CurrentActor.requireUser().id();
        ConversationsImpl.Caller seen = conversations.caller(access, userId);
        ConversationStore.Scope scope = ConversationService.scope(seen, view);
        DecisionPresenter.Decider decider = presenter.decider(access, userId);
        if (scope.none() || mine && decider.principalId() == null) {
            return new DecisionPage(List.of());
        }
        PageRequest request = PageRequest.of(limit, cursor);
        DecisionStore.Filter filter = new DecisionStore.Filter(scope, open, mine
                ? new DecisionStore.Mine(Objects.requireNonNull(decider.principalId()), decider.groupIds(),
                        decider.acting().all() ? Set.of() : decider.acting().triggerIds(), decider.admin())
                : null);
        List<DecisionStore.DecisionRow> rows = request.firstPage()
                ? store.page(filter, null, null, request.fetchSize())
                : store.page(filter, request.instantKey(0, 2), request.uuidKey(1, 2), request.fetchSize());
        Page<DecisionStore.DecisionRow> page = Page.of(rows, request,
                row -> List.of(row.createdAt().toString(), row.id().toString()));
        if (seen.owner() && view == ConversationView.APP) {
            // One record per conversation listed, of another person (a trigger's conversation has none: nothing).
            Map<UUID, @Nullable UUID> listed = new LinkedHashMap<>();
            page.items().forEach(row -> listed.computeIfAbsent(row.conversationId(), id -> core.conversations()
                .find(companyId, id).map(ConversationInfo::personPrincipalId).orElse(null)));
            ownerReads.readAll(companyId, seen, listed, OwnerReads.What.DECISIONS);
        }
        return new DecisionPage(presenter.decisions(companyId, page.items(), decider)).nextCursor(page.nextCursor());
    }

    /** {@code GET /decisions/{decisionId}}. */
    @Transactional(readOnly = true)
    public Decision get(UUID companyId, ConversationView view, UUID decisionId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
        Visible visible = visible(access, decisionId, view);
        ownerReads.read(access, CurrentActor.requireUser().id(), visible.conversation(), OwnerReads.What.DECISIONS);
        return presenter.decision(visible.decision(), presenter.decider(access, CurrentActor.requireUser().id()));
    }


    /**
     * Whether the decision is a team's approval (the package 6 contract, open point 15): an approval of a trigger's
     * conversation, which releases a write into a shared place and is decided only where Tinfirm renders the call, in
     * the app and the chat; never through a person's AI client.
     */
    static boolean teamApproval(DecisionStore.DecisionRow decision, ConversationInfo conversation) {
        return decision.kind() == DecisionStore.Kind.APPROVAL
                && conversation.origin() == ConversationInfo.Origin.TRIGGER;
    }

    /**
     * {@code POST /decisions/{decisionId}/decide}. Through a client, a team's approval is {@code 409 DECIDE_IN_APP},
     * whoever asks, and a decision decided is audited with {@code via CLIENT}. With an idempotency key (package R,
     * §3.5), the same request again answers the decision as it is now, and decides nothing.
     */
    @Transactional
    public Submitted<Decision> decide(UUID companyId, ConversationView view, UUID decisionId, DecideRequest request,
            @Nullable String idempotencyKey) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.WORK);
        UUID userId = CurrentActor.requireUser().id();
        Visible visible = visible(access, decisionId, view);
        if (view == ConversationView.CLIENT && teamApproval(visible.decision(), visible.conversation())) {
            throw ApiException.conflict(ProblemCode.DECIDE_IN_APP, "This approval is the team's: decide it in "
                    + "Tinfirm, where the call and its arguments are shown, not through an AI client",
                    Map.of("conversationId", visible.conversation().id().toString(), "decisionId",
                            visible.decision().id().toString()));
        }
        String via = view == ConversationView.CLIENT ? ConversationView.CLIENT.name() : null;
        DecisionPresenter.Decider decider = presenter.decider(access, userId);
        UUID caller = decider.principalId();
        if (caller == null && principals.ofMember(companyId, userId).filter(found -> !found.active()).isPresent()) {
            // G6: a suspended approver decides nothing, in the app, a chat or a client.
            throw ApiException.conflict(ProblemCode.PRINCIPAL_SUSPENDED, "You are suspended in this company: your "
                    + "decisions wait for an admin to lift the suspension, or for someone else to decide them");
        }
        if (!decider.approves(visible.decision(), visible.conversation())) {
            throw ApiException.conflict(ProblemCode.CONVERSATION_BOUND, "Only the conversation's person decides its "
                    + "questions and approvals; a trigger's, its team or its author (its approver)");
        }
        // The key before anything is locked or decided: a replay finds the decision decided.
        SubmissionKeys.Claim claim = keys.claim(companyId, caller, idempotencyKey,
                SubmissionKeys.Operation.decideDecision, SubmissionKeys.requestSha256("POST", "/api/companies/"
                        + companyId + "/decisions/" + decisionId + "/decide", request));
        if (claim instanceof SubmissionKeys.Claim.Replay) {
            return new Submitted<>(presenter.decision(store.find(decisionId).orElseThrow(DecisionService::notFound),
                    decider), true);
        }
        decideAs(companyId, userId, visible.conversation(), decisionId, request, Objects.requireNonNull(caller), via);
        keys.made(claim, SubmissionKeys.DECISION, decisionId);
        return new Submitted<>(presenter.decision(store.find(decisionId).orElseThrow(), decider), false);
    }

    /**
     * Decides as the approver who may decide it ({@link #mayDecide}, checked by the caller), under the conversation's
     * lock: the app's {@code decide}, and a press or an answer from the place ({@code PlaceDecisions}, the package 5
     * contract §5.8).
     *
     * @param userId the deciding person's user, for the right to edit the agent (scope {@code AGENT})
     * @param via    {@code CLIENT} for a decision through a person's AI client; null otherwise (a place's decision
     *               names its place itself, {@code PlaceDeciding})
     */
    void decideAs(UUID companyId, UUID userId, ConversationInfo conversation, UUID decisionId, DecideRequest request,
            UUID person, @Nullable String via) {
        store.lockConversation(conversation.id());
        DecisionStore.DecisionRow decision = store.lock(decisionId).orElseThrow(DecisionService::notFound);
        if (!decision.open()) {
            throw DecisionOps.decided();
        }
        if (!conversation.open()) {
            throw ApiException.conflict(ProblemCode.CONVERSATION_ENDED, "The conversation ended");
        }
        boolean turnFollowsTheCall = switch (decision.kind()) {
            case QUESTION -> {
                answer(conversation, decision, request, person, via);
                yield false;
            }
            case APPROVAL -> approve(companyId, userId, conversation, decision, request, person, via);
        };
        if (!turnFollowsTheCall) {
            core.turns().request(companyId, conversation.id(), TurnReason.DECIDED);
        }
        core.turns().refreshWaiting(companyId, conversation.id());
    }

    /** A question answered through its card, also the person's message quoting it (§5.8). */
    private void answer(ConversationInfo conversation, DecisionStore.DecisionRow decision, DecideRequest request,
            UUID person, @Nullable String via) {
        if (request.getOutcome() != null || request.getScope() != null || request.getAccess() != null
                || request.getNote() != null) {
            throw ApiException.validation("A question takes an answer or an option; outcome, note, scope and access "
                    + "are an approval's");
        }
        String option = pickedOption(decision, request.getOption());
        String answer = answerText(option, request.getAnswer());
        decisions.decide(decision, DecisionStore.Outcome.ANSWERED, answer, option, null, null, null, person, via);
        core.messages().post(conversation.companyId(), conversation.id(), ConversationMessages.Kind.PERSON, person,
                DecisionOps.quoted(decision.question(), answer), null, decision.id());
    }

    /**
     * An approval decided, and what it does (§5.8).
     *
     * @return whether the {@code DECIDED} turn follows an approved call after the commit
     */
    private boolean approve(UUID companyId, UUID userId, ConversationInfo conversation,
            DecisionStore.DecisionRow decision, DecideRequest request, UUID person, @Nullable String via) {
        if (request.getOutcome() == null) {
            throw ApiException.validation("An approval is decided with outcome APPROVED or DENIED");
        }
        if (request.getAnswer() != null || request.getOption() != null) {
            throw ApiException.validation("answer and option are a question's");
        }
        boolean approvedNow = request.getOutcome() == DecideRequest.OutcomeEnum.APPROVED;
        DecisionSubject subject = decision.requireSubject();
        boolean requestOf = subject.type() == DecisionSubject.Type.REPOSITORY
                || subject.type() == DecisionSubject.Type.HOST;
        if (request.getScope() != null && (!requestOf || !approvedNow)) {
            throw ApiException.validation("scope applies to an approved request of a repository or a host");
        }
        if (request.getAccess() != null && (subject.type() != DecisionSubject.Type.REPOSITORY || !approvedNow)) {
            throw ApiException.validation("access applies to an approved request of a repository");
        }
        String note = request.getNote() != null && !request.getNote().isBlank()
                ? DecisionOps.cut(request.getNote().strip(), DecisionOps.MAX_NOTE_LENGTH) : null;
        if (subject.type() == DecisionSubject.Type.RESULT) {
            return review(conversation, decision, subject, approvedNow, note, person, via);
        }
        if (!approvedNow) {
            decisions.decide(decision, DecisionStore.Outcome.DENIED, null, null, note, null, null, person, via);
            return false;
        }
        if (companySwitches.runsStopped(companyId).isPresent()) {
            // G4: nothing runs while the company's runs are stopped; answers and denials are taken.
            throw ApiException.conflict(ProblemCode.COMPANY_STOPPED, "The company's runs are stopped by an admin: "
                    + "approvals wait until the stop is lifted");
        }
        AgentInfo agent = agents.find(companyId, conversation.agentId()).orElseThrow(DecisionService::notFound);
        if (agent.paused()) {
            // I18: a paused agent's conversations run no approved call; answering a question is not refused.
            throw ApiException.conflict(ProblemCode.AGENT_PAUSED, agent.name() + " is paused: approvals wait until "
                    + "it is resumed");
        }
        DecisionStore.Scope scope = requestOf ? (request.getScope() != null
                ? DecisionStore.Scope.valueOf(request.getScope().getValue()) : DecisionStore.Scope.CONVERSATION)
                : null;
        if (scope == DecisionStore.Scope.AGENT && !agents.access(companyId, agent.id(), userId).edits()) {
            throw ApiException.forbidden(ProblemCode.AGENT_NOT_EDITABLE, "Approving it for the agent for good needs "
                    + "the right to edit " + agent.name() + "; approve it for this conversation instead");
        }
        RepositoryAccess access = subject.type() == DecisionSubject.Type.REPOSITORY
                ? repositoryAccess(conversation, subject, request) : null;
        decisions.decide(decision, DecisionStore.Outcome.APPROVED, null, null, note, scope, access, person, via);
        switch (subject.type()) {
            case RESULT -> throw new IllegalStateException("A result's review is decided by review()");
            case TOOL_CALL -> {
                approved.runAfterCommit(companyId, decision.id());
                return true;
            }
            case REPOSITORY -> {
                UUID repositoryId = subject.requireRepositoryId();
                if (scope == DecisionStore.Scope.AGENT) {
                    agents.addRepository(companyId, agent.id(), repositoryId, Objects.requireNonNull(access),
                            decision.id());
                }
                addToWorkspace(conversation, repositoryId, Objects.requireNonNull(access));
            }
            case HOST -> {
                String host = Objects.requireNonNull(subject.host());
                if (scope == DecisionStore.Scope.AGENT) {
                    agents.addHost(companyId, agent.id(), host, decision.reason(), decision.id());
                }
                else if (core.workspace().grantHost(companyId, conversation.id(), host)
                        == ConversationWorkspace.HostGrant.FULL) {
                    // A host a granted one covers already is approved as it is (COVERED): nothing to add.
                    throw ApiException.conflict(ProblemCode.NETWORK_HOST_LIMIT, "This conversation has "
                            + ConversationInfo.MAX_GRANTED_HOSTS + " network hosts already: approve this one for the "
                            + "agent, or deny it. The decision stays open.");
                }
            }
            case CHANGE_REQUEST -> approved.openAfterCommit(companyId, conversation.id(), decision.id(),
                    subject.requireRepositoryId());
        }
        return false;
    }

    /**
     * The review of a trigger's turn's result (the package 4 contract, §5.8, T14): approved, part c writes it (it
     * listens to {@code ResultReviewed}); returned with a note, which is required, the note is the team's message and a
     * {@code REVIEW_RETURNED} turn does the work again. No {@code DECIDED} turn follows either.
     *
     * @return true: the turn that follows, if any, is started here
     */
    private boolean review(ConversationInfo conversation, DecisionStore.DecisionRow decision, DecisionSubject subject,
            boolean approved, @Nullable String note, UUID person, @Nullable String via) {
        if (!approved && note == null) {
            throw ApiException.validation("A returned result needs a note: what the agent should do again");
        }
        decisions.decide(decision, approved ? DecisionStore.Outcome.APPROVED : DecisionStore.Outcome.DENIED, null,
                null, note, null, null, person, via);
        events.publishEvent(new ConversationEvents.ResultReviewed(conversation.companyId(), conversation.id(),
                decision.id(), Objects.requireNonNull(subject.runId(), "runId"), approved));
        if (!approved) {
            core.messages().post(conversation.companyId(), conversation.id(), ConversationMessages.Kind.PERSON, person,
                    Objects.requireNonNull(note), null, decision.id());
            core.turns().request(conversation.companyId(), conversation.id(), TurnReason.REVIEW_RETURNED);
        }
        return true;
    }

    /**
     * An approved repository's access (open point 10): the agent's when it lists the repository (the approver may
     * lower it to {@code READ}), else {@code READ} unless the approver picks {@code WRITE}.
     */
    private RepositoryAccess repositoryAccess(ConversationInfo conversation, DecisionSubject subject,
            DecideRequest request) {
        RepositoryAccess picked = request.getAccess() != null ? RepositoryAccess.valueOf(request.getAccess()
            .getValue()) : null;
        EffectiveSettings effective = settings.effective(conversation.companyId(), conversation.agentId(),
                conversation.settingsOwner());
        Optional<RepositoryAccess> listed = effective.repositories()
            .stream()
            .filter(entry -> entry.repositoryId().equals(subject.repositoryId()))
            .map(AgentDefinition.RepositoryEntry::access)
            .findFirst();
        if (listed.isPresent()) {
            return picked == RepositoryAccess.READ ? RepositoryAccess.READ : listed.get();
        }
        return picked != null ? picked : RepositoryAccess.READ;
    }

    private void addToWorkspace(ConversationInfo conversation, UUID repositoryId, RepositoryAccess access) {
        boolean there = conversation.workspace().stream().anyMatch(entry -> entry.repositoryId().equals(repositoryId));
        if (!there && conversation.workspace().size() >= ConversationInfo.MAX_WORKSPACE) {
            throw ApiException.conflict(ProblemCode.TOO_MANY_REPOSITORIES, "This conversation's workspace holds "
                    + ConversationInfo.MAX_WORKSPACE + " repositories already. The decision stays open.");
        }
        core.workspace().addRequested(conversation.companyId(), conversation.id(), repositoryId, access);
    }

    /** The picked option: one of the question's, matched exactly, then ignoring case; in the question's spelling. */
    static @Nullable String pickedOption(DecisionStore.DecisionRow decision, @Nullable String option) {
        if (option == null || option.isBlank()) {
            return null;
        }
        String wanted = option.strip();
        return decision.options()
            .stream()
            .filter(wanted::equals)
            .findFirst()
            .or(() -> decision.options().stream().filter(wanted::equalsIgnoreCase).findFirst())
            .orElseThrow(() -> ApiException.validation("option is not one of the question's options"));
    }

    /** The answer: {@code **option**}, then a blank line and the text, either alone. */
    static String answerText(@Nullable String option, @Nullable String answer) {
        String text = answer != null && !answer.isBlank() ? answer.strip() : null;
        if (option == null && text == null) {
            throw ApiException.validation("Pick an option or write an answer");
        }
        if (option == null) {
            return text;
        }
        return text == null ? "**" + option + "**" : "**" + option + "**\n\n" + text;
    }

    /** A decision of a conversation the caller sees, with that conversation. */
    private record Visible(DecisionStore.DecisionRow decision, ConversationInfo conversation) {
    }

    private Visible visible(CompanyAccess access, UUID decisionId, ConversationView view) {
        UUID userId = CurrentActor.requireUser().id();
        DecisionStore.DecisionRow decision = store.find(decisionId).orElseThrow(DecisionService::notFound);
        ConversationInfo conversation = core.conversations().find(access.companyId(), decision.conversationId())
            .filter(found -> core.conversations().maySee(access, userId, found, view))
            .orElseThrow(DecisionService::notFound);
        return new Visible(decision, conversation);
    }

    static ApiException notFound() {
        return ApiException.notFound("Decision not found");
    }
}
