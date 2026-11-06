package com.flowtivo.tinfirm.conversation.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.run.Turns;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link TriggerConversations} (the package 4 contract, §5.6 to §5.11 and §11): a trigger's conversations, started,
 * continued, ended, paused, reviewed and rebound for the {@code trigger} module. Every method runs in the caller's
 * transaction, which holds the trigger's row when it changes the trigger (CONVENTIONS §10: the trigger before its
 * conversations); each conversation is locked here, by id, before its runs, bindings and decisions.
 */
@Service
class TriggerConversationsImpl implements TriggerConversations {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The longest title, as a personal conversation's. */
    private static final int MAX_TITLE = 200;

    private final ConversationStore store;

    private final ConversationService conversations;

    private final TurnService turns;

    private final BindingService bindings;

    private final BindingStore bindingStore;

    private final ConversationMessagesImpl messages;

    private final DecisionOps decisionOps;

    private final DecisionStore decisionStore;

    private final DecisionReads decisions;

    private final Turns runTurns;

    private final RunReads runs;

    private final Principals principals;

    private final JdbcClient jdbc;

    private final ConversationOutputs outputs;

    private final AttachmentStore attachments;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    TriggerConversationsImpl(ConversationStore store, ConversationService conversations, TurnService turns,
            BindingService bindings, BindingStore bindingStore, ConversationMessagesImpl messages,
            DecisionOps decisionOps, DecisionStore decisionStore, DecisionReads decisions, Turns runTurns,
            RunReads runs, Principals principals, JdbcClient jdbc, ConversationOutputs outputs,
            AttachmentStore attachments, AuditLog auditLog, ApplicationEventPublisher events, TimeSource time) {
        this.outputs = outputs;
        this.attachments = attachments;
        this.store = store;
        this.conversations = conversations;
        this.turns = turns;
        this.bindings = bindings;
        this.bindingStore = bindingStore;
        this.messages = messages;
        this.decisionOps = decisionOps;
        this.decisionStore = decisionStore;
        this.decisions = decisions;
        this.runTurns = runTurns;
        this.runs = runs;
        this.principals = principals;
        this.jdbc = jdbc;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    // ---- start -----------------------------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Started start(NewTriggerConversation start) {
        UUID companyId = start.companyId();
        Instant now = time.now();
        UUID id = Ids.newId();
        TrackerSubject tracker = start.subject();
        ConversationInfo.Subject subject = tracker != null
                ? new ConversationInfo.Subject(tracker.systemId(), tracker.key(), tracker.url(), tracker.title())
                : null;
        String key = conversations.key(companyId, subject, handleOf(start));
        String title = title(start);
        ModelAccessChoice access = start.modelAccess();
        String label = access instanceof ModelAccessChoice.Subscription subscription ? subscription.label() : null;
        UUID subscriptionHost = access instanceof ModelAccessChoice.Subscription subscription
                ? subscription.runnerHostId() : null;
        ConversationInfo conversation = new ConversationInfo(id, companyId, start.agentId(), key, title, start.place(),
                ConversationInfo.Origin.TRIGGER, null, subject, start.runtime(), start.payerPrincipalId(), access, label,
                start.workspace(), List.of(), ConversationInfo.State.OPEN, null, null, null,
                ConversationInfo.WaitingOn.NONE, null, null, 0, null, null, null, now, now, start.triggerId(),
                start.authorPrincipalId(), start.chainDepth());
        store.insert(conversation, subscriptionHost, start.startedBy(), placeRef(start));
        store.lock(companyId, id);
        auditLog.record(AuditRecord.company(companyId, AuditAction.CONVERSATION_STARTED, CurrentActor.actor())
            .target(ConversationService.TARGET, id)
            .detail("agentId", start.agentId().toString())
            .detail("triggerId", start.triggerId().toString())
            .detail("authorPrincipalId", start.authorPrincipalId().toString())
            .detail("firingId", start.firingId() != null ? start.firingId().toString() : null)
            .detail("chainDepth", start.chainDepth())
            .detail("key", key)
            .detail("place", start.place().name())
            .detail("origin", ConversationInfo.Origin.TRIGGER.name())
            .detail("subjectSystemId", tracker != null ? tracker.systemId().toString() : null)
            .detail("subjectKey", tracker != null ? tracker.key() : null)
            .detail("payerPrincipalId", start.payerPrincipalId().toString())
            .detail("modelAccess", label != null ? "SUBSCRIPTION" : "BUDGET")
            .detail("runtime", start.runtime().name())
            .build());
        ConversationInfo stored = store.find(companyId, id).orElseThrow().info();
        for (FixedAccount account : start.accounts()) {
            bindings.fixed(stored, account.systemId(), account.accountId(), account.holderPrincipalId(),
                    account.externalId());
        }
        events.publishEvent(new ConversationEvents.ConversationStarted(companyId, id, start.agentId(),
                start.authorPrincipalId()));
        Optional<UUID> runId = turns.request(companyId, id, TurnReason.FIRST);
        turns.refreshWaiting(companyId, id);
        return new Started(store.find(companyId, id).orElseThrow().info(), runId);
    }

