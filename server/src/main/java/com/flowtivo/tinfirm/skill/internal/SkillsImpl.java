package com.flowtivo.tinfirm.skill.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.skill.SkillInfo;
import com.flowtivo.tinfirm.skill.SkillVersionInfo;
import com.flowtivo.tinfirm.skill.Skills;

/** {@link Skills}: the library's lookups for other modules, in the caller's transaction. */
@Component
class SkillsImpl implements Skills {

    private final SkillStore store;

    private final SkillCallers callers;

    SkillsImpl(SkillStore store, SkillCallers callers) {
        this.store = store;
        this.callers = callers;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SkillInfo> find(UUID companyId, UUID skillId) {
        return store.find(companyId, skillId).map(SkillStore.SkillRow::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, SkillInfo> findAll(UUID companyId, Collection<UUID> skillIds) {
        Map<UUID, SkillInfo> found = new HashMap<>();
        store.findAll(companyId, skillIds).forEach((id, row) -> found.put(id, row.info()));
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SkillVersionInfo> version(UUID companyId, UUID skillId, int version) {
        return store.version(companyId, skillId, version).map(SkillStore.VersionRow::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<SkillVersionKey, SkillVersionInfo> versions(UUID companyId, Collection<SkillVersionKey> keys) {
        Map<SkillVersionKey, SkillVersionInfo> found = new HashMap<>();
        store.versions(companyId, keys).forEach((key, row) -> found.put(key, row.info()));
        return found;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<byte[]> content(UUID companyId, UUID skillId, int version) {
        return store.content(companyId, skillId, version);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean sees(UUID companyId, UUID skillId, UUID userId) {
        Optional<SkillStore.SkillRow> row = store.findLive(companyId, skillId);
        if (row.isEmpty()) {
            return false;
        }
        return callers.member(companyId, userId)
            .map(caller -> SkillCallers.access(caller, row.get(), store.shares(companyId, skillId)).sees())
            .orElse(false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockForPins(UUID companyId, Collection<UUID> skillIds) {
        store.lockForPins(companyId, skillIds);
    }
}
