package com.flowtivo.tinfirm.skill;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A skill as other modules see it (the skills contract, §3.1).
 *
 * @param name             the Agent Skills name, its folder's name in a sandbox
 * @param latestVersion    its newest version
 * @param ownerPrincipalId the member's principal who owns it; null once it passed to the admins
 * @param deletedAt        set for a deleted skill, whose versions stay for the agents that pin them
 */
public record SkillInfo(UUID id, UUID companyId, String name, int latestVersion, @Nullable UUID ownerPrincipalId,
        @Nullable Instant deletedAt) {

    /** Whether the skill is not deleted. */
    public boolean live() {
        return deletedAt == null;
    }
}
