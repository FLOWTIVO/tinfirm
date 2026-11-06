package com.flowtivo.tinfirm.runner.internal;

import java.util.Set;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.runner.RunnerMessages.EventType;

import tools.jackson.databind.JsonNode;

/**
 * The required parts of each {@code $defs/EventPayload*} of the protocol schema. Payloads are stored unchanged;
 * this check only keeps malformed events out of the store.
 */
final class RunEventPayloads {

    private static final Set<String> TOOL_KINDS = Set.of("read", "edit", "delete", "move", "search", "execute",
            "think", "fetch", "switch_mode", "other");

    private static final Set<String> TOOL_STATUSES = Set.of("pending", "in_progress", "completed", "failed");

    private static final Set<String> RATE_LIMIT_STATUSES = Set.of("allowed", "allowed_warning", "rejected");

    private static final Set<String> PHASES = Set.of("preparing", "cloning", "starting", "running", "finalizing",
            "pushing", "cancelling");

    private static final Set<String> GIT_ACTIONS = Set.of("cloned", "committed", "pushed", "nothing_to_push");

    private static final Set<String> LOG_LEVELS = Set.of("debug", "info", "warn", "error");

    private RunEventPayloads() {
    }

    /** What is wrong with the payload, or null when it has the required shape. */
    static @Nullable String problem(EventType type, JsonNode payload) {
        if (!payload.isObject()) {
            return "payload must be an object";
        }
        return switch (type) {
            case AGENT_MESSAGE, AGENT_THOUGHT -> string(payload, "text");
            case TOOL_CALL -> first(string(payload, "toolCallId"), string(payload, "title"),
                    oneOf(payload, "kind", TOOL_KINDS), oneOf(payload, "status", TOOL_STATUSES));
            case TOOL_CALL_UPDATE -> string(payload, "toolCallId");
            case PLAN -> array(payload, "entries");
            case USAGE -> null;
            case RATE_LIMIT -> oneOf(payload, "status", RATE_LIMIT_STATUSES);
            case STATUS -> oneOf(payload, "phase", PHASES);
            case GIT -> first(oneOf(payload, "action", GIT_ACTIONS), string(payload, "branch"),
                    optionalString(payload, "componentId"), optionalString(payload, "dir"));
            case LOG -> first(oneOf(payload, "level", LOG_LEVELS), string(payload, "message"));
            case ERROR -> string(payload, "message");
        };
    }

    private static @Nullable String string(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value != null && value.isString() ? null : field + " must be a string";
    }

    /** Absent, null or a string: events stored before multi-repository runs have no componentId and dir. */
    private static @Nullable String optionalString(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value == null || value.isNull() || value.isString() ? null : field + " must be a string or null";
    }

    private static @Nullable String array(JsonNode payload, String field) {
        JsonNode value = payload.get(field);
        return value != null && value.isArray() ? null : field + " must be an array";
    }

    private static @Nullable String oneOf(JsonNode payload, String field, Set<String> allowed) {
        JsonNode value = payload.get(field);
        return value != null && value.isString() && allowed.contains(value.stringValue()) ? null
                : field + " must be one of " + allowed;
    }

    private static @Nullable String first(@Nullable String... problems) {
        for (String problem : problems) {
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }
}
