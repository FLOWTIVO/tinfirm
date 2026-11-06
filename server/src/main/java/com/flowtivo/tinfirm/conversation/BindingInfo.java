package com.flowtivo.tinfirm.conversation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The account a conversation uses on one system (the package 3 contract, §3.9, §5.3): resolved at the system's first
 * use, checked at every use, fixed for the conversation's life (I6), and never re-bound (I7).
 *
 * @param how               how it bound; null until first bound
 * @param accountId         the bound account; null until first bound, then set once (replaced only by the holder's
 *                          account on the same system with the same identity, a reconnect); null for
 *                          {@link How#OPEN}, which binds none
 * @param holderPrincipalId whom it acts through: the person for their own account, a trigger's author or service
 *                          account (FIXED); null through a grant, which lends a system's credential (ADR-0072)
 * @param externalId        the account's identity on the system when bound; null where the system has none
 * @param grantId           the grant it acts through; null for the person's own account
 * @param reason            why it waits or why it is gone; null while {@link State#BOUND}
 * @param choices           the grants offered for {@link Reason#PICK_GRANT} and {@link Reason#CHOOSE}
 * @param waitingSince      since when it waits; null unless {@link State#WAITING}
 */
public record BindingInfo(UUID id, UUID companyId, UUID conversationId, UUID systemId, State state, @Nullable How how,
        @Nullable UUID accountId, @Nullable UUID holderPrincipalId, @Nullable String externalId, @Nullable UUID grantId,
        @Nullable Reason reason, List<UUID> choices, @Nullable Instant waitingSince, @Nullable Instant boundAt) {

    public BindingInfo {
        choices = List.copyOf(choices);
    }

    public boolean bound() {
        return state == State.BOUND;
    }

    public boolean waiting() {
        return state == State.WAITING;
    }

    public boolean gone() {
        return state == State.GONE;
    }

    /** {@code BOUND}; {@code WAITING}: a card, the person or an admin acts first; {@code GONE}: final. */
    public enum State {
        BOUND, WAITING, GONE
    }

    /**
     * The person's own account; the one usable grant; a grant the person picked; a trigger's account table, copied
     * when its conversation started (package 4, T5); no account, on an MCP server that needs none (ADR-0071), which a
     * call reaches without a credential.
     */
    public enum How {
        OWN, GRANTED, CHOSEN, FIXED, OPEN
    }

    /** Bound without an account: an MCP server that needs none (ADR-0071). */
    public boolean open() {
        return how == How.OPEN;
    }

    /** Why a binding waits, or why it is gone (contract §3.9). */
    public enum Reason {
        /** Waits: no account of one's own and no usable grant; a Connect card. */
        NONE(true),
        /** Waits: the person signs their own account in again. */
        RECONNECT_YOURS(true),
        /** Waits: an admin signs the granted account in again. */
        RECONNECT_COMPANY(true),
        /** Waits: the person's own account is broken and a grant exists; reconnect, or use the grant. */
        CHOOSE(true),
        /** Waits: several usable grants; the person picks one. */
        PICK_GRANT(true),
        /** Waits: the holder is suspended; admins resume them. */
        SUSPENDED(true),
        /** Waits: the company switched personal MCP servers off. */
        SYSTEM_OFF(true),
        /**
         * Waits: a trigger's author lost the right to use the service identity whose account it is (package 4, §5.3);
         * it binds again if the right comes back.
         */
        NOT_ENTITLED(true),
        /** Gone: the grant was withdrawn. */
        GRANT_REMOVED(false),
        /** Gone: the person left the grant's group. */
        LEFT_GROUP(false),
        /** Gone: the holder's account is another identity on the system now. */
        IDENTITY_CHANGED(false),
        /** Gone: the holder left the company. */
        HOLDER_LEFT(false),
        /** Gone: the system was removed. */
        SYSTEM_REMOVED(false),
        /** Gone: a reassigned trigger's new account table has no account on the system (package 4, §11). */
        ACCOUNT_REMOVED(false);

        private final boolean waits;

        Reason(boolean waits) {
            this.waits = waits;
        }

        /** Whether the binding waits for this reason; else it is gone for it. */
        public boolean waits() {
            return waits;
        }

        /** Whether the person can act on it themselves (the inbox's second list, contract §5.10). */
        public boolean personActs() {
            return this == NONE || this == RECONNECT_YOURS || this == CHOOSE || this == PICK_GRANT;
        }
    }
}