    /** The key's handle: the agent's, as a personal conversation's key has it. */
    private String handleOf(NewTriggerConversation start) {
        return jdbc.sql("SELECT handle FROM agent WHERE id = ? AND company_id = ?")
            .params(start.agentId(), start.companyId())
            .query(String.class)
            .optional()
            .orElse("agent");
    }

    /** {@code {trigger}: {key} {title}}, or a schedule's {@code {trigger} {local date}} (§5.7). */
    static String title(NewTriggerConversation start) {
        String title;
        if (start.held() != null) {
            title = start.held().title();
        }
        else if (start.subject() != null) {
            TrackerSubject subject = start.subject();
            title = start.triggerName() + ": " + subject.key() + (subject.title() != null ? " " + subject.title() : "");
        }
        else {
            title = start.triggerName() + " " + Objects.requireNonNull(start.schedule()).localDate();
        }
        return ConversationStore.cut(title.strip(), MAX_TITLE);
    }

    /** The place's reference (V011): the issue's stable id and key, or the schedule's firing. */
    static String placeRef(NewTriggerConversation start) {
        ObjectNode node = JSON.createObjectNode();
        if (start.held() != null) {
            new java.util.TreeMap<>(start.held().placeRef()).forEach(node::put);
        }
        else if (start.subject() != null) {
            TrackerSubject subject = start.subject();
            node.put("systemId", subject.systemId().toString());
            node.put("subjectId", subject.subjectId());
            node.put("key", subject.key());
            node.put("kind", subject.kind());
            if (subject.url() != null) {
                node.put("url", subject.url());
            }
        }
        else {
            ScheduleFiring schedule = Objects.requireNonNull(start.schedule());
            node.put("triggerId", start.triggerId().toString());
            if (schedule.firingId() != null) {
                node.put("firingId", schedule.firingId().toString());
            }
            node.put("dueAt", schedule.dueAt().toString());
        }
        return JSON.writeValueAsString(node);
    }

