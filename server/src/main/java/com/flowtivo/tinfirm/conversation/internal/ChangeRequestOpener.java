package com.flowtivo.tinfirm.conversation.internal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.AfterCommit;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChangeRequests;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.SystemTools;
import com.flowtivo.tinfirm.system.Systems;

import jakarta.annotation.PreDestroy;

/**
 * Opens a conversation's change request in a repository its turn pushed to (the package 3 contract, §5.4, open point 5):
 * a pull request on GitHub, a merge request on GitLab, of the conversation's branch {@code agent/{key}} into the
 * repository's default branch, through {@link SystemTools#openChangeRequest} with the token of the conversation's
 * binding on the code host ({@link ConversationBindings#use}, purpose {@code change-request:{runId}}, audited as
 * {@code secret.accessed}). Under {@code ALLOW} after the commit of the turn's end ({@link #openAfterCommit}); under
 * {@code ASK} once the person approved it ({@link #approved}, the seam part 2 calls). Never inside a transaction
 * (CONVENTIONS §10), in three steps:
 * <ol>
 * <li>plan, in a short transaction: the conversation open and without a change request in the repository that is not
 * MERGED (a merged one is followed by the next, V038), the repository on a GitHub or GitLab system, the conversation's binding there, the title and the body
 * ({@link ChangeRequestBodies});</li>
 * <li>outside any transaction: the token, with a {@code WRITE} access (a read-only grant opens none, I9), and the code
 * host's call. A change request the code host has open for the branch already is taken as it is;</li>
 * <li>record, in a transaction that locks the conversation: a {@code CHANGE_REQUEST} output and its watch row
 * ({@code run_change_request}), unless one that is not MERGED was recorded meanwhile; or a {@code SYSTEM} message that
 * says why none was opened.</li>
 * </ol>
 * Once the conversation has two or more change requests, in one repository or several, a new one's body lists the
 * others, and each other one's body is rewritten to list it too ({@code GET}, then {@code PATCH} or {@code PUT} when it changed), each with the binding
 * of its own code host; a failed rewrite is logged and not retried. An attempt lost between the commit and the call (the
 * server stopped) is not retried by itself: the conversation's next push opens it.
 */
@Component
class ChangeRequestOpener implements ChangeRequestOpening {

    private static final Logger log = LoggerFactory.getLogger(ChangeRequestOpener.class);

    /** The purpose of a token revealed to open, read or rewrite a change request (the contract's §8). */
    static String purpose(UUID runId) {
        return "change-request:" + runId;
    }

    private final SystemTransactions transactions;

    private final Conversations conversations;

    private final ConversationBindings bindings;

    private final ConversationMessages messages;

    private final ChangeRequestStore changeRequests;

    private final AttachmentStore locks;

    private final OutputService outputs;

    private final Systems systems;

    private final SystemTools tools;

    private final Agents agents;

    private final Principals principals;

    private final RunReads runs;

    private final TinfirmProperties tinfirm;

    private final TimeSource time;

    private final TriggerPorts triggers;

    private final ApplicationEventPublisher events;

