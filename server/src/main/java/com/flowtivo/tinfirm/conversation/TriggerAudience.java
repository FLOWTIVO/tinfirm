package com.flowtivo.tinfirm.conversation;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.access.CompanyAccess;

/**
 * Port: a trigger as its conversations need it (the package 4 contract, §5.1, §5.13, T12, T15), implemented by the
 * {@code trigger} module, which depends on this one. The conversation module looks it up when used
 * ({@code ObjectProvider}); without a bean no conversation has a trigger. Every method runs in the caller's transaction
 * and tenant context.
 * <p>
 * A trigger, its firings and its conversations (with their messages, runs, events, transcripts, decisions, outputs,
 * files and bindings) are seen by its author, the members of its team and OWNER and ADMIN; a conversation also by the
 * author it started with (who may have left). Anything else is {@code 404}, and lists and counts leave it out (T15).
 */
public interface TriggerAudience {

    /** The trigger as its conversations see it; empty for an unknown one. A deleted trigger is answered, marked so. */
    Optional<TriggerView> trigger(UUID companyId, UUID triggerId);

    /**
     * Whether the caller sees the trigger, its firings and its conversations: its author, a member of its team, OWNER
     * and ADMIN (an instance admin acting in the company too).
     */
    boolean sees(CompanyAccess access, UUID userId, UUID triggerId);

    /**
     * The triggers whose conversations the caller sees through their audience (as {@link #sees}), for lists, counts
     * and the inbox. {@link Seen#all()} for OWNER and ADMIN.
     */
    Seen seenBy(CompanyAccess access, UUID userId);

    /**
     * Whether the caller acts in the trigger's conversations as its people do (T12): answers its questions in Tinfirm,
     * sends a {@code SCHEDULE} conversation messages, decides its approvals as their approver names them (its team,
     * or its author without one), cancels, retries and ends it. Its author and the members of its team; OWNER and
     * ADMIN only as one of them.
     */
    boolean acts(UUID companyId, UUID userId, UUID triggerId);

    /**
     * A trigger, as its conversations see it.
     *
     * @param authorPrincipalId whose the trigger is now (a reassignment changes it; a conversation keeps the author it
     *                          started with)
     * @param teamGroupId       its team; null: its author alone
     * @param pausedReason      why it is paused ({@code USER}, {@code AUTHOR_LEFT}, …); null while it is active: a
     *                          paused trigger's conversations take no turn ({@code TRIGGER_PAUSED})
     * @param deleted           whether it was deleted
     */
    record TriggerView(UUID id, UUID companyId, String name, UUID agentId, UUID authorPrincipalId,
            @Nullable UUID teamGroupId, String instruction, @Nullable String pausedReason, boolean deleted) {

        public boolean paused() {
            return pausedReason != null;
        }
    }

    /**
     * The triggers a caller sees through their audience.
     *
     * @param all        every trigger of the company (OWNER, ADMIN)
     * @param triggerIds the triggers the caller authored or whose team they are in; empty with {@code all}
     */
    record Seen(boolean all, Set<UUID> triggerIds) {

        public static final Seen NONE = new Seen(false, Set.of());

        public Seen {
            triggerIds = Set.copyOf(triggerIds);
        }

        public boolean sees(@Nullable UUID triggerId) {
            return triggerId != null && (all || triggerIds.contains(triggerId));
        }
    }
}
