package com.flowtivo.tinfirm.conversation.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.run.RunGitRevocations;

/**
 * The conversation module's side of ADR-0116 (decision 2): a binding that fails its check, for any reason and on any
 * path ({@link ConversationEvents.BindingWaiting}, {@link ConversationEvents.BindingGone}), and a trigger's binding that
 * follows its service account to another credential ({@link ConversationEvents.BindingFollowed},
 * {@code CREDENTIAL_REPLACED}) revoke the git tokens its account gave the runs under way ({@link RunGitRevocations});
 * a paused system does so in its pause ({@link ConversationSwitches}).
 * <p>
 * Each listener runs inside the change's transaction ({@code @EventListener}), which holds the conversation's row
 * lock (CONVENTIONS §10): the revocation is durable with the change, and its frame goes after the commit. A run on a
 * host without the feature {@code run.git.revoke} keeps its tokens until it ends, which the conversation says.
 * <p>
 * Nothing here revokes for a kill switch that holds (ADR-0105, decision 3): the company's stop, a paused agent, a
 * paused trigger (the owner's answer of 2026-11-19).
 */
@Component
class GitRevocations {

    private final RunGitRevocations revocations;

    private final BindingStore bindings;

    private final BindingTexts texts;

    private final ConversationMessagesImpl messages;

    GitRevocations(RunGitRevocations revocations, BindingStore bindings, BindingTexts texts,
            ConversationMessagesImpl messages) {
        this.revocations = revocations;
        this.bindings = bindings;
        this.texts = texts;
        this.messages = messages;
    }

    @EventListener
    void bindingWaiting(ConversationEvents.BindingWaiting event) {
        failed(event.companyId(), event.bindingId(), event.reason());
    }

    @EventListener
    void bindingGone(ConversationEvents.BindingGone event) {
        failed(event.companyId(), event.bindingId(), event.reason());
    }

    @EventListener
    void bindingFollowed(ConversationEvents.BindingFollowed event) {
        String system = texts.systemName(event.companyId(), event.systemId());
        told(event.companyId(), revocations.ofBinding(event.companyId(), event.bindingId(),
                RunGitRevocations.CREDENTIAL_REPLACED, "the trigger's service account borrows another credential on "
                        + system + " now"));
    }

    private void failed(UUID companyId, UUID bindingId, BindingInfo.Reason reason) {
        BindingInfo binding = bindings.find(companyId, bindingId).map(BindingStore.BindingRow::info).orElse(null);
        if (binding == null) {
            return;
        }
        told(companyId, revocations.ofBinding(companyId, bindingId, reason.name(), texts.revoked(binding)));
    }

    /**
     * A member leaves (in the leaving transaction, which holds the conversation's lock), before the conversation's
     * running turn is cancelled: a cancelled run pushes its work, and the conversation ends, so no later check of its
     * bindings would revoke anything. Every binding of the conversation that holds goes: through the person's own
     * account ({@code HOLDER_LEFT}) or a credential lent to their group ({@code LEFT_GROUP}).
     */
    void personLeaving(UUID companyId, UUID principalId, UUID conversationId) {
        for (BindingStore.BindingRow row : bindings.ofConversation(companyId, conversationId)) {
            BindingInfo binding = row.info();
            if (!binding.bound() || binding.open()) {
                continue;
            }
            boolean held = principalId.equals(binding.holderPrincipalId());
            revocations.ofBinding(companyId, binding.id(), held ? BindingInfo.Reason.HOLDER_LEFT.name()
                    : BindingInfo.Reason.LEFT_GROUP.name(), held ? "the account's holder left the company"
                            : "the person left the company");
        }
    }

    /** The runs of a paused system, in the pause's transaction, before any of them is cancelled. */
    List<RunGitRevocations.Revoked> systemPaused(UUID companyId, UUID systemId) {
        List<RunGitRevocations.Revoked> revoked = revocations.ofSystem(companyId, systemId,
                texts.systemName(companyId, systemId) + " was paused by an admin");
        told(companyId, revoked);
        return revoked;
    }

    /**
     * The conversation of a run whose host does not take revocations is told that its turn keeps its git access until
     * it ends; the others learn it from the binding's card and the run's page.
     */
    private void told(UUID companyId, Collection<RunGitRevocations.Revoked> revoked) {
        for (RunGitRevocations.Revoked run : revoked) {
            if (run.delivery() == RunGitRevocations.Delivery.UNSUPPORTED) {
                messages.system(companyId, run.conversationId(), "The turn under way keeps its git access to "
                        + String.join(", ", run.repositories()) + " until it ends: its runner host cannot drop a "
                        + "token yet. Upgrade the runner host to revoke at once.", run.runId());
            }
        }
    }
}
