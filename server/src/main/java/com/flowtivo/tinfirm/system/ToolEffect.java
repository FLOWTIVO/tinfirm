package com.flowtivo.tinfirm.system;

/**
 * Whether a tool reads or writes on its system (the package 1 contract, §3.5; the redesign, Part 6). A fixed tool's is
 * known; an MCP tool's is {@code UNKNOWN} until an admin confirms it, and {@code UNKNOWN} counts as {@code WRITE}
 * everywhere ({@link #effective}).
 */
public enum ToolEffect {
    READ, WRITE, UNKNOWN;

    /** The effect a rule goes by: {@code UNKNOWN} counts as {@code WRITE}. */
    public ToolEffect effective() {
        return this == READ ? READ : WRITE;
    }
}
