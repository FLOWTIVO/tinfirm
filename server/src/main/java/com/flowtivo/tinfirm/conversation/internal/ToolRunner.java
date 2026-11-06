package com.flowtivo.tinfirm.conversation.internal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.SystemTools;
import com.flowtivo.tinfirm.system.ToolCallResult;
import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.ToolOutcome;

import tools.jackson.databind.JsonNode;

/**
 * One tool call with the conversation's binding (the package 3 contract, §5.3, §5.4): {@code ConversationBindings.use}
 * with the call's purpose, the only path to the token ({@code secret.accessed}), then {@code SystemTools.call} with
 * the comment's label, both outside any transaction; then, in the caller's transaction, what the call leaves:
 * {@code tool.called}, a {@code SystemWritten} per write, and for a credential the system refused (a {@code 401}) the
 * account turned {@code RECONNECT} and the binding checked again, so that it waits for its card. A binding without an
 * account ({@code OPEN}, ADR-0071) calls without a credential, and a {@code 401} then is the agent's error: there is
 * no account to sign in again.
 * <p>
 * The label a comment starts with names the agent, and the person when the conversation acts through a grant:
 * {@code {agent} (Tinfirm agent):}, {@code {agent} (Tinfirm agent) for {person}:} (§5.4).
 */
@Component
class ToolRunner {

    private final ConversationCore core;

    private final SystemTools systemTools;

    private final Accounts accounts;

    private final ToolAudit audit;

    private final ApplicationEventPublisher events;

    ToolRunner(ConversationCore core, SystemTools systemTools, Accounts accounts, ToolAudit audit,
            ApplicationEventPublisher events) {
        this.core = core;
        this.systemTools = systemTools;
        this.accounts = accounts;
        this.audit = audit;
        this.events = events;
    }

    /**
     * A call that passed steps 1 to 5 and is about to run.
     *
     * @param runId     the run whose agent called it; null when none is known
     * @param name      the tool as the run lists it
     * @param tool      the tool as its system names it
     * @param arguments checked and normalized ({@code SystemTools.check})
     */
    record Prepared(UUID companyId, UUID conversationId, @Nullable UUID runId, UUID agentId, UUID bindingId,
            UUID systemId, String name, String tool, JsonNode arguments, ToolEffect effect, String agentName,
            String personName) {
    }

    /** How a call ended. */
    sealed interface Ran {

        /** No token: the binding waits, or is gone; nothing was called. */
        record Unavailable(BindingInfo binding) implements Ran {
        }

        /**
         * No token for a passing failure, though the binding holds ({@code BindingUse.Failed}); nothing was called,
         * and a later try may work.
         */
        record Failed(BindingInfo binding) implements Ran {
        }

        /**
         * No token, though the binding holds: a kill switch holds the conversation ({@code BindingUse.Held}: the
         * company's runs stopped, its trigger or its agent paused); nothing was revealed and nothing was called.
         *
         * @param why the switch in words, e.g. "the company's runs are stopped"
         */
        record Held(BindingInfo binding, String why) implements Ran {
        }

        /**
         * The system was called; its outcome, and the binding's use it acted with: a token's, or none
         * ({@code BindingUse.Open}).
         */
        record Called(ConversationBindings.BindingUse use, ToolOutcome outcome) implements Ran {

            public Called {
                if (!(use instanceof ConversationBindings.BindingUse.Token
                        || use instanceof ConversationBindings.BindingUse.Open)) {
                    throw new IllegalArgumentException("A call acts with a token, or without one on an open binding");
                }
            }

            /** The account the call acted with; null without one. */
            public @Nullable AccountInfo account() {
                return use instanceof ConversationBindings.BindingUse.Token token ? token.account() : null;
            }

            @Override
            public String toString() {
                return "Called[binding=" + use.binding().id() + ", outcome=" + outcome + "]";
            }
        }
    }

    /** Runs the call outside any transaction: the token for the purpose, then the system. */
    Ran run(Prepared call, String purpose) {
        ConversationBindings.BindingUse use = core.bindings().use(call.companyId(), call.bindingId(), purpose);
        if (use instanceof ConversationBindings.BindingUse.Failed failed) {
            return new Ran.Failed(failed.binding());
        }
        if (use instanceof ConversationBindings.BindingUse.Held held) {
            return new Ran.Held(held.binding(), held.why());
        }
        if (use instanceof ConversationBindings.BindingUse.Open open) {
            ToolOutcome outcome = systemTools.call(call.companyId(), call.systemId(), CallCredential.none(),
                    call.tool(), call.arguments(), call.agentName() + " (Tinfirm agent):");
            return new Ran.Called(open, withoutAccount(outcome, call));
        }
        if (!(use instanceof ConversationBindings.BindingUse.Token token)) {
            return new Ran.Unavailable(use.binding());
        }
        // Pinned to the endpoint the token was revealed for (ADR-0079): SystemTools sends it nowhere else.
        CallCredential credential = token.credential().callCredential();
        String label = call.agentName() + " (Tinfirm agent)"
                + (token.binding().grantId() != null ? " for " + call.personName() : "") + ":";
        return new Ran.Called(token, systemTools.call(call.companyId(), call.systemId(), credential, call.tool(),
                call.arguments(), label));
    }

