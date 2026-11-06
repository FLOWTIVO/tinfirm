package com.flowtivo.tinfirm.system.internal.tools;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.FixedTools;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.SystemTools;
import com.flowtivo.tinfirm.system.ToolCheck;
import com.flowtivo.tinfirm.system.ToolOutcome;
import com.flowtivo.tinfirm.system.internal.github.GitHubTracker;
import com.flowtivo.tinfirm.system.internal.gitlab.GitLabProject;
import com.flowtivo.tinfirm.system.internal.gitlab.GitLabTracker;
import com.flowtivo.tinfirm.system.internal.http.Json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The arguments of a tool call, checked and normalized before anything runs or waits for an approval
 * ({@link SystemTools#check}): an object of at most {@link SystemTools#MAX_ARGUMENT_BYTES}; for a fixed tool the fields
 * of its schema, each stripped, others left out: a {@code key} in the system's format, a {@code query} of at most
 * {@value FixedTools#MAX_QUERY_LENGTH} characters, an integer {@code limit} clamped to 1 to
 * {@value FixedTools#MAX_SEARCH_LIMIT} ({@value FixedTools#DEFAULT_SEARCH_LIMIT} without one), a {@code body} of at most
 * {@value FixedTools#MAX_COMMENT_LENGTH}, a {@code transition} of at most {@value FixedTools#MAX_TRANSITION_LENGTH}, the
 * lists of a {@link LabelCall}; Slack's {@code channel} id, a thread's {@code ts}, a {@code limit} clamped to 1 to
 * {@value FixedTools#MAX_HISTORY_LIMIT} ({@value FixedTools#DEFAULT_HISTORY_LIMIT} without one) and a {@code cursor}.
 * An MCP server validates the arguments of its own tools. The normalized arguments are what the call runs with, now or
 * once a person approved it, and what an approval shows.
 */
public final class ToolArguments {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** How much of a query the description of a call quotes. */
    static final int DESCRIBED_QUERY = 200;

    /** A Slack channel's id: a public channel's {@code C…}, a private one's {@code C…} or {@code G…}. */
    static final Pattern SLACK_CHANNEL = Pattern.compile("^[CG][A-Z0-9]{2,39}$");

    /** A Slack message's {@code ts}: seconds, a dot, a sequence. */
    static final Pattern SLACK_TS = Pattern.compile("^[0-9]{1,12}\\.[0-9]{1,9}$");

    private ToolArguments() {
    }

    /** The arguments do not fit the tool; the message is for the agent. */
    static final class Invalid extends RuntimeException {

        Invalid(String message) {
            super(message, null, false, false);
        }
    }

    /** The check of {@link SystemTools#check}. */
    public static ToolCheck check(SystemKind kind, String tool, @Nullable JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) {
            return invalid("`" + tool + "` takes its arguments as a JSON object.");
        }
        if (Json.write(arguments).getBytes(StandardCharsets.UTF_8).length > SystemTools.MAX_ARGUMENT_BYTES) {
            return invalid("The arguments of `" + tool + "` are larger than " + SystemTools.MAX_ARGUMENT_BYTES / 1024
                    + " KiB.");
        }
        if (kind.hasCatalogue()) {
            return new ToolCheck.Valid(arguments, null);
        }
        if (FixedTools.tool(kind, tool).isEmpty()) {
            return new ToolCheck.Invalid(ToolOutcome.Code.UNKNOWN_TOOL, "`" + tool + "` is not a tool of a " + kind
                    + " system.");
        }
        if (!FixedTools.calledByAgent(tool)) {
            return new ToolCheck.Invalid(ToolOutcome.Code.UNKNOWN_TOOL, "`" + tool + "` is not called by an agent: "
                    + "Tinfirm opens the change request of a branch that a turn pushed.");
        }
        try {
            return switch (tool) {
                case FixedTools.GET -> {
                    String key = key(kind, tool, arguments);
                    yield new ToolCheck.Valid(object().put("key", key), "read " + key);
                }
                case FixedTools.SEARCH -> {
                    String query = text(tool, arguments, "query", FixedTools.MAX_QUERY_LENGTH, "a query");
                    yield new ToolCheck.Valid(object().put("query", query).put("limit", limit(tool, arguments)),
                            "search `" + (query.length() <= DESCRIBED_QUERY ? query
                                    : query.substring(0, DESCRIBED_QUERY - 1) + "…") + "`");
                }
                case FixedTools.COMMENT -> {
                    String key = key(kind, tool, arguments);
                    String body = text(tool, arguments, "body", FixedTools.MAX_COMMENT_LENGTH,
                            "the comment in Markdown");
                    yield new ToolCheck.Valid(object().put("key", key).put("body", body), "comment on " + key);
                }
                case FixedTools.TRANSITION -> {
                    String key = key(kind, tool, arguments);
                    String transition = text(tool, arguments, "transition", FixedTools.MAX_TRANSITION_LENGTH,
                            "the transition's name or the status it leads to");
                    yield new ToolCheck.Valid(object().put("key", key).put("transition", transition), "move " + key
                            + " through `" + transition + "`");
                }
                case FixedTools.LABEL -> {
                    LabelCall call = LabelCall.parse(tool, key(kind, tool, arguments), arguments);
                    yield new ToolCheck.Valid(call.toJson(), call.describe());
                }
                case FixedTools.CLOSE -> {
                    String key = key(kind, tool, arguments);
                    yield new ToolCheck.Valid(object().put("key", key), "close " + key);
                }
                case FixedTools.HISTORY -> history(tool, arguments);
                default -> new ToolCheck.Invalid(ToolOutcome.Code.UNKNOWN_TOOL, "`" + tool + "` is not a tool of a "
                        + kind + " system.");
            };
        }
        catch (Invalid ex) {
            return invalid(ex.getMessage());
        }
    }

    /** Slack's {@code history}: the channel, the thread when one is named, the limit, the cursor when one is named. */
    private static ToolCheck history(String tool, JsonNode arguments) {
        String channel = pattern(tool, arguments, "channel", SLACK_CHANNEL, "the channel's id, e.g. C0123ABCD", true);
        String thread = pattern(tool, arguments, "thread", SLACK_TS, "the ts of the thread's first message, e.g. "
                + "1712345678.000100", false);
        ObjectNode normalized = object().put("channel", channel);
        if (thread != null) {
            normalized.put("thread", thread);
        }
        normalized.put("limit", limit(tool, arguments, FixedTools.DEFAULT_HISTORY_LIMIT, FixedTools.MAX_HISTORY_LIMIT));
        JsonNode cursorNode = arguments.get("cursor");
        if (cursorNode != null && !cursorNode.isNull()) {
            String cursor = cursorNode.isString() ? cursorNode.stringValue().strip() : "";
            if (cursor.isEmpty() || cursor.length() > FixedTools.MAX_CURSOR_LENGTH
                    || cursor.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
                throw new Invalid("The `cursor` of `" + tool + "` is the one a previous result named.");
            }
            normalized.put("cursor", cursor);
        }
        return new ToolCheck.Valid(normalized, thread != null ? "read the thread " + thread + " of " + channel
                : "read the messages of " + channel);
    }

    /** A text field that matches the pattern, stripped; null when absent and not required. */
    private static @Nullable String pattern(String tool, JsonNode arguments, String field, Pattern pattern,
            String what, boolean required) {
        JsonNode node = arguments.get(field);
        if (!required && (node == null || node.isNull())) {
            return null;
        }
        String value = node != null && node.isString() ? node.stringValue().strip() : "";
        if (!pattern.matcher(value).matches()) {
            throw new Invalid("`" + tool + "` takes `" + field + "`: " + what + ".");
        }
        return value;
    }

    private static ToolCheck invalid(String message) {
        return new ToolCheck.Invalid(ToolOutcome.Code.INVALID_REQUEST, message);
    }

    private static ObjectNode object() {
        return JSON.createObjectNode();
    }

    /** The subject's key, stripped, in the system's format. */
    static String key(SystemKind kind, String tool, JsonNode arguments) {
        String format = switch (kind) {
            case GITHUB -> "owner/repo#number, e.g. acme/shop#57";
            case GITLAB -> "group/project#iid for an issue or group/project!iid for a merge request";
            default -> "the issue key, e.g. SHOP-241";
        };
        JsonNode node = arguments.get("key");
        String key = node != null && node.isString() ? node.stringValue().strip() : "";
        boolean fits = !key.isEmpty() && key.length() <= FixedTools.MAX_KEY_LENGTH
                && key.chars().noneMatch(Character::isWhitespace);
        if (fits && kind == SystemKind.GITHUB) {
            var matcher = GitHubTracker.KEY.matcher(key);
            fits = matcher.matches() && Long.parseLong(matcher.group(3)) >= 1
                    && Long.parseLong(matcher.group(3)) <= Integer.MAX_VALUE;
        }
        if (fits && kind == SystemKind.GITLAB) {
            var matcher = GitLabTracker.KEY.matcher(key);
            fits = matcher.matches() && Long.parseLong(matcher.group(3)) >= 1
                    && GitLabProject.parse(matcher.group(1)).isPresent();
        }
        if (!fits) {
            throw new Invalid("`" + tool + "` takes the subject's `key`: " + format + ".");
        }
        return key;
    }

    /** A text field, stripped, of 1 to {@code max} characters. */
    static String text(String tool, JsonNode arguments, String field, int max, String what) {
        JsonNode node = arguments.get(field);
        String value = node != null && node.isString() ? node.stringValue().strip() : "";
        if (value.isEmpty() || value.length() > max) {
            throw new Invalid("`" + tool + "` takes `" + field + "`, " + what + ", a text of 1 to " + max
                    + " characters.");
        }
        return value;
    }

    /** The {@code limit}, clamped to 1 to 50; 20 without one. */
    static int limit(String tool, JsonNode arguments) {
        return limit(tool, arguments, FixedTools.DEFAULT_SEARCH_LIMIT, FixedTools.MAX_SEARCH_LIMIT);
    }

    /** The {@code limit}, clamped to 1 to {@code max}; {@code fallback} without one. */
    static int limit(String tool, JsonNode arguments, int fallback, int max) {
        JsonNode limit = arguments.get("limit");
        if (limit == null || limit.isNull()) {
            return fallback;
        }
        if (!limit.isIntegralNumber()) {
            throw new Invalid("The `limit` of `" + tool + "` is a whole number from 1 to " + max + ".");
        }
        return (int) Math.clamp(limit.longValue(), 1, max);
    }
}
