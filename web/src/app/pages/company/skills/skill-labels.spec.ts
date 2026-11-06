import { HttpErrorResponse } from '@angular/common/http';
import { IDS, JIRA_SKILL_MD } from '../../../testing/fixtures';
import { problem } from '../../../testing/test-helpers';
import {
  agentSkillUrl,
  emptyUpload,
  frontMatterName,
  SKILL_MD_TEMPLATE,
  SKILL_UPLOAD_MAX_BYTES,
  skillSharingText,
  skillVersionUrl,
  uploadBody,
  uploadDraftProblem,
  uploadFailure,
  usedByText,
} from './skill-labels';

const refused = (status: number, code: string, extra: Record<string, unknown> = {}) =>
  new HttpErrorResponse({ status, error: { ...problem(status, code), ...extra } });

describe('the skills library’s words', () => {
  it('reads the name of a SKILL.md’s front matter, as written', () => {
    expect(frontMatterName(JIRA_SKILL_MD)).toBe('write-jira-ticket');
    expect(frontMatterName('---\nname: "release-notes"\n---\n')).toBe('release-notes');
    expect(frontMatterName(SKILL_MD_TEMPLATE)).toBeNull();
    expect(frontMatterName('name: no-front-matter')).toBeNull();
    expect(frontMatterName('---\ndescription: x\n---\nname: after-it')).toBeNull();
  });

  it('checks a draft before it is sent, and sends exactly one of the file and the text', () => {
    expect(uploadDraftProblem(emptyUpload(''))).toContain('Write the SKILL.md');
    expect(uploadDraftProblem(emptyUpload('---\nname: Write_Ticket\n---\n'))).toContain(
      'lower-case letters and digits',
    );
    expect(uploadDraftProblem(emptyUpload(JIRA_SKILL_MD))).toBeNull();
    expect(uploadDraftProblem({ ...emptyUpload(JIRA_SKILL_MD), source: 'zip' })).toBe(
      'Choose the zip of the skill’s folder.',
    );
    const big = new File(['x'], 'big.zip');
    Object.defineProperty(big, 'size', { value: SKILL_UPLOAD_MAX_BYTES + 1 });
    expect(uploadDraftProblem({ ...emptyUpload(), source: 'zip', file: big })).toBe(
      'The zip may be at most 5 MiB.',
    );

    const zip = new File(['PK'], 'skill.zip');
    expect(uploadBody({ ...emptyUpload(JIRA_SKILL_MD), source: 'zip', file: zip })).toEqual({
      file: zip,
    });
    expect(uploadBody({ ...emptyUpload(JIRA_SKILL_MD), note: '  First version ' })).toEqual({
      skillMd: JIRA_SKILL_MD,
      note: 'First version',
    });
  });

  it.each([
    ['NOT_A_ZIP', 'The file is not a zip Tinfirm can read'],
    ['NO_SKILL_MD', 'The zip has no SKILL.md at its root'],
    ['FRONT_MATTER', 'SKILL.md starts with its front matter'],
    ['NAME', 'The name is 1 to 64 lower-case letters'],
    ['DESCRIPTION', 'The description is required'],
    ['KEY', 'only name, description, license, compatibility, metadata and allowed-tools'],
  ])('says the rule SKILL_INVALID names (%s)', (rule, text) => {
    const failure = uploadFailure(refused(400, 'SKILL_INVALID', { details: { rule } }));
    expect(failure.message).toContain(text);
    expect(failure.path).toBeNull();
  });

  it('names the file a refused path is in', () => {
    const failure = uploadFailure(
      refused(400, 'SKILL_INVALID', { details: { rule: 'PATH', path: 'scripts/../x.sh' } }),
    );
    expect(failure.message).toContain('A path in the zip is not accepted');
    expect(failure.path).toBe('scripts/../x.sh');
  });

  it.each([
    ['FILES', 'A skill holds at most 200 files.'],
    ['BYTES', 'A skill holds at most 5 MiB of files, uncompressed.'],
    ['UPLOAD', 'The zip may be at most 5 MiB.'],
    ['STORED', 'Zipped again as the server stores it, the skill passes 5 MiB: make its files a little smaller.'],
  ])('says the limit SKILL_TOO_LARGE names (%s)', (limit, text) => {
    expect(uploadFailure(refused(400, 'SKILL_TOO_LARGE', { details: { limit } })).message).toBe(
      `The skill is too large. ${text}`,
    );
  });

  it('names the skill a name is taken by, or must stay', () => {
    expect(uploadFailure(refused(409, 'DUPLICATE_SKILL_NAME'), 'write-jira-ticket').message).toBe(
      'The library already has a skill named write-jira-ticket. Name this one otherwise, or upload a new version of that skill from its page.',
    );
    expect(uploadFailure(refused(400, 'SKILL_NAME_CHANGED'), 'write-jira-ticket').message).toBe(
      'A new version keeps the skill’s name: write name: write-jira-ticket in the front matter of SKILL.md.',
    );
  });

  it('lists what the credential scan found, by name only', () => {
    const failure = uploadFailure(
      refused(422, 'SKILL_CONTAINS_CREDENTIAL', {
        credentialMatches: [{ kind: 'VAULT_SECRET', name: 'JIRA_TOKEN' }, 'GitHub token', 42],
      }),
    );
    expect(failure.message).toContain('The skill contains a credential, so nothing was stored.');
    expect(failure.matches).toEqual([
      { kind: 'VAULT_SECRET', name: 'JIRA_TOKEN' },
      { kind: null, name: 'GitHub token' },
    ]);
  });

  it('says whom a skill is shared with and how many agents use it', () => {
    expect(skillSharingText([], 0, [], 0)).toBe('Not shared');
    expect(skillSharingText(['Analysts'], 1, [], 2)).toBe('Shared with Analysts and 2 people');
    expect(skillSharingText([], 2, ['Carol Dvořák'], 1)).toBe(
      'Shared with 2 groups and Carol Dvořák',
    );
    expect(usedByText(null)).toBeNull();
    expect(usedByText(0)).toBe('Used by no agent');
    expect(usedByText(1)).toBe('Used by 1 agent');
  });

  it('links a version’s zip, and an agent’s pinned skill', () => {
    expect(skillVersionUrl('c1', IDS.skill, 3)).toBe(
      `/api/companies/c1/skills/${IDS.skill}/versions/3/content`,
    );
    expect(agentSkillUrl('c1', IDS.agent, IDS.skill)).toBe(
      `/api/companies/c1/agents/${IDS.agent}/skills/${IDS.skill}/content`,
    );
  });
});
