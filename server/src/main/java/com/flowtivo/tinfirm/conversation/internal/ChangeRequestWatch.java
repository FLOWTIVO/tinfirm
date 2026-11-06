package com.flowtivo.tinfirm.conversation.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.CallCredential;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChangeRequests;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.SystemTools;
import com.flowtivo.tinfirm.system.Systems;

/**
 * Reads the state of the conversations' change requests (OPEN, DRAFT, MERGED, CLOSED) from their code host, GitHub or
 * GitLab (ADR-0022), every {@code state-tick} on replicas with the {@code worker} role. Due are the watched ones never
 * read, made due by a later push, or last read longer than {@code state-interval} ago (5 minutes); a change request is
 * watched until it is MERGED, its conversation ends or its binding is gone. They come from the cross-company lookup of
 * V008 and are handled per company and binding:
 * <ol>
 * <li>claim: stamp each one's read time in its company, so that no other worker reads it;</li>
 * <li>the token of the conversation's binding on the code host ({@link ConversationBindings#use}, purpose
 * {@code change-request:{runId}}), once per binding and pass, and each state, outside any transaction;</li>
 * <li>record each state that was read, with its head commit, on the watch row and on the {@code CHANGE_REQUEST}
 * output. A change request read MERGED that is its conversation's latest in the repository, while the conversation is
 * open, is merged without the branch's latest push when that push's head commit is another than the one it was merged
 * at: the push then gets the next one ({@link ChangeRequestPolicy}), as at the end of a turn that pushed after the
 * merge was read. It covers a push between the merge and its read, and a change request merged before V038.</li>
 * </ol>
 * A failure is logged; the change request keeps its last state and is read again after the interval. A binding that
 * cannot be used now (it waits) is skipped the same way; one that is gone stops the watch of its change requests.
 */
@Component
class ChangeRequestWatch {

    private static final Logger log = LoggerFactory.getLogger(ChangeRequestWatch.class);

    private static final int BATCH = 100;

    private final ChangeRequestStore changeRequests;

    private final OutputService outputs;

    private final ConversationBindings bindings;

    private final SystemTools tools;

    private final SystemTransactions transactions;

    private final ChangeRequestProperties properties;

    private final boolean worker;

    private final TimeSource time;

    private final Conversations conversations;

    private final RunReads runs;

    private final Systems systems;

    private final ChangeRequestPolicy policy;

    ChangeRequestWatch(ChangeRequestStore changeRequests, OutputService outputs, ConversationBindings bindings,
            SystemTools tools, SystemTransactions transactions, ChangeRequestProperties properties,
            TinfirmProperties tinfirm, TimeSource time, Conversations conversations, RunReads runs, Systems systems,
            ChangeRequestPolicy policy) {
        this.changeRequests = changeRequests;
        this.outputs = outputs;
        this.bindings = bindings;
        this.tools = tools;
        this.transactions = transactions;
        this.properties = properties;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
        this.time = time;
        this.conversations = conversations;
        this.runs = runs;
        this.systems = systems;
        this.policy = policy;
    }

    /** The company and binding of a group of due change requests. */
    private record Group(UUID companyId, UUID bindingId) {
    }

    @Scheduled(fixedDelayString = "${tinfirm.conversation.change-requests.state-tick:PT15S}",
            initialDelayString = "${tinfirm.conversation.change-requests.state-tick:PT15S}")
    void scheduledRefresh() {
        if (worker) {
            try {
                refresh();
            }
            catch (RuntimeException ex) {
                log.warn("The pass over the states of change requests failed", ex);
            }
        }
    }

    /**
     * Reads the state of up to one batch of due change requests; safe to run on several workers at once.
     *
     * @return how many states were recorded
     */
    int refresh() {
        Instant checkedBefore = time.now().minus(properties.stateInterval());
        Map<Group, List<ChangeRequestStore.ChangeRequestRow>> claimed = new LinkedHashMap<>();
        for (ChangeRequestStore.DueRef ref : transactions.unscoped(() -> changeRequests.stateDue(checkedBefore,
                BATCH))) {
            Optional<ChangeRequestStore.ChangeRequestRow> row = transactions.inCompany(ref.companyId(),
                    () -> changeRequests.claim(ref.outputId(), checkedBefore, time.now())
                            ? changeRequests.find(ref.outputId()) : Optional.empty());
            row.ifPresent(found -> claimed.computeIfAbsent(new Group(found.companyId(), found.bindingId()),
                    key -> new ArrayList<>()).add(found));
        }
        int recorded = 0;
        for (Map.Entry<Group, List<ChangeRequestStore.ChangeRequestRow>> group : claimed.entrySet()) {
            recorded += SystemContext.callInCompany(group.getKey().companyId(),
                    () -> read(group.getKey(), group.getValue()));
        }
        return recorded;
    }

