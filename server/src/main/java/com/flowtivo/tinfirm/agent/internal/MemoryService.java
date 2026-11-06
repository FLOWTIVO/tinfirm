package com.flowtivo.tinfirm.agent.internal;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.flowtivo.tinfirm.agent.AgentMemory;
import com.flowtivo.tinfirm.agent.EffectiveSettings;
import com.flowtivo.tinfirm.agent.SettingsOwner;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.vault.ScanReport;
import com.flowtivo.tinfirm.vault.VaultService;

/**
 * The rules of agent memory (ADR-0063, CONVENTIONS §15.18), over {@link MemoryStore}: a memory is a settings row, every
 * write takes its row lock first, and in the caller's transaction and tenant context.
 * <ul>
 * <li>A note is one line: every run of whitespace, control and format characters (a line break, a zero-width or
 * direction mark) is one space, then it is trimmed; 1 to {@value AgentMemory#MAX_NOTE_CHARACTERS} characters.</li>
 * <li>It is about something the settings in effect list (one of their systems, own servers included, or
 * repositories), and it is scanned by the vault before it is written (ADR-0014).</li>
 * <li>A person's note is active at once, within {@value AgentMemory#MAX_NOTES} notes and {@code maxCharacters}
 * characters; a trigger's is proposed, at most {@value AgentMemory#MAX_PROPOSALS} at once, and counts once
 * accepted.</li>
 * <li>The tiers read active notes, most recently used first within their budget, and mark what they show used.</li>
 * </ul>
 * Codes of a refusal (the runner protocol's {@code run.request.ack}): {@code INVALID_REQUEST}, {@code MEMORY_OFF},
 * {@code UNKNOWN_NOTE}, {@code MEMORY_REFUSED}, {@code MEMORY_FULL}, {@code MEMORY_REVIEW_FULL}.
 */
@Service
class MemoryService implements AgentMemory {

    static final String INVALID_REQUEST = "INVALID_REQUEST";

    static final String MEMORY_OFF = "MEMORY_OFF";

    static final String UNKNOWN_NOTE = "UNKNOWN_NOTE";

    static final String MEMORY_REFUSED = "MEMORY_REFUSED";

    static final String MEMORY_FULL = "MEMORY_FULL";

    static final String MEMORY_REVIEW_FULL = "MEMORY_REVIEW_FULL";

    /** The budgets of the tiers, in characters (ADR-0063, decision 4). */
    static final int GENERAL_BUDGET = 400;

    static final int REPOSITORY_BUDGET = 600;

    static final int SYSTEM_BUDGET = 600;

    /** The workspace's repositories whose notes a prompt shows. */
    static final int PROMPT_REPOSITORIES = 3;

    static final int RECALL_LIMIT = 5;

    /** The words of a query {@code recall} searches for. */
    static final int RECALL_WORDS = 8;

