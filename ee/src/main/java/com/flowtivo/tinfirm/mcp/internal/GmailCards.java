package com.flowtivo.tinfirm.mcp.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.AgentSummary;
import com.flowtivo.tinfirm.shared.api.model.Conversation;
import com.flowtivo.tinfirm.shared.api.model.ConversationMessage;
import com.flowtivo.tinfirm.shared.api.model.ConversationState;
import com.flowtivo.tinfirm.shared.api.model.ConversationTurnStatus;
import com.flowtivo.tinfirm.shared.api.model.ConversationWaitingOn;
import com.flowtivo.tinfirm.shared.api.model.DecisionKind;
import com.flowtivo.tinfirm.shared.api.model.DecisionStatus;
import com.flowtivo.tinfirm.shared.api.model.Run;
import com.flowtivo.tinfirm.shared.api.model.RunStatus;

/**
 * The Gmail add-on's cards (ADR-0083, decision 6), as Google's card JSON: the sign-in card, the homepage, the start
 * card and the conversation card. Their buttons call the add-on's {@code /act} endpoint with a parameter {@code do}
 * (and {@code action} with the start card's id {@code card}, {@code conversationId} or {@code runId}); their links
 * open Tinfirm, or a draft in Gmail. Text is Google's small HTML: everything the cards show of people's and agents'
 * words is escaped, and an agent's Markdown keeps its bold and line breaks only.
 */
final class GmailCards {

    /** The newest messages the conversation card shows. */
    static final int MAX_MESSAGES = 10;

    /** The characters of one message the conversation card shows. */
    static final int MAX_MESSAGE_CHARS = 2_000;

    private static final String MUTED = "#5f6368";

    private static final String ERROR = "#b3261e";

    /** Where the cards' buttons and links go: the add-on's action endpoint, the company's pages in Tinfirm. */
    record Urls(String action, String app) {
    }

    private GmailCards() {
    }

    // ---- responses -----------------------------------------------------------------------------------------------------

    /** A trigger's answer: the card, pushed. */
    static Map<String, Object> pushed(Map<String, Object> card) {
        return Map.of("action", Map.of("navigations", List.of(Map.of("pushCard", card))));
    }

    /** A button's answer: the card in place of the one shown. */
    static Map<String, Object> updated(Map<String, Object> card) {
        return Map.of("action", Map.of("navigations", List.of(Map.of("updateCard", card))));
    }

    // ---- cards ---------------------------------------------------------------------------------------------------------

    /** An unlinked Google account's card: the sign-in link, which reloads the add-on when its window closes. */
    static Map<String, Object> signIn(String companyName, @Nullable String googleEmail, String signInUrl) {
        List<Object> widgets = new ArrayList<>();
        widgets.add(text("Ask " + escape(companyName) + "’s agents about your emails: summarise one, triage it, "
                + "investigate the code it is about, or draft a reply."));
        widgets.add(text("Sign in to Tinfirm once to link "
                + (googleEmail != null ? "the Google account <b>" + escape(googleEmail) + "</b>" : "this Google account")
                + " to your Tinfirm account. Tinfirm asks you to confirm it."));
        widgets.add(buttons(Map.of("text", "Sign in to Tinfirm", "type", "FILLED", "onClick",
                Map.of("openLink", Map.of("url", signInUrl, "openAs", "OVERLAY", "onClose", "RELOAD")))));
        widgets.add(muted("The sign-in link works for 10 minutes. Close its window when you are done to come back."));
        return card("Tinfirm", null, List.of(section(null, widgets)));
    }

    /** Gmail without an open email. */
    static Map<String, Object> home(String companyName, String personName, Urls urls, @Nullable String error) {
        List<Object> widgets = new ArrayList<>();
        error(widgets, error);
        widgets.add(text("Open an email to ask one of " + escape(companyName) + "’s agents about it."));
        widgets.add(buttons(link("Open Tinfirm", urls.app() + "/inbox"), action("Sign out", urls, "signout")));
        return card("Tinfirm", "Signed in as " + personName, List.of(section(null, widgets)));
    }

