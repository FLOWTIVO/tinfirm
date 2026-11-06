package com.flowtivo.tinfirm.skill;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The skill module's API for other modules (the skills contract, §12). Every method runs in the caller's tenant
 * context and transaction; row-level security limits it to the company of that context.
 */
public interface Skills {

    /** The skill, live or deleted. */
    Optional<SkillInfo> find(UUID companyId, UUID skillId);

    /** The skills, live or deleted, by id; an unknown id is left out. */
    Map<UUID, SkillInfo> findAll(UUID companyId, Collection<UUID> skillIds);

    /** A version of a skill, live or deleted. */
    Optional<SkillVersionInfo> version(UUID companyId, UUID skillId, int version);

    /** Versions of skills; one that does not exist is left out. */
    Map<SkillVersionKey, SkillVersionInfo> versions(UUID companyId, Collection<SkillVersionKey> keys);

    /** The canonical zip of a version. */
    Optional<byte[]> content(UUID companyId, UUID skillId, int version);

    /**
     * Whether the member sees the live skill in the library (the contract's §4.1): its owner, a member of a group it is
     * shared with, a member it is shared with, OWNER and ADMIN. False for a deleted skill and for anyone else.
     */
    boolean sees(UUID companyId, UUID skillId, UUID userId);

    /**
     * Locks the skills a change of an agent's pins names, live or deleted ({@code FOR KEY SHARE}, in the order of their
     * ids; an unknown id is left out), before the agent's row (CONVENTIONS §10). A skill's delete locks its row
     * {@code FOR UPDATE} before it counts and enumerates the agents that pin it: so it waits for the change and sees
     * its pin, or the change waits for it and, reading the skill afterwards, finds it deleted. Nothing else waits on
     * this lock. In the caller's transaction, which must exist.
     */
    void lockForPins(UUID companyId, Collection<UUID> skillIds);

    /** A skill's version, as a key. */
    record SkillVersionKey(UUID skillId, int version) {
    }
}
