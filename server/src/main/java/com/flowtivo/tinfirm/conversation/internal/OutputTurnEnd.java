package com.flowtivo.tinfirm.conversation.internal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnEnded;
import com.flowtivo.tinfirm.system.ChangeRequests;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.Systems;

/**
 * What part 3 does at the end of a turn ({@link TurnEndHook}, in the transaction that ended its run, after part 1
 * recorded it):
 * <ul>
 * <li>the files the agent left in {@code /output}, which its host uploaded and listed, become {@code DOCUMENT} outputs
 * ({@link OutputService#turnDocuments});</li>
 * <li>a repository the turn pushed new commits to gets a change request (the package 3 contract, §5.4 and open point
 * 5) when the conversation has none there yet, or its latest there was merged at another commit than the one pushed:
 * {@link ChangeRequestPolicy} decides by the mode of {@code open_change_request}. A latest one merged before Tinfirm
 * read the commit it was merged at (V038) is read once more, and the watch decides then. Only after a
 * {@code SUCCEEDED} or {@code CANCELLED} turn of an open conversation, and never in a read-only repository (I9) or one of
 * a code host without change requests ({@code GIT});</li>
 * <li>a push to a change request the conversation has open makes its state due to be read again (ADR-0022), while the
 * conversation is open and the change request watched: a push reported after the conversation ended watches nothing
 * again. One that is closed gets nothing, and a {@code SYSTEM} message says so for a push with new commits.</li>
 * </ul>
 */
@Component
class OutputTurnEnd implements TurnEndHook {

    private final OutputService outputs;

    private final ChangeRequestStore changeRequests;

    private final ChangeRequestPolicy policy;

    private final Systems systems;

    private final RunReads runs;

    private final ApplicationEventPublisher events;

    OutputTurnEnd(OutputService outputs, ChangeRequestStore changeRequests, ChangeRequestPolicy policy,
            Systems systems, RunReads runs, ApplicationEventPublisher events) {
        this.events = events;
        this.outputs = outputs;
        this.changeRequests = changeRequests;
        this.policy = policy;
        this.systems = systems;
        this.runs = runs;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void turnEnded(ConversationInfo conversation, TurnEnded.TurnEnd end) {
        outputs.turnDocuments(conversation, end);
        pushes(end);
        changeRequests(conversation, end);
    }

    /**
     * Each branch the turn pushed is Tinfirm's own write (the package 4 contract, §2.2 and open point 13): a
     * {@code SystemWritten} of kind {@code PUSH} with its head commit, so that a poll raises no
     * {@code CHANGE_REQUEST_UPDATED} for it.
     */
    private void pushes(TurnEnded.TurnEnd end) {
        for (TurnEnded.RepositoryResult repository : end.repositories()) {
            if (Boolean.TRUE.equals(repository.pushed()) && repository.systemId() != null
                    && repository.headSha() != null && !repository.headSha().isBlank()) {
                String key = repository.name() + "@" + repository.branch();
                events.publishEvent(new ConversationEvents.SystemWritten(end.companyId(), end.conversationId(),
                        repository.systemId(), "PUSH " + key, repository.headSha(), "PUSH", key,
                        ConversationEvents.WrittenValues.NONE));
            }
        }
    }

    private void changeRequests(ConversationInfo conversation, TurnEnded.TurnEnd end) {
        for (TurnEnded.RepositoryResult repository : end.repositories()) {
            UUID repositoryId = repository.repositoryId();
            if (!Boolean.TRUE.equals(repository.pushed()) || repositoryId == null) {
                continue;
            }
            ChangeRequestStore.ChangeRequestRow latest = changeRequests.latest(conversation.id(), repositoryId)
                .orElse(null);
            if (latest != null && !latest.merged()) {
                if (conversation.open()) {
                    changeRequests.markDue(conversation.id(), repositoryId);
                }
                if (latest.state() == ChangeRequests.State.CLOSED && due(conversation, end, repository)) {
                    push(end, repository).ifPresent(push -> policy.closed(conversation, push, latest.number()));
                }
                continue;
            }
            if (!due(conversation, end, repository)) {
                continue;
            }
            if (latest != null) {
                // Merged: the push needs the next one only with a commit the merged one did not have.
                if (latest.headSha() == null) {
                    changeRequests.rearm(latest.outputId());
                    continue;
                }
                if (repository.headSha() == null
                        || ChangeRequestPolicy.sameCommit(latest.headSha(), repository.headSha())) {
                    continue;
                }
            }
            push(end, repository).ifPresent(push -> policy.due(conversation, end.agentId(), push));
        }
    }

    /**
     * The push of a repository of a GitHub or GitLab system, fresh when the conversation's previous push there had
     * another head commit; empty for another code host.
     */
    private Optional<ChangeRequestPolicy.Push> push(TurnEnded.TurnEnd end,
            TurnEnded.RepositoryResult repository) {
        SystemInfo system = systems.find(end.companyId(), repository.systemId()).orElse(null);
        if (system == null || system.kind() != SystemKind.GITHUB && system.kind() != SystemKind.GITLAB) {
            return Optional.empty();
        }
        UUID repositoryId = Objects.requireNonNull(repository.repositoryId());
        boolean fresh = repository.headSha() == null || runs.lastPush(end.companyId(), end.conversationId(),
                repositoryId, end.runId())
            .map(previous -> !ChangeRequestPolicy.sameCommit(previous.headSha(), repository.headSha()))
            .orElse(true);
        return Optional.of(new ChangeRequestPolicy.Push(end.runId(), repositoryId, repository.name(),
                repository.branch(), system, fresh));
    }

    /**
     * A change request is due after a turn that succeeded or was cancelled, in an open conversation, for new commits
     * pushed to a repository that is not read-only and whose system and binding the run names.
     */
    private static boolean due(ConversationInfo conversation, TurnEnded.TurnEnd end,
            TurnEnded.RepositoryResult repository) {
        return conversation.open() && (end.status() == RunStatus.SUCCEEDED || end.status() == RunStatus.CANCELLED)
                && !repository.readOnly() && repository.commits() != null && repository.commits() > 0
                && repository.systemId() != null && repository.bindingId() != null;
    }
}
