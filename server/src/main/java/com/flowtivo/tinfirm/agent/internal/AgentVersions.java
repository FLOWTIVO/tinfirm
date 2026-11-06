package com.flowtivo.tinfirm.agent.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentContent;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.AgentEvents;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.agent.EffectiveAgent;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.security.CurrentActor;

/**
 * Versions of agents (the package 3 contract, §11): every change of an agent's definition makes a version, which
 * widens when the new ceiling allows anything the one before did not ({@link AgentDefinition#wideningOver}), or when
 * it changes the instructions or the skills and the new ceiling can act ({@link AgentDefinition#canAct}; the security
 * review of 2026-10-27, D1; the owner's decision Q4). A version keeps its instructions and pins ({@link AgentContent},
 * V080). The agent of a person's turns is the current ceiling narrowed by the version they accepted, with that
 * version's instructions and skills while a change of them waits for their acceptance ({@link #effective}). Runs in
 * the caller's transaction, with the agent's row locked.
 */
@Component
class AgentVersions {

    static final String TARGET = "agent";

    private final AgentStore store;

    private final AgentSkillStore pins;

    private final SettingsStore settings;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    AgentVersions(AgentStore store, AgentSkillStore pins, SettingsStore settings, AuditLog auditLog,
            ApplicationEventPublisher events) {
        this.store = store;
        this.pins = pins;
        this.settings = settings;
        this.auditLog = auditLog;
        this.events = events;
    }

    /**
     * A version just written: its number, what it allows beyond the one before, and whether it changed the
     * instructions or the skills (which widens only within a ceiling that can act).
     */
    record Versioned(int version, AgentDefinition.Widening widening, boolean instructionsChanged,
            boolean skillsChanged) {

        boolean widens() {
            return !widening.none();
        }

        /** A change of the instructions or skills that widens nothing: audited and announced in the conversations. */
        boolean announced() {
            return (instructionsChanged || skillsChanged) && !widening.contentChanged();
        }
    }

    /** Version 1 of a new agent: widens nothing. */
    void first(UUID companyId, UUID agentId, String name, String handle, List<RuntimeKind> runtimes,
            AgentDefinition definition, Instant at) {
        AgentContent content = content(companyId, agentId);
        store.insertVersion(companyId, agentId, 1, AgentJson.version(name, handle, runtimes, definition),
                content.instructions(), AgentJson.skills(content.skills()), false, null, at,
                userOf(CurrentActor.actor()));
    }

    /**
     * Writes the next version after a change of the agent's tables: the ceiling, instructions and pins as the tables
     * hold them now, compared with {@code before} and with the instructions and pins of the version before. The caller
     * audits its change with the version, then {@link #announce}s it.
     *
     * @param row the agent's row as locked before the change
     */
    Versioned next(AgentStore.AgentRow row, AgentDefinition before, String name, String handle,
            List<RuntimeKind> runtimes, @Nullable UUID decisionId, Instant at) {
        AgentDefinition after = store.definition(row.companyId(), row.id());
        AgentContent content = content(row.companyId(), row.id());
        AgentContent previous = store.version(row.companyId(), row.id(), row.version())
            .map(AgentStore.StoredVersion::content)
            .orElse(content);
        AgentDefinition.Widening widening = after.wideningOver(before, content, previous);
        int version = row.version() + 1;
        store.insertVersion(row.companyId(), row.id(), version, AgentJson.version(name, handle, runtimes, after),
                content.instructions(), AgentJson.skills(content.skills()), !widening.none(), decisionId, at,
                userOf(CurrentActor.actor()));
        store.setVersion(row.companyId(), row.id(), version, at);
        return new Versioned(version, widening, !content.instructions().equals(previous.instructions()),
                !content.skills().equals(previous.skills()));
    }

