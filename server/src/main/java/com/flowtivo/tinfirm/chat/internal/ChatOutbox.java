package com.flowtivo.tinfirm.chat.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.conversation.PlaceConversations;
import com.flowtivo.tinfirm.conversation.PlaceDecisions;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.trigger.OutboxCause;
import com.flowtivo.tinfirm.trigger.OutboxKind;
import com.flowtivo.tinfirm.trigger.PlaceOutbox;

/**
 * What a chat thread's conversation sends back to its thread (the package 5 contract, §5.9, C12): rows of package 4's
 * outbox ({@link PlaceOutbox}), queued in the transaction that commits their cause, for every {@code CHAT_THREAD}
 * conversation, a person's direct message and a channel trigger's; {@link ChatPlaceWriter} delivers them with the
 * system's bot, in order per conversation. So an answer survives a restart, waits while its trigger is paused, its
 * system paused or the company stopped, and waits for a usable bot at most 7 days.
 * <table>
 * <caption>Causes and rows</caption>
 * <tr><th>Cause</th><th>On</th><th>Row</th></tr>
 * <tr><td>{@code RECEIVED}</td><td>a message taken (a direct message, a mention)</td><td>{@code CHAT_REACTION}:
 * Slack's {@code eyes}, Teams' typing</td></tr>
 * <tr><td>{@code NOTICE}</td><td>a chat user's first conversation; a result under review</td><td>{@code CHAT_POST}</td></tr>
 * <tr><td>{@code RESULT}</td><td>{@code TurnRecorded} {@code SUCCEEDED}, after the trigger's review when it asks
 * one</td><td>{@code CHAT_POST} of the turn's answer and links; a {@code CHAT_FILE} per document (Slack)</td></tr>
 * <tr><td>{@code FAILURE}</td><td>{@code TurnRecorded} {@code FAILED} or {@code LOST}, not while it is retried by itself
 * (ADR-0073)</td><td>{@code CHAT_POST}</td></tr>
 * <tr><td>{@code QUESTION}, {@code APPROVAL}</td><td>{@code DecisionRaised} (not a result's review, which is the
 * app's)</td><td>{@code CHAT_POST}, its buttons added at delivery</td></tr>
 * <tr><td>{@code DECIDED}, {@code ANSWER}</td><td>{@code DecisionClosed}</td><td>{@code CHAT_UPDATE} of the card;
 * {@code CHAT_POST} of an answer given in Tinfirm</td></tr>
 * <tr><td>{@code CONNECT}, {@code CONNECTED}</td><td>{@code BindingWaiting}, {@code BindingBound} of a direct
 * message</td><td>{@code CHAT_POST} of a Connect card; {@code CHAT_UPDATE} of it</td></tr>
 * </table>
 * A turn that ended on its own questions posts only its questions (ADR-0036); a cancelled turn posts nothing.
 */
@Component
class ChatOutbox {

    /** The most option buttons of a question card; more are listed (§5.8). */
    static final int OPTION_BUTTONS = 5;

    /** The most a post's body holds ({@code outbox_shape_ck}); longer is cut, the whole read in Tinfirm. */
    static final int MAX_BODY = 40_000;

    private final PlaceOutbox outbox;

    private final Conversations conversations;

    private final PlaceConversations places;

    private final PlaceDecisions decisions;

    private final Agents agents;

    private final Systems systems;

    private final Principals principals;

    private final TinfirmProperties tinfirm;

    ChatOutbox(PlaceOutbox outbox, Conversations conversations, PlaceConversations places, PlaceDecisions decisions,
            Agents agents, Systems systems, Principals principals, TinfirmProperties tinfirm) {
        this.outbox = outbox;
        this.conversations = conversations;
        this.places = places;
        this.decisions = decisions;
        this.agents = agents;
        this.systems = systems;
        this.principals = principals;
        this.tinfirm = tinfirm;
    }

    /** A chat thread's conversation: its system, kind ({@code DM}, {@code CHANNEL}), thread and agent. */
    record Thread(ConversationInfo conversation, UUID systemId, boolean slack, boolean channel, String subjectKey,
            String agentName, String page) {

        UUID companyId() {
            return conversation.companyId();
        }
    }

