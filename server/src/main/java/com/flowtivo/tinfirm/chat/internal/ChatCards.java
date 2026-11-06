package com.flowtivo.tinfirm.chat.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * What the bot posts, in each platform's form (the package 5 contract, §5.9, §5.10, open point 12): Slack's Block Kit
 * ({@code section} blocks of mrkdwn of at most 3000 characters, at most 40 of them, a {@code context} block naming the
 * agent and the conversation, an {@code actions} block of buttons) and Teams' Markdown text, with an Adaptive Card 1.5
 * whose buttons are {@code Action.Submit} (a press arrives as a message, §5.8) and {@code Action.OpenUrl}. A post names
 * the agent "(Tinfirm agent)", never a person (row 16, C11).
 */
final class ChatCards {

    static final int SECTION = 3000;

    static final int SECTIONS = 40;

    static final int BUTTONS = 25;

    private ChatCards() {
    }

    /**
     * A message: its Markdown, the agent it is from (null for Tinfirm's own words), the conversation's key and page,
     * and its buttons.
     */
    record Message(String markdown, @Nullable String agentName, @Nullable String conversationKey,
            @Nullable String pageUrl, List<Button> buttons) {

        static Message of(String markdown) {
            return new Message(markdown, null, null, null, List.of());
        }

        Message withButtons(List<Button> more) {
            List<Button> all = new ArrayList<>(buttons);
            all.addAll(more);
            return new Message(markdown, agentName, conversationKey, pageUrl, all);
        }

        Message withMarkdown(String text) {
            return new Message(text, agentName, conversationKey, pageUrl, buttons);
        }
    }

    /**
     * A button: a press carries {@code value} back (Slack's button value, Teams' {@code Action.Submit} data); a link
     * button opens {@code url} instead.
     */
    record Button(String label, @Nullable Map<String, Object> value, @Nullable String url, String style) {

        static Button press(String label, Map<String, Object> value) {
            return new Button(label, value, null, "default");
        }

        static Button primary(String label, Map<String, Object> value) {
            return new Button(label, value, null, "primary");
        }

        static Button danger(String label, Map<String, Object> value) {
            return new Button(label, value, null, "danger");
        }

        static Button link(String label, String url) {
            return new Button(label, null, url, "default");
        }
    }

    /** The name a post shows: the agent's, marked as Tinfirm's (row 16). */
    static @Nullable String username(Message message) {
        return message.agentName() != null ? ChatStore.cut(message.agentName(), 60) + " (Tinfirm agent)" : null;
    }

    // ---- Slack -----------------------------------------------------------------------------------------------------

    /** The blocks of one part of a message. */
    static List<Map<String, Object>> slackBlocks(Message message, String mrkdwnPart, boolean last) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (String section : sections(mrkdwnPart, SECTION)) {
            if (blocks.size() >= SECTIONS) {
                break;
            }
            blocks.add(Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", section)));
        }
        if (last) {
            StringBuilder context = new StringBuilder();
            if (message.agentName() != null) {
                context.append(ChatMarkdown.escapeSlack(message.agentName())).append(" (Tinfirm agent)");
            }
            if (message.conversationKey() != null) {
                context.append(context.isEmpty() ? "" : " · ").append(ChatMarkdown.escapeSlack(
                        message.conversationKey()));
            }
            if (message.pageUrl() != null) {
                context.append(context.isEmpty() ? "" : " · ").append('<').append(message.pageUrl())
                    .append("|Open in Tinfirm>");
            }
            if (!context.isEmpty()) {
                blocks.add(Map.of("type", "context", "elements", List.of(Map.of("type", "mrkdwn", "text",
                        context.toString()))));
            }
            List<Map<String, Object>> elements = new ArrayList<>();
            for (Button button : message.buttons()) {
                if (elements.size() >= BUTTONS) {
                    break;
                }
                Map<String, Object> element = new LinkedHashMap<>();
                element.put("type", "button");
                element.put("text", Map.of("type", "plain_text", "text", ChatStore.cut(button.label(), 75),
                        "emoji", false));
                if (button.url() != null) {
                    element.put("url", button.url());
                    element.put("action_id", "tinfirm-link-" + elements.size());
                }
                else {
                    element.put("action_id", "tinfirm-" + elements.size());
                    element.put("value", ChatStore.JSON.writeValueAsString(button.value()));
                }
                if (!"default".equals(button.style())) {
                    element.put("style", button.style());
                }
                elements.add(element);
            }
            if (!elements.isEmpty()) {
                blocks.add(Map.of("type", "actions", "elements", elements));
            }
        }
        return blocks;
    }

    /** Text cut in pieces of at most {@code max} characters at line breaks. */
    static List<String> sections(String text, int max) {
        List<String> sections = new ArrayList<>();
        String rest = text.isBlank() ? " " : text;
        while (!rest.isEmpty()) {
            if (rest.length() <= max) {
                sections.add(rest);
                break;
            }
            int cut = rest.lastIndexOf('\n', max);
            if (cut < max / 2) {
                cut = max;
            }
            sections.add(rest.substring(0, cut));
            rest = rest.substring(cut).replaceFirst("^\n", "");
        }
        return sections;
    }

    /** The plain fallback of a Slack message: its text without marks, at most 3000 characters. */
    static String slackFallback(String markdown) {
        String plain = markdown.replaceAll("[*_~`>#]", "").strip();
        return plain.isEmpty() ? "Tinfirm" : ChatStore.cut(plain, 3000);
    }

    // ---- Teams -----------------------------------------------------------------------------------------------------

    /** An activity of one part: Markdown text headed by the agent's name, and the buttons as an Adaptive Card. */
    static Map<String, Object> teamsActivity(Message message, String markdownPart, boolean last) {
        Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("type", "message");
        String head = message.agentName() != null
                ? "**" + ChatMarkdown.escapeTeams(message.agentName()) + " (Tinfirm agent)**\n\n" : "";
        List<Map<String, Object>> actions = new ArrayList<>();
        if (last) {
            for (Button button : message.buttons()) {
                if (button.url() != null) {
                    actions.add(Map.of("type", "Action.OpenUrl", "title", button.label(), "url", button.url()));
                }
                else {
                    actions.add(Map.of("type", "Action.Submit", "title", button.label(), "data",
                            java.util.Objects.requireNonNull(button.value())));
                }
            }
            if (message.pageUrl() != null) {
                actions.add(Map.of("type", "Action.OpenUrl", "title", "Open in Tinfirm", "url", message.pageUrl()));
            }
        }
        if (actions.isEmpty()) {
            activity.put("textFormat", "markdown");
            activity.put("text", head + markdownPart);
            return activity;
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", "AdaptiveCard");
        card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        card.put("version", "1.5");
        card.put("body", List.of(Map.of("type", "TextBlock", "text", head + markdownPart, "wrap", true)));
        card.put("actions", actions);
        activity.put("attachments", List.of(Map.of("contentType", "application/vnd.microsoft.card.adaptive",
                "content", card)));
        return activity;
    }
}