    /** The open email without a conversation: the agents, the actions and the person's own question. */
    static Map<String, Object> start(String personName, List<AgentSummary> agents, @Nullable UUID lastAgentId,
            String ownerNotice, Urls urls, @Nullable String error) {
        List<Object> widgets = new ArrayList<>();
        error(widgets, error);
        if (agents.isEmpty()) {
            widgets.add(text("You may not use any agent yet: no agent is shared with you, or all are paused. Ask "
                    + "an Admin of your company to share one with you."));
            widgets.add(buttons(link("Open Tinfirm", urls.app() + "/agents"), action("Sign out", urls, "signout")));
            return card("Ask an agent", "Signed in as " + personName, List.of(section(null, widgets)));
        }
        UUID chosen = agents.stream().map(AgentSummary::getId).filter(id -> id.equals(lastAgentId)).findFirst()
            .orElse(agents.getFirst().getId());
        List<Object> items = new ArrayList<>();
        for (AgentSummary agent : agents) {
            items.add(Map.of("text", agent.getName() + " (@" + agent.getHandle() + ")", "value",
                    agent.getId().toString(), "selected", agent.getId().equals(chosen)));
        }
        widgets.add(Map.of("selectionInput", Map.of("name", "agent", "label", "Agent", "type", "DROPDOWN", "items",
                items)));
        // This card's own id on each of its starts: Google's retry of a click repeats it, a new card has another.
        String card = UUID.randomUUID().toString();
        widgets.add(buttons(start("Summarise", urls, GmailEmails.Action.SUMMARISE, card),
                start("Triage", urls, GmailEmails.Action.TRIAGE, card)));
        widgets.add(buttons(start("Investigate", urls, GmailEmails.Action.INVESTIGATE, card),
                start("Draft a reply", urls, GmailEmails.Action.DRAFT_REPLY, card)));
        widgets.add(Map.of("selectionInput", Map.of("name", "attachments", "type", "CHECK_BOX", "items",
                List.of(Map.of("text", "Send its attachments too", "value", "yes", "selected", true)))));
        List<Object> own = new ArrayList<>();
        own.add(Map.of("textInput", Map.of("name", "question", "label", "Your question about this email", "type",
                "MULTIPLE_LINE")));
        own.add(buttons(start("Ask", urls, GmailEmails.Action.ASK, card)));
        own.add(muted("The agent gets the email’s subject, sender, recipients, date and text, and its attachments when "
                + "you send them: at most " + GmailAddOnImpl.MAX_ATTACHMENTS + " files and "
                + GmailAddOnImpl.MAX_ATTACHMENT_MEGABYTES + " MB, none that holds a credential. " + escape(ownerNotice)));
        return card("Ask an agent", "Signed in as " + personName, List.of(section(null, widgets),
                section("Or in your own words", own), section(null, List.of(buttons(action("Sign out", urls,
                        "signout"))))));
    }

    /** The open email's conversation: its messages, where it stands, and what the person may do next. */
    static Map<String, Object> conversation(Conversation conversation, boolean aboutAnotherEmail, Urls urls,
            @Nullable String error) {
        return conversation(conversation, aboutAnotherEmail, urls, error, null);
    }

    /**
     * As {@link #conversation(Conversation, boolean, Urls, String)}, after an answer was saved as a draft reply: a link
     * that opens it in Gmail.
     */
    static Map<String, Object> conversation(Conversation conversation, boolean aboutAnotherEmail, Urls urls,
            @Nullable String error, @Nullable String draftUrl) {
        List<Map<String, Object>> sections = new ArrayList<>();
        List<Object> top = new ArrayList<>();
        error(top, error);
        if (draftUrl != null) {
            top.add(text("The answer is a draft reply in this thread: edit it and send it from Gmail."));
            top.add(buttons(Map.of("text", "Open the draft", "onClick", Map.of("openLink", Map.of("url", draftUrl,
                    "openAs", "FULL_SIZE")))));
        }
        if (aboutAnotherEmail) {
            top.add(muted("About an earlier email of this thread."));
        }
        List<ConversationMessage> messages = conversation.getMessages();
        int from = Math.max(0, messages.size() - MAX_MESSAGES);
        if (from > 0) {
            top.add(muted(from + " earlier " + (from == 1 ? "message is" : "messages are") + " in Tinfirm."));
        }
        if (!top.isEmpty()) {
            sections.add(section(null, top));
        }
        for (int index = from; index < messages.size(); index++) {
            ConversationMessage message = messages.get(index);
            List<Object> widgets = new ArrayList<>();
            widgets.add(text(body(conversation, message, index)));
            if (reply(conversation, message) != null) {
                widgets.add(buttons(action("Use as reply", urls, "draft", "conversationId",
                        conversation.getId().toString(), "runId", Objects.requireNonNull(message.getRunId()).toString())));
            }
            sections.add(section(author(conversation, message), widgets));
        }
        List<Object> next = new ArrayList<>();
        next.add(text("<i>" + escape(status(conversation)) + "</i>"));
        String link = urls.app() + "/conversations/" + conversation.getId();
        boolean open = conversation.getState() == ConversationState.OPEN;
        if (open && (Boolean.TRUE.equals(conversation.getMayPost()) || Boolean.TRUE.equals(conversation.getMayAnswer()))) {
            next.add(Map.of("textInput", Map.of("name", "reply", "label", openQuestion(conversation)
                    ? "Your answer" : "Your follow-up", "type", "MULTIPLE_LINE")));
            // This card's own id on its Send, as on a start card's starts.
            next.add(buttons(action("Send", urls, "send", "conversationId", conversation.getId().toString(), "card",
                    UUID.randomUUID().toString()),
                    action("Refresh", urls, "refresh", "conversationId", conversation.getId().toString())));
        }
        else {
            next.add(buttons(action("Refresh", urls, "refresh", "conversationId", conversation.getId().toString())));
        }
        next.add(buttons(link("Open in Tinfirm", link), action("Ask something else", urls, "new")));
        sections.add(section(null, next));
        return card(conversation.getAgentName(), conversation.getTitle(), sections);
    }