    /** The conversation, when a chat thread holds it; in the caller's transaction. */
    Optional<Thread> thread(UUID companyId, UUID conversationId) {
        ConversationInfo conversation = conversations.find(companyId, conversationId).orElse(null);
        if (conversation == null || conversation.place() != ConversationInfo.Place.CHAT_THREAD) {
            return Optional.empty();
        }
        Map<String, String> ref = places.placeRef(companyId, conversationId).orElse(Map.of());
        String systemId = ref.get("systemId");
        SystemInfo system = systemId != null ? systems.find(companyId, UUID.fromString(systemId)).orElse(null) : null;
        if (system == null) {
            return Optional.empty();
        }
        String agent = agents.find(companyId, conversation.agentId()).map(AgentInfo::name).orElse("An agent");
        return Optional.of(new Thread(conversation, system.id(), system.kind() == SystemKind.SLACK,
                "CHANNEL".equals(ref.get("kind")), ref.getOrDefault("channelId", "") + ":"
                        + ref.getOrDefault("threadId", ""), agent, page(tinfirm, companyId, conversationId)));
    }

    // ---- what the conversation module announces ---------------------------------------------------------------------

    @EventListener
    void turnRecorded(ConversationEvents.TurnRecorded event) {
        // A failure retried by itself is posted only once the automatic retries are spent (ADR-0073).
        if (event.place() != ConversationInfo.Place.CHAT_THREAD || event.status() == RunStatus.CANCELLED
                || event.retrying()) {
            return;
        }
        Thread thread = thread(event.companyId(), event.conversationId()).orElse(null);
        PlaceConversations.TurnResult result = thread != null ? places.result(event.companyId(), event.runId())
            .orElse(null) : null;
        if (thread == null || result == null) {
            return;
        }
        switch (result.status()) {
            case "SUCCEEDED" -> succeeded(thread, result);
            case "FAILED", "LOST" -> queue(thread, PlaceOutbox.Entry.post(thread.systemId(), thread.subjectKey(),
                    OutboxCause.FAILURE, "FAILURE:" + result.runId(), thread.agentName() + " could not finish this ("
                            + (result.failureClass() != null ? result.failureClass() : result.status()) + "). "
                            + (thread.channel() ? "Its team was told in Tinfirm." : "Open it in Tinfirm to retry it."))
                .withRun(result.runId()));
            default -> {
            }
        }
    }

    /** The answer, its links and its documents; or, when the trigger reviews results, the review instead (T14). */
    private void succeeded(Thread thread, PlaceConversations.TurnResult result) {
        List<String> links = new ArrayList<>();
        List<PlaceConversations.Output> documents = new ArrayList<>();
        for (PlaceConversations.Output output : result.outputs()) {
            if ("DOCUMENT".equals(output.kind()) && output.attachmentId() != null) {
                documents.add(output);
            }
            else if (output.url() != null) {
                links.add("- [" + output.title() + "](" + output.url() + ")");
            }
        }
        if (!thread.slack()) {
            for (PlaceConversations.Output document : documents) {
                links.add("- " + document.title() + " (in Tinfirm: " + thread.page() + ")");
            }
        }
        String body = result.agentMessage();
        if (body == null && links.isEmpty() && documents.isEmpty()) {
            // A turn that ended on its own questions: their cards say what it asks (ADR-0036).
            return;
        }
        StringBuilder text = new StringBuilder(body != null ? body : "Done.");
        if (!links.isEmpty()) {
            text.append("\n\n**Outputs**\n").append(String.join("\n", links));
        }
        List<PlaceOutbox.Entry> entries = new ArrayList<>();
        entries.add(PlaceOutbox.Entry.post(thread.systemId(), thread.subjectKey(), OutboxCause.RESULT,
                "RESULT:" + result.runId(), cut(text.toString())).withRun(result.runId()));
        List<TriggerConversations.ReviewWrite> writes = new ArrayList<>(List.of(new TriggerConversations.ReviewWrite(
                OutboxKind.CHAT_POST.name(), thread.subjectKey(), null, "The answer, posted in the thread")));
        if (thread.slack()) {
            for (PlaceConversations.Output document : documents) {
                entries.add(new PlaceOutbox.Entry(thread.systemId(), thread.subjectKey(), OutboxKind.CHAT_FILE,
                        OutboxCause.RESULT, "FILE:" + document.id(), result.runId(), null, document.id(),
                        document.attachmentId(), null, null, document.title(), null, null));
                writes.add(new TriggerConversations.ReviewWrite(OutboxKind.CHAT_FILE.name(), thread.subjectKey(),
                        document.title(), "The document " + document.title() + ", uploaded to the thread"));
            }
        }
        UUID companyId = thread.companyId();
        if (outbox.reviewsResults(companyId, thread.conversation().id())) {
            outbox.review(companyId, thread.conversation().id(), result.runId(), body != null ? body : "", writes,
                    entries);
            queue(thread, PlaceOutbox.Entry.post(thread.systemId(), thread.subjectKey(), OutboxCause.NOTICE,
                    "REVIEW:" + result.runId(), thread.agentName() + "'s answer waits for its team's review in "
                            + "Tinfirm.").withRun(result.runId()));
            return;
        }
        entries.forEach(entry -> queue(thread, entry));
    }

