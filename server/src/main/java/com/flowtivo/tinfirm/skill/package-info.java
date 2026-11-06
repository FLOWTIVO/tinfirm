/**
 * Skills (ADR-0064; the skills contract, docs/reviews/2026-10-15-skills-contract.md, §2 to §4 and §6): a company's
 * library of skills in the Agent Skills format, each a folder with a {@code SKILL.md}, in immutable versions stored as
 * canonical zips and scanned for credentials; private to its owner until shared with groups or with members, as an
 * agent is. Agents pin a version of each of their skills (the agent module); runs get the pinned versions as files.
 * <p>
 * API: {@link com.flowtivo.tinfirm.skill.Skills} (lookups, a version's content, whether a member sees a skill),
 * {@link com.flowtivo.tinfirm.skill.SkillInfo}, {@link com.flowtivo.tinfirm.skill.SkillVersionInfo} and the events of
 * {@link com.flowtivo.tinfirm.skill.SkillEvents}. It implements the principal module's
 * {@link com.flowtivo.tinfirm.principal.PrincipalDependents} (a leaving member's skills pass to the admins, and the
 * skills shared with them on their own are no longer) and the company module's
 * {@link com.flowtivo.tinfirm.company.GroupDependents} (a deleted group's shares go).
 */
@org.springframework.modulith.ApplicationModule(displayName = "Skills")
package com.flowtivo.tinfirm.skill;
