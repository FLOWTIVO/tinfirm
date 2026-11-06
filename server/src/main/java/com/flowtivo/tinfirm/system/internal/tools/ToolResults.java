package com.flowtivo.tinfirm.system.internal.tools;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.FixedTools;
import com.flowtivo.tinfirm.system.ToolCallResult;
import com.flowtivo.tinfirm.system.internal.mcp.McpClient;
import com.flowtivo.tinfirm.system.internal.slack.SlackApi;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The results of tools as the agent reads them (archive/v2 PLATFORM.md §7.2): a Markdown text and the same as
 * {@code structuredContent}.
 * <ul>
 * <li>{@code get}: the subject's key and title, status (or state), type, labels, assignees, author, times and link,
 * its description in Markdown, and its newest {@value Tracker#COMMENTS} comments, oldest first, each in Markdown as a
 * quote under its author and time.</li>
 * <li>{@code search}: one line per subject (key, title, status, labels, assignees).</li>
 * <li>A write: what it did, with the comment's id and link, the transition and the status it led to.</li>
 * <li>Slack's {@code history}: the messages oldest first, each with its time, author, {@code ts} and replies, its
 * text as a quote, and the cursor of the next page.</li>
 * <li>An MCP server's: its text blocks as they are, any other block as a text that names its type.</li>
 * <li>A failure of the system: a result with {@code isError}, whose text names the tool, the system's answer and what
 * to do.</li>
 * </ul>
 * The text is the system's content, which the prompt marks as other people's words.
 */
final class ToolResults {

    /** What a subject without a description shows. */
    static final String NO_DESCRIPTION = "(no description)";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ToolResults() {
    }

    /** The result of {@code get}: the subject with its newest comments, texts converted to Markdown. */
    static ToolCallResult subject(Tracker tracker, Subject subject) {
        StringBuilder text = new StringBuilder("# ").append(subject.key()).append(": ").append(subject.title())
            .append('\n');
        if (subject.changeRequest()) {
            text.append("Kind: change request\n");
        }
        text.append(state(subject)).append('\n');
        if (subject.type() != null) {
            text.append("Type: ").append(subject.type()).append('\n');
        }
        text.append("Labels: ").append(subject.labels().isEmpty() ? "none" : String.join(", ", subject.labels()))
            .append('\n')
            .append("Assignees: ").append(users(subject.assignees())).append('\n');
        if (subject.author() != null) {
            text.append("Author: ").append(user(subject.author())).append('\n');
        }
        text.append("Created: ").append(time(subject.created())).append(", updated: ")
            .append(time(subject.updated())).append('\n');
        if (subject.url() != null) {
            text.append("Link: ").append(subject.url()).append('\n');
        }
        String description = tracker.toMarkdown(subject.body());
        text.append("\n## Description\n\n")
            .append(description != null ? description.strip() : NO_DESCRIPTION).append('\n');

        List<Subject.Comment> comments = newest(subject.comments());
        ArrayNode commentNodes = JSON.createArrayNode();
        int total = Math.max(subject.commentTotal(), subject.comments().size());
        if (!comments.isEmpty() || total > 0) {
            text.append("\n## Comments");
            if (total > comments.size()) {
                text.append(" (the newest ").append(comments.size()).append(" of ").append(total)
                    .append(", oldest first)");
            }
            text.append('\n');
            for (Subject.Comment comment : comments) {
                String body = tracker.toMarkdown(comment.body());
                String author = comment.author() != null ? comment.author().shown() : "Someone";
                text.append("\n**").append(author).append("** · ").append(time(comment.created())).append('\n')
                    .append(quote(body != null ? body.strip() : "")).append('\n');
                ObjectNode node = commentNodes.addObject();
                node.put("id", comment.id());
                if (comment.author() != null) {
                    node.set("author", userNode(comment.author()));
                }
                node.put("created", time(comment.created()));
                node.put("body", body);
            }
        }

        ObjectNode structured = subjectNode(subject);
        structured.put("type", subject.type());
        if (subject.author() != null) {
            structured.set("author", userNode(subject.author()));
        }
        structured.put("created", time(subject.created()));
        structured.put("description", description);
        structured.set("comments", commentNodes);
        structured.put("commentTotal", total);
        return ToolCallResult.text(text.toString(), structured, false);
    }

    /** The result of {@code search}: one line per subject. */
    static ToolCallResult search(List<Subject> subjects) {
        StringBuilder text = new StringBuilder(subjects.isEmpty() ? "No results."
                : subjects.size() == 1 ? "1 result" : subjects.size() + " results");
        ArrayNode items = JSON.createArrayNode();
        if (!subjects.isEmpty()) {
            text.append('\n');
        }
        for (Subject subject : subjects) {
            text.append('\n').append(subject.key()).append(' ').append(subject.title()).append(" (")
                .append(subject.status() != null ? subject.status() : subject.closed() ? "closed" : "open");
            if (!subject.labels().isEmpty()) {
                text.append("; labels: ").append(String.join(", ", subject.labels()));
            }
            text.append("; assignees: ").append(subject.assignees().isEmpty() ? "none"
                    : String.join(", ", subject.assignees().stream().map(Subject.User::shown).toList()))
                .append(')');
            items.add(subjectNode(subject));
        }
        ObjectNode structured = JSON.createObjectNode();
        structured.put("count", subjects.size());
        structured.set("issues", items);
        return ToolCallResult.text(text.toString(), structured, false);
    }

    /** The result of {@code comment}: the comment's id and link. */
    static ToolCallResult commented(String key, Subject.Posted posted) {
        ObjectNode structured = JSON.createObjectNode();
        structured.put("key", key);
        structured.put("commentId", Long.toString(posted.id()));
        structured.put("url", posted.url());
        return ToolCallResult.text("Commented on " + key + " (comment " + posted.id() + ")."
                + (posted.url() != null ? " Link: " + posted.url() : ""), structured, false);
    }

    /** The result of {@code transition}: the transition applied and the status it leads to. */
    static ToolCallResult transitioned(String key, Subject.Transition transition) {
        ObjectNode structured = JSON.createObjectNode();
        structured.put("key", key);
        structured.put("transition", transition.name());
        structured.put("status", transition.to());
        return ToolCallResult.text("Moved " + key + " through the transition `" + transition.name() + "`"
                + (transition.to() != null ? "; its status is " + transition.to() + " now." : "."), structured, false);
    }

    /** The answer of {@code transition} when the issue's status offers no such transition: those it offers. */
    static ToolCallResult noSuchTransition(String key, String asked, List<Subject.Transition> offered) {
        List<String> names = new ArrayList<>();
        ArrayNode offeredNode = JSON.createArrayNode();
        for (Subject.Transition transition : offered) {
            names.add("`" + transition.name() + "`" + (transition.to() != null ? " (to " + transition.to() + ")" : ""));
            offeredNode.addObject().put("name", transition.name()).put("status", transition.to());
        }
        ObjectNode structured = JSON.createObjectNode();
        structured.put("key", key);
        structured.set("offered", offeredNode);
        return ToolCallResult.text("The status of " + key + " offers no transition named or leading to `" + asked
                + "`. " + (names.isEmpty() ? "It offers none to this account."
                        : "It offers: " + String.join(", ", names) + "."), structured, true);
    }

    /** The result of {@code close}. */
    static ToolCallResult closed(String key) {
        ObjectNode structured = JSON.createObjectNode();
        structured.put("key", key);
        structured.put("closed", true);
        return ToolCallResult.text("Closed " + key + ".", structured, false);
    }

    /**
     * The result of an MCP server's tool as the server sent it: its text blocks as they are, any other block (an
     * image, audio, a resource) as a text that names its type, since the frame carries text; its structured content
     * and its error flag.
     */
    static ToolCallResult mcp(McpClient.Called called) {
        List<ToolCallResult.Content> content = new ArrayList<>();
        for (JsonNode block : called.content()) {
            JsonNode type = block.get("type");
            String name = type != null && type.isString() ? type.stringValue() : "unknown";
            JsonNode text = block.get("text");
            if ("text".equals(name) && text != null && text.isString()) {
                content.add(ToolCallResult.Content.text(text.stringValue()));
            }
            else {
                content.add(ToolCallResult.Content.text("(The tool returned a block of type `" + name + "`, which "
                        + "Tinfirm does not forward.)"));
            }
        }
        return new ToolCallResult(content, called.structuredContent(), called.isError());
    }

    /**
     * The result of Slack's {@code history}: the page's messages oldest first (a channel's page comes newest first, a
     * thread's oldest first), each text cut at {@value FixedTools#MAX_HISTORY_TEXT} characters.
     */
    static ToolCallResult history(String channel, @Nullable String thread, SlackApi.Messages page) {
        List<SlackApi.Message> messages = new ArrayList<>(page.messages());
        if (thread == null) {
            Collections.reverse(messages);
        }
        StringBuilder text = new StringBuilder(thread != null ? "The thread " + thread + " of " + channel
                : "The messages of " + channel);
        text.append(messages.isEmpty() ? ": none." : ", oldest first (" + messages.size()
                + (messages.size() == 1 ? " message):" : " messages):"));
        ArrayNode items = JSON.createArrayNode();
        for (SlackApi.Message message : messages) {
            String body = message.text().length() <= FixedTools.MAX_HISTORY_TEXT ? message.text()
                    : message.text().substring(0, FixedTools.MAX_HISTORY_TEXT - 1) + "…";
            Instant at = slackTime(message.ts());
            String author = message.user() != null ? "<@" + message.user() + ">"
                    : message.botName() != null ? message.botName() + " (an app)" : "an app";
            text.append("\n\n").append(at != null ? time(at) : message.ts()).append(" · ").append(author)
                .append(" · ts ").append(message.ts());
            if (thread == null && message.replyCount() > 0) {
                text.append(" · a thread of ").append(message.replyCount())
                    .append(message.replyCount() == 1 ? " reply" : " replies");
            }
            text.append('\n').append(quote(body.isBlank() ? "(no text)" : body));
            ObjectNode item = items.addObject();
            item.put("ts", message.ts());
            item.put("time", at != null ? time(at) : null);
            item.put("user", message.user());
            item.put("app", message.botName());
            item.put("text", body);
            item.put("threadTs", message.threadTs());
            item.put("replyCount", message.replyCount());
        }
        if (page.nextCursor() != null) {
            text.append("\n\nThere are more: call again with `cursor` ").append(page.nextCursor())
                .append(thread != null ? " for the thread's later messages." : " for older messages.");
        }
        ObjectNode structured = JSON.createObjectNode();
        structured.put("channel", channel);
        structured.put("thread", thread);
        structured.put("count", messages.size());
        structured.set("messages", items);
        structured.put("nextCursor", page.nextCursor());
        return ToolCallResult.text(text.toString(), structured, false);
    }

    /** A Slack {@code ts}'s time, to the second; null when it is not one. */
    private static @Nullable Instant slackTime(String ts) {
        int dot = ts.indexOf('.');
        try {
            return Instant.ofEpochSecond(Long.parseLong(dot < 0 ? ts : ts.substring(0, dot)));
        }
        catch (NumberFormatException | java.time.DateTimeException ex) {
            return null;
        }
    }

    /** A failure the system answered with, for the agent to see: an error result. */
    static ToolCallResult failure(String tool, String system, CallResult.Failure failure) {
        return ToolCallResult.text(failureText(tool, system, failure), null, true);
    }

    /** {@code `{tool}` on {system} failed: {the system's answer}.} and what to do. */
    static String failureText(String tool, String system, CallResult.Failure failure) {
        String hint = switch (failure.kind()) {
            case UNAUTHORIZED -> " The system refused the account's credential; the account must be connected again.";
            case FORBIDDEN -> " The account the conversation uses on " + system + " may not do this there.";
            case NOT_FOUND -> " Check the key, or search for it.";
            case RATE_LIMITED -> " The system's rate limit is exhausted; try again later.";
            case UNAVAILABLE -> " The system is not available right now; try again later.";
            case INVALID -> " Check the arguments, such as the key's format or the query's syntax.";
            case OTHER -> "";
        };
        String message = failure.message().strip();
        return "`" + tool + "` on " + system + " failed: " + message + (message.endsWith(".") ? "" : ".") + hint;
    }

    private static ObjectNode subjectNode(Subject subject) {
        ObjectNode node = JSON.createObjectNode();
        node.put("key", subject.key());
        node.put("title", subject.title());
        node.put("kind", subject.changeRequest() ? "CHANGE_REQUEST" : "ISSUE");
        node.put("status", subject.status());
        node.put("closed", subject.closed());
        ArrayNode labels = node.putArray("labels");
        subject.labels().forEach(labels::add);
        ArrayNode assignees = node.putArray("assignees");
        subject.assignees().forEach(user -> assignees.add(userNode(user)));
        node.put("url", subject.url());
        node.put("updated", time(subject.updated()));
        return node;
    }

    private static ObjectNode userNode(Subject.User user) {
        ObjectNode node = JSON.createObjectNode();
        node.put("name", user.name());
        node.put("displayName", user.displayName());
        return node;
    }

    private static String state(Subject subject) {
        if (subject.status() != null) {
            return "Status: " + subject.status() + (subject.closed() ? " (resolved)" : "");
        }
        return "State: " + (subject.closed() ? "closed" : "open");
    }

    private static String users(List<Subject.User> users) {
        return users.isEmpty() ? "none" : String.join(", ", users.stream().map(ToolResults::user).toList());
    }

    private static String user(Subject.User user) {
        String shown = user.shown();
        return shown.equals(user.name()) ? shown : shown + " (" + user.name() + ")";
    }

    private static List<Subject.Comment> newest(List<Subject.Comment> comments) {
        return comments.size() <= Tracker.COMMENTS ? comments
                : comments.subList(comments.size() - Tracker.COMMENTS, comments.size());
    }

    /** An instant to the second, ISO-8601 in UTC. */
    static String time(Instant at) {
        return DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(ChronoUnit.SECONDS));
    }

    /** The text as a Markdown quote, line by line. */
    static String quote(String text) {
        StringBuilder quoted = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (!quoted.isEmpty()) {
                quoted.append('\n');
            }
            quoted.append(line.isBlank() ? ">" : "> " + line);
        }
        return quoted.toString();
    }
}
