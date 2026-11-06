package com.flowtivo.tinfirm.system.internal.tools;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.FixedTools;
import com.flowtivo.tinfirm.system.ToolCallResult;
import com.flowtivo.tinfirm.system.ToolOutcome;
import com.flowtivo.tinfirm.system.internal.mcp.McpClient;
import com.flowtivo.tinfirm.system.internal.slack.SlackApi;

import tools.jackson.databind.JsonNode;

/**
 * Runs one checked call on a tracker, or answers an MCP server's, as a {@link ToolOutcome}: the system's answer as the
 * agent's result; a refused credential ({@code UNAUTHORIZED}) as {@link ToolOutcome.Reconnect}, before anything was
 * written; any other refusal as a result with {@code isError} that names it.
 */
public final class ToolCalls {

    private ToolCalls() {
    }

    /**
     * Runs a fixed tool with arguments {@link ToolArguments} normalized.
     *
     * @param system the system's name, as the answers name it
     * @param label  the bold label in front of a comment; null for none
     */
    public static ToolOutcome run(Tracker tracker, String system, String tool, JsonNode arguments,
            @Nullable String label) {
        String key = text(arguments, "key");
        return switch (tool) {
            case FixedTools.GET -> answer(tool, system, tracker.get(key, Tracker.COMMENTS),
                    subject -> ToolResults.subject(tracker, subject), null);
            case FixedTools.SEARCH -> answer(tool, system, tracker.search(text(arguments, "query"),
                    Objects.requireNonNull(arguments.get("limit")).asInt()), ToolResults::search, null);
            case FixedTools.COMMENT -> {
                CallResult<Subject.Posted> posted = tracker.comment(key, text(arguments, "body"), label);
                yield answer(tool, system, posted, value -> ToolResults.commented(key, value),
                        posted.value() != null ? new ToolOutcome.Written(ToolOutcome.WriteKind.COMMENT, key,
                                Long.toString(posted.value().id())) : null);
            }
            case FixedTools.TRANSITION -> transition(tracker, system, tool, key, text(arguments, "transition"));
            case FixedTools.LABEL -> LabelCall.parse(tool, key, arguments).run(tool, system, tracker);
            case FixedTools.CLOSE -> answer(tool, system, tracker.close(key), closed -> ToolResults.closed(key),
                    new ToolOutcome.Written(ToolOutcome.WriteKind.CLOSE, key, null));
            default -> new ToolOutcome.Refused(ToolOutcome.Code.UNKNOWN_TOOL, "`" + tool + "` is not a tool of "
                    + system + ".");
        };
    }

    /**
     * The transition of that name among those the issue's status offers, ignoring case, else the one that leads to a
     * status of that name; when there is none, an error result that lists those it offers.
     */
    private static ToolOutcome transition(Tracker tracker, String system, String tool, String key, String asked) {
        CallResult<List<Subject.Transition>> offered = tracker.transitions(key);
        if (offered.value() == null) {
            return refusal(tool, system, Objects.requireNonNull(offered.failure()));
        }
        Optional<Subject.Transition> chosen = offered.value()
            .stream()
            .filter(candidate -> candidate.name().equalsIgnoreCase(asked))
            .findFirst()
            .or(() -> offered.value()
                .stream()
                .filter(candidate -> candidate.to() != null && candidate.to().equalsIgnoreCase(asked))
                .findFirst());
        if (chosen.isEmpty()) {
            return new ToolOutcome.Done(ToolResults.noSuchTransition(key, asked, offered.value()),
                    CallResult.Failure.of(CallResult.Kind.NOT_FOUND, "no transition " + asked), null);
        }
        Subject.Transition transition = chosen.get();
        return answer(tool, system, tracker.transition(key, transition.id()),
                done -> ToolResults.transitioned(key, transition),
                new ToolOutcome.Written(ToolOutcome.WriteKind.TRANSITION, key, null, transition.to(), List.of(),
                        List.of()));
    }

    /**
     * Slack's {@code history} (the package 5 contract, open point 13) with arguments {@link ToolArguments} normalized,
     * read with the account's user token: a channel's latest messages or a thread's. A token without the history
     * scopes ({@code missing_scope}) must be connected again, as a refused one.
     */
    public static ToolOutcome slack(SlackApi slack, String token, String system, String tool, JsonNode arguments) {
        if (!FixedTools.HISTORY.equals(tool)) {
            return new ToolOutcome.Refused(ToolOutcome.Code.UNKNOWN_TOOL, "`" + tool + "` is not a tool of " + system
                    + ".");
        }
        String channel = text(arguments, "channel");
        String thread = arguments.has("thread") ? text(arguments, "thread") : null;
        String cursor = arguments.has("cursor") ? text(arguments, "cursor") : null;
        int limit = Objects.requireNonNull(arguments.get("limit")).asInt();
        CallResult<SlackApi.Messages> read = slack.history(token, channel, thread, limit, cursor);
        CallResult.Failure failure = read.failure();
        if (failure != null && failure.message().contains("missing_scope")) {
            return new ToolOutcome.Reconnect(failure.message() + ": the account was connected without the history "
                    + "scopes and must be connected again");
        }
        if (failure != null && failure.kind() == CallResult.Kind.NOT_FOUND) {
            return new ToolOutcome.Done(ToolCallResult.text("`" + tool + "` on " + system + " failed: "
                    + failure.message() + ". The channel or the thread does not exist, or the account's Slack user may "
                    + "not read it (a private channel they are not in).", null, true), failure, null);
        }
        return answer(tool, system, read, messages -> ToolResults.history(channel, thread, messages), null);
    }

    /** An MCP server's answer to {@code tools/call}. */
    public static ToolOutcome mcp(String system, String tool, CallResult<McpClient.Called> called) {
        return answer(tool, system, called, ToolResults::mcp, null);
    }

    /** The result of a value, or the refusal; what was written only when it succeeded. */
    private static <T> ToolOutcome answer(String tool, String system, CallResult<T> result,
            Function<T, ToolCallResult> render, ToolOutcome.@Nullable Written written) {
        if (result.failure() != null) {
            return refusal(tool, system, result.failure());
        }
        return new ToolOutcome.Done(render.apply(Objects.requireNonNull(result.value())), null, written);
    }

    /** {@code UNAUTHORIZED}: the account must be connected again; any other refusal: a result with {@code isError}. */
    static ToolOutcome refusal(String tool, String system, CallResult.Failure failure) {
        if (failure.kind() == CallResult.Kind.UNAUTHORIZED) {
            return new ToolOutcome.Reconnect(failure.message());
        }
        return new ToolOutcome.Done(ToolResults.failure(tool, system, failure), failure, null);
    }

    private static String text(JsonNode arguments, String field) {
        JsonNode value = arguments.get(field);
        return value != null && value.isString() ? value.stringValue() : "";
    }
}
