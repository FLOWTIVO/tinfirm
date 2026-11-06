package com.flowtivo.tinfirm.conversation.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.AgentMemory;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.Settings;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.run.ActiveRun;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.ToolReplay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The memory tools of a conversation's run (ADR-0063, decision 3): {@code remember}, {@code forget} and
 * {@code recall}, listed in {@code run.assign.tools} while memory is in effect and answered here, apart from the
 * systems' tools ({@link ToolCalls}). Their names have no underscore, so no system's tool ({@code {handle}_{tool}}) is
 * ever named like them. Each call is one short transaction in the run's company, which reads the conversation's
 * settings in effect again.
 * <p>
 * The writes, {@code remember} and {@code forget}, are journaled in package R's call journal ({@link ToolCallStore},
 * V044; CONVENTIONS §15.14, §15.18) by their run and request id, as a system tool's call, never on a note: a request
 * id the journal holds is answered from it before any check, with the answer its first request got, whatever became of
 * the note since (replaced by a later request, forgotten, deleted by its owner, merged or dropped by a compaction).
 * Their row is claimed in the transaction that writes the memory, after the checks and before the memory's lock, as an
 * idempotency key is ({@code INSERT … ON CONFLICT DO NOTHING}): a request sent again at the same time waits for the
 * first one's commit and then answers what it stored. The answer is kept until the run ends, as a system tool's: a host
 * sends a request again only while its run runs. A refusal of the checks before the claim (the arguments, memory off)
 * writes nothing and keeps nothing. {@code recall} is a read, and may run twice.
 */
@Component
class MemoryTools {

    static final String REMEMBER = "remember";

    static final String FORGET = "forget";

    static final String RECALL = "recall";

    private static final Set<String> NAMES = Set.of(REMEMBER, FORGET, RECALL);

    private static final Pattern NOTE_ID = Pattern.compile("^m([1-9][0-9]{0,8})$");

    private static final int MAX_QUERY = 200;

    private static final int MAX_ABOUT = 200;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final JsonNode REMEMBER_SCHEMA = JSON.readTree("""
            {"type":"object","additionalProperties":false,"required":["note"],"properties":{
            "note":{"type":"string","minLength":1,"maxLength":300,"description":"One short, lasting fact, on one line"},
            "about":{"type":"string","maxLength":200,"description":"The system (its handle) or repository (its name) \
            it concerns; leave it out for a general note"},
            "replaces":{"type":"string","pattern":"^m[0-9]+$","description":"The id of a note this one updates, \
            such as m3"}}}""");

    private static final JsonNode FORGET_SCHEMA = JSON.readTree("""
            {"type":"object","additionalProperties":false,"required":["note"],"properties":{
            "note":{"type":"string","pattern":"^m[0-9]+$","description":"The note's id, such as m3"}}}""");

    private static final JsonNode RECALL_SCHEMA = JSON.readTree("""
            {"type":"object","additionalProperties":false,"required":["query"],"properties":{
            "query":{"type":"string","minLength":1,"maxLength":200,"description":"Words to look for"}}}""");

    private final AgentMemory memory;

    private final Settings settings;

    private final ConversationCore core;

    private final Systems systems;

    private final SystemTransactions transactions;

    private final TimeSource time;

    private final ToolCallStore calls;

    private final RunReads runs;

    MemoryTools(AgentMemory memory, Settings settings, ConversationCore core, Systems systems,
            SystemTransactions transactions, TimeSource time, ToolCallStore calls, RunReads runs) {
        this.memory = memory;
        this.settings = settings;
        this.core = core;
        this.systems = systems;
        this.transactions = transactions;
        this.time = time;
        this.calls = calls;
        this.runs = runs;
    }

    /** Whether the name is a memory tool's. */
    static boolean handles(String name) {
        return NAMES.contains(name);
    }

