package com.flowtivo.tinfirm.conversation.internal;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.PlaceDecisions;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.api.model.DecideRequest;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.Systems;

/**
 * {@link PlaceDecisions} (the package 5 contract, §5.8, C10): a card's content, and a press or an answer from a chat
 * decided exactly as the app decides ({@link DecisionService#decideAs}), recorded as decided from the place
 * ({@link PlaceDeciding}). In a personal conversation only its person decides, question or approval; the decision
 * stays open for anyone else. An approval from a place has scope {@code CONVERSATION} and the agent's access: a scope
 * {@code AGENT}, a repository's {@code WRITE} and a denial's note are given in the app.
 */
@Component
class PlaceDecisionsImpl implements PlaceDecisions {

    private final DecisionStore store;

    private final DecisionService decisions;

    private final ConversationStore conversations;

    private final PlaceStore places;

    private final Agents agents;

    private final Principals principals;

    private final Systems systems;

    private final DecisionOps ops;

    private final DecisionPresenter presenter;

    private final ConversationTurns turns;

    private final com.flowtivo.tinfirm.company.GroupMembership groups;

    PlaceDecisionsImpl(DecisionStore store, DecisionService decisions, ConversationStore conversations,
            PlaceStore places, Agents agents, Principals principals, Systems systems, DecisionOps ops,
            DecisionPresenter presenter, ConversationTurns turns, com.flowtivo.tinfirm.company.GroupMembership groups) {
        this.ops = ops;
        this.presenter = presenter;
        this.turns = turns;
        this.groups = groups;
        this.store = store;
        this.decisions = decisions;
        this.conversations = conversations;
        this.places = places;
        this.agents = agents;
        this.principals = principals;
        this.systems = systems;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Card> find(UUID companyId, UUID decisionId) {
        return store.find(decisionId)
            .filter(row -> row.companyId().equals(companyId))
            .map(row -> {
                PlaceStore.Via via = places.via(row.id());
                return new Card(row.id(), row.conversationId(), row.kind().name(), row.status().name(),
                        row.question(), row.options(), what(row), row.reason(),
                        row.outcome() != null ? row.outcome().name() : null, row.answer(), via.name(), via.via(),
                        row.subject() != null ? row.subject().type().name() : null, row.decidedByPrincipalId(),
                        row.approverGroupId());
            });
    }

    /** An approval's subject in words: the call, the repository, the host. */
    private String what(DecisionStore.DecisionRow row) {
        DecisionSubject subject = row.subject();
        if (subject == null) {
            return null;
        }
        return switch (subject.type()) {
            case TOOL_CALL -> subject.what() != null ? subject.what()
                    : subject.tool() != null ? "call " + subject.tool() : "a tool call";
            case REPOSITORY -> "access to the repository " + repositoryName(row.companyId(), subject.repositoryId());
            case HOST -> "network access to " + subject.host();
            case CHANGE_REQUEST -> "open a change request in " + repositoryName(row.companyId(),
                    subject.repositoryId());
            default -> subject.type().name();
        };
    }

    private String repositoryName(UUID companyId, UUID repositoryId) {
        return repositoryId == null ? "a repository" : systems.repository(companyId, repositoryId)
            .map(RepositoryInfo::name)
            .orElse("a removed repository");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome decide(UUID companyId, UUID decisionId, PlaceDecider decider, Choice choice) {
        DecisionStore.DecisionRow decision = store.find(decisionId)
            .filter(row -> row.companyId().equals(companyId))
            .orElse(null);
        if (decision == null) {
            return new Refused("NOT_FOUND", "This decision is not known");
        }
        ConversationInfo conversation = conversations.lock(companyId, decision.conversationId()).orElseThrow().info();
        decision = store.lock(decisionId).orElseThrow();
        if (!decision.open()) {
            PlaceStore.Via via = places.via(decisionId);
            return new AlreadyDecided(via.name(), via.via(), decision.status().name());
        }
        if (conversation.personPrincipalId() == null) {
            return decideForTrigger(companyId, conversation, decision, decider, choice);
        }
        if (decider.principalId() == null || !decider.principalId().equals(decision.approverPrincipalId())) {
            return new NotAllowed("Only " + personName(conversation) + ", whose conversation this is, decides this");
        }
        if (!conversation.open()) {
            return new Refused("CONVERSATION_ENDED", "This conversation ended");
        }
        PrincipalInfo person = principals.find(companyId, decider.principalId()).orElse(null);
        if (person == null || person.userId() == null || !person.active()) {
            return new NotAllowed("Only an active member decides this");
        }
        if (presenter.decider(companyId, person.userId()).principalId() == null) {
            // A Viewer decides nothing (ADR-0077, decision 5); the decider is NONE for them.
            return new NotAllowed("A Viewer decides nothing: the company's Owners and Admins decide this");
        }
        DecideRequest request = new DecideRequest();
        switch (choice) {
            case Answer answer -> {
                if (decision.kind() != DecisionStore.Kind.QUESTION) {
                    return new Refused("VALIDATION_FAILED", "An approval is approved or denied");
                }
                if (answer.option() != null) {
                    boolean known = decision.options().stream().anyMatch(answer.option()::equalsIgnoreCase);
                    if (!known) {
                        return new Refused("VALIDATION_FAILED", "That is not one of the question's options");
                    }
                    request.option(answer.option());
                }
                if (answer.text() != null && !answer.text().isBlank()) {
                    request.answer(answer.text());
                }
                if (request.getOption() == null && request.getAnswer() == null) {
                    return new Refused("VALIDATION_FAILED", "Pick an option or write an answer");
                }
            }
            case Approve approve -> {
                if (decision.kind() != DecisionStore.Kind.APPROVAL) {
                    return new Refused("VALIDATION_FAILED", "A question is answered");
                }
                AgentInfo agent = agents.find(companyId, conversation.agentId()).orElse(null);
                if (agent == null || agent.paused()) {
                    return new Refused("AGENT_PAUSED", (agent != null ? agent.name() : "The agent")
                            + " is paused: approvals wait until it is resumed");
                }
                request.outcome(DecideRequest.OutcomeEnum.APPROVED);
            }
            case Deny deny -> {
                if (decision.kind() != DecisionStore.Kind.APPROVAL) {
                    return new Refused("VALIDATION_FAILED", "A question is answered");
                }
                request.outcome(DecideRequest.OutcomeEnum.DENIED);
            }
        }
        PlaceDeciding.Context context = new PlaceDeciding.Context("CHAT", decider.via(), decider.externalId(),
                decider.name());
        UUID personUserId = person.userId();
        ConversationInfo locked = conversation;
        PlaceDeciding.with(context, () -> {
            decisions.decideAs(companyId, personUserId, locked, decisionId, request, decider.principalId(), null);
            return null;
        });
        DecisionStore.DecisionRow decided = store.find(decisionId).orElseThrow();
        return new Decided(decisionId, decided.outcome() != null ? decided.outcome().name() : "DECIDED");
    }

    /**
     * A channel trigger's conversation (the package 5 contract, §5.8): a question by any member of the workspace the
     * caller vouches for, linked or not, its answer an {@code EXTERNAL} message of the chat; an approval only by a
     * linked, active member who may decide it now (the trigger's team, or its author without one), else
     * {@code NotAllowed} and it stays open (C10); a result's review only in the app.
     */
    private Outcome decideForTrigger(UUID companyId, ConversationInfo conversation, DecisionStore.DecisionRow decision,
            PlaceDecider decider, Choice choice) {
        if (conversation.place() != ConversationInfo.Place.CHAT_THREAD) {
            return new NotAllowed("This is decided in Tinfirm");
        }
        if (!conversation.open()) {
            return new Refused("CONVERSATION_ENDED", "This conversation ended");
        }
        PlaceDeciding.Context context = new PlaceDeciding.Context("CHAT", decider.via(), decider.externalId(),
                decider.name());
        if (decision.kind() == DecisionStore.Kind.QUESTION) {
            if (!(choice instanceof Answer answer)) {
                return new Refused("VALIDATION_FAILED", "A question is answered");
            }
            String option = null;
            if (answer.option() != null) {
                option = decision.options().stream().filter(answer.option()::equalsIgnoreCase).findFirst()
                    .orElse(null);
                if (option == null) {
                    return new Refused("VALIDATION_FAILED", "That is not one of the question's options");
                }
            }
            String text = option != null ? option : answer.text() != null ? answer.text().strip() : "";
            if (text.isBlank()) {
                return new Refused("VALIDATION_FAILED", "Pick an option or write an answer");
            }
            Map<String, Object> author = new java.util.LinkedHashMap<>();
            author.put("id", decider.externalId());
            author.put("name", decider.name());
            author.put("via", decider.via());
            author.put("verified", true);
            UUID messageId = places.insertMessage(companyId, conversation.id(), "EXTERNAL", decider.principalId(),
                    DecisionOps.quoted(decision.question(), text), false, "answer:" + decision.id(), author)
                .orElse(null);
            if (messageId == null) {
                PlaceStore.Via via = places.via(decision.id());
                return new AlreadyDecided(via.name(), via.via(), decision.status().name());
            }
            String chosen = option;
            boolean answered = PlaceDeciding.with(context, () -> ops.answerOne(conversation, decision.id(), messageId,
                    text, chosen, decider.principalId(), decider.name()));
            if (!answered) {
                PlaceStore.Via via = places.via(decision.id());
                return new AlreadyDecided(via.name(), via.via(), decision.status().name());
            }
            if (store.lockOpen(conversation.id()).isEmpty()) {
                turns.request(companyId, conversation.id(), TurnReason.DECIDED);
            }
            turns.refreshWaiting(companyId, conversation.id());
            return new Decided(decision.id(), "ANSWERED");
        }
        if (decision.subject() != null && decision.subject().type() == DecisionSubject.Type.RESULT) {
            return new NotAllowed("A result's review is decided in Tinfirm, where the result is shown");
        }
        PrincipalInfo member = decider.principalId() != null
                ? principals.find(companyId, decider.principalId()).orElse(null) : null;
        if (member == null || member.userId() == null || !member.active()) {
            return new NotAllowed(approvers(companyId, decision, conversation) + " decide this");
        }
        DecisionPresenter.Decider deciding = presenter.decider(companyId, member.userId());
        if (!deciding.approves(decision, conversation)) {
            return new NotAllowed(approvers(companyId, decision, conversation) + " decide this");
        }
        DecideRequest request = new DecideRequest();
        switch (choice) {
            case Approve approve -> {
                AgentInfo agent = agents.find(companyId, conversation.agentId()).orElse(null);
                if (agent == null || agent.paused()) {
                    return new Refused("AGENT_PAUSED", (agent != null ? agent.name() : "The agent")
                            + " is paused: approvals wait until it is resumed");
                }
                request.outcome(DecideRequest.OutcomeEnum.APPROVED);
            }
            case Deny deny -> request.outcome(DecideRequest.OutcomeEnum.DENIED);
            case Answer answer -> {
                return new Refused("VALIDATION_FAILED", "An approval is approved or denied");
            }
        }
        UUID userId = member.userId();
        PlaceDeciding.with(context, () -> {
            decisions.decideAs(companyId, userId, conversation, decision.id(), request, member.id(), null);
            return null;
        });
        DecisionStore.DecisionRow decided = store.find(decision.id()).orElseThrow();
        return new Decided(decision.id(), decided.outcome() != null ? decided.outcome().name() : "DECIDED");
    }

    /** Who decides a trigger's approval, in words: "Only members of Support", "Only Alice Author". */
    private String approvers(UUID companyId, DecisionStore.DecisionRow decision, ConversationInfo conversation) {
        if (decision.approverGroupId() != null) {
            String team = groups.groupNames(companyId, java.util.List.of(decision.approverGroupId()))
                .getOrDefault(decision.approverGroupId(), "the trigger's team");
            return "Only members of " + team;
        }
        UUID approver = decision.approverPrincipalId() != null ? decision.approverPrincipalId()
                : conversation.authorPrincipalId();
        if (approver == null) {
            return "Only the trigger's team";
        }
        return "Only " + principals.displayNames(companyId, java.util.List.of(approver))
            .getOrDefault(approver, "the trigger's author");
    }

    private String personName(ConversationInfo conversation) {
        return principals.displayNames(conversation.companyId(), java.util.List.of(conversation.personPrincipalId()))
            .getOrDefault(conversation.personPrincipalId(), "its person");
    }
}