    private int read(Group group, List<ChangeRequestStore.ChangeRequestRow> rows) {
        ConversationBindings.BindingUse use = bindings.use(group.companyId(), group.bindingId(),
                ChangeRequestOpener.purpose(rows.getFirst().runId()));
        if (!(use instanceof ConversationBindings.BindingUse.Token token)) {
            if (use.binding().gone()) {
                transactions.runInCompany(group.companyId(), () -> changeRequests.unwatchBinding(group.bindingId()));
            }
            log.debug("The state of {} change requests is not read: their binding {} cannot be used now", rows.size(),
                    group.bindingId());
            return 0;
        }
        CallCredential credential = token.credential().callCredential();
        Map<ChangeRequestStore.ChangeRequestRow, ChangeRequests.Read> reads = new LinkedHashMap<>();
        for (ChangeRequestStore.ChangeRequestRow row : rows) {
            CallResult<ChangeRequests.Read> read = tools.changeRequestState(group.companyId(), row.repositoryId(),
                    credential, row.number());
            if (read.value() != null) {
                reads.put(row, read.value());
            }
            else {
                log.warn("Reading the state of {} failed: {}", row.url(), read.failureMessage());
            }
        }
        if (!reads.isEmpty()) {
            transactions.runInCompany(group.companyId(), () -> reads.forEach((row, read) -> {
                changeRequests.recordState(row.outputId(), read);
                outputs.changeRequestState(row.outputId(), read.state());
                if (read.state() == ChangeRequests.State.MERGED && read.headSha() != null) {
                    merged(row, read.headSha());
                }
            }));
        }
        return reads.size();
    }

    /**
     * The change request was merged at {@code mergedAt}: when it is its conversation's latest in the repository, the
     * conversation is open, and the branch's latest push, by a turn that succeeded or was cancelled, has another head,
     * that push is due the next one.
     */
    private void merged(ChangeRequestStore.ChangeRequestRow row, String mergedAt) {
        UUID companyId = row.companyId();
        ConversationInfo conversation = conversations.find(companyId, row.conversationId())
            .filter(ConversationInfo::open)
            .orElse(null);
        boolean latest = changeRequests.latest(row.conversationId(), row.repositoryId())
            .map(found -> found.outputId().equals(row.outputId()))
            .orElse(false);
        if (conversation == null || !latest) {
            return;
        }
        RunReads.Push push = runs.lastPush(companyId, row.conversationId(), row.repositoryId(), null).orElse(null);
        if (push == null || ChangeRequestPolicy.sameCommit(push.headSha(), mergedAt)) {
            return;
        }
        RunStatus status = runs.find(companyId, push.runId()).map(RunInfo::status).orElse(null);
        RepositoryInfo repository = systems.repository(companyId, row.repositoryId()).orElse(null);
        SystemInfo system = repository != null ? systems.find(companyId, repository.systemId()).orElse(null) : null;
        if (status != RunStatus.SUCCEEDED && status != RunStatus.CANCELLED || repository == null || system == null
                || system.kind() != SystemKind.GITHUB && system.kind() != SystemKind.GITLAB) {
            return;
        }
        log.info("Conversation {}: {} was merged without the push of {}", row.conversationId(), row.url(),
                push.headSha());
        policy.due(conversation, conversation.agentId(), new ChangeRequestPolicy.Push(push.runId(), repository.id(),
                repository.name(), ChangeRequestOpener.branch(conversation), system, true));
    }

    /** The conversation ended: its change requests are no longer watched. */
    @EventListener
    void conversationEnded(ConversationEvents.ConversationEnded event) {
        changeRequests.unwatchConversation(event.conversationId());
    }

    /** The binding is gone for good: what it was the account of is no longer read with it. */
    @EventListener
    void bindingGone(ConversationEvents.BindingGone event) {
        changeRequests.unwatchBinding(event.bindingId());
    }

}