    // ---- the conversation ----------------------------------------------------------------------------------------------

    /** Who wrote the message, as the card names them. */
    private static String author(Conversation conversation, ConversationMessage message) {
        return switch (message.getKind()) {
            case PERSON -> "You";
            case AGENT -> conversation.getAgentName();
            case SYSTEM -> "Tinfirm";
            case EXTERNAL -> Objects.requireNonNullElse(message.getAuthorName(), "Someone");
        };
    }

    /** The message as the card shows it: the first request as its action or words, never the email. */
    private static String body(Conversation conversation, ConversationMessage message, int index) {
        if (index == 0 && message.getKind() == ConversationMessage.KindEnum.PERSON) {
            String described = GmailEmails.describe(message.getBody());
            if (described != null) {
                return "<b>" + escape(described) + "</b><br><font color=\"" + MUTED + "\">With this email’s text"
                        + "</font>";
            }
        }
        String text = markdown(cut(message.getBody()));
        if (message.getKind() == ConversationMessage.KindEnum.PERSON && Boolean.TRUE.equals(message.getWaiting())
                && conversation.getState() == ConversationState.OPEN) {
            text += "<br><font color=\"" + MUTED + "\">Waits for the agent</font>";
        }
        if (message.getKind() == ConversationMessage.KindEnum.AGENT && message.getDecisionId() == null) {
            Run turn = conversation.getTurns().stream().filter(run -> run.getId().equals(message.getRunId()))
                .findFirst().orElse(null);
            if (turn != null && turn.getStatus() == RunStatus.FAILED) {
                text += "<br><font color=\"" + ERROR + "\">This turn failed.</font>";
            }
        }
        return text;
    }

    /**
     * What <b>Use as reply</b> puts in the draft, as the Outlook pane's ({@code pane-thread.ts}): the answer's turn's
     * summary, without its next steps, when the turn succeeded. Null for any other message.
     */
    static @Nullable String reply(Conversation conversation, ConversationMessage message) {
        if (message.getKind() != ConversationMessage.KindEnum.AGENT || message.getDecisionId() != null
                || message.getRunId() == null) {
            return null;
        }
        return reply(conversation, message.getRunId());
    }

    /** The summary of the turn, when it succeeded and has one. */
    static @Nullable String reply(Conversation conversation, UUID runId) {
        Run turn = conversation.getTurns().stream().filter(run -> run.getId().equals(runId)).findFirst().orElse(null);
        if (turn == null || turn.getStatus() != RunStatus.SUCCEEDED) {
            return null;
        }
        String summary = turn.getResult() != null ? turn.getResult().getSummary() : turn.getSummary();
        return summary != null && !summary.isBlank() ? summary : null;
    }

    private static boolean openQuestion(Conversation conversation) {
        return conversation.getDecisions().stream()
            .anyMatch(decision -> decision.getKind() == DecisionKind.QUESTION
                    && decision.getStatus() == DecisionStatus.OPEN);
    }

