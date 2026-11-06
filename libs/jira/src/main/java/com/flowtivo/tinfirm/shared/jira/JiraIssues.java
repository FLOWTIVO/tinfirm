package com.flowtivo.tinfirm.shared.jira;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * Reads issues, comments and users out of Jira's JSON, the same for Data Center (REST API v2) and Cloud (v3). Lenient:
 * a member that is missing or of another type is null, and an issue or a comment that cannot be used is left out
 * rather than failing the answer it came in.
 */
public final class JiraIssues {

    private JiraIssues() {
    }

    /**
     * An issue of a search or a {@code GET …/issue/{key}}; null without a key, a {@code fields} object or a usable
     * {@code updated}.
     */
    public static @Nullable JiraIssue parse(JsonNode item) {
        String key = JiraJson.text(item, "key");
        JsonNode fields = item.get("fields");
        if (key == null || fields == null || !fields.isObject()) {
            return null;
        }
        Instant updated = JiraTime.parse(JiraJson.text(fields, "updated"));
        if (updated == null) {
            return null;
        }
        List<String> labels = new ArrayList<>();
        JsonNode labelNodes = fields.get("labels");
        if (labelNodes != null && labelNodes.isArray()) {
            labelNodes.forEach(label -> {
                if (label.isString()) {
                    labels.add(label.stringValue());
                }
            });
        }
        JsonNode comment = fields.get("comment");
        List<JiraIssue.Comment> comments = comment != null ? comments(comment.get("comments")) : List.of();
        int total = comment != null ? JiraJson.integer(comment, "total", comments.size()) : 0;
        String resolutionDate = JiraJson.text(fields, "resolutiondate");
        return new JiraIssue(JiraJson.textOrNumber(item, "id"), key, JiraJson.text(fields.get("project"), "key"),
                JiraJson.text(fields, "summary"), text(fields.get("description")),
                JiraJson.text(fields.get("status"), "name"), JiraJson.text(fields.get("issuetype"), "name"),
                JiraJson.text(fields.get("resolution"), "name"), resolutionDate != null,
                JiraTime.parse(resolutionDate), JiraTime.parse(JiraJson.text(fields, "created")), updated, labels,
                user(fields.get("assignee")), user(fields.get("reporter")), comments, total);
    }

    /** The issues of a search's answer that can be used, in its order. */
    public static List<JiraIssue> issues(JsonNode body) {
        List<JiraIssue> issues = new ArrayList<>();
        JsonNode items = body.get("issues");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                JiraIssue issue = parse(item);
                if (issue != null) {
                    issues.add(issue);
                }
            }
        }
        return issues;
    }

    /** The comments, oldest first by id; those without a numeric id or a usable {@code created} are left out. */
    public static List<JiraIssue.Comment> comments(@Nullable JsonNode items) {
        List<JiraIssue.Comment> comments = new ArrayList<>();
        if (items == null || !items.isArray()) {
            return comments;
        }
        for (JsonNode item : items) {
            Long id = JiraJson.longValue(item, "id");
            Instant created = JiraTime.parse(JiraJson.text(item, "created"));
            if (id == null || created == null) {
                continue;
            }
            comments.add(new JiraIssue.Comment(id, user(item.get("author")), text(item.get("body")), created,
                    restricted(item)));
        }
        comments.sort((a, b) -> Long.compare(a.id(), b.id()));
        return comments;
    }

    /**
     * Whether only some of the issue's readers see the comment (JiraIssue.Comment#restricted). Any {@code visibility}
     * but null counts, an empty or odd one too: a comment is never taken for public on an answer that says otherwise.
     */
    static boolean restricted(JsonNode comment) {
        JsonNode visibility = comment.get("visibility");
        if (visibility != null && !visibility.isNull()) {
            return true;
        }
        JsonNode jsdPublic = comment.get("jsdPublic");
        if (jsdPublic != null && jsdPublic.isBoolean() && !jsdPublic.booleanValue()) {
            return true;
        }
        for (JsonNode property : comment.path("properties")) {
            if ("sd.public.comment".equals(property.path("key").asString(""))
                    && property.path("value").path("internal").asBoolean(false)) {
                return true;
            }
        }
        return false;
    }

    /** A user object as it came; null when there is none (an unassigned issue, an app). */
    public static JiraIssue.@Nullable User user(@Nullable JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new JiraIssue.User(JiraJson.text(node, "accountId"), JiraJson.text(node, "key"),
                JiraJson.text(node, "name"), JiraJson.text(node, "displayName"));
    }

    /** A text field as it came: wiki markup as it is, an ADF document as JSON text, null when absent. */
    public static @Nullable String text(@Nullable JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isString()) {
            return node.stringValue();
        }
        return node.isObject() ? node.toString() : null;
    }

    /** A raw text as Markdown: Cloud's ADF ({@link Adf}), Data Center's wiki markup ({@link JiraMarkup}). */
    public static @Nullable String markdown(@Nullable String raw, boolean cloud) {
        return cloud ? Adf.toMarkdown(raw) : JiraMarkup.toMarkdown(raw);
    }

    /** The project of an issue key: what comes before its last dash. */
    public static String projectOf(String key) {
        int dash = key.lastIndexOf('-');
        return dash > 0 ? key.substring(0, dash) : key;
    }
}