    /** {@code QUESTION}, {@code APPROVAL}: the card's text; its buttons are the decision's at delivery. */
    @EventListener
    void decisionRaised(ConversationEvents.DecisionRaised event) {
        Thread thread = thread(event.companyId(), event.conversationId()).orElse(null);
        PlaceDecisions.Card card = thread != null ? decisions.find(event.companyId(), event.decisionId()).orElse(null)
                : null;
        if (thread == null || card == null || !"OPEN".equals(card.status()) || card.review()) {
            return;
        }
        boolean question = "QUESTION".equals(card.kind());
        String text;
        if (question) {
            StringBuilder body = new StringBuilder("**" + thread.agentName() + " asks:** " + card.question());
            List<String> options = card.options();
            for (int i = OPTION_BUTTONS; i < options.size(); i++) {
                body.append(i == OPTION_BUTTONS ? "\n\nAlso:" : "").append("\n- ").append(options.get(i));
            }
            body.append(thread.channel() ? "\n\nAnswer with a button, or mention me in this thread with your answer."
                    : "\n\nAnswer with a button, or reply here in your own words.");
            text = body.toString();
        }
        else {
            text = "**" + thread.agentName() + " asks to " + (card.what() != null ? card.what() : "go on") + ".**"
                    + (card.reason() != null ? "\n\n" + card.reason() : "")
                    + (thread.channel() ? "\n\nIts team approves or denies it here, or in Tinfirm with its details."
                            : "\n\nApprove or deny it here, or in Tinfirm with its details.");
        }
        queue(thread, PlaceOutbox.Entry.post(thread.systemId(), thread.subjectKey(), question ? OutboxCause.QUESTION
                : OutboxCause.APPROVAL, card(event.decisionId()), cut(text)).withDecision(event.decisionId())
            .withUrl(thread.page()));
    }

    /** {@code DECIDED}: the card updated, without its buttons; {@code ANSWER}: an answer given in the app. */
    @EventListener
    void decisionClosed(ConversationEvents.DecisionClosed event) {
        Thread thread = thread(event.companyId(), event.conversationId()).orElse(null);
        PlaceDecisions.Card card = thread != null ? decisions.find(event.companyId(), event.decisionId()).orElse(null)
                : null;
        if (thread == null || card == null || card.review()) {
            return;
        }
        UUID companyId = thread.companyId();
        String where = "CHAT".equals(event.via()) ? " in " + (thread.slack() ? "Slack" : "Teams") : " in Tinfirm";
        String name = decider(companyId, card);
        String by = name != null ? " by " + name : "";
        if (outbox.queued(companyId, thread.conversation().id(), card(event.decisionId())) > 0) {
            String text = switch (event.outcome()) {
                case "APPROVED" -> "Approved" + by + where + ": " + card.what() + ".";
                case "DENIED" -> "Denied" + by + where + ": " + card.what() + ".";
                case "ANSWERED" -> "**" + thread.agentName() + " asked:** " + card.question() + "\n\nAnswered" + by
                        + where + ".";
                default -> "This was cancelled.";
            };
            queue(thread, new PlaceOutbox.Entry(thread.systemId(), thread.subjectKey(), OutboxKind.CHAT_UPDATE,
                    OutboxCause.DECIDED, "DECIDED:" + event.decisionId(), null, event.decisionId(), null, null, null,
                    cut(text), null, null, card(event.decisionId())));
        }
        if ("APP".equals(event.via()) && "ANSWERED".equals(event.outcome()) && card.answer() != null) {
            queue(thread, PlaceOutbox.Entry.post(thread.systemId(), thread.subjectKey(), OutboxCause.ANSWER,
                    "ANSWER:" + event.decisionId(), cut("*" + (name != null ? name : "A member")
                            + " answered in Tinfirm:* " + card.answer())).withDecision(event.decisionId()));
        }
    }

    /** Who decided: their name in the chat, else the member's. */
    private @Nullable String decider(UUID companyId, PlaceDecisions.Card card) {
        if (card.decidedByName() != null) {
            return card.decidedByName();
        }
        if (card.decidedByPrincipalId() == null) {
            return null;
        }
        return principals.displayNames(companyId, List.of(card.decidedByPrincipalId()))
            .get(card.decidedByPrincipalId());
    }

