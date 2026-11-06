package com.flowtivo.tinfirm.conversation.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.PersonalConversations;
import com.flowtivo.tinfirm.conversation.PlaceConversations;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * {@link PersonalConversations} and {@link PlaceConversations} (the package 5 contract, §2.2, §5.6): facades over the
 * conversation module's own seams, each in the caller's transaction, each locking the conversation first
 * (CONVENTIONS §10).
 */
@Component
class PlaceConversationsImpl implements PersonalConversations, PlaceConversations {

    private final ConversationService conversations;

    private final ConversationStore store;

    private final PlaceStore places;

    private final TurnService turns;

    private final DecisionReads decisions;

    private final ObjectProvider<QuestionAnswers> questionAnswers;

    private final ConversationMessagesImpl messages;

    private final OutputStore outputs;

    private final AttachmentStore attachments;

    private final RunReads runs;

    private final Principals principals;

    PlaceConversationsImpl(ConversationService conversations, ConversationStore store, PlaceStore places,
            TurnService turns, DecisionReads decisions, ObjectProvider<QuestionAnswers> questionAnswers,
            ConversationMessagesImpl messages, OutputStore outputs, AttachmentStore attachments, RunReads runs,
            Principals principals) {
        this.conversations = conversations;
        this.store = store;
        this.places = places;
        this.turns = turns;
        this.decisions = decisions;
        this.questionAnswers = questionAnswers;
        this.messages = messages;
        this.outputs = outputs;
        this.attachments = attachments;
        this.runs = runs;
        this.principals = principals;
    }