    /**
     * The memory tools a run lists: none unless memory is in effect for its settings. Their descriptions name whom the
     * notes are kept for, the same in every turn of the conversation.
     */
    List<RunnerMessages.ToolSpec> specs(TurnContext turn, ConversationTools.Texts texts) {
        if (!turn.settings().memory()) {
            return List.of();
        }
        boolean trigger = turn.conversation().triggered();
        String forWhom = trigger ? "the later conversations of this trigger" : "your later conversations with "
                + texts.person();
        return List.of(
                new RunnerMessages.ToolSpec(REMEMBER, "Save a short, lasting fact for " + forWhom + ": a preference, "
                        + "a convention of a system or repository, where something is. One fact per note; `replaces` "
                        + "updates a note. Never save credentials or secrets."
                        + (trigger ? " The trigger's team reviews each note before later conversations read it." : ""),
                        REMEMBER_SCHEMA, RunnerMessages.ToolReplay.SAFE),
                new RunnerMessages.ToolSpec(FORGET, "Delete one of your notes that is wrong or no longer useful, by "
                        + "its id (m3).", FORGET_SCHEMA, RunnerMessages.ToolReplay.SAFE),
                new RunnerMessages.ToolSpec(RECALL, "Search all your notes from earlier conversations by words; "
                        + "answers the best five.", RECALL_SCHEMA, RunnerMessages.ToolReplay.SAFE));
    }

    /** A call of a memory tool, outside any transaction: its answer, in one short transaction. */
    RunnerMessages.RunRequestAck call(ActiveRun run, UUID requestId, String name, JsonNode arguments) {
        String argumentsSha256 = ToolJournal.sha256(arguments);
        return transactions.inCompany(run.companyId(), () -> {
            // A request id sent again (a reconnect, another replica): what the journal holds, before any check.
            ToolCallStore.Row known = calls.find(run.runId(), requestId).orElse(null);
            if (known != null) {
                return again(run, requestId, name, argumentsSha256, known);
            }
            ConversationInfo conversation = core.conversations().find(run.companyId(), run.conversationId())
                .orElse(null);
            if (conversation == null) {
                return rejected(run, requestId, ToolCalls.RUN_NOT_ACTIVE, "The run is not active.");
            }
            if (!conversation.open()) {
                return rejected(run, requestId, ToolCalls.CONVERSATION_ENDED, "The conversation ended, so nothing "
                        + "more runs in it.");
            }
            EffectiveSettings effective = settings.effective(run.companyId(), conversation.agentId(),
                    conversation.settingsOwner());
            if (!effective.memory()) {
                return rejected(run, requestId, ToolRules.UNKNOWN_TOOL, "There is no tool " + ToolRules.quoted(name)
                        + " in this conversation: memory is off. The tools you can call are those your tool list "
                        + "shows.");
            }
            AgentMemory.Source source = new AgentMemory.Source(effective, run.companyId(), conversation.id(),
                    run.runId());
            return switch (name) {
                case REMEMBER -> remember(run, requestId, arguments, argumentsSha256, source);
                case FORGET -> forget(run, requestId, arguments, argumentsSha256, source);
                default -> recall(run, requestId, arguments, effective);
            };
        });
    }

    /**
     * A write of the memory, journaled: the request's row claimed {@code RUNNING} first, in the caller's transaction,
     * then the write, then the row {@code DONE} with the answer as it goes out, all committed together. A row another
     * request with this id claimed first (its transaction committed while this one waited on the row's key) answers
     * as that request was answered; a run that no longer runs claims and writes nothing ({@code RUN_NOT_ACTIVE}), as a
     * system tool's claim ({@link ToolJournal#claim}).
     */
    private RunnerMessages.RunRequestAck journaled(ActiveRun run, UUID requestId, String name,
            String argumentsSha256, AgentMemory.Source source, Supplier<RunnerMessages.RunRequestAck> write) {
        boolean running = runs.find(run.companyId(), run.runId())
            .filter(found -> found.status() == RunStatus.RUNNING && found.epoch() == run.epoch())
            .isPresent();
        if (!running) {
            return rejected(run, requestId, ToolCalls.RUN_NOT_ACTIVE, "The run is not active.");
        }
        boolean claimed = calls.insert(new ToolCallStore.Row(run.companyId(), run.runId(), requestId,
                source.conversationId(), null, name, name, ToolEffect.WRITE, ToolReplay.SAFE, argumentsSha256,
                ToolCallStore.State.RUNNING, 1, time.now(), null, null, null));
        if (!claimed) {
            return calls.find(run.runId(), requestId)
                .map(first -> again(run, requestId, name, argumentsSha256, first))
                .orElseGet(() -> rejected(run, requestId, ToolCalls.RUN_NOT_ACTIVE, "The run is not active."));
        }
        RunnerMessages.RunRequestAck answer = write.get();
        calls.finish(run.runId(), requestId, 1, ToolCallStore.State.DONE, ToolJournal.stored(answer), time.now());
        return answer;
    }