    /**
     * {@code CONNECT}: in a direct message, a card that opens the conversation in the app, where its account is
     * connected (§5.6). A channel's conversation binds its trigger's table only, whose break pauses the trigger.
     */
    @EventListener
    void bindingWaiting(ConversationEvents.BindingWaiting event) {
        Thread thread = thread(event.companyId(), event.conversationId()).orElse(null);
        if (thread == null || thread.channel()) {
            return;
        }
        UUID companyId = thread.companyId();
        String system = systems.find(companyId, event.systemId()).map(SystemInfo::name).orElse("a system");
        boolean admin = "RECONNECT_COMPANY".equals(event.reason().name()) || "SUSPENDED".equals(event.reason().name());
        String text = thread.agentName() + " needs " + system + ": "
                + (admin ? "an admin of your company has to reconnect its account first."
                        : "connect it in Tinfirm to go on.");
        int earlier = outbox.queued(companyId, thread.conversation().id(), "CONNECT:" + event.bindingId() + ":");
        PlaceOutbox.Entry entry = PlaceOutbox.Entry.post(thread.systemId(), thread.subjectKey(), OutboxCause.CONNECT,
                "CONNECT:" + event.bindingId() + ":" + (earlier + 1), text);
        queue(thread, admin ? entry : entry.withUrl(thread.page() + "?binding=" + event.systemId()));
    }

    /** {@code CONNECTED}: the binding's last Connect card updated. */
    @EventListener
    void bindingBound(ConversationEvents.BindingBound event) {
        Thread thread = thread(event.companyId(), event.conversationId()).orElse(null);
        if (thread == null || thread.channel()) {
            return;
        }
        int cards = outbox.queued(thread.companyId(), thread.conversation().id(), "CONNECT:" + event.bindingId() + ":");
        if (cards == 0) {
            return;
        }
        queue(thread, new PlaceOutbox.Entry(thread.systemId(), thread.subjectKey(), OutboxKind.CHAT_UPDATE,
                OutboxCause.CONNECTED, "CONNECTED:" + event.bindingId() + ":" + cards, null, null, null, null, null,
                "Connected, continuing.", null, null, "CONNECT:" + event.bindingId() + ":" + cards));
    }

    /**
     * A reassigned trigger's conversation was rebound on the resume (§5.14): its note is posted to the thread too
     * ({@code NOTICE}).
     */
    @EventListener
    void rebound(ConversationEvents.ConversationRebound event) {
        Thread thread = thread(event.companyId(), event.conversationId()).orElse(null);
        if (thread == null) {
            return;
        }
        int earlier = outbox.queued(thread.companyId(), thread.conversation().id(), "NOTICE:REBOUND:");
        notice(thread.companyId(), thread.conversation().id(), "REBOUND:" + (earlier + 1), event.note());
    }

    // ---- what the chat module queues itself ----------------------------------------------------------------------

    /**
     * {@code RECEIVED}: the message was taken, in its transaction: Slack's {@code eyes} on it, Teams' typing.
     *
     * @param messageId the message's id at the platform (Slack's {@code ts}, a Teams activity id)
     */
    void received(UUID companyId, UUID conversationId, String messageId) {
        thread(companyId, conversationId).ifPresent(thread -> queue(thread, new PlaceOutbox.Entry(thread.systemId(),
                thread.subjectKey(), OutboxKind.CHAT_REACTION, OutboxCause.RECEIVED, "RECEIVED:" + messageId, null,
                null, null, null, null, null, messageId, null, null)));
    }

    /** {@code NOTICE}: Tinfirm's note in the thread, once per key, in the caller's transaction. */
    void notice(UUID companyId, UUID conversationId, String key, String text) {
        thread(companyId, conversationId).ifPresent(thread -> queue(thread, PlaceOutbox.Entry.post(thread.systemId(),
                thread.subjectKey(), OutboxCause.NOTICE, "NOTICE:" + key, cut(text))));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private void queue(Thread thread, PlaceOutbox.Entry entry) {
        outbox.queue(thread.companyId(), thread.conversation().id(), entry);
    }

    /** A card's cause key, which its update names. */
    static String card(UUID decisionId) {
        return "CARD:" + decisionId;
    }

    static String cut(String text) {
        return text.length() <= MAX_BODY ? text : text.substring(0, MAX_BODY - 1) + "…";
    }

    static String page(TinfirmProperties tinfirm, UUID companyId, UUID conversationId) {
        String base = tinfirm.publicUrl().toString();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/c/" + companyId
                + "/conversations/" + conversationId;
    }
}
