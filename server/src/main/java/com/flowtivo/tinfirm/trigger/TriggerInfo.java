package com.flowtivo.tinfirm.trigger;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A trigger (the package 4 contract, §3.1): a standing rule on a tracker system, or a schedule, whose agent does its
 * instruction with its table's accounts, paid by its payer.
 *
 * @param systemId           the source system of a {@code TRACKER} trigger; null for a schedule
 * @param events             one to five for a tracker trigger; none for a schedule
 * @param scope              a Jira project key, {@code owner/repo} or a GitLab path; null: everything the source account
 *                           sees
 * @param conditions         at most five groups, any of which must hold; none: no condition
 * @param schedule           a schedule's timing; null for a tracker trigger
 * @param authorPrincipalId  a member's principal: the creator, or whom an admin reassigned it to
 * @param teamGroupId        its team; null: its author alone (open point 3)
 * @param payerPrincipalId   the author, or a service identity the author may use (T1); null only once deleted
 * @param pausedReason       why it is paused; null while {@code ACTIVE}
 * @param waitingReason      what it waits on while active; null when nothing waits
 * @param armedAt            a change before it fires nothing (T16): the creation, each resume, each reassignment
 * @param deletedAt          the soft delete; the row stays for its conversations' history
 */
public record TriggerInfo(UUID id, UUID companyId, String name, TriggerSource source, @Nullable UUID systemId,
        List<TriggerEventSpec> events, TriggerSubject subject, @Nullable String scope, List<ConditionGroup> conditions,
        TriggerRepeat repeat, @Nullable TriggerSchedule schedule, UUID agentId, String instruction,
        UUID authorPrincipalId, @Nullable UUID teamGroupId, @Nullable UUID payerPrincipalId, TriggerResults results,
        int maxFiringsPerHour, TriggerStatus status, @Nullable PausedReason pausedReason, @Nullable Instant pausedAt,
        @Nullable WaitingReason waitingReason, @Nullable Instant waitingSince, Instant armedAt, int missedCount,
        int rateSkippedCount, @Nullable Instant openEventsAcknowledgedAt, @Nullable Instant deletedAt,
        @Nullable String deletedCause, Instant createdAt, Instant updatedAt) {

    public TriggerInfo {
        events = List.copyOf(events);
        conditions = List.copyOf(conditions);
    }

    /** Whether the trigger is not deleted. */
    public boolean live() {
        return deletedAt == null;
    }

    /** Whether it is live and active: it may fire, once the gate passes (§5.3). */
    public boolean active() {
        return live() && status == TriggerStatus.ACTIVE;
    }

    public boolean paused() {
        return status == TriggerStatus.PAUSED;
    }
}
