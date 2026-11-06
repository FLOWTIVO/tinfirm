package com.flowtivo.tinfirm.trigger;

import java.util.Objects;
import java.util.UUID;

/**
 * The entitlement of a trigger at every use (the package 4 contract, §5.3 with §11, point 9; T7, T16):
 * {@code TriggerGate.check(trigger)} before every firing (in the firing's transaction), at a resume and a reassignment,
 * and after the commit of package 1's and 3a's events for the triggers they concern (each trigger in a transaction of
 * its own). In the caller's transaction and tenant context; it locks the trigger's row ({@code FOR NO KEY UPDATE}),
 * first in the lock order.
 * <p>
 * In order: the author an active member ({@code AUTHOR_LEFT}); the author uses the agent ({@code NOT_ENTITLED}); each
 * row of the table through {@code Accounts.check} with the author acting and no grant ({@code USE_RIGHT_GONE} and an
 * inactive author {@code NOT_ENTITLED}; the account gone or another identity {@code ACCOUNT_REMOVED}; another row's
 * system gone {@code SYSTEM_REMOVED}, the row removed; a reconnect or a suspended holder {@code ACCOUNT_BROKEN}); the
 * payer the author or a service identity the author may use ({@code NOT_ENTITLED}); {@code ModelAccess.forRun}
 * ({@code Refuse(PRINCIPAL_INACTIVE)} waits {@code SUSPENDED}, {@code Refuse(GATEWAY_NOT_CONFIGURED)} waits
 * {@code GATEWAY_NOT_CONFIGURED}, {@code Refuse(SUBSCRIPTION_GONE)} pauses {@code NOT_ENTITLED}, a {@code Wait} fires and
 * the turn waits). A failure pauses or makes the trigger wait, audited ({@code trigger.paused}, {@code trigger.waiting}
 * once per wait) and announced, with its inbox item; a pass clears a wait. Nothing re-binds and nothing switches (T7).
 * A paused trigger stays paused until a person resumes it; a deleted one is {@link Result.Paused} as it is.
 */
public interface TriggerGate {

    Result check(UUID companyId, UUID triggerId);

    /** What the gate answers. */
    sealed interface Result {

        /** Whether the trigger may fire now. */
        default boolean fires() {
            return this instanceof Passes;
        }

        /** It may fire. */
        record Passes() implements Result {
        }

        /** It is paused (now or before), for this reason. */
        record Paused(PausedReason reason) implements Result {

            public Paused {
                Objects.requireNonNull(reason, "reason");
            }
        }

        /** It waits for this reason and fires nothing until the event that repairs it. */
        record Waits(WaitingReason reason) implements Result {

            public Waits {
                Objects.requireNonNull(reason, "reason");
            }
        }

        /** The trigger is deleted, or unknown in this company. */
        record Gone() implements Result {
        }
    }
}