    /** Where the conversation stands, as the Outlook pane says it ({@code pane-status.ts}). */
    static String status(Conversation conversation) {
        if (conversation.getState() == ConversationState.ENDED) {
            return "The conversation has ended.";
        }
        ConversationTurnStatus turn = conversation.getTurnStatus();
        ConversationWaitingOn waiting = conversation.getWaitingOn();
        if (turn == ConversationTurnStatus.RUNNING || waiting == ConversationWaitingOn.AGENT) {
            return "Working on it. A turn takes a few minutes: choose Refresh to see the answer.";
        }
        if (turn == ConversationTurnStatus.QUEUED || waiting == ConversationWaitingOn.RUNNER) {
            return "Waiting for a runner host. Choose Refresh to see the answer.";
        }
        if (waiting == ConversationWaitingOn.PERSON) {
            return switch (Objects.requireNonNullElse(conversation.getWaitingReason(), "")) {
                case "QUESTION" -> "The agent asks you something: answer below.";
                case "APPROVAL" -> "The agent needs your approval. Decide it in Tinfirm.";
                case "ACCOUNT" -> "The agent needs an account of yours. Connect it in Tinfirm.";
                case "FAILED" -> Objects.requireNonNullElse(conversation.getFailureCause(), "The turn failed.");
                default -> "The agent waits for you in Tinfirm.";
            };
        }
        if (turn == ConversationTurnStatus.FAILED) {
            return Objects.requireNonNullElse(conversation.getFailureCause(), "The turn failed.");
        }
        if (turn == ConversationTurnStatus.CANCELLED) {
            return "The turn was cancelled.";
        }
        return "Done.";
    }

    // ---- widgets -------------------------------------------------------------------------------------------------------

    private static Map<String, Object> card(String title, @Nullable String subtitle,
            List<Map<String, Object>> sections) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", title);
        if (subtitle != null && !subtitle.isBlank()) {
            header.put("subtitle", subtitle);
        }
        return Map.of("header", header, "sections", sections);
    }

    private static Map<String, Object> section(@Nullable String header, List<Object> widgets) {
        Map<String, Object> section = new LinkedHashMap<>();
        if (header != null) {
            section.put("header", escape(header));
        }
        section.put("widgets", widgets);
        return section;
    }

    private static Map<String, Object> text(String html) {
        return Map.of("textParagraph", Map.of("text", html));
    }

    private static Map<String, Object> muted(String html) {
        return text("<font color=\"" + MUTED + "\">" + html + "</font>");
    }

    private static void error(List<Object> widgets, @Nullable String error) {
        if (error != null) {
            widgets.add(text("<font color=\"" + ERROR + "\">" + escape(error) + "</font>"));
        }
    }

    private static Map<String, Object> buttons(Object... buttons) {
        return Map.of("buttonList", Map.of("buttons", List.of(buttons)));
    }

    private static Map<String, Object> start(String text, Urls urls, GmailEmails.Action action, String card) {
        return action(text, urls, "start", "action", action.name(), "card", card);
    }

    /** A button that calls the add-on's action endpoint with {@code do} and one more parameter, if any. */
    private static Map<String, Object> action(String text, Urls urls, String what, String... more) {
        List<Map<String, String>> parameters = new ArrayList<>();
        parameters.add(Map.of("key", "do", "value", what));
        for (int i = 0; i + 1 < more.length; i += 2) {
            parameters.add(Map.of("key", more[i], "value", more[i + 1]));
        }
        return Map.of("text", text, "onClick", Map.of("action", Map.of("function", urls.action(), "parameters",
                parameters, "loadIndicator", "SPINNER")));
    }

    private static Map<String, Object> link(String text, String url) {
        return Map.of("text", text, "onClick", Map.of("openLink", Map.of("url", url)));
    }

    // ---- text ----------------------------------------------------------------------------------------------------------

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String cut(String text) {
        if (text.length() <= MAX_MESSAGE_CHARS) {
            return text;
        }
        int end = MAX_MESSAGE_CHARS;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).stripTrailing() + " … (the rest is in Tinfirm)";
    }

    /** Markdown as the card's HTML: escaped, its bold and headings bold, its line breaks kept. */
    static String markdown(String markdown) {
        StringBuilder html = new StringBuilder();
        for (String line : markdown.replace("\r\n", "\n").strip().split("\n", -1)) {
            String escaped = escape(line);
            if (line.matches("#{1,6}\\s.*")) {
                escaped = "<b>" + escape(line.replaceFirst("#{1,6}\\s+", "")) + "</b>";
            }
            else {
                escaped = escaped.replaceAll("\\*\\*([^*]+)\\*\\*", "<b>$1</b>");
            }
            if (!html.isEmpty()) {
                html.append("<br>");
            }
            html.append(escaped);
        }
        return html.toString();
    }
}
