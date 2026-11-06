package com.flowtivo.tinfirm.agent.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentContent;
import com.flowtivo.tinfirm.shared.api.model.AgentSkill;
import com.flowtivo.tinfirm.shared.api.model.AgentSkillInput;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.skill.SkillInfo;
import com.flowtivo.tinfirm.skill.SkillVersionInfo;
import com.flowtivo.tinfirm.skill.Skills;

/**
 * An agent's skills (the skills contract, §5): each a skill of the library at the version the agent pins. Its editors
 * add a skill they see in the library at its latest version or at one they name, move a pin to any version of a live
 * skill they see (L23), and remove one; a deleted skill stays where it is pinned and moves nowhere. The pins are part
 * of a version, like its instructions: a skill added or a pin moved widens when the agent's ceiling can act (the
 * security review of 2026-10-27, D1; {@link AgentVersions}); a skill removed reaches everyone's next turn. The library
 * reaches the pins through {@link AgentSkillUsage}.
 */
@Component
class AgentSkills {

    static final int MAX_SKILLS = 20;

    private final AgentSkillStore store;

    private final Skills skills;

    AgentSkills(AgentSkillStore store, Skills skills) {
        this.store = store;
        this.skills = skills;
    }

    /**
     * What a replacement changed, for {@code agent.skills_changed}.
     *
     * @param added   joined, at their versions
     * @param removed left, at the versions they had
     * @param moved   pins moved, from and to
     */
    record Change(List<Entry> added, List<Entry> removed, List<Move> moved) {

        boolean none() {
            return added.isEmpty() && removed.isEmpty() && moved.isEmpty();
        }

        Map<String, Object> details() {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("added", added.stream().map(Entry::detail).toList());
            details.put("removed", removed.stream().map(Entry::detail).toList());
            details.put("moved", moved.stream().map(Move::detail).toList());
            return details;
        }
    }

    record Entry(UUID skillId, String name, int version) {

        Map<String, Object> detail() {
            return Map.of("skillId", skillId.toString(), "name", name, "version", version);
        }
    }

    record Move(UUID skillId, String name, int from, int to) {

        Map<String, Object> detail() {
            return Map.of("skillId", skillId.toString(), "name", name, "from", from, "to", to);
        }
    }

    /**
     * Locks the skills a replacement names, before the caller locks the agent's row (CONVENTIONS §10;
     * {@link Skills#lockForPins}): a delete of one of them then waits for the replacement and sees its pin, or the
     * replacement waits for the delete and reads the skill deleted. A list over the limit locks nothing:
     * {@link #replace} refuses it.
     */
    void lockForPins(UUID companyId, List<AgentSkillInput> input) {
        if (input.size() > MAX_SKILLS) {
            return;
        }
        skills.lockForPins(companyId, input.stream().map(AgentSkillInput::getSkillId).filter(Objects::nonNull)
            .toList());
    }