    // ---- events, comments, posts -----------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> event(UUID companyId, UUID conversationId) {
        ConversationInfo conversation = store.lock(companyId, conversationId).orElseThrow().info();
        if (!conversation.open()) {
            return Optional.empty();
        }
        return turns.requestNext(companyId, conversationId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public External external(UUID companyId, UUID conversationId, ExternalComment comment) {
        ConversationInfo conversation = store.lock(companyId, conversationId).orElseThrow().info();
        Optional<UUID> stored = store.externalMessage(companyId, conversationId, comment.externalId());
        if (stored.isPresent()) {
            return new External(stored.get(), false, 0, Optional.empty());
        }
        UUID id = Ids.newId();
        boolean answers = comment.answers() && conversation.open()
                && decisions.open(companyId, conversationId).stream().anyMatch(open -> "QUESTION".equals(open.kind()));
        String body = comment.body().isBlank() ? "(empty)"
                : ConversationStore.cut(comment.body(), ConversationMessagesImpl.MAX_BODY);
        ObjectNode author = JSON.createObjectNode();
        comment.author().forEach(author::put);
        store.insertExternal(id, companyId, conversationId, comment.authorPrincipalId(), body, comment.externalId(),
                JSON.writeValueAsString(author), answers, comment.at());
        store.touch(companyId, conversationId, time.now());
        int answered = 0;
        Optional<UUID> runId = Optional.empty();
        if (answers) {
            String name = comment.author().getOrDefault("name", comment.author().getOrDefault("login", "someone"));
            answered = decisionOps.answerAll(conversation, id, body, comment.authorPrincipalId(), name);
            if (answered > 0 && decisions.open(companyId, conversationId).isEmpty()) {
                runId = turns.request(companyId, conversationId, TurnReason.DECIDED);
            }
        }
        turns.refreshWaiting(companyId, conversationId);
        return new External(id, true, answered, runId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID post(UUID companyId, UUID conversationId, String body) {
        store.lock(companyId, conversationId);
        return messages.system(companyId, conversationId, body, null);
    }

    // ---- end, cancel -----------------------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void end(UUID companyId, UUID conversationId, ConversationInfo.EndedReason reason,
            @Nullable UUID endedBy) {
        ConversationInfo conversation = store.lock(companyId, conversationId).orElseThrow().info();
        if (!conversation.open()) {
            return;
        }
        // A queued turn is cancelled; a running one finishes and its result is recorded (§5.7).
        runs.activeOf(companyId, conversationId)
            .filter(run -> run.status() == RunStatus.QUEUED)
            .ifPresent(run -> runTurns.cancelActive(companyId, conversationId));
        conversations.end(store.lock(companyId, conversationId).orElseThrow().info(), reason, endedBy);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> cancelTurn(UUID companyId, UUID conversationId) {
        ConversationInfo conversation = store.lock(companyId, conversationId).orElseThrow().info();
        if (!conversation.open()) {
            return Optional.empty();
        }
        return runTurns.cancelActive(companyId, conversationId);
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ConversationInfo> openOnSubject(UUID companyId, UUID triggerId, String subjectId) {
        return jdbc.sql("""
                SELECT id FROM conversation
                 WHERE company_id = ? AND trigger_id = ? AND state = 'OPEN' AND place = 'TRACKER_ISSUE'
                   AND place_ref ->> 'subjectId' = ?""")
            .params(companyId, triggerId, subjectId)
            .query(UUID.class)
            .optional()
            .flatMap(id -> store.find(companyId, id))
            .map(ConversationStore.ConversationRow::info);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ConversationInfo> openOnWebhook(UUID companyId, UUID triggerId, @Nullable String subjectKey) {
        return jdbc.sql("SELECT id FROM conversation WHERE company_id = ? AND trigger_id = ? AND state = 'OPEN'"
                + " AND place = 'WEBHOOK' AND " + (subjectKey != null ? "place_ref ->> 'subjectKey' = ?"
                        : "place_ref ->> 'subjectKey' IS NULL") + " ORDER BY id")
            .params(subjectKey != null ? List.of(companyId, triggerId, subjectKey) : List.of(companyId, triggerId))
            .query(UUID.class)
            .list()
            .stream()
            .map(id -> store.find(companyId, id).orElseThrow().info())
            .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ConversationInfo> openOf(UUID companyId, UUID triggerId) {
        return jdbc.sql("SELECT id FROM conversation WHERE company_id = ? AND trigger_id = ? AND state = 'OPEN'"
                + " ORDER BY id")
            .params(companyId, triggerId)
            .query(UUID.class)
            .list()
            .stream()
            .map(id -> store.find(companyId, id).orElseThrow().info())
            .toList();
    }

    @Override
    public List<UUID> allOf(UUID companyId, UUID triggerId) {
        return jdbc.sql("SELECT id FROM conversation WHERE company_id = ? AND trigger_id = ? ORDER BY id")
            .params(companyId, triggerId)
            .query(UUID.class)
            .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean busy(UUID companyId, UUID conversationId) {
        ConversationInfo conversation = store.find(companyId, conversationId).orElseThrow().info();
        if (!conversation.open()) {
            return false;
        }
        return runs.activeOf(companyId, conversationId).isPresent()
                || !decisions.open(companyId, conversationId).isEmpty()
                || bindingStore.ofConversation(companyId, conversationId).stream()
                    .anyMatch(row -> row.info().waiting());
    }

    // ---- decisions -------------------------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID raiseResultReview(UUID companyId, UUID conversationId, UUID runId, String summary,
            List<ReviewWrite> writes) {
        ConversationInfo conversation = store.lock(companyId, conversationId).orElseThrow().info();
        if (!conversation.triggered()) {
            throw new IllegalArgumentException("Only a trigger's conversation's result is reviewed");
        }
        StringBuilder card = new StringBuilder("Review the result before Tinfirm writes it:");
        writes.forEach(write -> card.append("\n\n").append(write.text().strip()));
        DecisionOps.Raised raised = decisionOps.approval(conversation, runId, null,
                DecisionSubject.result(runId, summary, writes), null, false,
                ConversationStore.cut(card.toString(), ConversationMessagesImpl.MAX_BODY));
        turns.refreshWaiting(companyId, conversationId);
        return raised.decision().id();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Question> openQuestionsOf(UUID companyId, UUID runId) {
        return jdbc.sql("""
                SELECT id FROM decision WHERE company_id = ? AND run_id = ? AND kind = 'QUESTION' AND status = 'OPEN'
                 ORDER BY created_at, id""")
            .params(companyId, runId)
            .query(UUID.class)
            .list()
            .stream()
            .map(id -> decisionStore.find(id).orElseThrow())
            .map(row -> new Question(row.id(), Objects.requireNonNullElse(row.question(), ""), row.options()))
            .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RunOutput> outputsOf(UUID companyId, UUID runId) {
        RunInfo run = runs.find(companyId, runId).orElse(null);
        if (run == null) {
            return List.of();
        }
        return outputs.outputs(companyId, run.conversationId())
            .stream()
            .filter(output -> runId.equals(output.runId()))
            .map(output -> new RunOutput(output.id(), output.kind().name(), output.title(), output.url(),
                    output.attachmentId()))
            .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Answer> answerOf(UUID companyId, UUID decisionId) {
        DecisionStore.DecisionRow decision = decisionStore.find(decisionId)
            .filter(row -> row.companyId().equals(companyId))
            .orElse(null);
        if (decision == null || decision.kind() != DecisionStore.Kind.QUESTION
                || decision.outcome() != DecisionStore.Outcome.ANSWERED) {
            return Optional.empty();
        }
        String answer = decision.answer() != null && !decision.answer().isBlank() ? decision.answer()
                : decision.option();
        if (answer == null || answer.isBlank()) {
            return Optional.empty();
        }
        List<String> questions = new ArrayList<>();
        if (decision.decidedByMessageId() != null) {
            jdbc.sql("""
                    SELECT id FROM decision
                     WHERE company_id = ? AND conversation_id = ? AND decided_by_message_id = ? AND kind = 'QUESTION'
                     ORDER BY created_at, id""")
                .params(companyId, decision.conversationId(), decision.decidedByMessageId())
                .query(UUID.class)
                .list()
                .forEach(id -> decisionStore.find(id).map(DecisionStore.DecisionRow::question)
                    .ifPresent(questions::add));
        }
        else if (decision.question() != null) {
            questions.add(decision.question());
        }
        return Optional.of(new Answer(decision.id(), questions, answer, decision.decidedByPrincipalId(),
                decision.decidedByName(), decision.decidedByMessageId()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Document> document(UUID companyId, UUID conversationId, UUID attachmentId) {
        return attachments.find(conversationId, attachmentId)
            .filter(row -> row.companyId().equals(companyId))
            .flatMap(row -> attachments.content(conversationId, attachmentId)
                .map(content -> new Document(row.id(), row.fileName(), row.contentType(), row.sizeBytes(),
                        content)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void approversChanged(UUID companyId, UUID triggerId, @Nullable UUID teamGroupId,
            UUID authorPrincipalId) {
        List<ConversationInfo> open = openOf(companyId, triggerId);
        open.forEach(conversation -> store.lock(companyId, conversation.id()));
        List<UUID> ids = open.stream().map(ConversationInfo::id).toList();
        if (ids.isEmpty()) {
            return;
        }
        java.util.Map<UUID, ConversationInfo> byId = open.stream()
            .collect(java.util.stream.Collectors.toMap(ConversationInfo::id, conversation -> conversation));
        for (DecisionStore.DecisionRow decision : decisionStore.lockOpenApprovalsOf(ids)) {
            // The new team or author, or the Owners and Admins when they are only Viewers (ADR-0077, decision 5).
            decisionOps.reroute(decision, decisionOps.approverOf(byId.get(decision.conversationId()),
                    DecisionStore.Kind.APPROVAL, teamGroupId, authorPrincipalId));
        }
    }

    // ---- rebind (§11, open point 6) --------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Rebound> rebind(UUID companyId, UUID conversationId) {
        ConversationInfo conversation = store.lock(companyId, conversationId).orElseThrow().info();
        if (!conversation.open() || !conversation.triggered()) {
            return Optional.empty();
        }
        Optional<RunInfo> active = runs.activeOf(companyId, conversationId);
        if (active.isPresent()) {
            return Optional.empty();
        }
        String result = jdbc.sql("SELECT conversation_rebind(?)::text")
            .param(conversationId)
            .query(String.class)
            .single();
        JsonNode changed = JSON.readTree(result);
        JsonNode author = changed.path("author");
        JsonNode payer = changed.path("payer");
        JsonNode access = changed.path("modelAccess");
        if (author.path("from").asString("").equals(author.path("to").asString(""))
                && payer.path("from").asString("").equals(payer.path("to").asString(""))
                && access.path("from").asString("").equals(access.path("to").asString(""))
                && changed.path("systems").isEmpty()) {
            return Optional.empty();
        }
        List<Map<String, @Nullable Object>> systems = new ArrayList<>();
        for (JsonNode system : changed.path("systems")) {
            Map<String, @Nullable Object> entry = new LinkedHashMap<>();
            system.properties().forEach(field -> entry.put(field.getKey(),
                    field.getValue().isNull() ? null : field.getValue().asString()));
            systems.add(entry);
        }
        Rebound rebound = new Rebound(conversationId, UUID.fromString(author.path("from").asString()),
                UUID.fromString(author.path("to").asString()), UUID.fromString(payer.path("from").asString()),
                UUID.fromString(payer.path("to").asString()), access.path("from").asString(""),
                access.path("to").asString(""), systems);
        auditLog.record(AuditRecord.company(companyId, AuditAction.CONVERSATION_REBOUND, CurrentActor.actor())
            .target(ConversationService.TARGET, conversationId)
            .detail("triggerId", Objects.requireNonNull(conversation.triggerId()).toString())
            .detail("fromAuthorPrincipalId", rebound.fromAuthor().toString())
            .detail("toAuthorPrincipalId", rebound.toAuthor().toString())
            .detail("fromPayerPrincipalId", rebound.fromPayer().toString())
            .detail("toPayerPrincipalId", rebound.toPayer().toString())
            .detail("fromModelAccess", rebound.fromModelAccess())
            .detail("toModelAccess", rebound.toModelAccess())
            .detail("systems", systems)
            .build());
        Map<UUID, String> names = principals.displayNames(companyId,
                List.of(rebound.toAuthor(), rebound.toPayer()));
        String newAuthor = names.getOrDefault(rebound.toAuthor(), "the trigger's new author");
        StringBuilder note = new StringBuilder("From now on, ").append(newAuthor)
            .append("'s choices act and pay: paid by ")
            .append(names.getOrDefault(rebound.toPayer(), "the trigger's payer"));
        long gone = systems.stream().filter(system -> "GONE".equals(system.get("state"))).count();
        if (gone > 0) {
            note.append("; ").append(gone).append(gone == 1 ? " system is" : " systems are")
                .append(" no longer available to this conversation");
        }
        String posted = note.append('.').toString();
        messages.system(companyId, conversationId, posted, null);
        events.publishEvent(new ConversationEvents.ConversationRebound(companyId, conversationId,
                conversation.triggerId(), posted));
        turns.refreshWaiting(companyId, conversationId);
        return Optional.of(rebound);
    }

    /** Conversations by id, the lock order. */
    static List<ConversationInfo> inLockOrder(List<ConversationInfo> conversations) {
        return conversations.stream().sorted(Comparator.comparing(ConversationInfo::id)).toList();
    }
}
