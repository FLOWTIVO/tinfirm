package com.flowtivo.tinfirm.skill;

import java.util.UUID;

/**
 * A version of a skill as other modules see it (the skills contract, §3.2): what an agent shows of the version it pins
 * and what a run's {@code run.assign.skills} names.
 *
 * @param name        the skill's name
 * @param description its {@code SKILL.md}'s description
 * @param sha256      the SHA-256 of its canonical zip, in lower-case hex
 * @param sizeBytes   the canonical zip's size
 */
public record SkillVersionInfo(UUID skillId, int version, String name, String description, String sha256,
        int sizeBytes) {
}
