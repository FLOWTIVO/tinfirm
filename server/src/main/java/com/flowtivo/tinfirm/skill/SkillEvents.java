package com.flowtivo.tinfirm.skill;

import java.util.UUID;

/**
 * What the skill module announces (the skills contract, §11): ids only, published with Spring's
 * {@code ApplicationEventPublisher} in the transaction that made the change, after it records its audit event.
 */
public final class SkillEvents {

    private SkillEvents() {
    }

    /** A skill was created, at version 1. */
    public record SkillCreated(UUID companyId, UUID skillId) {
    }

    /** A skill got a new version; no agent moves to it by itself. */
    public record SkillVersionAdded(UUID companyId, UUID skillId, int version) {
    }

    /** The groups or members a skill is shared with changed. */
    public record SkillShared(UUID companyId, UUID skillId) {
    }

    /** A skill was deleted (soft); the agents that pin it keep it. */
    public record SkillDeleted(UUID companyId, UUID skillId) {
    }

    /** A skill passed to the admins, its owner having left. */
    public record SkillPassedToAdmins(UUID companyId, UUID skillId, UUID formerOwnerPrincipalId) {
    }
}
