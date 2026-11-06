package com.flowtivo.tinfirm.conversation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.agent.SettingsOwner;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.runner.RuntimeKind;

/**
 * A conversation as other modules and the conversation module's parts see it (the package 3 contract, §3.8): one agent,
 * one place, one person it acts for and who pays, its accounts and model access fixed for its life. A trigger's
 * conversation (the package 4 contract, §3.4) has no person: it acts for its author and is paid by its payer, which
 * only {@code conversation_rebind} changes after a reassignment (§11).
 *
 * @param key               {@code {base}-{n}}, unique in the company and never reused (I12): the branch
 *                          {@code agent/{key}}
 * @param personPrincipalId the member it acts for, is paid by and is seen by; null for a trigger's conversation
 * @param subject           what it is about; null for no subject
 * @param runtime           fixed at its start (open point 3)
 * @param payerPrincipalId  who pays (I10): the person
 * @param modelAccess       the person's budget or a subscription on their personal host, fixed at the start; null for
 *                          a subscription whose host was removed (nothing pays any more)
 * @param subscriptionLabel the subscription's label, kept after its host was removed; null for the budget
 * @param workspace         the repositories of its turns, in order (§5.6): the start's, plus approved requests
 * @param grantedHosts      the network hosts approved requests gave it, normalized
 * @param turnStatus        the latest turn's status; null before the first
 * @param waitingReason     what it waits on in detail: {@code QUESTION}, {@code APPROVAL}, {@code ACCOUNT},
 *                          {@code FAILED} on the person, {@code RUNNING} on the agent, the run's queue reason on the
 *                          runner; null for {@link WaitingOn#NONE}
 * @param triggerId         a trigger's conversation's trigger; null for a personal one
 * @param authorPrincipalId a trigger's conversation's author: its trigger's when it started (or when it was rebound);
 *                          null for a personal one
 * @param chainDepth        0, or the depth of a conversation fired by a subject Tinfirm created (§5.6), at most 3
 */
