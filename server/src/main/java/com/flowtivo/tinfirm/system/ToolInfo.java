package com.flowtivo.tinfirm.system;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * A tool a system serves (the package 1 contract, §3.5): one of Tinfirm's fixed tools of a tracker, or an entry of an
 * MCP server's catalogue as its last refresh found it.
 *
 * @param effect  the fixed tool's effect; an MCP tool's confirmed effect, else {@code UNKNOWN} (counts as
 *                {@code WRITE}: {@link ToolEffect#effective})
 * @param enabled whether agents may list it; a fixed tool always is
 * @param fixed   whether it is one of Tinfirm's fixed tools
 * @param replay  whether its call may run again after Tinfirm lost track of it (package R, §3.1): the effect's default
 *                ({@link ToolReplay#of}) unless the system's catalogue declares a write {@code SAFE}
 */
public record ToolInfo(String name, @Nullable String title, @Nullable String description, JsonNode inputSchema,
        ToolEffect effect, boolean enabled, boolean fixed, ToolReplay replay) {

    public ToolInfo {
        Objects.requireNonNull(replay, "replay");
    }

    /** A tool whose replay class is its effect's default. */
    public ToolInfo(String name, @Nullable String title, @Nullable String description, JsonNode inputSchema,
            ToolEffect effect, boolean enabled, boolean fixed) {
        this(name, title, description, inputSchema, effect, enabled, fixed, ToolReplay.of(effect));
    }
}