    /**
     * Replaces the agent's skills as a whole (§5.1), with the skills it names ({@link #lockForPins}) and then the
     * agent's row locked by the caller: whether a skill is live is read under its lock.
     *
     * @param editorUserId the editor, who must see in the library each skill that joins, and each skill whose pin
     *                     moves to another version (L23)
     */
    Change replace(UUID companyId, UUID agentId, List<AgentSkillInput> input, UUID editorUserId, Instant at) {
        if (input.size() > MAX_SKILLS) {
            throw ApiException.validation("An agent has at most " + MAX_SKILLS + " skills");
        }
        Map<UUID, AgentSkillStore.Pin> current = new LinkedHashMap<>();
        store.pins(companyId, agentId).forEach(pin -> current.put(pin.skillId(), pin));
        Set<UUID> requestedIds = new HashSet<>();
        for (AgentSkillInput entry : input) {
            if (!requestedIds.add(entry.getSkillId())) {
                throw ApiException.validation("The skill " + entry.getSkillId() + " is named twice");
            }
        }
        Map<UUID, SkillInfo> known = skills.findAll(companyId, requestedIds);
        List<AgentSkillStore.Pin> wanted = new ArrayList<>();
        for (AgentSkillInput entry : input) {
            UUID skillId = entry.getSkillId();
            SkillInfo skill = known.get(skillId);
            AgentSkillStore.Pin pinned = current.get(skillId);
            Integer version = entry.getVersion();
            if (pinned != null) {
                if (version == null || version == pinned.version()) {
                    wanted.add(pinned);
                    continue;
                }
                if (skill == null || !skill.live()) {
                    throw deleted(skill);
                }
                if (!skills.sees(companyId, skillId, editorUserId)) {
                    // L23: moving a pin brings the skill's other version into the agent, as adding it does.
                    throw ApiException.validation("The skill " + skillId + " is not one you see in the library: "
                            + "you may keep or remove its pin, not move it");
                }
                requireVersion(companyId, skill, version);
                wanted.add(new AgentSkillStore.Pin(skillId, version));
                continue;
            }
            if (skill == null || !skills.sees(companyId, skillId, editorUserId)) {
                if (skill != null && !skill.live()) {
                    throw deleted(skill);
                }
                throw ApiException.validation("The skill " + skillId + " is not one you see in the library");
            }
            int joinAt = version != null ? version : skill.latestVersion();
            requireVersion(companyId, skill, joinAt);
            wanted.add(new AgentSkillStore.Pin(skillId, joinAt));
        }
        Map<UUID, SkillInfo> all = skills.findAll(companyId, union(current.keySet(), requestedIds));
        Set<String> names = new HashSet<>();
        for (AgentSkillStore.Pin pin : wanted) {
            String name = all.get(pin.skillId()).name();
            if (!names.add(name)) {
                throw ApiException.badRequest(ProblemCode.SKILL_NAME_CONFLICT, "Two skills of the agent are named "
                        + name + ": their folders would be one; remove one of them");
            }
        }
        List<Entry> added = new ArrayList<>();
        List<Entry> removed = new ArrayList<>();
        List<Move> moved = new ArrayList<>();
        Map<UUID, AgentSkillStore.Pin> wantedById = new LinkedHashMap<>();
        wanted.forEach(pin -> wantedById.put(pin.skillId(), pin));
        List<UUID> gone = new ArrayList<>();
        for (AgentSkillStore.Pin pin : current.values()) {
            if (!wantedById.containsKey(pin.skillId())) {
                gone.add(pin.skillId());
                removed.add(new Entry(pin.skillId(), all.get(pin.skillId()).name(), pin.version()));
            }
        }
        store.remove(companyId, agentId, gone);
        for (AgentSkillStore.Pin pin : wanted) {
            AgentSkillStore.Pin before = current.get(pin.skillId());
            String name = all.get(pin.skillId()).name();
            if (before == null) {
                store.insert(companyId, agentId, pin, editorUserId, at);
                added.add(new Entry(pin.skillId(), name, pin.version()));
            }
            else if (before.version() != pin.version()) {
                store.move(companyId, agentId, pin, editorUserId, at);
                moved.add(new Move(pin.skillId(), name, before.version(), pin.version()));
            }
        }
        Comparator<Entry> byName = Comparator.comparing(Entry::name);
        added.sort(byName);
        removed.sort(byName);
        moved.sort(Comparator.comparing(Move::name));
        return new Change(added, removed, moved);
    }

    /** The agent's skills as the API shows them, by name (§5.2). */
    List<AgentSkill> views(UUID companyId, UUID agentId) {
        List<AgentSkillStore.Pin> pins = store.pins(companyId, agentId);
        if (pins.isEmpty()) {
            return new ArrayList<>();
        }
        Map<UUID, SkillInfo> known = skills.findAll(companyId, pins.stream().map(AgentSkillStore.Pin::skillId)
            .toList());
        Map<Skills.SkillVersionKey, SkillVersionInfo> versions = skills.versions(companyId, pins.stream()
            .map(pin -> new Skills.SkillVersionKey(pin.skillId(), pin.version()))
            .toList());
        List<AgentSkill> views = new ArrayList<>();
        for (AgentSkillStore.Pin pin : pins) {
            SkillInfo skill = known.get(pin.skillId());
            SkillVersionInfo version = versions.get(new Skills.SkillVersionKey(pin.skillId(), pin.version()));
            if (skill == null || version == null) {
                continue;
            }
            views.add(new AgentSkill(skill.id(), skill.name(), version.description(), pin.version(),
                    skill.latestVersion(), !skill.live()));
        }
        views.sort(Comparator.comparing(AgentSkill::getName));
        return views;
    }