    /**
     * Audits a widening version ({@code agent.widened}), or a change of the instructions or skills that widens nothing
     * ({@code agent.instructions_changed}), lets the editor's own settings follow their change when they had accepted
     * the version before it, and announces the change ({@code AgentChanged}, {@code AgentWidened}).
     *
     * @param editorPrincipalId the editing member's principal; null for a system change or an instance admin
     */
    void announce(UUID companyId, UUID agentId, int previousVersion, Versioned versioned,
            @Nullable UUID editorPrincipalId) {
        if (versioned.widens()) {
            AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.AGENT_WIDENED, CurrentActor.actor())
                .target(TARGET, agentId)
                .detail("version", versioned.version());
            versioned.widening().details().forEach(audit::detail);
            auditLog.record(audit.build());
        }
        if (versioned.announced()) {
            List<String> changed = new ArrayList<>();
            if (versioned.instructionsChanged()) {
                changed.add("instructions");
            }
            if (versioned.skillsChanged()) {
                changed.add("skills");
            }
            auditLog.record(AuditRecord.company(companyId, AuditAction.AGENT_INSTRUCTIONS_CHANGED, CurrentActor.actor())
                .target(TARGET, agentId)
                .detail("agentId", agentId.toString())
                .detail("version", versioned.version())
                .detail("by", editorPrincipalId != null ? editorPrincipalId.toString() : null)
                .detail("changed", changed)
                .build());
        }
        if (editorPrincipalId != null) {
            settings.advanceAccepted(companyId, agentId, editorPrincipalId, previousVersion, versioned.version());
        }
        events.publishEvent(new AgentEvents.AgentChanged(companyId, agentId, versioned.version(), versioned.widens()));
        if (versioned.widens()) {
            events.publishEvent(new AgentEvents.AgentWidened(companyId, agentId, versioned.version()));
        }
    }

    /**
     * The agent as the turns of someone who accepted {@code acceptedVersion} use it: the current ceiling narrowed by
     * that version's, with what the current one adds over it; the accepted version's instructions and skills (less
     * those the agent no longer has) while a change of them waits for acceptance, else the current ones. A version at
     * or above the current one is the current.
     */
    EffectiveAgent effective(AgentStore.AgentRow row, int acceptedVersion) {
        AgentDefinition current = store.definition(row.companyId(), row.id());
        AgentContent content = new AgentContent(row.instructions(), currentPins(row.companyId(), row.id()));
        if (acceptedVersion >= row.version()) {
            return new EffectiveAgent(row.info(), row.version(), current, AgentDefinition.Widening.NONE,
                    row.version(), content);
        }
        Optional<AgentStore.StoredVersion> stored = store.version(row.companyId(), row.id(), acceptedVersion);
        AgentDefinition accepted = stored.map(AgentStore.StoredVersion::definition).orElse(AgentDefinition.EMPTY);
        AgentContent acceptedContent = stored.map(AgentStore.StoredVersion::content).orElse(content);
        AgentDefinition.Widening widening = current.wideningOver(accepted, content, acceptedContent);
        if (widening.contentChanged()) {
            return new EffectiveAgent(row.info(), acceptedVersion, current.narrowedBy(accepted), widening,
                    acceptedVersion, acceptedContent.within(content));
        }
        return new EffectiveAgent(row.info(), acceptedVersion, current.narrowedBy(accepted), widening, row.version(),
                content);
    }

    /**
     * How the instructions and skills of version {@code to} differ from those of version {@code from}, with the
     * newest version between them that changed them; empty when they are the same.
     */
    Optional<Agents.ContentChange> contentChange(UUID companyId, UUID agentId, int from, int to) {
        if (from == to) {
            return Optional.empty();
        }
        int low = Math.min(from, to);
        int high = Math.max(from, to);
        List<AgentStore.ContentRow> rows = store.contentRows(companyId, agentId, low, high);
        if (rows.size() < 2 || rows.getFirst().version() != low || rows.getLast().version() != high) {
            return Optional.empty();
        }
        AgentStore.ContentRow first = rows.getFirst();
        AgentStore.ContentRow last = rows.getLast();
        boolean instructions = !first.instructionsDigest().equals(last.instructionsDigest());
        boolean skills = !samePins(first, last);
        if (!instructions && !skills) {
            return Optional.empty();
        }
        AgentStore.ContentRow changed = last;
        for (int i = rows.size() - 1; i > 0; i--) {
            AgentStore.ContentRow later = rows.get(i);
            AgentStore.ContentRow earlier = rows.get(i - 1);
            if (!later.instructionsDigest().equals(earlier.instructionsDigest()) || !samePins(earlier, later)) {
                changed = later;
                break;
            }
        }
        return Optional.of(new Agents.ContentChange(from, to, changed.version(), changed.createdAt(),
                changed.createdBy(), instructions, skills));
    }

    /** The agent's instructions and pins as its tables hold them now. */
    private AgentContent content(UUID companyId, UUID agentId) {
        return new AgentContent(store.instructions(companyId, agentId), currentPins(companyId, agentId));
    }

    private List<AgentContent.Pin> currentPins(UUID companyId, UUID agentId) {
        return pins.pins(companyId, agentId)
            .stream()
            .map(pin -> new AgentContent.Pin(pin.skillId(), pin.version()))
            .toList();
    }

    private static boolean samePins(AgentStore.ContentRow a, AgentStore.ContentRow b) {
        return new AgentContent("", AgentJson.skills(a.skills()))
            .equals(new AgentContent("", AgentJson.skills(b.skills())));
    }

    private static @Nullable UUID userOf(Actor actor) {
        return actor.type() == ActorType.USER ? actor.id() : null;
    }
}
