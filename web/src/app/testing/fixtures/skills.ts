import type {
  AgentSkill,
  Skill,
  SkillSummary,
  SkillVersion,
  SkillVersionSummary,
} from '../../api/generated/models';
import { AT, IDS } from './ids';

/**
 * Tag `Skills` (ADR-0064): Bob's `write-jira-ticket`, shared with Analysts, at version 3 (a
 * SKILL.md and a template), and Carol's `release-notes`, private to her.
 */

export const JIRA_SKILL_MD = [
  '---',
  'name: write-jira-ticket',
  'description: Writes a Jira ticket from a brief, with acceptance criteria.',
  '---',
  '',
  'Read the brief, then fill templates/ticket.md.',
  '',
].join('\n');

export function aSkillSummary(overrides: Partial<SkillSummary> = {}): SkillSummary {
  return {
    id: IDS.skill,
    name: 'write-jira-ticket',
    description: 'Writes a Jira ticket from a brief, with acceptance criteria.',
    latestVersion: 3,
    ownerPrincipalId: IDS.bob,
    ownerName: 'Bob Svoboda',
    editableByMe: false,
    shareGroupIds: [IDS.analysts],
    sharePrincipalIds: [],
    agentCount: null,
    deleted: false,
    createdAt: AT.earlier,
    updatedAt: AT.earlier,
    ...overrides,
  };
}

export function aSkillVersionSummary(
  overrides: Partial<SkillVersionSummary> = {},
): SkillVersionSummary {
  return {
    version: 3,
    description: 'Writes a Jira ticket from a brief, with acceptance criteria.',
    sha256: 'a'.repeat(64),
    sizeBytes: 2048,
    fileCount: 2,
    note: 'Adds the acceptance criteria',
    createdAt: AT.start,
    createdByName: 'Bob Svoboda',
    ...overrides,
  };
}

/** The skill with its three versions, newest first. */
export function aSkill(overrides: Partial<Skill> = {}): Skill {
  return {
    ...aSkillSummary(),
    memberShares: [],
    versions: [
      aSkillVersionSummary(),
      aSkillVersionSummary({
        version: 2,
        sha256: 'b'.repeat(64),
        sizeBytes: 1536,
        note: null,
        createdAt: AT.earlier,
      }),
      aSkillVersionSummary({
        version: 1,
        description: 'Writes a Jira ticket.',
        sha256: 'c'.repeat(64),
        sizeBytes: 512,
        fileCount: 1,
        note: 'First version',
        createdAt: AT.earlier,
      }),
    ],
    ...overrides,
  };
}

export function aSkillVersion(overrides: Partial<SkillVersion> = {}): SkillVersion {
  return {
    ...aSkillVersionSummary(),
    skillId: IDS.skill,
    name: 'write-jira-ticket',
    files: [
      { path: 'SKILL.md', sizeBytes: 160 },
      { path: 'templates/ticket.md', sizeBytes: 900 },
    ],
    skillMd: JIRA_SKILL_MD,
    ...overrides,
  };
}

/** The skill on an agent, pinned at version 2 while version 3 is the latest. */
export function anAgentSkill(overrides: Partial<AgentSkill> = {}): AgentSkill {
  return {
    skillId: IDS.skill,
    name: 'write-jira-ticket',
    description: 'Writes a Jira ticket from a brief, with acceptance criteria.',
    version: 2,
    latestVersion: 3,
    deleted: false,
    ...overrides,
  };
}