public record ConversationInfo(UUID id, UUID companyId, UUID agentId, String key, String title, Place place,
        Origin origin, @Nullable UUID personPrincipalId, @Nullable Subject subject, RuntimeKind runtime,
        UUID payerPrincipalId, @Nullable ModelAccessChoice modelAccess, @Nullable String subscriptionLabel,
        List<WorkspaceEntry> workspace, List<String> grantedHosts, State state, @Nullable EndedReason endedReason,
        @Nullable Instant endedAt, @Nullable TurnStatus turnStatus, WaitingOn waitingOn,
        @Nullable String waitingReason, @Nullable Instant waitingSince, int turnCount, @Nullable UUID activeRunId,
        @Nullable UUID lastRunId, @Nullable Instant lastTurnAt, Instant createdAt, Instant updatedAt,
        @Nullable UUID triggerId, @Nullable UUID authorPrincipalId, int chainDepth) {

    /** The longest key: the branch {@code agent/{key}} fits a branch name of 100 characters. */
    public static final int MAX_KEY_LENGTH = 93;

    /** The longest base of a key, before {@code -{n}}. */
    public static final int MAX_KEY_BASE_LENGTH = 80;

    /** The most repositories a workspace holds (the protocol's {@code run.assign}). */
    public static final int MAX_WORKSPACE = 20;

    /** The most hosts approved requests give one conversation. */
    public static final int MAX_GRANTED_HOSTS = 100;

    public ConversationInfo {
        workspace = List.copyOf(workspace);
        grantedHosts = List.copyOf(grantedHosts);
        if ((personPrincipalId == null) == (triggerId == null) || (triggerId == null) != (authorPrincipalId == null)) {
            throw new IllegalArgumentException("A conversation belongs to a person or to a trigger with its author (T4)");
        }
    }

    /** A personal conversation (package 3), with no trigger. */
    public ConversationInfo(UUID id, UUID companyId, UUID agentId, String key, String title, Place place,
            Origin origin, UUID personPrincipalId, @Nullable Subject subject, RuntimeKind runtime,
            UUID payerPrincipalId, @Nullable ModelAccessChoice modelAccess, @Nullable String subscriptionLabel,
            List<WorkspaceEntry> workspace, List<String> grantedHosts, State state, @Nullable EndedReason endedReason,
            @Nullable Instant endedAt, @Nullable TurnStatus turnStatus, WaitingOn waitingOn,
            @Nullable String waitingReason, @Nullable Instant waitingSince, int turnCount, @Nullable UUID activeRunId,
            @Nullable UUID lastRunId, @Nullable Instant lastTurnAt, Instant createdAt, Instant updatedAt) {
        this(id, companyId, agentId, key, title, place, origin, personPrincipalId, subject, runtime, payerPrincipalId,
                modelAccess, subscriptionLabel, workspace, grantedHosts, state, endedReason, endedAt, turnStatus,
                waitingOn, waitingReason, waitingSince, turnCount, activeRunId, lastRunId, lastTurnAt, createdAt,
                updatedAt, null, null, 0);
    }

    public boolean open() {
        return state == State.OPEN;
    }

    /** Whether a trigger started it (the package 4 contract, §3.4). */
    public boolean triggered() {
        return triggerId != null;
    }

    /**
     * Whom its turns act for, whose entitlements its bindings and tools are checked against: its person, or a
     * trigger's conversation's author.
     */
    public UUID actingPrincipalId() {
        return Objects.requireNonNull(personPrincipalId != null ? personPrincipalId : authorPrincipalId);
    }

    /** Whose settings of the agent its turns use: its person's, or its trigger's. */
    public SettingsOwner settingsOwner() {
        return triggerId != null ? new SettingsOwner.Trigger(triggerId)
                : new SettingsOwner.Person(Objects.requireNonNull(personPrincipalId));
    }

    /**
     * Where a conversation is held: a Tinfirm thread (package 3); a tracker's issue or a schedule's firing (a
     * trigger's, package 4); a chat thread (a direct message, a channel trigger's thread) or a webhook's subject
     * (package 5).
     */
    public enum Place {
        TINFIRM_THREAD, CHAT_THREAD, TRACKER_ISSUE, SCHEDULE,
        /** A webhook trigger's conversation (the package 5 contract, §5.12, open point 17): its results stay in Tinfirm. */
        WEBHOOK
    }

    /** Where it was started: in the app (package 3), by a trigger (package 4); the others are reserved. */
    public enum Origin {
        APP, CLIENT, DIRECT_MESSAGE, TRIGGER
    }

    /** {@code OPEN}, or {@code ENDED}, which is final. */
    public enum State {
        OPEN, ENDED
    }

    /**
     * Why it ended: a person ended it (its person or OWNER; a trigger's author, team or an admin), its agent was
     * deleted, its person left. Package 4 adds a trigger's: its author left ({@code AUTHOR_LEFT}, kept for the record:
     * since the review of the package 4 contract, §11, such a conversation waits for the reassignment instead), its
     * trigger was deleted, its subject closed, the next firing of its schedule started ({@code SUPERSEDED}). Package 5 adds a
     * Teams direct message's next conversation ({@code SUPERSEDED} too) and its chat system removed
     * ({@code SYSTEM_REMOVED}).
     */
    public enum EndedReason {
        USER, AGENT_DELETED, PERSON_LEFT, AUTHOR_LEFT, TRIGGER_DELETED, SUBJECT_CLOSED, SUPERSEDED, SYSTEM_REMOVED
    }

    /** The latest turn's status. */
    public enum TurnStatus {
        QUEUED, RUNNING, WAITING, SUCCEEDED, FAILED, CANCELLED
    }

    /** Whom the conversation waits on (today's resolver, ADR-0028). */
    public enum WaitingOn {
        PERSON, AGENT, RUNNER, NONE
    }

    /**
     * The subject, a reference and not a copy (open point 4): a company system with issues or change requests, the key
     * people know, its link and the title given at the start.
     *
     * @param systemId null once the system was removed
     */
    public record Subject(@Nullable UUID systemId, String key, @Nullable String url, @Nullable String title) {

        public Subject {
            Objects.requireNonNull(key, "key");
        }
    }

    /**
     * A repository of the workspace (§5.6).
     *
     * @param readOnly whether it was given read-only: the agent's access {@code READ} at the start, or an approval
     *                 that gave {@code READ}. A binding's read-only grant makes it read-only at each turn as well
     * @param source   {@code AGENT}: the agent's or the settings' at the start, which leaves when the agent no longer
     *                 lists it; {@code REQUESTED}: an approved request
     */
    public record WorkspaceEntry(UUID repositoryId, boolean readOnly, Source source) {

        public enum Source {
            AGENT, REQUESTED
        }
    }
}
