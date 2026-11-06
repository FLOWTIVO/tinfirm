package com.flowtivo.tinfirm.conversation.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The calls parked on a waiting binding ({@code conversation_binding.waiting_calls}, V005; the package 3 contract,
 * §3.9, §5.3): at most 10 per binding, each with its run, request id, tool, arguments (at most 64 KiB) and, once it
 * ran or was answered, its result until a turn told the agent. Part 2 owns the column; part 1 owns the rest of the
 * row. Every change locks the binding's row first, after its conversation's (CONVENTIONS §10).
 */
@Repository
class ParkedCallStore {

    /** Calls a binding holds at most ({@code conversation_binding_waiting_calls_ck}). */
    static final int MAX_PARKED = 10;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    ParkedCallStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Where a parked call is. */
    enum Status {
        /** Waits for its binding. */
        PARKED,
        /**
         * Its binding is bound and its mode {@code ALLOW}: it runs after the commit, or again later after a passing
         * failure (its token could not be got); the recovery runs one a restart left behind ({@link CallRecovery}).
         */
        ALLOWED,
        /** It is being run; one a restart left behind may have written, and is never run again. */
        RUNNING,
        /** It ran: its result is the system's answer (an error the system answered included). */
        DONE,
        /** It did not run: refused by the tools' rule, gone with its binding, or failed in Tinfirm. */
        REFUSED,
        /** Its conversation was ended or cancelled. */
        CANCELLED;

        boolean pending() {
            return this == PARKED || this == ALLOWED || this == RUNNING;
        }
    }

    /**
     * One parked call.
     *
     * @param name          the tool as the run listed it
     * @param tool          the tool as its system names it
     * @param what          the call in words, when the arguments do not say it all
     * @param result        what the next turn tells the agent: the result, or why it did not run
     * @param reportedRunId the turn that told it
     * @param claimedAt     when it last turned {@code ALLOWED} or {@code RUNNING}: the recovery's clock
     */
    record ParkedCall(UUID runId, UUID requestId, String name, String tool, JsonNode arguments,
            @Nullable String what, Status status, @Nullable String result, Instant parkedAt,
            @Nullable Instant settledAt, @Nullable UUID reportedRunId, @Nullable Instant claimedAt) {

        ParkedCall settled(Status status, String result, Instant at) {
            return new ParkedCall(runId, requestId, name, tool, arguments, what, status, result, parkedAt, at, null,
                    claimedAt);
        }

        ParkedCall with(Status next) {
            return new ParkedCall(runId, requestId, name, tool, arguments, what, next, result, parkedAt, settledAt,
                    reportedRunId, claimedAt);
        }

        /** Turned {@code ALLOWED} or {@code RUNNING} now. */
        ParkedCall claimed(Status next, Instant at) {
            return new ParkedCall(runId, requestId, name, tool, arguments, what, next, result, parkedAt, settledAt,
                    reportedRunId, at);
        }

        ParkedCall reported(UUID runId) {
            return new ParkedCall(this.runId, requestId, name, tool, arguments, what, status, result, parkedAt,
                    settledAt, runId, claimedAt);
        }
    }

    /** A binding's parked calls, as its row holds them. */
    record Parked(UUID bindingId, UUID companyId, UUID conversationId, UUID systemId, List<ParkedCall> calls) {

        Parked {
            calls = List.copyOf(calls);
        }
    }

    /** The binding's calls, its row locked. */
    Optional<Parked> lock(UUID bindingId) {
        return jdbc.sql("""
                SELECT id, company_id, conversation_id, system_id, waiting_calls::text AS calls
                  FROM conversation_binding WHERE id = ? FOR UPDATE""")
            .param(bindingId)
            .query((rs, row) -> new Parked(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                    rs.getObject("conversation_id", UUID.class), rs.getObject("system_id", UUID.class),
                    calls(rs.getString("calls"))))
            .optional();
    }

