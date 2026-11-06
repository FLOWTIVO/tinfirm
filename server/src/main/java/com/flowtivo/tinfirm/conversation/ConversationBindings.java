package com.flowtivo.tinfirm.conversation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.GrantInfo;

/**
 * The binding of a conversation (the package 3 contract, §5.3, I6 to I8): which account a conversation uses on each
 * system, resolved once at the system's first use through {@code Accounts.bind}, and checked at every use. Every method
 * but {@link #use} runs in the caller's transaction and tenant context. Each that may change a binding locks the
 * binding's conversation before the binding (CONVENTIONS §10): a binding that changes writes its conversation.
 */
public interface ConversationBindings {

    /**
     * {@code ConversationBindings.resolve(conversation, systemId)}: at the system's first use (its first tool call, or
     * the first turn whose workspace holds one of its repositories). Stores the outcome of {@code Accounts.bind} as
     * {@code BOUND} or {@code WAITING} with its reason, audited ({@code conversation.binding.bound} or
     * {@code .waiting}); a binding that exists already is answered as it is, never bound again (I7). A system the
     * person may not bind at all (another member's personal server) waits with {@code NONE}, and a personal server
     * while they are switched off with {@code SYSTEM_OFF}. An MCP server that needs no account binds {@code OPEN}
     * (ADR-0071), in a trigger's conversation too, which binds nothing else outside its trigger's table.
     */
    default BindingInfo resolve(UUID companyId, UUID conversationId, UUID systemId) {
        return resolve(companyId, conversationId, systemId, null);
    }

    /**
     * {@link #resolve(UUID, UUID, UUID)}, asked for by a run: its tool call, or its turn's workspace. A binding it makes
     * names that run for good (its {@code runId}, where the conversation page places its card); one that exists already
     * keeps the run that asked first, or none.
     */
    BindingInfo resolve(UUID companyId, UUID conversationId, UUID systemId, @Nullable UUID runId);

    /**
     * Resolves a {@code WAITING} binding again by the same call, never by a fallback: on the person's {@code continue},
     * and on package 1's events for someone it concerns. A binding that is not waiting is answered as it is.
     */
    BindingInfo resolveAgain(UUID companyId, UUID bindingId);

    /**
     * The checks of {@code use} without the token (contract §5.3's table): the binding {@code BOUND}, the holder
     * active, the holder's account with the bound identity and {@code OK}, the grant still there with the person in its
     * group, the system there (and on, for a personal server). A failed check records the binding {@code WAITING} or
     * {@code GONE} with its reason (audited once per wait), and answers so; it never re-binds.
     */
    BindingCheck check(UUID companyId, UUID bindingId);

    /**
     * {@code ConversationBindings.use(binding, purpose)}: the only path to a token (I7). Runs {@link #check} in a
     * transaction of its own, then reveals the account's credential outside any ({@code Accounts.reveal}, audited as
     * {@code secret.accessed} with the purpose), so it runs outside any transaction (CONVENTIONS §10) and throws
     * {@link IllegalStateException} inside one. Purposes: {@code run:{runId}} (a workspace's git token),
     * {@code run-tool:{runId}}, {@code decision-tool:{decisionId}}, {@code binding-call:{bindingId}},
     * {@code change-request:{runId}}; from package 4 {@code write-back:{outboxId}} (a row of the trigger module's
     * write-back outbox, written with a trigger's conversation's {@code FIXED} binding).
     * <p>
     * The reveal is the gate (the security review of 2026-10-27, D5, M6): once the binding's check passes, nothing is
     * revealed, for any purpose, while the company's runs are stopped, the conversation's trigger is paused or its
     * agent is paused; the answer is then {@link BindingUse.Held}, which tells the caller that the binding holds and
     * changes nothing. A binding that waits or is gone is answered as such first, so that the outbox still drops a
     * row whose binding is gone. Every other check of these switches (the dispatcher's, the outbox's claim, the
     * approved and parked calls') is an early exit, not the control.
     */
    BindingUse use(UUID companyId, UUID bindingId, String purpose);

