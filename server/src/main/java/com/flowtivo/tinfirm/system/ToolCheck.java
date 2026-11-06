package com.flowtivo.tinfirm.system;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * The arguments of a tool call checked before anything runs or waits for an approval ({@link SystemTools#check}): the
 * normalized arguments the call runs with, now or once a person approved it, and what the call does in words, which an
 * approval shows; or why they do not fit.
 */
public sealed interface ToolCheck permits ToolCheck.Valid, ToolCheck.Invalid {

    /**
     * @param arguments the normalized arguments: a fixed tool's checked and trimmed, an MCP tool's as given (the
     *                  server validates them against its own schema)
     * @param what      the call in words, e.g. {@code comment on SHOP-241}; null when the arguments say it all (an
     *                  MCP tool's)
     */
    record Valid(JsonNode arguments, @Nullable String what) implements ToolCheck {
    }

    /**
     * @param code    {@code UNKNOWN_TOOL} or {@code INVALID_REQUEST}
     * @param message for the agent
     */
    record Invalid(ToolOutcome.Code code, String message) implements ToolCheck {
    }
}
