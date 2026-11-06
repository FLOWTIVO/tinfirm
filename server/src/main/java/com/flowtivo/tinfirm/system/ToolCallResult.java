package com.flowtivo.tinfirm.system;

import java.util.List;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

/**
 * What a tool call answers the agent, in the shape of an MCP {@code tools/call} result as the runner protocol's
 * {@code run.request.ack} of a {@code TOOL} request carries it ({@code $defs/ToolResult}): content blocks, which the
 * server sends as text, the same as structured content when the tool has one, and whether the system answered with an
 * error the agent should see. The text is other people's words in the prompt, never instructions.
 *
 * @param structuredContent a JSON object, or null
 */
public record ToolCallResult(List<Content> content, @Nullable JsonNode structuredContent,
        @JsonProperty("isError") boolean isError) {

    public ToolCallResult {
        content = List.copyOf(content);
        if (structuredContent != null && !structuredContent.isObject()) {
            throw new IllegalArgumentException("Structured content is an object");
        }
    }

    /** An MCP content block; Tinfirm sends text blocks. */
    public record Content(String type, String text) {

        public static Content text(String text) {
            return new Content("text", text);
        }
    }

    /** A result of one text block. */
    public static ToolCallResult text(String text, @Nullable JsonNode structuredContent, boolean isError) {
        return new ToolCallResult(List.of(Content.text(text)), structuredContent, isError);
    }

    /** The text of the blocks, one after another with a blank line between: what a decision keeps as its result. */
    public String text() {
        return content.stream().map(Content::text).collect(Collectors.joining("\n\n"));
    }
}
