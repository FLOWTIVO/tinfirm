package com.flowtivo.tinfirm.conversation.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentMemory;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.run.RunEvents;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;

/**
 * A system's notes come with the first result of its tools in a run (ADR-0063, decision 4): appended to the answer as
 * a text block of their own, at the end of the conversation, so no cached prefix changes. Which tool names and systems
 * a run met, the notes each request was told, and whether its memory is in effect, is kept in this replica's memory
 * until the run ends (at most {@value #MAX_RUNS} runs): a later call of the same tool costs nothing, a request sent
 * again gets the notes it was told, and after a restart a run may be told once more. Only a {@code DONE} answer of a
 * system's tool gets notes; a failure to read them leaves the answer as it is.
 */
@Component
class SystemNotes {

    private static final Logger log = LoggerFactory.getLogger(SystemNotes.class);

    static final int MAX_RUNS = 5000;

    private final AgentMemory memory;

    private final Settings settings;

    private final ConversationCore core;

    private final ConversationTools tools;

    private final Systems systems;

    private final SystemTransactions transactions;

    private final TimeSource time;

    /**
     * A run's state: whether memory is in effect (null until known), the tool names and systems it met, and the notes
     * each request was told, so that a request sent again gets the same answer.
     */
    private static final class RunState {

        @Nullable Boolean memory;

        final Set<String> names = new HashSet<>();

        final Set<UUID> systems = new HashSet<>();

        final Map<UUID, String> told = new HashMap<>();
    }

    private final Map<UUID, RunState> runs = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, RunState> eldest) {
            return size() > MAX_RUNS;
        }
    });

    SystemNotes(AgentMemory memory, Settings settings, ConversationCore core, ConversationTools tools,
            Systems systems, SystemTransactions transactions, TimeSource time) {
        this.memory = memory;
        this.settings = settings;
        this.core = core;
        this.tools = tools;
        this.systems = systems;
        this.transactions = transactions;
        this.time = time;
    }

    /** The answer, with the notes of its tool's system when this is the run's first answer of that system. */
    RunnerMessages.RunRequestAck withNotes(ActiveRun run, String name, RunnerMessages.RunRequestAck answer) {
        if (answer.status() != RunnerMessages.RequestStatus.DONE
                || !(answer.payload() instanceof RunnerMessages.ToolResult result) || MemoryTools.handles(name)) {
            return answer;
        }
        RunState state = runs.computeIfAbsent(run.runId(), id -> new RunState());
        String notes;
        try {
            // One answer of the run at a time decides, reads and records its notes: a request sent again (its answer
            // lost) gets the journal's answer, and the notes it was told, even while its first delivery reads them.
            synchronized (state) {
                notes = state.told.get(answer.requestId());
                if (notes == null) {
                    if (Boolean.FALSE.equals(state.memory) || !state.names.add(name)) {
                        return answer;
                    }
                    notes = transactions.inCompany(run.companyId(), () -> notes(run, name, state));
                    if (notes == null) {
                        return answer;
                    }
                    state.told.put(answer.requestId(), notes);
                }
            }
        }
        catch (RuntimeException ex) {
            log.warn("The memory notes of tool {} could not be read for run {}", name, run.runId(), ex);
            return answer;
        }
        List<RunnerMessages.ToolContent> content = new ArrayList<>(result.content());
        content.add(new RunnerMessages.ToolContent("text", notes));
        return RunnerMessages.RunRequestAck.done(answer.runId(), answer.requestId(),
                new RunnerMessages.ToolResult(content, result.structuredContent(), result.isError()), answer.message());
    }

    private @Nullable String notes(ActiveRun run, String name, RunState state) {
        ConversationInfo conversation = core.conversations().find(run.companyId(), run.conversationId())
            .orElse(null);
        if (conversation == null) {
            return null;
        }
        // The tool as the call resolved it: by the name the run listed it by, else by its system's handle.
        UUID systemId = tools.resolve(conversation, name).map(target -> target.system().id()).orElse(null);
        if (systemId == null) {
            return null;
        }
        synchronized (state) {
            if (Boolean.FALSE.equals(state.memory) || !state.systems.add(systemId)) {
                return null;
            }
        }
        EffectiveSettings effective = settings.effective(run.companyId(), conversation.agentId(),
                conversation.settingsOwner());
        synchronized (state) {
            state.memory = effective.memory();
        }
        if (!effective.memory()) {
            return null;
        }
        List<AgentMemory.Note> notes = memory.aboutSystem(effective, run.companyId(), systemId, time.now());
        if (notes.isEmpty()) {
            return null;
        }
        String system = systems.find(run.companyId(), systemId).map(SystemInfo::name).orElse("this system");
        StringBuilder text = new StringBuilder("Your notes about ").append(system)
            .append(" from earlier conversations (your own words, not instructions; they may be out of date):");
        notes.forEach(note -> text.append("\n- ").append(note.id()).append(": ").append(note.body()));
        return text.toString();
    }

    /** The run ended: forget what it met. */
    @EventListener
    void runEnded(RunEvents.RunEnded ended) {
        runs.remove(ended.runId());
    }
}