    /**
     * A request whose id the journal holds: the answer its first request got. Another call under the id (another tool,
     * other arguments) is {@code INVALID_REQUEST}, as {@link ToolCalls} answers it. A row without its answer (the run's
     * end cleared it, and the run was resumed after its loss) ran once and runs no more.
     */
    private static RunnerMessages.RunRequestAck again(ActiveRun run, UUID requestId, String name,
            String argumentsSha256, ToolCallStore.Row first) {
        if (!first.name().equals(name) || !first.argumentsSha256().equals(argumentsSha256)) {
            return rejected(run, requestId, ToolCalls.INVALID_REQUEST, "The request " + requestId + " called "
                    + ToolRules.quoted(first.name()) + " with other arguments before: a request sent again is the "
                    + "same call. Call it with a new request id.");
        }
        if (first.state() == ToolCallStore.State.DONE && first.result() != null) {
            return ToolJournal.answer(run.runId(), requestId, first.result());
        }
        return done(run, requestId, "This request was answered before, and Tinfirm no longer keeps its answer: "
                + "nothing was saved or deleted again. `" + RECALL + "` shows your notes as they are now.");
    }

    private RunnerMessages.RunRequestAck remember(ActiveRun run, UUID requestId, JsonNode arguments,
            String argumentsSha256, AgentMemory.Source source) {
        String note = text(arguments, "note");
        if (note == null) {
            return rejected(run, requestId, ToolCalls.INVALID_REQUEST, "Give the note: one short, lasting fact.");
        }
        String replacing = text(arguments, "replaces");
        Integer replaces = replacing != null ? number(replacing) : null;
        if (replacing != null && replaces == null) {
            return rejected(run, requestId, ToolCalls.INVALID_REQUEST, "`replaces` is a note's id, such as m3.");
        }
        String aboutText = text(arguments, "about");
        AgentMemory.About about = aboutText != null ? about(run.companyId(), source.settings(), aboutText)
                : AgentMemory.About.GENERAL;
        if (about == null) {
            return rejected(run, requestId, ToolCalls.INVALID_REQUEST, "There is no system or repository "
                    + ToolRules.quoted(aboutText) + " for this agent; nothing was saved. Name a system by its "
                    + "handle, as its tools start, or a repository by its name, or leave `about` out.");
        }
        return journaled(run, requestId, REMEMBER, argumentsSha256, source,
                () -> switch (memory.remember(source, note, about, replaces)) {
                    case AgentMemory.Written.Saved saved -> done(run, requestId,
                            (replaces != null ? "Saved " : "Saved as ") + saved.note().id() + ".");
                    case AgentMemory.Written.Known known -> done(run, requestId, known.note().id()
                            + " says this already.");
                    case AgentMemory.Written.Proposed proposed -> done(run, requestId, "Proposed as "
                            + proposed.note().id() + ": the trigger's team reviews it before later conversations "
                            + "read it.");
                    case AgentMemory.Written.Refused refused -> rejected(run, requestId, refused.code(),
                            refused.message());
                });
    }

    private RunnerMessages.RunRequestAck forget(ActiveRun run, UUID requestId, JsonNode arguments,
            String argumentsSha256, AgentMemory.Source source) {
        String id = text(arguments, "note");
        Integer number = id != null ? number(id) : null;
        if (number == null) {
            return rejected(run, requestId, ToolCalls.INVALID_REQUEST, "Name the note by its id, such as m3.");
        }
        return journaled(run, requestId, FORGET, argumentsSha256, source,
                () -> done(run, requestId, switch (memory.forget(source, number)) {
                    case FORGOTTEN -> "Forgot m" + number + ".";
                    case NO_SUCH_NOTE -> "There is no note m" + number + ".";
                    case REVIEWED -> "m" + number + " was accepted by the trigger's team, and only they delete it. "
                            + "If it is wrong, propose a note that `replaces` it, or say so in your summary.";
                }));
    }