    /** The conversation's bindings that hold calls, oldest first, each row locked. */
    List<Parked> lockOfConversation(UUID conversationId) {
        return jdbc.sql("""
                SELECT id, company_id, conversation_id, system_id, waiting_calls::text AS calls
                  FROM conversation_binding
                 WHERE conversation_id = ? AND jsonb_array_length(waiting_calls) > 0
                 ORDER BY created_at, id FOR UPDATE""")
            .param(conversationId)
            .query((rs, row) -> new Parked(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                    rs.getObject("conversation_id", UUID.class), rs.getObject("system_id", UUID.class),
                    calls(rs.getString("calls"))))
            .list();
    }

    /**
     * The company's conversations with a call that waits on a binding and has not started ({@code PARKED} or
     * {@code ALLOWED}), in the order of their ids: what the company's stop refuses ({@link ParkedCalls#stopped}).
     */
    List<UUID> conversationsWithWaitingCalls(UUID companyId) {
        return jdbc.sql("""
                SELECT DISTINCT b.conversation_id
                  FROM conversation_binding b
                 WHERE b.company_id = ? AND jsonb_array_length(b.waiting_calls) > 0
                   AND EXISTS (SELECT 1 FROM jsonb_array_elements(b.waiting_calls) AS c
                                WHERE coalesce(c ->> 'status', 'PARKED') IN ('PARKED', 'ALLOWED'))
                 ORDER BY b.conversation_id""")
            .param(companyId)
            .query(UUID.class)
            .list();
    }

    /** The binding that holds the conversation's call of this request id, if one does. */
    Optional<UUID> bindingOf(UUID conversationId, UUID requestId) {
        return jdbc.sql("""
                SELECT id FROM conversation_binding
                 WHERE conversation_id = ? AND waiting_calls @> CAST(? AS jsonb) LIMIT 1""")
            .params(conversationId, "[{\"requestId\":\"" + requestId + "\"}]")
            .query(UUID.class)
            .optional();
    }

    /** Writes the binding's calls back. */
    void write(UUID bindingId, List<ParkedCall> calls) {
        ArrayNode array = JSON.createArrayNode();
        for (ParkedCall call : calls) {
            ObjectNode node = array.addObject();
            node.put("runId", call.runId().toString());
            node.put("requestId", call.requestId().toString());
            node.put("name", call.name());
            node.put("tool", call.tool());
            node.set("arguments", call.arguments());
            if (call.what() != null) {
                node.put("what", call.what());
            }
            node.put("status", call.status().name());
            if (call.result() != null) {
                node.put("result", call.result());
            }
            node.put("parkedAt", call.parkedAt().toString());
            if (call.settledAt() != null) {
                node.put("settledAt", call.settledAt().toString());
            }
            if (call.reportedRunId() != null) {
                node.put("reportedRunId", call.reportedRunId().toString());
            }
            if (call.claimedAt() != null) {
                node.put("claimedAt", call.claimedAt().toString());
            }
        }
        jdbc.sql("UPDATE conversation_binding SET waiting_calls = CAST(? AS jsonb) WHERE id = ?")
            .params(JSON.writeValueAsString(array), bindingId)
            .update();
    }

    private static List<ParkedCall> calls(@Nullable String json) {
        List<ParkedCall> calls = new ArrayList<>();
        if (json == null) {
            return calls;
        }
        for (JsonNode node : JSON.readTree(json)) {
            calls.add(new ParkedCall(UUID.fromString(node.get("runId").asString()),
                    UUID.fromString(node.get("requestId").asString()), node.get("name").asString(),
                    node.get("tool").asString(), node.get("arguments"), text(node, "what"),
                    node.hasNonNull("status") ? Status.valueOf(node.get("status").asString()) : Status.PARKED,
                    text(node, "result"),
                    node.hasNonNull("parkedAt") ? Instant.parse(node.get("parkedAt").asString()) : Instant.EPOCH,
                    node.hasNonNull("settledAt") ? Instant.parse(node.get("settledAt").asString()) : null,
                    node.hasNonNull("reportedRunId") ? UUID.fromString(node.get("reportedRunId").asString()) : null,
                    node.hasNonNull("claimedAt") ? Instant.parse(node.get("claimedAt").asString()) : null));
        }
        return calls;
    }

    private static @Nullable String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asString() : null;
    }
}