    /**
     * A call without a credential that the system refused as unauthorised: nobody's account is there to sign in again,
     * so it is the agent's error, which names what to tell the person (ADR-0071).
     */
    private static ToolOutcome withoutAccount(ToolOutcome outcome, Prepared call) {
        if (!(outcome instanceof ToolOutcome.Reconnect reconnect)) {
            return outcome;
        }
        String text = "The system refused the call without a credential (" + reconnect.message() + "). Tinfirm calls "
                + "it without an account, as its admins set it; tell " + call.personName() + " in your summary that it "
                + "now asks for one, so an admin sets it to need accounts.";
        return new ToolOutcome.Done(ToolCallResult.text(text, null, true),
                CallResult.Failure.of(CallResult.Kind.UNAUTHORIZED, reconnect.message()), null);
    }

    /**
     * Records a call that reached its system, in the caller's transaction, which locked the conversation first: what the
     * call left ({@link #effects}), then {@code tool.called} and a write's {@code SystemWritten} ({@link #audited}).
     *
     * @param mode       {@code ALLOW}, or {@code ASK} for an approved call
     * @param decisionId an approved call's decision
     * @param parked     the binding a parked call waited on
     */
    void record(Prepared call, Ran.Called called, String mode, @Nullable UUID decisionId, @Nullable UUID parked) {
        effects(call, called);
        audited(call, called, mode, decisionId, parked);
    }

    /**
     * What a call left on its account and binding, in the caller's transaction, before any audit record of the call
     * (CONVENTIONS §10: the account's row before the audit chain head): for a credential the system refused (a
     * {@code 401}) the account turns {@code RECONNECT} ({@code Accounts.refused}, which locks the account's row) and the
     * binding is checked again, so that it waits for its card. A run's call then ends its journal row, and audits last.
     */
    void effects(Prepared call, Ran.Called called) {
        AccountInfo account = called.account();
        if (called.outcome() instanceof ToolOutcome.Reconnect reconnect && account != null) {
            accounts.refused(call.companyId(), account.id(), reconnect.message());
            core.bindings().check(call.companyId(), call.bindingId());
        }
    }

    /** {@code tool.called} and a write's {@code SystemWritten}, in the caller's transaction, after {@link #effects}. */
    void audited(Prepared call, Ran.Called called, String mode, @Nullable UUID decisionId, @Nullable UUID parked) {
        BindingInfo binding = called.use().binding();
        ToolAudit.Call audited = new ToolAudit.Call(call.companyId(), call.conversationId(), call.runId(),
                call.agentId(), call.systemId(), call.tool(), call.effect(),
                called.account() != null ? Objects.requireNonNull(called.account()).id() : null,
                binding.holderPrincipalId(), binding.grantId());
        switch (called.outcome()) {
            case ToolOutcome.Done done -> {
                audit.called(audited, mode, decisionId, parked, !done.result().isError(),
                        done.failure() != null ? done.failure().kind().name() : null);
                if (done.written() != null) {
                    ToolOutcome.Written written = done.written();
                    events.publishEvent(new ConversationEvents.SystemWritten(call.companyId(), call.conversationId(),
                            call.systemId(), written.kind().name() + " " + written.key(), written.externalId(),
                            written.kind() == ToolOutcome.WriteKind.LABELS ? "LABEL" : written.kind().name(),
                            written.key(), new ConversationEvents.WrittenValues(written.status(),
                                    written.labelsAdded(), written.labelsRemoved())));
                }
            }
            case ToolOutcome.Reconnect reconnect -> audit.called(audited, mode, decisionId, parked, false, "RECONNECT");
            case ToolOutcome.Refused refused -> audit.called(audited, mode, decisionId, parked, false,
                    refused.code().name());
        }
    }

    /** The runner protocol's form of a system's answer. */
    static RunnerMessages.ToolResult toolResult(ToolCallResult result) {
        List<RunnerMessages.ToolContent> content = result.content()
            .stream()
            .map(block -> new RunnerMessages.ToolContent(block.type(), block.text()))
            .toList();
        return new RunnerMessages.ToolResult(content, result.structuredContent(), result.isError());
    }
}
