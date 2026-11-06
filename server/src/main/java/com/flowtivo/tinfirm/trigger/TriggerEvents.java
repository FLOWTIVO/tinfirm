package com.flowtivo.tinfirm.trigger;

import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The trigger module's domain events (the package 4 contract, §5.2, §5.3, §5.11), ids only, published with
 * {@code ApplicationEventPublisher} in the transaction that made the change, after it records its audit event, which
 * the audit log appends when the transaction commits (CONVENTIONS §8): a listener reacts after the commit
 * ({@code @TransactionalEventListener}) or inside the transaction ({@code @EventListener}). Part b's polls and
 * schedules follow them: a poll serves the active triggers whose source row names its account.
 */
public final class TriggerEvents {

    private TriggerEvents() {
    }

    /** A trigger was created, {@code ACTIVE}. */
    public record TriggerCreated(UUID companyId, UUID triggerId, TriggerSource source, @Nullable UUID systemId) {
    }

    /** Its definition or its settings changed (the fields, as {@code trigger.changed} names them). */
    public record TriggerChanged(UUID companyId, UUID triggerId, Set<String> changed) {

        public TriggerChanged {
            changed = Set.copyOf(changed);
        }
    }

    /** Its account table or its payer changed: an author's save, an admin's on their behalf, or a reassignment. */
    public record TriggerAccountsChanged(UUID companyId, UUID triggerId) {
    }

    /** It was paused, for the reason; {@code stopRunning} cancelled its conversations' active turns. */
    public record TriggerPaused(UUID companyId, UUID triggerId, PausedReason reason) {
    }

    /** It waits, for the reason, and fires nothing until the event that repairs it. */
    public record TriggerWaiting(UUID companyId, UUID triggerId, WaitingReason reason) {
    }

    /** It was resumed, or its wait was repaired: {@code armed_at} moved to now on a resume. */
    public record TriggerResumed(UUID companyId, UUID triggerId) {
    }

    /** An admin reassigned it to another author; it stays paused until resumed. */
    public record TriggerReassigned(UUID companyId, UUID triggerId, UUID fromAuthorPrincipalId,
            UUID toAuthorPrincipalId) {
    }

    /** It was deleted (soft), with its cause: {@code USER}, {@code agent.deleted}, {@code system.deleted}. */
    public record TriggerDeleted(UUID companyId, UUID triggerId, String cause) {
    }
}
