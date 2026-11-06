package com.flowtivo.tinfirm.conversation.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.agent.ToolNames;

/**
 * The names a conversation's runs list its tools by (V009 {@code conversation_tool_name}; the package 3 contract, §5.4
 * and §11). A tool is named {@code ToolNames.of(handle, tool)} when the conversation first lists it, and keeps that
 * name for the conversation's life, also after its system's handle changed; a call names it, and is mapped back
 * through this table before anything else. {@code ToolNames.of} gives the runner protocol's lower-case names
 * ({@code ToolSpec.name}); a name another tool of the conversation holds already (two tools differing in case only, a
 * reused handle), or a platform tool's, is made unique with a hash of the system and the tool, within the runtimes' 64
 * characters.
 */
@Repository
class ToolNameStore {

    /** The platform tools the runner host serves itself; a system's tool never takes their names. */
    static final Set<String> PLATFORM_TOOLS = Set.of("ask", "finish", "request_access", "request_network_access",
            "save_artifact", "list_repositories");

    private static final Pattern WIRE = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    private final JdbcClient jdbc;

    ToolNameStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A tool of a system, by the system's name for it. */
    record Target(UUID systemId, String tool) {
    }

    /**
     * The name the conversation lists the tool by: the one it holds, else a new one, stored. In the caller's
     * transaction.
     */
    String nameOf(UUID companyId, UUID conversationId, UUID systemId, String handle, String tool, Instant now) {
        Optional<String> held = held(conversationId, systemId, tool);
        if (held.isPresent()) {
            return held.get();
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            String candidate = attempt == 0 ? wireName(handle, tool) : hashed(handle, systemId, tool, attempt);
            if (PLATFORM_TOOLS.contains(candidate) || !WIRE.matcher(candidate).matches()) {
                continue;
            }
            int inserted = jdbc.sql("""
                    INSERT INTO conversation_tool_name (company_id, conversation_id, name, system_id, tool, created_at)
                    VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING""")
                .params(companyId, conversationId, candidate, systemId, tool, DecisionStore.ts(now))
                .update();
            if (inserted == 1) {
                return candidate;
            }
            // Taken: by this tool meanwhile (a concurrent listing), or by another tool.
            Optional<String> raced = held(conversationId, systemId, tool);
            if (raced.isPresent()) {
                return raced.get();
            }
        }
        throw new IllegalStateException("No free name for tool " + tool + " of system " + systemId);
    }

    /** The tool a name of the conversation was listed for. */
    Optional<Target> target(UUID conversationId, String name) {
        return jdbc.sql("SELECT system_id, tool FROM conversation_tool_name WHERE conversation_id = ? AND name = ?")
            .params(conversationId, name)
            .query((rs, row) -> new Target(rs.getObject("system_id", UUID.class), rs.getString("tool")))
            .optional();
    }

    /** The names the conversation holds, for its tools. */
    List<String> names(UUID conversationId) {
        return jdbc.sql("SELECT name FROM conversation_tool_name WHERE conversation_id = ? ORDER BY name")
            .param(conversationId)
            .query(String.class)
            .list();
    }

    private Optional<String> held(UUID conversationId, UUID systemId, String tool) {
        return jdbc.sql("""
                SELECT name FROM conversation_tool_name WHERE conversation_id = ? AND system_id = ? AND tool = ?""")
            .params(conversationId, systemId, tool)
            .query(String.class)
            .optional();
    }

    /** The name a tool gets when nothing else holds it: {@code ToolNames.of}, the agent's editor's name too. */
    static String wireName(String handle, String tool) {
        return ToolNames.of(handle, tool);
    }

    /**
     * A name made unique: {@code {handle}_{the tool cut to fit}_{hex}}, the hex the first {@code 4 + 2 * attempt}
     * digits of the SHA-256 of the system's id and the tool, at most 64 characters.
     */
    static String hashed(String handle, UUID systemId, String tool, int attempt) {
        int digits = Math.min(4 + 2 * attempt, 32);
        String hex = sha256(systemId + "/" + tool).substring(0, digits);
        int room = ToolNames.MAX_LENGTH - handle.length() - 2 - digits;
        String cut = tool.length() > room ? tool.substring(0, Math.max(0, room)) : tool;
        String name = cut.isEmpty() ? handle + "_" + hex : handle + "_" + cut + "_" + hex;
        return name.toLowerCase(Locale.ROOT);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
