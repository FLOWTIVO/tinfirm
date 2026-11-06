package com.flowtivo.tinfirm.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * The name a run lists a system's tool by (the package 3 contract, §5.4 and §11): {@code {handle}_{tool}} in lower
 * case, with the system's handle (package 1: {@code [a-z][a-z0-9_]}, at most 20 characters) and the tool as its system
 * names it (an MCP tool's name: {@code [A-Za-z0-9_-]}, at most 64). The runner protocol takes names of lower-case
 * letters, digits, {@code _} and {@code -} only ({@code ToolSpec.name}: {@code ^[a-z0-9][a-z0-9_-]{0,99}$}), and the
 * runtimes at most 64 characters, so a longer name is {@code {handle}_{the tool's name cut to fit}_{4 hex of its
 * hash}}: the first four hex digits of the SHA-256 of the tool's name in UTF-8, as its system spells it, so that two
 * tools cut to the same prefix still differ. A pure function: the same handle and tool give the same name in every
 * turn, in the agent's editor as in a run; the conversation keeps the names it listed and maps a call back by them.
 * Two tools of one system whose names differ in case only get the same name here: the conversation names the second
 * with a hash of its own.
 */
public final class ToolNames {

    /** The longest tool name the runtimes accept. */
    public static final int MAX_LENGTH = 64;

    private static final int HASH_LENGTH = 4;

    private ToolNames() {
    }

    /** The name a run lists the tool of the system with this handle by, in the runner protocol's lower case. */
    public static String of(String systemHandle, String tool) {
        if (systemHandle.isEmpty() || tool.isEmpty()) {
            throw new IllegalArgumentException("A tool name needs the system's handle and the tool");
        }
        String name = systemHandle + "_" + tool;
        if (name.length() <= MAX_LENGTH) {
            return name.toLowerCase(Locale.ROOT);
        }
        int room = MAX_LENGTH - systemHandle.length() - 2 - HASH_LENGTH;
        if (room < 1) {
            throw new IllegalArgumentException("The handle " + systemHandle + " leaves no room for a tool's name");
        }
        return (systemHandle + "_" + tool.substring(0, room) + "_" + hash(tool)).toLowerCase(Locale.ROOT);
    }

    private static String hash(String tool) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(tool.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HASH_LENGTH);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