    Optional<BindingInfo> find(UUID companyId, UUID bindingId);

    /** The conversation's binding on the system, if it was resolved. */
    Optional<BindingInfo> of(UUID companyId, UUID conversationId, UUID systemId);

    /** The conversation's bindings, oldest first. */
    List<BindingInfo> of(UUID companyId, UUID conversationId);

    /** What {@link #check} answers. */
    sealed interface BindingCheck {

        BindingInfo binding();

        /**
         * The binding holds: its account, and the access now ({@code WRITE} for the person's own account, the grant's
         * access at this check otherwise; open point 9).
         */
        record Usable(BindingInfo binding, AccountInfo account, GrantInfo.Access access) implements BindingCheck {

            public Usable {
                Objects.requireNonNull(account, "account");
                Objects.requireNonNull(access, "access");
            }
        }

        /** The binding waits for its card ({@link BindingInfo#reason()}). */
        record Waiting(BindingInfo binding) implements BindingCheck {
        }

        /** The binding is gone, for good ({@link BindingInfo#reason()}). */
        record Gone(BindingInfo binding) implements BindingCheck {
        }

        /** The binding holds without an account ({@code OPEN}, ADR-0071): a call goes without a credential. */
        record Open(BindingInfo binding) implements BindingCheck {
        }
    }

    /** What {@link #use} answers. */
    sealed interface BindingUse {

        BindingInfo binding();

        /**
         * The token for one call, clone or push, and the access now. Never log it.
         *
         * @param credential the account's credential, as {@code Accounts.reveal} answers it
         */
        record Token(BindingInfo binding, AccountInfo account, GrantInfo.Access access,
                Accounts.RevealedCredential credential) implements BindingUse {

            @Override
            public String toString() {
                return "Token[binding=" + binding.id() + ", access=" + access + ", credential=***]";
            }
        }

        /**
         * No token now: the binding waits ({@link BindingInfo#waiting()}) or is gone. Also answered when the account
         * could not be revealed after the check because it went, or turned {@code RECONNECT} on a refused refresh: the
         * binding then waits for its reconnect.
         */
        record Unavailable(BindingInfo binding) implements BindingUse {
        }

        /**
         * No token now, though the binding holds: the account could not be revealed for a passing failure (its OAuth
         * grant could not be refreshed because the system's token endpoint answered 5xx or 429, or could not be
         * reached) and its check still passes. Nothing changed and nothing was called: trying again later may work.
         */
        record Failed(BindingInfo binding) implements BindingUse {
        }

        /**
         * No token, and none needed: the binding holds without an account ({@code OPEN}, ADR-0071), and the call goes
         * without a credential. Nothing was revealed.
         */
        record Open(BindingInfo binding) implements BindingUse {
        }

        /**
         * No token now, though the binding holds (with or without an account): a kill switch holds the conversation
         * ({@link Hold}). Nothing was revealed, nothing changed on the binding, and nothing may be called with it until
         * the switch is off.
         *
         * @param why the switch in words for the thread and the agent, e.g. "the company's runs are stopped"
         */
        record Held(BindingInfo binding, Hold hold, String why) implements BindingUse {

            public Held {
                Objects.requireNonNull(hold, "hold");
                Objects.requireNonNull(why, "why");
            }
        }
    }

    /** The switch that holds a conversation's every use of its bindings ({@link BindingUse.Held}), in this order. */
    enum Hold {
        /** The company's runs are stopped by an admin (the package 6 contract, §5.4, G4). */
        COMPANY_STOPPED,
        /** The conversation's trigger is paused (the package 4 contract, §5.11). */
        TRIGGER_PAUSED,
        /** The conversation's agent is paused (I18). */
        AGENT_PAUSED
    }
}
