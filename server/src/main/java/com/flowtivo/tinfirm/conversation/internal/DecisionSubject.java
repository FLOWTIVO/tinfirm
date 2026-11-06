package com.flowtivo.tinfirm.conversation.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.agent.RepositoryAccess;
import com.flowtivo.tinfirm.conversation.TriggerConversations.ReviewWrite;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What an approval is about (the package 3 contract, §3.12), as {@code decision.subject} holds it:
 * {@code {type: TOOL_CALL, systemId, name, tool, arguments, what}}, {@code {type: REPOSITORY, repositoryId, access}},
 * {@code {type: HOST, host}} or {@code {type: CHANGE_REQUEST, repositoryId}}.
 *
 * @param name      a tool call's tool as the conversation's runs list it ({@code jira_comment})
 * @param tool      a tool call's tool as its system names it ({@code comment})
 * @param arguments a tool call's arguments, checked and normalized, at most 64 KiB
 * @param what      a tool call in words, e.g. {@code comment on SHOP-241}; null when its arguments say it all
 * @param access    a repository's access once approved: the agent's when it lists the repository, else {@code READ}
 *                  unless the approver picks {@code WRITE} (open point 10)
 * @param writes    a result's writes, in order: kept as {@code writes} (their words) and {@code writeItems} (each with
 *                  its kind, target and value); one stored without {@code writeItems} has its words only
 */
record DecisionSubject(Type type, @Nullable UUID systemId, @Nullable String name, @Nullable String tool,
        @Nullable JsonNode arguments, @Nullable String what, @Nullable UUID repositoryId,
        @Nullable RepositoryAccess access, @Nullable String host, @Nullable UUID runId, @Nullable String summary,
        @Nullable List<ReviewWrite> writes) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The longest summary and write a result's review holds. */
    static final int MAX_RESULT_TEXT = 20_000;

    enum Type {
        TOOL_CALL, REPOSITORY, HOST, CHANGE_REQUEST,
        /** A trigger's turn's result, reviewed before anything of it is written (the package 4 contract, §5.8). */
        RESULT
    }

    DecisionSubject(Type type, @Nullable UUID systemId, @Nullable String name, @Nullable String tool,
            @Nullable JsonNode arguments, @Nullable String what, @Nullable UUID repositoryId,
            @Nullable RepositoryAccess access, @Nullable String host) {
        this(type, systemId, name, tool, arguments, what, repositoryId, access, host, null, null, null);
    }

    /** {@code {type: RESULT, runId, summary, writes, writeItems}}. */
    static DecisionSubject result(UUID runId, String summary, List<ReviewWrite> writes) {
        return new DecisionSubject(Type.RESULT, null, null, null, null, null, null, null, null, runId,
                cut(summary), writes.stream()
                    .map(write -> new ReviewWrite(write.kind(), write.target(), write.value(), cut(write.text())))
                    .toList());
    }

    /** A result's writes in words, as {@code writes} keeps them. */
    @Nullable List<String> writeTexts() {
        return writes != null ? writes.stream().map(ReviewWrite::text).toList() : null;
    }

    private static String cut(String text) {
        return text.length() <= MAX_RESULT_TEXT ? text : text.substring(0, MAX_RESULT_TEXT);
    }

    static DecisionSubject toolCall(UUID systemId, String name, String tool, JsonNode arguments,
            @Nullable String what) {
        return new DecisionSubject(Type.TOOL_CALL, systemId, name, tool, arguments, what, null, null, null);
    }

    static DecisionSubject repository(UUID repositoryId, RepositoryAccess access) {
        return new DecisionSubject(Type.REPOSITORY, null, null, null, null, null, repositoryId, access, null);
    }

    static DecisionSubject host(String host) {
        return new DecisionSubject(Type.HOST, null, null, null, null, null, null, null, host);
    }

    static DecisionSubject changeRequest(UUID repositoryId) {
        return new DecisionSubject(Type.CHANGE_REQUEST, null, null, null, null, null, repositoryId, null, null);
    }

    String json() {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", type.name());
        if (systemId != null) {
            node.put("systemId", systemId.toString());
        }
        if (name != null) {
            node.put("name", name);
        }
        if (tool != null) {
            node.put("tool", tool);
        }
        if (arguments != null) {
            node.set("arguments", arguments);
        }
        if (what != null) {
            node.put("what", what);
        }
        if (repositoryId != null) {
            node.put("repositoryId", repositoryId.toString());
        }
        if (access != null) {
            node.put("access", access.name());
        }
        if (host != null) {
            node.put("host", host);
        }
        if (runId != null) {
            node.put("runId", runId.toString());
        }
        if (summary != null) {
            node.put("summary", summary);
        }
        if (writes != null) {
            ArrayNode texts = node.putArray("writes");
            ArrayNode items = node.putArray("writeItems");
            for (ReviewWrite write : writes) {
                texts.add(write.text());
                ObjectNode item = items.addObject();
                if (write.kind() != null) {
                    item.put("kind", write.kind());
                }
                if (write.target() != null) {
                    item.put("target", write.target());
                }
                if (write.value() != null) {
                    item.put("value", write.value());
                }
                item.put("text", write.text());
            }
        }
        return JSON.writeValueAsString(node);
    }

    static @Nullable DecisionSubject of(@Nullable String json) {
        if (json == null) {
            return null;
        }
        JsonNode node = JSON.readTree(json);
        return new DecisionSubject(Type.valueOf(node.get("type").asString()), uuid(node, "systemId"),
                text(node, "name"), text(node, "tool"), node.hasNonNull("arguments") ? node.get("arguments") : null,
                text(node, "what"), uuid(node, "repositoryId"),
                node.hasNonNull("access") ? RepositoryAccess.valueOf(node.get("access").asString()) : null,
                text(node, "host"), uuid(node, "runId"), text(node, "summary"), writes(node));
    }

    /** A result's writes: its items, or, stored without them, its words alone. */
    private static @Nullable List<ReviewWrite> writes(JsonNode node) {
        JsonNode items = node.get("writeItems");
        if (items != null && items.isArray()) {
            List<ReviewWrite> writes = new java.util.ArrayList<>();
            items.forEach(item -> writes.add(new ReviewWrite(text(item, "kind"), text(item, "target"),
                    text(item, "value"), Objects.requireNonNullElse(text(item, "text"), ""))));
            return writes;
        }
        JsonNode array = node.get("writes");
        if (array == null || !array.isArray()) {
            return null;
        }
        List<ReviewWrite> writes = new java.util.ArrayList<>();
        array.forEach(value -> writes.add(new ReviewWrite(null, null, null, value.asString())));
        return writes;
    }

    /** The subject for the audit log: the type and ids, the tool's name and the host; never the arguments. */
    Map<String, Object> auditDetail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", type.name());
        if (systemId != null) {
            detail.put("systemId", systemId.toString());
        }
        if (tool != null) {
            detail.put("tool", tool);
        }
        if (repositoryId != null) {
            detail.put("repositoryId", repositoryId.toString());
        }
        if (host != null) {
            detail.put("host", host);
        }
        if (runId != null) {
            detail.put("runId", runId.toString());
        }
        return detail;
    }

    UUID requireRepositoryId() {
        return Objects.requireNonNull(repositoryId, "repositoryId");
    }

    private static @Nullable String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }

    private static @Nullable UUID uuid(JsonNode node, String field) {
        return node.hasNonNull(field) ? UUID.fromString(node.get(field).asString()) : null;
    }
}
