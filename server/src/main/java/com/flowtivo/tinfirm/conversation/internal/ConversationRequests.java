package com.flowtivo.tinfirm.conversation.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.run.RunRequests;
import com.flowtivo.tinfirm.runner.RunnerMessages;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The run module's port {@link RunRequests}, the platform requests of a conversation's runs (the package 3 contract,
 * §5.3 to §5.5, §5.8): a tool call ({@link ToolCalls}) outside any transaction, the other kinds
 * ({@link PlatformRequests}) in the transaction that checked the run. Each is idempotent by its request id: a tool
 * call through the call journal ({@link ToolJournal}, package R), on any replica and after a restart. The memory tools
 * ({@link MemoryTools}, ADR-0063) are answered apart, their writes journaled in the same call journal, and a system's
 * notes come with its first answer in the run ({@link SystemNotes}).
 */
@Component
class ConversationRequests implements RunRequests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ToolCalls tools;

    private final PlatformRequests platform;

    private final MemoryTools memory;

    private final SystemNotes notes;

    ConversationRequests(ToolCalls tools, PlatformRequests platform, MemoryTools memory, SystemNotes notes) {
        this.tools = tools;
        this.platform = platform;
        this.memory = memory;
        this.notes = notes;
    }

    @Override
    public RunnerMessages.RunRequestAck request(ActiveRun run, RunnerMessages.RequestKind kind, UUID requestId,
            JsonNode payload) {
        return platform.request(run, kind, requestId, payload);
    }

    @Override
    public RunnerMessages.RunRequestAck call(ActiveRun run, UUID requestId, JsonNode payload) {
        JsonNode tool = payload.get("tool");
        String name = tool != null && tool.isString() ? tool.asString().strip() : null;
        if (name != null && MemoryTools.handles(name)) {
            JsonNode arguments = payload.get("arguments");
            if (arguments != null && !arguments.isNull() && !arguments.isObject()) {
                return RunnerMessages.RunRequestAck.rejected(run.runId(), requestId, ToolCalls.INVALID_REQUEST,
                        "The arguments of " + ToolRules.quoted(name) + " are a JSON object.");
            }
            return memory.call(run, requestId, name, arguments != null && arguments.isObject() ? arguments
                    : JSON.createObjectNode());
        }
        RunnerMessages.RunRequestAck answer = tools.call(run, requestId, payload);
        return name != null ? notes.withNotes(run, name, answer) : answer;
    }
}
