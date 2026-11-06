import { downloadAgentSkill, downloadSkillVersion } from '../../../api/generated/functions';
import type { CredentialMatch, SkillUpload } from '../../../api/generated/models';
import { problemDetail, problemMessage, scanMatches, toProblem } from '../../../api/problem';
import { namesText } from '../agents/agent-labels';

/** The words and links of the skills library (ADR-0064, the skills contract). */

/** The limits of an upload (the skills contract §4.2). */
export const SKILL_MD_MAX_CHARS = 100_000;
export const SKILL_UPLOAD_MAX_BYTES = 5 * 1024 * 1024;
export const SKILL_NOTE_MAX_CHARS = 500;
/** At most this many skills on one agent (§5.1). */
export const AGENT_SKILLS_MAX = 20;

/** The SKILL.md a new skill starts from: its front matter, to fill in (§2). */
export const SKILL_MD_TEMPLATE = '---\nname: \ndescription: \n---\n';

/** The Agent Skills name (§2): lower-case words of letters and digits, joined by single hyphens. */
export const SKILL_NAME_PATTERN = /^[a-z0-9]+(-[a-z0-9]+)*$/;

const NAME_RULE =
  'The name is 1 to 64 lower-case letters and digits, in words joined by single hyphens, such as write-jira-ticket.';

/**
 * The `name` of a SKILL.md's front matter, as written, for the page to show before the upload:
 * the server reads the YAML and decides. Null without front matter or a name.
 */