    /** Whitespace, control and format characters, each run of which is one space in a note. */
    private static final Pattern FOLD = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}\\p{Zs}\\s]+");

    /** What separates the words of a query. */
    private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final MemoryStore store;

    private final SettingsStore settings;

    private final AgentStore agents;

    private final VaultService vault;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final int maxCharacters;

    private final MemoryCompaction compaction;

    MemoryService(MemoryStore store, SettingsStore settings, AgentStore agents, VaultService vault, AuditLog auditLog,
            TimeSource time, @Value("${tinfirm.memory.max-characters:20000}") int maxCharacters,
            MemoryCompaction compaction) {
        if (maxCharacters < 1000 || maxCharacters > 100_000) {
            throw new IllegalStateException("TINFIRM_MEMORY_MAX_CHARACTERS must be 1000 to 100000, not "
                    + maxCharacters);
        }
        this.store = store;
        this.settings = settings;
        this.agents = agents;
        this.vault = vault;
        this.auditLog = auditLog;
        this.time = time;
        this.maxCharacters = maxCharacters;
        this.compaction = compaction;
    }

    int maxCharacters() {
        return maxCharacters;
    }

    // ---- the agent's writes ----------------------------------------------------------------------------------------

    @Override
    public Written remember(Source source, String note, About about, @Nullable Integer replaces) {
        EffectiveSettings effective = source.settings();
        UUID companyId = source.companyId();
        String body = normalized(note);
        if (body == null) {
            return refused(INVALID_REQUEST, "A note is one line of 1 to " + MAX_NOTE_CHARACTERS + " characters: one "
                    + "short, lasting fact. Nothing was saved.");
        }
        if (!effective.memory()) {
            return refused(MEMORY_OFF, "Memory is off for this conversation, so nothing was saved.");
        }
        String outside = outside(effective, about);
        if (outside != null) {
            return refused(INVALID_REQUEST, outside);
        }
        SettingsStore.SettingsRow row = row(companyId, effective.agentId(), effective.owner()).orElse(null);
        if (row == null || store.lock(companyId, row.id()).isEmpty()) {
            return refused(MEMORY_OFF, "Memory is off for this conversation, so nothing was saved.");
        }
        boolean proposal = row.triggerId() != null;
        Instant now = time.now();
        MemoryStore.NoteRow replaced = null;
        if (replaces != null) {
            replaced = store.byNumber(row.id(), replaces)
                .filter(found -> found.note().state() == State.ACTIVE)
                .orElse(null);
            if (replaced == null) {
                return refused(UNKNOWN_NOTE, "There is no note m" + replaces + " to replace; nothing was saved. "
                        + "Save it without `replaces`.");
            }
        }
        else if (!proposal) {
            Optional<MemoryStore.NoteRow> same = store.sameActive(row.id(), about, body);
            if (same.isPresent()) {
                store.touch(List.of(same.get().id()), now);
                return new Written.Known(same.get().note());
            }
        }
        ScanReport scan = vault.scan(companyId, body.getBytes(StandardCharsets.UTF_8));
        if (!scan.clean()) {
            auditLog.record(audit(AuditAction.MEMORY_REFUSED, Actor.agent(row.agentId()), row)
                .detail("conversationId", source.conversationId().toString())
                .detail("runId", source.runId().toString())
                .detail("names", scan.vaultSecrets())
                .detail("formats", scan.formats())
                .build());
            return refused(MEMORY_REFUSED, "Nothing was saved: the note looks like it holds a credential. Never save "
                    + "credentials, tokens or other secrets.");
        }
        MemoryStore.Totals totals = store.totals(row.id());
        if (proposal) {
            if (totals.proposed() >= MAX_PROPOSALS) {
                return refused(MEMORY_REVIEW_FULL, MAX_PROPOSALS + " notes wait for the trigger's team to review "
                        + "them already, so nothing was saved. Save only what matters most, once they have reviewed.");
            }
            int number = store.takeNumber(companyId, row.id(), now);
            store.insert(Ids.newId(), companyId, row.id(), number, State.PROPOSED, about, body, replaces,
                    source.conversationId(), now, now);
            MemoryStore.NoteRow saved = store.byNumber(row.id(), number).orElseThrow();
            auditSaved(row, source, saved.note());
            return new Written.Proposed(saved.note());
        }
        int length = characters(body);
        int notes = totals.active() + (replaced == null ? 1 : 0);
        int characters = totals.characters() + length - (replaced != null ? characters(replaced.note().body()) : 0);
        if (notes > MAX_NOTES || characters > maxCharacters) {
            return refused(MEMORY_FULL, "Your notes are full (" + totals.active() + " notes, " + totals.characters()
                    + " of " + maxCharacters + " characters), so nothing was saved. Replace a note that says less "
                    + "with `replaces`, or `forget` one that is no longer true, then save again.");
        }
        int number;
        if (replaced != null) {
            number = replaced.note().number();
            store.replace(row.id(), number, about, body, source.conversationId(), now);
            store.changed(companyId, row.id(), now);
        }
        else {
            number = store.takeNumber(companyId, row.id(), now);
            store.insert(Ids.newId(), companyId, row.id(), number, State.ACTIVE, about, body, null,
                    source.conversationId(), now, now);
        }
        MemoryStore.NoteRow saved = store.byNumber(row.id(), number).orElseThrow();
        auditSaved(row, source, saved.note());
        return new Written.Saved(saved.note());
    }

    @Override
    public Forgotten forget(Source source, int number) {
        EffectiveSettings effective = source.settings();
        UUID companyId = source.companyId();
        if (!effective.memory()) {
            return Forgotten.NO_SUCH_NOTE;
        }
        SettingsStore.SettingsRow row = row(companyId, effective.agentId(), effective.owner()).orElse(null);
        if (row == null || store.lock(companyId, row.id()).isEmpty()) {
            return Forgotten.NO_SUCH_NOTE;
        }
        Optional<MemoryStore.NoteRow> note = store.byNumber(row.id(), number);
        if (note.isEmpty()) {
            return Forgotten.NO_SUCH_NOTE;
        }
        if (row.triggerId() != null && note.get().note().state() == State.ACTIVE) {
            return Forgotten.REVIEWED;
        }
        Instant now = time.now();
        store.delete(row.id(), number);
        store.changed(companyId, row.id(), now);
        auditLog.record(audit(AuditAction.MEMORY_DELETED, Actor.agent(row.agentId()), row)
            .detail("number", number)
            .detail("state", note.get().note().state().name())
            .detail("by", "AGENT")
            .detail("conversationId", source.conversationId().toString())
            .detail("runId", source.runId().toString())
            .build());
        return Forgotten.FORGOTTEN;
    }

    // ---- the tiers -------------------------------------------------------------------------------------------------

    @Override
    public PromptNotes forPrompt(EffectiveSettings effective, UUID companyId, List<UUID> repositoryIds,
            Instant now) {
        if (!effective.memory()) {
            return new PromptNotes(0, 0, List.of(), Map.of());
        }
        SettingsStore.SettingsRow row = row(companyId, effective.agentId(), effective.owner()).orElse(null);
        if (row == null) {
            return new PromptNotes(0, 0, List.of(), Map.of());
        }
        MemoryStore.Totals totals = store.totals(row.id());
        List<UUID> shown = new ArrayList<>();
        List<Note> general = within(store.general(row.id()), GENERAL_BUDGET, shown);
        Set<UUID> known = new LinkedHashSet<>();
        effective.repositories().forEach(repository -> known.add(repository.repositoryId()));
        List<UUID> repositories = repositoryIds.stream().filter(known::contains).distinct()
            .limit(PROMPT_REPOSITORIES).toList();
        Map<UUID, List<MemoryStore.NoteRow>> byRepository = new LinkedHashMap<>();
        repositories.forEach(repositoryId -> byRepository.put(repositoryId, new ArrayList<>()));
        for (MemoryStore.NoteRow note : store.aboutRepositories(row.id(), repositories)) {
            if (note.note().about() instanceof About.Repository repository) {
                byRepository.get(repository.repositoryId()).add(note);
            }
        }
        Map<UUID, List<Note>> repositoryNotes = new LinkedHashMap<>();
        byRepository.forEach((repositoryId, notes) -> {
            List<Note> kept = within(notes, REPOSITORY_BUDGET, shown);
            if (!kept.isEmpty()) {
                repositoryNotes.put(repositoryId, kept);
            }
        });
        store.touch(shown, now);
        return new PromptNotes(totals.active(), totals.proposed(), general, repositoryNotes);
    }

    @Override
    public List<Note> aboutSystem(EffectiveSettings effective, UUID companyId, UUID systemId, Instant now) {
        if (!effective.memory()) {
            return List.of();
        }
        SettingsStore.SettingsRow row = row(companyId, effective.agentId(), effective.owner()).orElse(null);
        if (row == null) {
            return List.of();
        }
        List<UUID> shown = new ArrayList<>();
        List<Note> notes = within(store.aboutSystem(row.id(), systemId), SYSTEM_BUDGET, shown);
        store.touch(shown, now);
        return notes;
    }

    @Override
    public List<Note> recall(EffectiveSettings effective, UUID companyId, String query, Instant now) {
        String tsquery = tsquery(query);
        if (!effective.memory() || tsquery == null) {
            return List.of();
        }
        SettingsStore.SettingsRow row = row(companyId, effective.agentId(), effective.owner()).orElse(null);
        if (row == null) {
            return List.of();
        }
        List<MemoryStore.NoteRow> found = store.search(row.id(), tsquery, RECALL_LIMIT);
        store.touch(found.stream().map(MemoryStore.NoteRow::id).toList(), now);
        return found.stream().map(MemoryStore.NoteRow::note).toList();
    }

    /**
     * The notes within the budget, most recently used first (a note that does not fit is passed over for a shorter
     * one), then by number, so that the same notes always render the same.
     */
    private static List<Note> within(List<MemoryStore.NoteRow> rows, int budget, List<UUID> shown) {
        List<MemoryStore.NoteRow> kept = new ArrayList<>();
        int used = 0;
        for (MemoryStore.NoteRow row : rows) {
            int length = characters(row.note().body());
            if (used + length <= budget) {
                kept.add(row);
                used += length;
            }
        }
        kept.forEach(row -> shown.add(row.id()));
        return kept.stream().map(MemoryStore.NoteRow::note).sorted(Comparator.comparingInt(Note::number)).toList();
    }

    /** The words of a query as a prefix query of the 'simple' configuration, any word matching; null for none. */
    static @Nullable String tsquery(@Nullable String query) {
        if (query == null) {
            return null;
        }
        Set<String> words = new LinkedHashSet<>();
        for (String word : NOT_WORD.split(query.toLowerCase(Locale.ROOT))) {
            if (!word.isEmpty() && words.size() < RECALL_WORDS) {
                words.add(word.length() > 64 ? word.substring(0, 64) : word);
            }
        }
        if (words.isEmpty()) {
            return null;
        }
        return String.join(" | ", words.stream().map(word -> word + ":*").toList());
    }

    // ---- the owner's page ------------------------------------------------------------------------------------------

    @Override
    public Notes notes(UUID companyId, UUID agentId, SettingsOwner owner) {
        boolean allowed = agents.find(companyId, agentId).map(AgentStore.AgentRow::memoryAllowed).orElse(false);
        SettingsStore.SettingsRow row = row(companyId, agentId, owner).orElse(null);
        if (row == null) {
            return new Notes(false, List.of(), 0, maxCharacters, null);
        }
        List<Note> notes = store.all(row.id()).stream().map(MemoryStore.NoteRow::note).toList();
        int characters = notes.stream().filter(note -> note.state() == State.ACTIVE)
            .mapToInt(note -> characters(note.body()))
            .sum();
        Instant compactedAt = store.find(companyId, row.id()).map(MemoryStore.MemoryRow::compactedAt).orElse(null);
        return new Notes(allowed && row.memoryOn(), notes, characters, maxCharacters, compactedAt);
    }

    @Override
    public boolean delete(UUID companyId, UUID agentId, SettingsOwner owner, int number, Actor actor) {
        SettingsStore.SettingsRow row = row(companyId, agentId, owner).orElse(null);
        if (row == null || store.lock(companyId, row.id()).isEmpty()) {
            return false;
        }
        Optional<MemoryStore.NoteRow> note = store.byNumber(row.id(), number);
        if (note.isEmpty()) {
            return false;
        }
        store.delete(row.id(), number);
        store.changed(companyId, row.id(), time.now());
        auditLog.record(audit(AuditAction.MEMORY_DELETED, actor, row)
            .detail("number", number)
            .detail("state", note.get().note().state().name())
            .detail("by", "OWNER")
            .build());
        return true;
    }

    @Override
    public int clear(UUID companyId, UUID agentId, SettingsOwner owner, Actor actor) {
        SettingsStore.SettingsRow row = row(companyId, agentId, owner).orElse(null);
        if (row == null || store.lock(companyId, row.id()).isEmpty()) {
            return 0;
        }
        int count = store.deleteAll(row.id());
        if (count > 0) {
            store.changed(companyId, row.id(), time.now());
            auditLog.record(audit(AuditAction.MEMORY_CLEARED, actor, row).detail("count", count).build());
        }
        return count;
    }

    @Override
    public Review accept(UUID companyId, UUID agentId, SettingsOwner owner, int number, Actor actor) {
        SettingsStore.SettingsRow row = row(companyId, agentId, owner).orElse(null);
        if (row == null || store.lock(companyId, row.id()).isEmpty()) {
            return Review.NOT_PROPOSED;
        }
        MemoryStore.NoteRow proposal = store.byNumber(row.id(), number)
            .filter(found -> found.note().state() == State.PROPOSED)
            .orElse(null);
        if (proposal == null) {
            return Review.NOT_PROPOSED;
        }
        Integer replacesNumber = proposal.note().replaces();
        MemoryStore.NoteRow replaced = replacesNumber == null ? null : store.byNumber(row.id(), replacesNumber)
            .filter(found -> found.note().state() == State.ACTIVE)
            .orElse(null);
        MemoryStore.Totals totals = store.totals(row.id());
        int notes = totals.active() + (replaced == null ? 1 : 0);
        int characters = totals.characters() + characters(proposal.note().body())
                - (replaced != null ? characters(replaced.note().body()) : 0);
        if (notes > MAX_NOTES || characters > maxCharacters) {
            return Review.MEMORY_FULL;
        }
        Instant now = time.now();
        if (replaced != null) {
            store.delete(row.id(), replaced.note().number());
        }
        store.activate(row.id(), number, now);
        store.changed(companyId, row.id(), now);
        auditLog.record(audit(AuditAction.MEMORY_ACCEPTED, actor, row)
            .detail("number", number)
            .detail("replaced", replaced != null ? replaced.note().number() : null)
            .build());
        return Review.ACCEPTED;
    }

    @Override
    public Map<UUID, Waiting> proposalsOf(UUID companyId, Collection<UUID> triggerIds) {
        return store.proposalsOf(companyId, triggerIds);
    }

    // ---- retention and erasure --------------------------------------------------------------------------------------

    @Override
    public long purge(UUID companyId, @Nullable Instant before, Instant proposedBefore, Set<UUID> heldPrincipalIds,
            Set<UUID> heldTriggerIds, Set<UUID> heldConversationIds) {
        return store.purge(companyId, before, proposedBefore, heldPrincipalIds, heldTriggerIds, heldConversationIds);
    }

    @Override
    public long countPurge(UUID companyId, @Nullable Instant before, Instant proposedBefore, Set<UUID> heldPrincipalIds,
            Set<UUID> heldTriggerIds, Set<UUID> heldConversationIds) {
        return store.countPurge(companyId, before, proposedBefore, heldPrincipalIds, heldTriggerIds,
                heldConversationIds);
    }

    @Override
    public long eraseLearnedIn(UUID companyId, Collection<UUID> conversationIds) {
        return store.eraseLearnedIn(companyId, conversationIds);
    }

    @Override
    public long countLearnedIn(UUID companyId, Collection<UUID> conversationIds) {
        return store.countLearnedIn(companyId, conversationIds);
    }

    @Override
    public Compaction compact(UUID companyId, Instant now) {
        return compaction.compact(companyId, now);
    }

    // ---- rules -----------------------------------------------------------------------------------------------------

    /** The note on one line, trimmed; null when nothing is left or it is too long. */
    static @Nullable String normalized(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String folded = FOLD.matcher(text).replaceAll(" ").strip();
        return folded.isEmpty() || characters(folded) > MAX_NOTE_CHARACTERS ? null : folded;
    }

    static int characters(String text) {
        return text.codePointCount(0, text.length());
    }

    /** Why the note may not be about this: a system or repository the settings in effect do not list; null if it may. */
    static @Nullable String outside(EffectiveSettings effective, About about) {
        return switch (about) {
            case About.General general -> null;
            case About.System system -> effective.systems().contains(system.systemId()) ? null
                    : "A note may be about a system this conversation's agent uses, by its handle; nothing was saved.";
            case About.Repository repository -> effective.repositories()
                .stream()
                .anyMatch(entry -> entry.repositoryId().equals(repository.repositoryId())) ? null
                        : "A note may be about one of the agent's repositories, by its name; nothing was saved.";
        };
    }

    /** The settings row a memory is: the person's of the agent, or the trigger's. */
    Optional<SettingsStore.SettingsRow> row(UUID companyId, UUID agentId, SettingsOwner owner) {
        return switch (owner) {
            case SettingsOwner.Person person -> settings.find(companyId, agentId, person.principalId());
            case SettingsOwner.Trigger trigger -> settings.findOfTrigger(companyId, trigger.triggerId())
                .filter(row -> row.agentId().equals(agentId));
        };
    }

    private static Written refused(String code, String message) {
        return new Written.Refused(code, message);
    }

    private void auditSaved(SettingsStore.SettingsRow row, Source source, Note note) {
        auditLog.record(audit(AuditAction.MEMORY_SAVED, Actor.agent(row.agentId()), row)
            .detail("conversationId", source.conversationId().toString())
            .detail("runId", source.runId().toString())
            .detail("number", note.number())
            .detail("state", note.state().name())
            .detail("about", MemoryStore.kind(note.about()))
            .detail("replaces", note.replaces())
            .detail("characters", characters(note.body()))
            .build());
    }

    /** An event about a memory: its settings row, agent and owner. */
    static AuditRecord.Builder audit(AuditAction action, Actor actor, SettingsStore.SettingsRow row) {
        return audit(action, actor, row.companyId(), row.id(), row.agentId(), row.principalId(), row.triggerId());
    }

    static AuditRecord.Builder audit(AuditAction action, Actor actor, UUID companyId, UUID settingsId, UUID agentId,
            @Nullable UUID principalId, @Nullable UUID triggerId) {
        AuditRecord.Builder record = AuditRecord.company(companyId, action, actor)
            .target(SettingsService.TARGET, settingsId)
            .detail("agentId", agentId.toString());
        if (principalId != null) {
            record.detail("principalId", principalId.toString());
        }
        if (triggerId != null) {
            record.detail("triggerId", triggerId.toString());
        }
        return record;
    }
}