    private final ExecutorService executor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("tinfirm-change-request-", 0).factory());

    ChangeRequestOpener(SystemTransactions transactions, Conversations conversations, ConversationBindings bindings,
            ConversationMessages messages, ChangeRequestStore changeRequests, AttachmentStore locks,
            OutputService outputs, Systems systems, SystemTools tools, Agents agents, Principals principals,
            RunReads runs, TinfirmProperties tinfirm, TimeSource time, TriggerPorts triggers,
            ApplicationEventPublisher events) {
        this.triggers = triggers;
        this.events = events;
        this.transactions = transactions;
        this.conversations = conversations;
        this.bindings = bindings;
        this.messages = messages;
        this.changeRequests = changeRequests;
        this.locks = locks;
        this.outputs = outputs;
        this.systems = systems;
        this.tools = tools;
        this.agents = agents;
        this.principals = principals;
        this.runs = runs;
        this.tinfirm = tinfirm;
        this.time = time;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /** What an attempt did. */
    enum Outcome {
        /** A change request was opened, or found open for the branch, and recorded. */
        OPENED,
        /** The conversation had one in the repository that is not MERGED already; nothing was called. */
        EXISTED,
        /** None was opened; a {@code SYSTEM} message says why. */
        REFUSED,
        /** Nothing to do: the conversation ended, or its turn is gone. */
        SKIPPED
    }

    /**
     * Under {@code ALLOW}: opens it after the commit of the caller's transaction (the turn's end), on a thread of its
     * own.
     */
    void openAfterCommit(UUID companyId, UUID conversationId, UUID runId, UUID repositoryId) {
        AfterCommit.run(() -> executor.execute(() -> {
            try {
                SystemContext.runInCompany(companyId, () -> open(companyId, conversationId, runId, repositoryId));
            }
            catch (RuntimeException ex) {
                log.warn("Opening the change request of conversation {} in repository {} failed", conversationId,
                        repositoryId, ex);
            }
        }));
    }

    /**
     * Part 2: the person approved the change request (§5.8), and the approval committed. Opens it now, outside any
     * transaction, for the turn the approval was raised for (its run), else the conversation's latest.
     */
    @Override
    public void approved(UUID companyId, UUID conversationId, UUID decisionId, UUID repositoryId) {
        SystemContext.runInCompany(companyId, () -> {
            UUID runId = transactions.inCompany(companyId, () -> changeRequests.runOfDecision(decisionId)
                .or(() -> runs.ofConversation(companyId, conversationId).stream()
                    .reduce((first, second) -> second)
                    .map(RunInfo::id))
                .orElse(null));
            if (runId == null) {
                log.warn("The approved change request {} of conversation {} names no turn", decisionId,
                        conversationId);
                return;
            }
            open(companyId, conversationId, runId, repositoryId);
        });
    }

    /** One attempt, as planned in a transaction: what to send, or what happened instead. */
    private sealed interface Plan {
    }

    private record Done(Outcome outcome) implements Plan {
    }

    /** No change request can be opened: the message for the conversation. */
    private record Refuse(String message) implements Plan {
    }

    /** A change request to open, with the binding whose token opens it. */
    private record Send(BindingInfo binding, RepositoryInfo repository, SystemKind kind, ChangeRequests.New request)
            implements Plan {
    }

    /**
     * Opens the conversation's change request in the repository for the run's push, outside any transaction in the
     * company's tenant context; safe to repeat.
     */
    Outcome open(UUID companyId, UUID conversationId, UUID runId, UUID repositoryId) {
        NoTransaction.require("Opening a change request");
        Plan plan = transactions.inCompany(companyId, () -> plan(companyId, conversationId, runId, repositoryId));
        if (plan instanceof Done done) {
            return done.outcome();
        }
        if (plan instanceof Refuse refuse) {
            post(companyId, conversationId, runId, refuse.message());
            return Outcome.REFUSED;
        }
        Send send = (Send) plan;
        String name = changeRequestName(send.kind());
        ConversationBindings.BindingUse use = bindings.use(companyId, send.binding().id(), purpose(runId));
        if (!(use instanceof ConversationBindings.BindingUse.Token token)) {
            post(companyId, conversationId, runId, "Tinfirm did not open a " + name + " for `"
                    + send.request().head() + "` in `" + send.repository().name() + "`: the account of this "
                    + "conversation on the code host cannot be used now (" + (use instanceof
                            ConversationBindings.BindingUse.Held held ? held.why() : reason(use.binding())) + ").");
            return Outcome.REFUSED;
        }
        if (token.access() != GrantInfo.Access.WRITE) {
            post(companyId, conversationId, runId, "Tinfirm did not open a " + name + " for `"
                    + send.request().head() + "` in `" + send.repository().name() + "`: this conversation acts "
                    + "through a read-only grant there.");
            return Outcome.REFUSED;
        }
        CallResult<ChangeRequests.Opened> result = tools.openChangeRequest(companyId, repositoryId,
                token.credential().callCredential(), send.request());
        ChangeRequests.Opened opened = result.value();
        if (opened == null) {
            post(companyId, conversationId, runId, "Tinfirm could not open the " + name + " for `"
                    + send.request().head() + "` in `" + send.repository().name() + "`: " + result.failureMessage()
                    + ".");
            return Outcome.REFUSED;
        }
        UUID recorded = transactions.inCompany(companyId, () -> record(companyId, conversationId, runId, send,
                opened));
        if (recorded != null && opened.created()) {
            rewriteRelated(companyId, conversationId, recorded);
        }
        return recorded != null ? Outcome.OPENED : Outcome.EXISTED;
    }

    private Plan plan(UUID companyId, UUID conversationId, UUID runId, UUID repositoryId) {
        ConversationInfo conversation = conversations.find(companyId, conversationId).orElse(null);
        RunInfo run = runs.find(companyId, runId).filter(found -> found.conversationId().equals(conversationId))
            .orElse(null);
        if (conversation == null || !conversation.open() || run == null) {
            return new Done(Outcome.SKIPPED);
        }
        if (current(conversationId, repositoryId)) {
            return new Done(Outcome.EXISTED);
        }
        String branch = branch(conversation);
        RepositoryInfo repository = systems.repository(companyId, repositoryId).orElse(null);
        SystemInfo system = repository != null ? systems.find(companyId, repository.systemId()).orElse(null) : null;
        if (repository == null || system == null) {
            return new Refuse("Tinfirm did not open a change request for `" + branch + "`: the repository no longer "
                    + "exists.");
        }
        if (system.kind() != SystemKind.GITHUB && system.kind() != SystemKind.GITLAB) {
            return new Done(Outcome.SKIPPED);
        }
        BindingInfo binding = bindings.of(companyId, conversationId, system.id()).orElse(null);
        if (binding == null) {
            return new Refuse("Tinfirm did not open a " + changeRequestName(system.kind()) + " for `" + branch
                    + "` in `" + repository.name() + "`: this conversation has no account on " + system.name() + ".");
        }
        String agentName = agents.find(companyId, conversation.agentId()).map(AgentInfo::name).orElse("the agent");
        String personName = principals.displayNames(companyId, List.of(conversation.actingPrincipalId()))
            .getOrDefault(conversation.actingPrincipalId(), "its person");
        String triggerName = conversation.triggered() ? triggers.name(companyId, conversation.triggerId()) : null;
        String summary = run.status() == RunStatus.SUCCEEDED && run.result() == null
                && changeRequests.hasOpenQuestion(runId)
                        ? ChangeRequestBodies.questionsSummary(agentName, run.summary())
                        : run.summary() != null && !run.summary().isBlank() ? run.summary().strip()
                                : "The agent left no summary of its work.";
        SystemKind subjectKind = conversation.subject() != null && conversation.subject().systemId() != null
                ? systems.find(companyId, conversation.subject().systemId()).map(SystemInfo::kind).orElse(null)
                : null;
        List<String> related = relatedLines(companyId, conversationId, null);
        String body = ChangeRequestBodies.body(summary, conversationUrl(companyId, conversationId),
                ChangeRequestBodies.issueLine(conversation.subject(), subjectKind, repository.url(), system.kind()),
                related, agentName, personName, triggerName);
        return new Send(binding, repository, system.kind(),
                new ChangeRequests.New(ChangeRequestBodies.title(conversation), branch, null, body));
    }

    /** Whether the conversation has a change request in the repository that is not MERGED: the one a push goes to. */
    private boolean current(UUID conversationId, UUID repositoryId) {
        return changeRequests.latest(conversationId, repositoryId).filter(row -> !row.merged()).isPresent();
    }

    /**
     * @return the output this attempt recorded it as; null when the conversation had one in the repository that is not
     *         MERGED meanwhile
     */
    private @Nullable UUID record(UUID companyId, UUID conversationId, UUID runId, Send send,
            ChangeRequests.Opened opened) {
        locks.lockConversation(conversationId);
        if (current(conversationId, send.repository().id())) {
            return null;
        }
        OutputStore.OutputRow output = outputs.changeRequest(companyId, conversationId, runId, send.repository(),
                opened, numberSign(send.kind()));
        changeRequests.insert(new ChangeRequestStore.ChangeRequestRow(output.id(), companyId, conversationId, runId,
                send.repository().id(), send.binding().id(), opened.number(), opened.url(), opened.state(),
                opened.state() != null ? time.now() : null, opened.state() != ChangeRequests.State.MERGED,
                time.now(), null));
        if (opened.created()) {
            // Tinfirm's own change request (the package 4 contract, open point 13): its opening raises no event, and
            // a subject it created fires triggers only within the chain of 3. A trigger's conversation links it from
            // its Jira issue (part c's write-back, §5.9) by its link, title and output.
            String key = send.repository().name() + numberSign(send.kind()) + opened.number();
            events.publishEvent(new ConversationEvents.SystemWritten(companyId, conversationId,
                    send.repository().systemId(), "CHANGE_REQUEST " + key, Integer.toString(opened.number()),
                    "CHANGE_REQUEST", key, ConversationEvents.WrittenValues.changeRequest(opened.url(),
                            output.title(), output.id())));
        }
        log.info("Conversation {}: {} {} of {} {}", conversationId, changeRequestName(send.kind()), opened.number(),
                send.repository().name(), opened.created() ? "opened" : "found open");
        return output.id();
    }

    /** The lines of the conversation's change requests but the one of that output (null: all of them), oldest first. */
    private List<String> relatedLines(UUID companyId, UUID conversationId, @Nullable UUID except) {
        List<String> lines = new ArrayList<>();
        for (ChangeRequestStore.ChangeRequestRow row : changeRequests.ofConversation(conversationId)) {
            if (!row.outputId().equals(except)) {
                String name = systems.repository(companyId, row.repositoryId()).map(RepositoryInfo::name)
                    .orElse("repository");
                lines.add(ChangeRequestBodies.line(name, row.url()));
            }
        }
        return lines;
    }

    /** A change request to rewrite: its row, its repository and the block its body should hold. */
    private record Rewrite(ChangeRequestStore.ChangeRequestRow row, String block) {
    }

    /**
     * Rewrites the related block in every other change request of the conversation, which the new one of the output
     * {@code created} joined; each with the binding it was opened with.
     */
    private void rewriteRelated(UUID companyId, UUID conversationId, UUID created) {
        List<Rewrite> rewrites = transactions.inCompany(companyId, () -> {
            List<ChangeRequestStore.ChangeRequestRow> all = changeRequests.ofConversation(conversationId);
            if (all.size() < 2) {
                return List.<Rewrite>of();
            }
            List<Rewrite> list = new ArrayList<>();
            for (ChangeRequestStore.ChangeRequestRow row : all) {
                if (!row.outputId().equals(created)) {
                    list.add(new Rewrite(row, ChangeRequestBodies.relatedBlock(relatedLines(companyId,
                            conversationId, row.outputId()))));
                }
            }
            return list;
        });
        Map<UUID, Optional<CallCredential>> credentials = new HashMap<>();
        for (Rewrite rewrite : rewrites) {
            ChangeRequestStore.ChangeRequestRow row = rewrite.row();
            Optional<CallCredential> credential = credentials.computeIfAbsent(row.bindingId(),
                    bindingId -> writable(companyId, bindingId, row.runId()));
            if (credential.isEmpty()) {
                log.warn("The related change requests of {} are not updated: its binding cannot write now", row.url());
                continue;
            }
            CallResult<String> body = tools.changeRequestBody(companyId, row.repositoryId(), credential.get(),
                    row.number());
            if (body.value() == null) {
                log.warn("Reading {} to update its related change requests failed: {}", row.url(),
                        body.failureMessage());
                continue;
            }
            String updated = ChangeRequestBodies.withRelated(body.value(), rewrite.block());
            if (updated.equals(body.value())) {
                continue;
            }
            CallResult<Boolean> written = tools.updateChangeRequestBody(companyId, row.repositoryId(),
                    credential.get(), row.number(), updated);
            if (!written.succeeded()) {
                log.warn("Updating the related change requests of {} failed: {}", row.url(), written.failureMessage());
            }
        }
    }

    /** The binding's credential when it is usable with {@code WRITE} access; empty otherwise. */
    private Optional<CallCredential> writable(UUID companyId, UUID bindingId, UUID runId) {
        ConversationBindings.BindingUse use = bindings.use(companyId, bindingId, purpose(runId));
        if (use instanceof ConversationBindings.BindingUse.Token token && token.access() == GrantInfo.Access.WRITE) {
            return Optional.of(token.credential().callCredential());
        }
        return Optional.empty();
    }

    private void post(UUID companyId, UUID conversationId, UUID runId, String message) {
        transactions.runInCompany(companyId, () -> messages.post(companyId, conversationId,
                ConversationMessages.Kind.SYSTEM, null, message, runId, null));
    }

    /** The conversation's branch, {@code agent/{key}}: its key is a valid branch name already (V005, I12). */
    static String branch(ConversationInfo conversation) {
        return "agent/" + conversation.key();
    }

    /** The conversation's page in the app. */
    String conversationUrl(UUID companyId, UUID conversationId) {
        return tinfirm.publicBaseUrl() + "/c/" + companyId + "/conversations/" + conversationId;
    }

    /** What the code host calls a change request. */
    static String changeRequestName(SystemKind kind) {
        return kind == SystemKind.GITLAB ? "merge request" : "pull request";
    }

    /** The sign before a change request's number: {@code !} for a GitLab merge request, else {@code #}. */
    static String numberSign(SystemKind kind) {
        return kind == SystemKind.GITLAB ? "!" : "#";
    }

    private static String reason(BindingInfo binding) {
        BindingInfo.Reason reason = binding.reason();
        if (binding.gone()) {
            return "it is gone" + (reason != null ? ", " + reason.name() : "");
        }
        return binding.waiting() ? "it waits for " + (reason != null && reason.personActs() ? "the person"
                : "an admin") + (reason != null ? ", " + reason.name() : "") : "it could not be revealed";
    }

}