export function frontMatterName(text: string): string | null {
  const lines = text.split(/\r?\n/);
  if (lines[0]?.trim() !== '---') return null;
  for (const line of lines.slice(1)) {
    if (line.trim() === '---') return null;
    const match = /^name:\s*(.*?)\s*$/.exec(line);
    if (match) {
      const name = match[1].replace(/^(["'])(.*)\1$/, '$2').trim();
      return name || null;
    }
  }
  return null;
}

/** What a new skill or version is uploaded from: a SKILL.md written here, or a zip. */
export type SkillSource = 'write' | 'zip';

/** The upload form's draft. */
export interface SkillUploadDraft {
  readonly source: SkillSource;
  readonly skillMd: string;
  readonly file: File | null;
  readonly note: string;
}

export function emptyUpload(skillMd = SKILL_MD_TEMPLATE): SkillUploadDraft {
  return { source: 'write', skillMd, file: null, note: '' };
}

/** What is wrong with the draft before it is sent, or null; the server checks everything again. */
export function uploadDraftProblem(draft: SkillUploadDraft): string | null {
  if (draft.note.length > SKILL_NOTE_MAX_CHARS) return 'The note has at most 500 characters.';
  if (draft.source === 'zip') {
    if (!draft.file) return 'Choose the zip of the skill’s folder.';
    if (draft.file.size > SKILL_UPLOAD_MAX_BYTES) {
      return 'The zip may be at most 5 MiB.';
    }
    return null;
  }
  const text = draft.skillMd;
  if (!text.trim()) return 'Write the SKILL.md: its front matter with a name and a description.';
  if (text.length > SKILL_MD_MAX_CHARS)
    return 'A SKILL.md written here has at most 100,000 characters.';
  const name = frontMatterName(text);
  if (name !== null && (name.length > 64 || !SKILL_NAME_PATTERN.test(name))) {
    return NAME_RULE;
  }
  return null;
}

/** The draft as `createSkill` and `addSkillVersion` take it: exactly one of `file` and `skillMd`. */
export function uploadBody(draft: SkillUploadDraft): SkillUpload {
  const note = draft.note.trim();
  const content: SkillUpload =
    draft.source === 'zip' && draft.file ? { file: draft.file } : { skillMd: draft.skillMd };
  return note ? { ...content, note } : content;
}

/** Why the server refused the files (`SKILL_INVALID`, `details.rule`, §9). */
const INVALID_RULES: Record<string, string> = {
  NOT_A_ZIP:
    'The file is not a zip Tinfirm can read: it is encrypted, damaged or another kind of file.',
  PATH: 'A path in the zip is not accepted: each is relative, at most 8 folders deep, without “..”, “\\” or “:”, and no two differ only in case.',
  NO_SKILL_MD: 'The zip has no SKILL.md at its root, or under its one top folder.',
  FRONT_MATTER:
    'SKILL.md starts with its front matter: a line ---, then YAML with its name and description, then a line ---.',
  NAME: NAME_RULE,
  DESCRIPTION: 'The description is required, at most 1,024 characters.',
  KEY: 'The front matter holds a key the Agent Skills format does not have: only name, description, license, compatibility, metadata and allowed-tools.',
};

/** Which limit an upload broke (`SKILL_TOO_LARGE`, `details.limit`, §4.2). */
const LIMITS: Record<string, string> = {
  FILES: 'A skill holds at most 200 files.',
  BYTES: 'A skill holds at most 5 MiB of files, uncompressed.',
  UPLOAD: 'The zip may be at most 5 MiB.',
  STORED: 'Zipped again as the server stores it, the skill passes 5 MiB: make its files a little smaller.',
};

/** A refused upload, in the page's words. */
export interface UploadFailure {
  readonly message: string;
  /** The file the problem names (`details.path`), shown in mono. */
  readonly path: string | null;
  /** What the credential scan found: names only, never a value. */
  readonly matches: readonly { kind: CredentialMatch['kind'] | null; name: string }[];
}

/**
 * A refused `createSkill` or `addSkillVersion` (§9): the rule or the limit it broke and the file
 * it names, a name the library has already, a name a new version changed, or what the scan found.
 * `name` is the skill's name when known (a new version, or the front matter's).
 */
export function uploadFailure(error: unknown, name: string | null = null): UploadFailure {
  const problem = toProblem(error);
  const path = problemDetail(error, 'path') ?? null;
  const withDetail = (text: string) =>
    problem.detail && !text.includes(problem.detail)
      ? `${text} ${problem.detail.trim().replace(/([^.!?])$/, '$1.')}`
      : text;
  switch (problem.code) {
    case 'SKILL_INVALID': {
      const rule = problemDetail(error, 'rule');
      const text = rule && INVALID_RULES[rule];
      return { message: text || withDetail(problemMessage(error)), path, matches: [] };
    }
    case 'SKILL_TOO_LARGE': {
      const limit = problemDetail(error, 'limit');
      return {
        message: `The skill is too large. ${(limit && LIMITS[limit]) || withDetail('')}`.trim(),
        path,
        matches: [],
      };
    }
    case 'DUPLICATE_SKILL_NAME':
      return {
        message: `The library already has a skill named ${name ?? 'so'}. Name this one otherwise, or upload a new version of that skill from its page.`,
        path: null,
        matches: [],
      };
    case 'SKILL_NAME_CHANGED':
      return {
        message: name
          ? `A new version keeps the skill’s name: write name: ${name} in the front matter of SKILL.md.`
          : problemMessage(error),
        path: null,
        matches: [],
      };
    case 'SKILL_CONTAINS_CREDENTIAL':
      return {
        message:
          'The skill contains a credential, so nothing was stored. Remove it and upload again: a skill never needs one, since its agent acts with the accounts of whoever uses it.',
        path,
        matches: scanMatches(error),
      };
  }
  const fieldMessages = problem.fieldErrors.map((e) => e.message).filter(Boolean);
  if (problem.code === 'VALIDATION_FAILED' && fieldMessages.length > 0) {
    return { message: fieldMessages.join(' '), path: null, matches: [] };
  }
  return { message: problemMessage(error), path: null, matches: [] };
}

// --- Words --------------------------------------------------------------------------------------

/** "1 file", "3 files". */
export function count(n: number, one: string, many: string): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** "Owned by Bob Svoboda", or the admins' once its owner left. */
export function ownerText(ownerName: string | null | undefined): string {
  return ownerName ? `owned by ${ownerName}` : 'owned by the company’s admins';
}

/**
 * Whom a skill is shared with, in one line: the groups by name (as far as they are known), then
 * the people.
 */
export function skillSharingText(
  groupNames: readonly string[],
  groupCount: number,
  peopleNames: readonly string[],
  peopleCount: number,
): string {
  if (groupCount + peopleCount === 0) return 'Not shared';
  const parts: string[] = [];
  if (groupNames.length === groupCount) parts.push(...groupNames);
  else parts.push(count(groupCount, 'group', 'groups'));
  if (peopleNames.length === peopleCount) parts.push(...peopleNames);
  else if (peopleCount > 0) parts.push(count(peopleCount, 'person', 'people'));
  return `Shared with ${namesText(parts)}`;
}

/** "Used by 3 agents", for whoever edits the skill (`agentCount`). */
export function usedByText(agentCount: number | null | undefined): string | null {
  if (agentCount === null || agentCount === undefined) return null;
  return agentCount === 0 ? 'Used by no agent' : `Used by ${count(agentCount, 'agent', 'agents')}`;
}

// --- Links --------------------------------------------------------------------------------------

/**
 * A version's canonical zip as a plain link: the browser downloads it with the session cookie, and
 * the server names it `{name}-v{version}.zip`.
 */
export function skillVersionUrl(companyId: string, skillId: string, version: number): string {
  return downloadSkillVersion.PATH.replace('{companyId}', encodeURIComponent(companyId))
    .replace('{skillId}', encodeURIComponent(skillId))
    .replace('{version}', String(version));
}

/** The version of a skill an agent pins, through the agent (§5.4): whoever sees the agent reads it. */
export function agentSkillUrl(companyId: string, agentId: string, skillId: string): string {
  return downloadAgentSkill.PATH.replace('{companyId}', encodeURIComponent(companyId))
    .replace('{agentId}', encodeURIComponent(agentId))
    .replace('{skillId}', encodeURIComponent(skillId));
}

/** The file name the server gives a version's zip. */
export function zipName(name: string, version: number): string {
  return `${name}-v${version}.zip`;
}