    // ---- PersonalConversations -------------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Started start(UUID companyId, UUID personPrincipalId, UUID agentId, FirstMessage firstMessage,
            ConversationInfo.Place place, ConversationInfo.Origin origin, @Nullable Map<String, String> placeRef) {
        PrincipalInfo person = principals.find(companyId, personPrincipalId)
            .filter(PrincipalInfo::member)
            .filter(PrincipalInfo::active)
            .orElseThrow(() -> new StartRefused("NOT_FOUND", "You are not a member of this company any more", null));
        Map<String, Object> audit = new java.util.LinkedHashMap<>();
        if (placeRef != null) {
            for (String key : List.of("systemId", "channelId", "threadId")) {
                if (placeRef.get(key) != null) {
                    audit.put(key, placeRef.get(key));
                }
            }
        }
        if (firstMessage.externalAuthor() != null && firstMessage.externalAuthor().get("id") != null) {
            audit.put("chatUserId", String.valueOf(firstMessage.externalAuthor().get("id")));
        }
        try {
            ConversationService.StartedTurn started = conversations.startFor(new ConversationService.StartInput(
                    companyId, person.id(), java.util.Objects.requireNonNull(person.userId()), agentId,
                    firstMessage.body().strip(), firstMessage.externalId(), firstMessage.externalAuthor(), null, null,
                    null, List.of(), place, origin, placeRef, audit));
            return new Started(started.conversationId(), started.key(), started.runId());
        }
        catch (ApiException ex) {
            Object details = ex.properties().get("details");
            String reason = details instanceof Map<?, ?> map && map.get("reason") != null
                    ? String.valueOf(map.get("reason")) : null;
            throw new StartRefused(ex.code() != null ? ex.code().name() : ex.status().name(), ex.getMessage(),
                    reason);
        }
    }

    // ---- PlaceConversations ----------------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Received receive(UUID companyId, UUID conversationId, PlaceMessage message) {
        ConversationInfo conversation = store.lock(companyId, conversationId).orElseThrow().info();
        if (!conversation.open()) {
            return new Ended();
        }
        Optional<UUID> known = places.messageOf(conversationId, message.externalId());
        if (known.isPresent()) {
            return new Duplicate(known.get());
        }
        boolean answers = message.answersOpenQuestions() && !decisions.open(companyId, conversationId).isEmpty();
        String body = message.body().strip();
        Optional<UUID> stored = places.insertMessage(companyId, conversationId, message.kind().name(),
                message.authorPrincipalId(), body, answers, message.externalId(), message.externalAuthor());
        if (stored.isEmpty()) {
            return new Duplicate(places.messageOf(conversationId, message.externalId()).orElseThrow());
        }
        UUID messageId = stored.get();
        int answered = 0;
        if (answers) {
            UUID answerer = message.authorPrincipalId() != null ? message.authorPrincipalId()
                    : conversation.personPrincipalId();
            QuestionAnswers answering = questionAnswers.getIfAvailable(() -> QuestionAnswers.NONE);
            if (message.kind() == Kind.EXTERNAL) {
                // A channel's mention (package 5b): decided from the chat, by its author's name there (§5.8).
                Object name = message.externalAuthor().get("name");
                Object via = message.externalAuthor().get("via");
                Object id = message.externalAuthor().get("id");
                String named = name != null ? String.valueOf(name) : null;
                PlaceDeciding.Context context = new PlaceDeciding.Context("CHAT", via != null ? String.valueOf(via)
                        : "CHAT", id != null ? String.valueOf(id) : message.externalId(), named != null ? named : "");
                answered = PlaceDeciding.with(context, () -> answering.answerAll(conversation, messageId, body,
                        answerer, named));
            }
            else {
                answered = answering.answerAll(conversation, messageId, body, answerer);
            }
        }
        UUID runId = null;
        if (decisions.open(companyId, conversationId).isEmpty()) {
            runId = turns.request(companyId, conversationId, answered > 0 ? TurnReason.DECIDED : TurnReason.MESSAGE)
                .orElse(null);
        }
        turns.refreshWaiting(companyId, conversationId);
        return new Stored(messageId, answered, runId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID system(UUID companyId, UUID conversationId, String body) {
        store.lock(companyId, conversationId).orElseThrow();
        return messages.system(companyId, conversationId, body, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean end(UUID companyId, UUID conversationId, ConversationInfo.EndedReason reason,
            @Nullable UUID endedByUserId) {
        ConversationInfo conversation = store.lock(companyId, conversationId).orElseThrow().info();
        if (!conversation.open()) {
            return false;
        }
        conversations.end(conversation, reason, endedByUserId);
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TurnResult> result(UUID companyId, UUID runId) {
        RunInfo run = runs.find(companyId, runId).orElse(null);
        if (run == null) {
            return Optional.empty();
        }
        List<Output> made = new ArrayList<>();
        for (OutputStore.OutputRow output : outputs.ofConversation(run.conversationId())) {
            if (!runId.equals(output.runId())) {
                continue;
            }
            Long size = output.attachmentId() != null
                    ? attachments.find(run.conversationId(), output.attachmentId())
                        .map(AttachmentStore.AttachmentRow::sizeBytes)
                        .orElse(null)
                    : null;
            made.add(new Output(output.id(), output.kind().name(), output.title(), output.url(),
                    output.attachmentId(), size));
        }
        return Optional.of(new TurnResult(run.conversationId(), runId, run.status().name(),
                places.agentMessage(runId).orElse(null), run.failureClass(), run.failureMessage(), made));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Map<String, String>> placeRef(UUID companyId, UUID conversationId) {
        return store.find(companyId, conversationId).flatMap(row -> places.placeRef(conversationId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ConversationInfo> ofThread(UUID companyId, UUID systemId, String channelId, String threadId) {
        return places.ofThread(companyId, systemId, channelId, threadId)
            .flatMap(id -> store.find(companyId, id))
            .map(ConversationStore.ConversationRow::info);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationInfo> openInChat(UUID companyId, UUID personPrincipalId, UUID systemId) {
        return places.openInChat(companyId, personPrincipalId, systemId)
            .stream()
            .map(id -> store.find(companyId, id))
            .flatMap(Optional::stream)
            .map(ConversationStore.ConversationRow::info)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> openOnSystem(UUID companyId, UUID systemId) {
        return places.openOnSystem(companyId, systemId);
    }
}