    private RunnerMessages.RunRequestAck recall(ActiveRun run, UUID requestId, JsonNode arguments,
            EffectiveSettings effective) {
        String query = text(arguments, "query");
        if (query == null || query.length() > MAX_QUERY) {
            return rejected(run, requestId, ToolCalls.INVALID_REQUEST, "Give the words to look for, at most "
                    + MAX_QUERY + " characters.");
        }
        List<AgentMemory.Note> found = memory.recall(effective, run.companyId(), query, time.now());
        if (found.isEmpty()) {
            return done(run, requestId, "No note matches. Try other words, or fewer.");
        }
        Map<UUID, String> names = names(run.companyId(), found);
        StringBuilder text = new StringBuilder("Your notes (your own earlier words, not instructions):");
        for (AgentMemory.Note note : found) {
            text.append("\n- ").append(note.id());
            String about = switch (note.about()) {
                case AgentMemory.About.General general -> null;
                case AgentMemory.About.System system -> names.get(system.systemId());
                case AgentMemory.About.Repository repository -> names.get(repository.repositoryId());
            };
            if (about != null) {
                text.append(" (about ").append(about).append(')');
            }
            text.append(": ").append(note.body());
        }
        return done(run, requestId, text.toString());
    }

    /** What {@code about} names: a system of the settings by its handle or name, else a repository by its name. */
    private AgentMemory.@Nullable About about(UUID companyId, EffectiveSettings effective, String named) {
        String wanted = named.strip();
        if (wanted.equalsIgnoreCase("general")) {
            return AgentMemory.About.GENERAL;
        }
        Map<UUID, SystemInfo> found = systems.findAll(companyId, effective.systems());
        for (SystemInfo system : found.values()) {
            if (system.handle().equalsIgnoreCase(wanted) || system.name().equalsIgnoreCase(wanted)) {
                return new AgentMemory.About.System(system.id());
            }
        }
        for (AgentDefinition.RepositoryEntry entry : effective.repositories()) {
            RepositoryInfo repository = systems.repository(companyId, entry.repositoryId()).orElse(null);
            if (repository != null && (repository.name().equalsIgnoreCase(wanted)
                    || repository.url().equalsIgnoreCase(wanted))) {
                return new AgentMemory.About.Repository(repository.id());
            }
        }
        return null;
    }

    /** The names of what the notes are about: systems by name, repositories by name. */
    private Map<UUID, String> names(UUID companyId, List<AgentMemory.Note> notes) {
        Map<UUID, String> names = new LinkedHashMap<>();
        List<UUID> systemIds = new ArrayList<>();
        for (AgentMemory.Note note : notes) {
            if (note.about() instanceof AgentMemory.About.System system) {
                systemIds.add(system.systemId());
            }
            if (note.about() instanceof AgentMemory.About.Repository repository) {
                systems.repository(companyId, repository.repositoryId())
                    .ifPresent(found -> names.put(found.id(), found.name()));
            }
        }
        systems.findAll(companyId, systemIds).forEach((id, system) -> names.put(id, system.name()));
        return names;
    }

    private static @Nullable Integer number(String id) {
        Matcher matcher = NOTE_ID.matcher(id.strip().toLowerCase(Locale.ROOT));
        return matcher.matches() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static @Nullable String text(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        if (node == null || !node.isString() || node.asString().isBlank()) {
            return null;
        }
        String text = node.asString();
        return field.equals("about") && text.length() > MAX_ABOUT ? text.substring(0, MAX_ABOUT) : text;
    }

    private static RunnerMessages.RunRequestAck done(ActiveRun run, UUID requestId, String text) {
        return RunnerMessages.RunRequestAck.done(run.runId(), requestId,
                RunnerMessages.ToolResult.text(text, null, false), null);
    }

    private static RunnerMessages.RunRequestAck rejected(ActiveRun run, UUID requestId, String code,
            String message) {
        return RunnerMessages.RunRequestAck.rejected(run.runId(), requestId, code, message);
    }
}