    /** The names of skills of the library, by id; a skill unknown is absent. */
    Map<UUID, String> names(UUID companyId, Collection<UUID> skillIds) {
        if (skillIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> names = new HashMap<>();
        skills.findAll(companyId, Set.copyOf(skillIds)).forEach((id, skill) -> names.put(id, skill.name()));
        return names;
    }

    /** For each agent, how many of its live skills have a version newer than the one it pins. */
    Map<UUID, Integer> updates(UUID companyId, Collection<UUID> agentIds) {
        Map<UUID, List<AgentSkillStore.Pin>> pins = store.pins(companyId, agentIds);
        Set<UUID> skillIds = new HashSet<>();
        pins.values().forEach(list -> list.forEach(pin -> skillIds.add(pin.skillId())));
        Map<UUID, SkillInfo> known = skills.findAll(companyId, skillIds);
        Map<UUID, Integer> updates = new HashMap<>();
        pins.forEach((agentId, list) -> {
            int count = (int) list.stream()
                .filter(pin -> {
                    SkillInfo skill = known.get(pin.skillId());
                    return skill != null && skill.live() && skill.latestVersion() > pin.version();
                })
                .count();
            if (count > 0) {
                updates.put(agentId, count);
            }
        });
        return updates;
    }

    /**
     * The skills of the agent a turn gets: the pins of the version it uses ({@link AgentVersions#effective}) less
     * those the settings switch off, by name (§5.3, §7.1).
     */
    List<SkillVersionInfo> turnSkills(UUID companyId, List<AgentContent.Pin> pins, Set<UUID> skillsOff) {
        List<Skills.SkillVersionKey> keys = pins.stream()
            .filter(pin -> !skillsOff.contains(pin.skillId()))
            .map(pin -> new Skills.SkillVersionKey(pin.skillId(), pin.version()))
            .toList();
        return skills.versions(companyId, keys)
            .values()
            .stream()
            .sorted(Comparator.comparing(SkillVersionInfo::name))
            .toList();
    }

    /** The version the agent pins of a skill; empty for a skill it does not have. */
    @Nullable Integer pinnedVersion(UUID companyId, UUID agentId, UUID skillId) {
        return store.pins(companyId, agentId)
            .stream()
            .filter(pin -> pin.skillId().equals(skillId))
            .map(AgentSkillStore.Pin::version)
            .findFirst()
            .orElse(null);
    }

    /** The ids of the agent's skills. */
    Set<UUID> skillIds(UUID companyId, UUID agentId) {
        Set<UUID> ids = new HashSet<>();
        store.pins(companyId, agentId).forEach(pin -> ids.add(pin.skillId()));
        return ids;
    }

    private void requireVersion(UUID companyId, SkillInfo skill, int version) {
        if (skills.version(companyId, skill.id(), version).isEmpty()) {
            throw ApiException.validation("The skill " + skill.name() + " has no version " + version);
        }
    }

    private static ApiException deleted(@Nullable SkillInfo skill) {
        return ApiException.badRequest(ProblemCode.SKILL_DELETED, "The skill "
                + (skill != null ? skill.name() + " " : "") + "was deleted from the library: an agent that has it "
                + "keeps the version it pins, and no agent adds it or moves to another version");
    }

    private static Set<UUID> union(Set<UUID> a, Set<UUID> b) {
        Set<UUID> all = new HashSet<>(a);
        all.addAll(b);
        return all;
    }
}
