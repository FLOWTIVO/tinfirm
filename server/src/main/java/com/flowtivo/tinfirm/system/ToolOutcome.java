package com.flowtivo.tinfirm.system;

import org.jspecify.annotations.Nullable;

/**
 * How a tool call of {@link SystemTools#call} ended (the package 3 contract, §5.4 and §7). Never holds a token.
 * <ul>
 * <li>{@link Done}: the call ran, and the system answered. The result is the agent's, also when the system refused
 * (step 6 of §5.4: its own permissions for the bound account, a key it does not know, its rate limit, a moment it is
 * unavailable), which is a result with {@code isError} that names the system's answer. The runner protocol's ack is
 * {@code DONE} with it.</li>
 * <li>{@link Reconnect}: the system refused the credential itself ({@code 401}): the account must be connected again.
 * Nothing was done. The caller stops the binding ({@code RECONNECT_YOURS} or {@code RECONNECT_COMPANY}, §5.3), which
 * is the conversation's to decide, never the system module's.</li>
 * <li>{@link Refused}: the call was not run, or its result cannot be carried: {@code UNKNOWN_TOOL},
 * {@code INVALID_REQUEST}, {@code RESULT_TOO_LARGE} (with a message that asks to narrow the query) or
 * {@code TOOL_FAILED} (the timeout, an internal error). The ack is {@code REJECTED} with the code and the message.</li>
 * </ul>
 */
public sealed interface ToolOutcome permits ToolOutcome.Done, ToolOutcome.Reconnect, ToolOutcome.Refused {

    /**
     * The call ran.
     *
     * @param failure what the system refused, when it did ({@code result.isError()}); for the audit's {@code ok} and
     *                {@code code}
     * @param written what the call wrote to the system, for the caller's {@code SystemWritten} (§3.14): null for a
     *                read, a write the system refused, and an MCP server's tool, of which Tinfirm knows nothing
     */
    record Done(ToolCallResult result, CallResult.@Nullable Failure failure, @Nullable Written written)
            implements ToolOutcome {
    }

    /**
     * The system refused the account's credential: it must be connected again.
     *
     * @param message the system's answer, without the credential, e.g. {@code Jira answered 401}
     */
    record Reconnect(String message) implements ToolOutcome {
    }

    /** The call was not run, or its result cannot be carried; the message is for the agent. */
    record Refused(Code code, String message) implements ToolOutcome {
    }

    /** The runner protocol's codes of a {@code REJECTED} tool call that the system module decides. */
    enum Code {
        /** Not one of the system's tools, switched off, or not called by the agent ({@code open_change_request}). */
        UNKNOWN_TOOL,
        /** The arguments do not fit the tool. */
        INVALID_REQUEST,
        /** The result is larger than {@link SystemTools#MAX_RESULT_BYTES}: the agent narrows the query. */
        RESULT_TOO_LARGE,
        /** The call did not finish within the timeout, or failed inside Tinfirm. */
        TOOL_FAILED
    }

    /**
     * What a write did on the system.
     *
     * @param key           the subject's key, e.g. {@code SHOP-241}
     * @param externalId    the system's id of what was written: a comment's id; null where there is none
     * @param status        a transition's status entered, when the system named it (the package 4 contract, §2.2:
     *                      a poll then raises no {@code STATUS_ENTERED} for it); null otherwise
     * @param labelsAdded   the labels a {@code LABELS} write added
     * @param labelsRemoved the labels a {@code LABELS} write removed
     */
    record Written(WriteKind kind, String key, @Nullable String externalId, @Nullable String status,
            java.util.List<String> labelsAdded, java.util.List<String> labelsRemoved) {

        public Written {
            labelsAdded = java.util.List.copyOf(labelsAdded);
            labelsRemoved = java.util.List.copyOf(labelsRemoved);
        }

        /** A write without a status or labels: a comment, a close. */
        public Written(WriteKind kind, String key, @Nullable String externalId) {
            this(kind, key, externalId, null, java.util.List.of(), java.util.List.of());
        }
    }

    /** The kinds of writes of the fixed catalogues. */
    enum WriteKind {
        COMMENT, TRANSITION, LABELS, CLOSE
    }
}
