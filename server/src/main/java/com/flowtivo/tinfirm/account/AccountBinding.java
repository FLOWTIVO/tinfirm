package com.flowtivo.tinfirm.account;

import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The types of the binding rule (the package 1 contract, §5): the context {@link Accounts#bind} is asked in, what it
 * answers, what the caller stores, and what {@link Accounts#check} answers at every use.
 */
public final class AccountBinding {

    private AccountBinding() {
    }

    // ---- Contexts ----------------------------------------------------------------------------------------------------

    /** Where a system is first needed. */
    public sealed interface Context permits Personal, Trigger {

        UUID companyId();
    }

    /** A personal conversation of the member whose principal this is. */
    public record Personal(UUID companyId, UUID memberPrincipalId) implements Context {
    }

    /**
     * A trigger saved by the author whose principal this is, its row for the system naming the account: the author's
     * own, or the credential a service account borrows (ADR-0072).
     *
     * @param holderPrincipalId whom the row acts through: the author, or the service account
     */
    public record Trigger(UUID companyId, UUID authorPrincipalId, UUID holderPrincipalId, UUID accountId)
            implements Context {
    }

    // ---- Outcomes ----------------------------------------------------------------------------------------------------

    /** What {@link Accounts#bind} answers. It stores nothing: the conversation or the trigger keeps it. */
    public sealed interface Outcome permits Own, Granted, Choose, ConnectRequired, Fixed, NotEntitled, Open {
    }

    /** The member's own account, which is {@code OK}. */
    public record Own(AccountInfo account) implements Outcome {
    }

    /** The one usable grant to one of the member's groups, and the credential it lends. */
    public record Granted(GrantInfo grant, AccountInfo account) implements Outcome {
    }

    /** Several usable grants: the person picks one. */
    public record Choose(List<GrantInfo> grants) implements Outcome {

        public Choose {
            grants = List.copyOf(grants);
        }
    }

    /**
     * Nothing to bind now; the person (or an admin) must act.
     *
     * @param choices for {@link ConnectReason#CHOOSE}: the usable grants beside the member's broken account
     */
    public record ConnectRequired(UUID systemId, ConnectReason reason, List<GrantInfo> choices) implements Outcome {

        public ConnectRequired {
            choices = List.copyOf(choices);
        }
    }

    /**
     * A trigger's row: the account it names, which its author is entitled to and which is {@code OK}.
     *
     * @param holderPrincipalId whom it acts through: the author for their own account, or the service account that
     *                          borrows the credential
     */
    public record Fixed(AccountInfo account, UUID holderPrincipalId) implements Outcome {
    }

    /** The context may not bind the system, or the account, at all. */
    public record NotEntitled(UUID systemId, Refusal reason) implements Outcome {
    }

    /**
     * An MCP server that needs no account (ADR-0071): bound without one, and called without a credential. As an
     * outcome, the binding of a personal conversation, whatever account the member holds there; as a check
     * ({@link Accounts#open}), its use now.
     */
    public record Open(UUID systemId) implements Outcome, Check {
    }

    /** Why a person must connect (contract §5). */
    public enum ConnectReason {
        /** No account of one's own and no usable grant: connect your own. */
        NONE,
        /** One's own account needs signing in again (and no grant exists). */
        RECONNECT_YOURS,
        /** The granted credential (or the one a trigger's service account borrows) needs an admin; admins are told. */
        RECONNECT_COMPANY,
        /** One's own account is broken and a grant exists: reconnect, or use the grant. Never chosen silently (I10). */
        CHOOSE
    }

    /** Why a context may not bind. */
    public enum Refusal {
        /** The system does not exist (in this company). */
        NO_SYSTEM,
        /** Another member's personal server: it binds for its owner only. */
        NOT_OWNER,
        /** The company switched personal MCP servers off: they bind for nobody. */
        PERSONAL_SYSTEMS_OFF,
        /** The member, the author, or the service account the row acts through is suspended or left (I7). */
        PRINCIPAL_INACTIVE,
        /**
         * A trigger's account that is neither its author's own nor a credential lent to a service account they may use,
         * or one that acts with nothing: a chat link ({@code LINK}, the package 5 contract, open point 13).
         */
        ACCOUNT_NOT_USABLE,
        /** A chat system's bot: never bound, granted or in a trigger's table (the package 5 contract, C9). */
        BOT_ACCOUNT
    }

    // ---- What is stored, and checked at every use ----------------------------------------------------------------

    /**
     * A binding as the conversation or trigger keeps it, from an outcome that bound: who acts, whom it acts through,
     * the account, the grant it goes through, and the identity it was bound to.
     *
     * @param actingPrincipalId the member of a personal conversation, or the trigger's author
     * @param holderPrincipalId whom it acts through: the acting member for their own account, a trigger's author for
     *                          theirs, the service account that borrows a credential; null for a credential a grant
     *                          lends (ADR-0072)
     * @param accountId         the account or credential it was bound to; one's own account is found again by its
     *                          holder, since connecting it anew makes another
     * @param grantId           the grant it goes through; null for one's own account and a trigger's row
     * @param externalId        the identity on the system when it was bound; null where the system has none
     */
    public record Stored(UUID companyId, UUID systemId, UUID actingPrincipalId, @Nullable UUID holderPrincipalId,
            UUID accountId, @Nullable UUID grantId, @Nullable String externalId) {

        /** The stored form of an outcome that bound ({@link Own}, {@link Granted}, {@link Fixed}). */
        public static Stored of(Outcome outcome, UUID actingPrincipalId) {
            return switch (outcome) {
                case Own own -> new Stored(own.account().companyId(), own.account().systemId(), actingPrincipalId,
                        own.account().principalId(), own.account().id(), null, own.account().externalId());
                case Fixed fixed -> new Stored(fixed.account().companyId(), fixed.account().systemId(),
                        actingPrincipalId, fixed.holderPrincipalId(), fixed.account().id(), null,
                        fixed.account().externalId());
                case Granted granted -> ofGrant(granted.grant(), granted.account(), actingPrincipalId);
                default -> throw new IllegalArgumentException("Only Own, Granted and Fixed bind: " + outcome);
            };
        }

        /** The stored form of a grant the person picked among {@link Choose}'s or {@link ConnectRequired}'s. */
        public static Stored ofGrant(GrantInfo grant, AccountInfo account, UUID actingPrincipalId) {
            return new Stored(grant.companyId(), grant.systemId(), actingPrincipalId, null, grant.accountId(),
                    grant.id(), account.externalId());
        }
    }

    /**
     * What {@link Accounts#check} answers: the account may be used, with this access now; or why the use stops. What
     * {@link Accounts#open} answers: the system may be used without an account; or why not.
     */
    public sealed interface Check permits Usable, Stopped, Open {
    }

    /**
     * The binding holds: the account to use, and the access now ({@code WRITE} for one's own account; a grant's and a
     * service account's lending's access as it is at this check, not as it was when bound).
     */
    public record Usable(AccountInfo account, GrantInfo.Access access) implements Check {
    }

    /** The use stops, never re-binds (contract §5, I10). */
    public record Stopped(StopReason reason) implements Check {
    }

    /** Why a use stops. */
    public enum StopReason {
        /** The acting member or the holder is suspended or left (I7): {@code Principals} tells which. */
        PRINCIPAL_INACTIVE,
        /** One's own account is gone or not {@code OK}: sign in again. */
        RECONNECT_YOURS,
        /** A credential is gone, disabled or not {@code OK}: an admin signs it in again or enables it. */
        RECONNECT_COMPANY,
        /** The account now is another identity on the system than the one bound. */
        IDENTITY_CHANGED,
        /** The grant was withdrawn. */
        GRANT_GONE,
        /** The acting member is no longer in the grant's group. */
        NOT_IN_GROUP,
        /**
         * A trigger's author may no longer use the service account its row names, or the service account no longer
         * borrows the credential the row names (ADR-0072: withdrawn, or replaced by another, which the trigger's
         * conversations follow).
         */
        USE_RIGHT_GONE,
        /** The system was removed. */
        SYSTEM_GONE,
        /** The system is a personal server and the company switched them off. */
        PERSONAL_SYSTEMS_OFF,
        /**
         * An admin paused the system (the package 6 contract, §5.4): its accounts are used for nothing until it is
         * resumed. Not a reason to change a conversation's binding, which keeps its state under the pause.
         */
        SYSTEM_PAUSED,
        /**
         * A binding without an account ({@link Open}) on a system that needs accounts again (ADR-0071): a personal
         * conversation waits for one, a trigger's conversation goes without it.
         */
        ACCOUNT_REQUIRED
    }
}
