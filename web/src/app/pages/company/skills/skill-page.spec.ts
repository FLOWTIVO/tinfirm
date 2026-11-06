import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type { MockInstance } from 'vitest';
import type { Skill } from '../../../api/generated/models';
import {
  aGroupSummary,
  aMember,
  aSkill,
  aSkillVersion,
  aSkillVersionSummary,
  IDS,
  JIRA_SKILL_MD,
} from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import { SkillPage } from './skill-page';

const API = '/api/companies/c1';
const SKILL = `${API}/skills/${IDS.skill}`;

/** Alice; Bob, who owns the skill; and Carol. */
const MEMBERS = [
  aMember({ principalId: IDS.alice }),
  aMember({
    userId: IDS.bobUser,
    principalId: IDS.bob,
    displayName: 'Bob Svoboda',
    email: 'bob@acme.test',
  }),
  aMember({
    userId: IDS.carolUser,
    principalId: IDS.carol,
    displayName: 'Carol Dvořák',
    email: 'carol@acme.test',
  }),
];

describe('SkillPage', () => {
  let fixture: ComponentFixture<SkillPage>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let navigate: MockInstance<Router['navigate']>;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  /** Opens the skill, and answers its latest version, the groups and, for an editor, the members. */
  async function open(skill: Skill | null): Promise<void> {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [SkillPage],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany('MEMBER').provider,
        notifier.provider,
      ],
    });
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SkillPage);
    fixture.componentRef.setInput('skillId', IDS.skill);
    el = fixture.nativeElement;
    await settle();
    const req = http.expectOne(SKILL);
    if (!skill) {
      req.flush(problem(404), { status: 404, statusText: 'Not Found' });
      await settle();
      return;
    }
    req.flush(skill);
    await settle();
    http.expectOne(`${SKILL}/versions/${skill.latestVersion}`).flush(aSkillVersion());
    http.expectOne((r) => r.url === `${API}/groups`).flush({ items: [aGroupSummary()] });
    if (skill.editableByMe) {
      http.expectOne((r) => r.url === `${API}/members`).flush({ items: MEMBERS });
    }
    await settle();
  }

  afterEach(() => http.verify());

  const $ = <T extends Element = HTMLElement>(selector: string) => el.querySelector<T>(selector);
  const words = (node: Element | null | undefined) =>
    node?.textContent?.replace(/\s+/g, ' ').trim() ?? null;
  const editable = () => aSkill({ editableByMe: true, agentCount: 2 });

  it('shows the skill, its latest version’s files and SKILL.md, and its versions', async () => {
    await open(aSkill());
    expect(words($('h1'))).toBe('write-jira-ticket');
    expect(words($('[data-testid=skill-meta]'))).toBe('Owned by Bob Svoboda · 3 versions');

    const view = $('[data-testid=version-view]')!;
    expect(words(view.querySelector('h2'))).toBe('Version 3 · the latest');
    expect(view.textContent).toContain('Adds the acceptance criteria');
    expect(
      [...view.querySelectorAll('[data-testid=version-files] [data-path]')].map((li) =>
        li.getAttribute('data-path'),
      ),
    ).toEqual(['SKILL.md', 'templates/ticket.md']);
    expect(view.querySelector('[data-testid=version-skill-md]')?.textContent).toBe(JIRA_SKILL_MD);
    const download = view.querySelector('[data-testid=download-viewed]')!;
    expect(download.getAttribute('href')).toBe(`${SKILL}/versions/3/content`);
    expect(download.getAttribute('download')).toBe('write-jira-ticket-v3.zip');

    const versions = [...el.querySelectorAll<HTMLElement>('[data-testid=skill-versions] li')];
    expect(versions.map((li) => li.dataset['version'])).toEqual(['3', '2', '1']);
    expect(words(versions[1].querySelector('[data-note]'))).toBe('No note');
    expect(versions[0].querySelector('[data-meta]')?.textContent).toContain('by Bob Svoboda');
    expect(versions[2].querySelector('[data-meta]')?.textContent).toContain('1 file');

    // A reader who does not edit it reads whom it is shared with, and changes nothing.
    expect($(`[data-testid=skill-sharing] [data-group="${IDS.analysts}"]`)?.textContent).toContain(
      'Analysts',
    );
    expect($('[data-testid=new-version]')).toBeNull();
    expect($('[data-testid=delete-skill]')).toBeNull();
    expect($('[data-testid=skill-used-by]')).toBeNull();
  });

  it('opens another version in place', async () => {
    await open(aSkill());
    [...el.querySelectorAll<HTMLElement>('[data-testid=view-version]')][2].click();
    await settle();
    http.expectOne(`${SKILL}/versions/1`).flush(
      aSkillVersion({
        ...aSkillVersionSummary({ version: 1, note: 'First version', fileCount: 1 }),
        files: [{ path: 'SKILL.md', sizeBytes: 120 }],
        skillMd: '---\nname: write-jira-ticket\ndescription: Writes a Jira ticket.\n---\n',
      }),
    );
    await settle();
    const view = $('[data-testid=version-view]')!;
    expect(view.getAttribute('data-version')).toBe('1');
    expect(words(view.querySelector('h2'))).toBe('Version 1');
    expect(view.querySelectorAll('[data-testid=version-files] li').length).toBe(1);
    expect(view.querySelector('[data-testid=version-skill-md]')?.textContent).toContain(
      'Writes a Jira ticket.',
    );
  });

  it('uploads a new version from the latest SKILL.md, which no agent moves to', async () => {
    await open(editable());
    expect(words($('[data-testid=skill-used-by] h2'))).toBe('Used by 2 agents');
    $<HTMLElement>('[data-testid=new-version]')!.click();
    await settle();
    const form = $('[data-testid=version-form]')!;
    expect(words(form.querySelector('h2'))).toBe('New version 4');
    const text = form.querySelector<HTMLTextAreaElement>('[data-testid=skill-md]')!;
    expect(text.value).toBe(JIRA_SKILL_MD);
    // Version 3 has a template beside its SKILL.md: a SKILL.md alone leaves it out.
    expect(words(form.querySelector('[data-testid=drops-files]'))).toContain(
      'the 1 other file of the latest version are left out',
    );
    typeInto(text, `${JIRA_SKILL_MD}Add the labels.\n`);
    typeInto(form.querySelector('[data-testid=skill-note]'), 'Adds the labels');
    submitForm(form);
    await settle();

    const req = http.expectOne({ method: 'POST', url: `${SKILL}/versions` });
    const body = req.request.body as FormData;
    expect(body.get('skillMd')).toBe(`${JIRA_SKILL_MD}Add the labels.\n`);
    expect(body.get('note')).toBe('Adds the labels');
    const stored = editable();
    req.flush(
      {
        ...stored,
        latestVersion: 4,
        versions: [
          aSkillVersionSummary({ version: 4, fileCount: 1, note: 'Adds the labels' }),
          ...stored.versions,
        ],
      },
      { status: 201, statusText: 'Created' },
    );
    await settle();
    expect(notify.success).toHaveBeenCalledWith(
      'Version 4 stored',
      'No agent moves to it by itself: their editors see that it is there.',
    );
    expect($('[data-testid=version-form]')).toBeNull();
    // The new latest version opens.
    http
      .expectOne(`${SKILL}/versions/4`)
      .flush(aSkillVersion({ version: 4, files: [{ path: 'SKILL.md', sizeBytes: 180 }] }));
    await settle();
    expect(words($('[data-testid=version-view] h2'))).toBe('Version 4 · the latest');
  });

  it('says when the files are the latest version’s, and stores nothing', async () => {
    await open(editable());
    $<HTMLElement>('[data-testid=new-version]')!.click();
    await settle();
    submitForm($('[data-testid=version-form]'));
    await settle();
    http
      .expectOne({ method: 'POST', url: `${SKILL}/versions` })
      .flush(editable(), { status: 200, statusText: 'OK' });
    await settle();
    expect(notify.warning).toHaveBeenCalledWith(
      'Nothing new to store',
      'These are the same files as version 3, so no version was added.',
    );
    expect(notify.success).not.toHaveBeenCalled();
  });

  it('says the name a new version must keep, before sending and when the zip changed it', async () => {
    await open(editable());
    $<HTMLElement>('[data-testid=new-version]')!.click();
    await settle();
    const kept =
      'A new version keeps the skill’s name: write name: write-jira-ticket in the front matter of SKILL.md.';
    typeInto($('[data-testid=skill-md]'), '---\nname: write-ticket\ndescription: x\n---\n');
    submitForm($('[data-testid=version-form]'));
    await settle();
    http.expectNone({ method: 'POST', url: `${SKILL}/versions` });
    expect(words($('[data-testid=upload-error]'))).toBe(kept);

    $<HTMLInputElement>('[id$=-source-zip]')!.click();
    await settle();
    const input = $<HTMLInputElement>('[data-testid=skill-zip]')!;
    Object.defineProperty(input, 'files', { value: [new File(['PK'], 'v4.zip')] });
    input.dispatchEvent(new Event('change'));
    await settle();
    submitForm($('[data-testid=version-form]'));
    await settle();
    http
      .expectOne({ method: 'POST', url: `${SKILL}/versions` })
      .flush(problem(400, 'SKILL_NAME_CHANGED'), { status: 400, statusText: 'Bad Request' });
    await settle();
    expect(words($('[data-testid=upload-error]'))).toBe(kept);
    expect($('[data-testid=version-form]')).not.toBeNull();
  });

  it('shares it with groups and people, as the agents are shared', async () => {
    await open(editable());
    const sharing = $('[data-testid=skill-sharing]')!;
    // Its owner is not listed: they see it already.
    expect(
      [...sharing.querySelectorAll<HTMLElement>('[data-testid=share-people] [data-person]')].map(
        (li) => li.dataset['person'],
      ),
    ).toEqual([IDS.alice, IDS.carol]);
    sharing.querySelector<HTMLInputElement>(`[data-group="${IDS.analysts}"] input`)!.click();
    sharing.querySelector<HTMLInputElement>(`[data-person="${IDS.carol}"] input`)!.click();
    await settle();
    expect(words(sharing.querySelector('[data-testid=share-summary]'))).toBe(
      'Shared with Carol Dvořák',
    );
    sharing.querySelector<HTMLElement>('[data-testid=save-shares]')!.click();
    await settle();
    const put = http.expectOne({ method: 'PUT', url: `${SKILL}/shares` });
    expect(put.request.body).toEqual({ groupIds: [], principalIds: [IDS.carol] });
    put.flush(
      aSkill({
        editableByMe: true,
        agentCount: 2,
        shareGroupIds: [],
        sharePrincipalIds: [IDS.carol],
        memberShares: [{ principalId: IDS.carol, userId: IDS.carolUser, name: 'Carol Dvořák' }],
      }),
    );
    await settle();
    expect(notify.success).toHaveBeenCalledWith(
      'Sharing saved',
      'Taking a share from someone takes the skill from no agent that pins it.',
    );
    expect(sharing.querySelector('[data-testid=save-shares]')).toBeNull();
  });

  it('deletes it once confirmed, saying that the agents that pin it keep it', async () => {
    await open(editable());
    $<HTMLElement>('[data-testid=delete-skill]')!.click();
    await settle();
    const dialog = document.body.querySelector('[data-testid=delete-dialog]')!;
    expect(dialog.textContent).toContain('Delete write-jira-ticket?');
    expect(words(dialog.querySelector('[data-testid=delete-consequence]'))).toBe(
      'The 2 agents that pin a version of it keep that version, and their runs still get it. It leaves the library: nobody can add it to an agent or move a pin to another of its versions, and its name is free again. You can’t undo this.',
    );
    document.body.querySelector<HTMLElement>('[data-testid=confirm-delete]')!.click();
    await settle();
    http.expectOne({ method: 'DELETE', url: SKILL }).flush(null, {
      status: 204,
      statusText: 'No Content',
    });
    await settle();
    expect(notify.success).toHaveBeenCalledWith(
      'write-jira-ticket deleted',
      'The agents that pin it keep their version.',
    );
    expect(navigate).toHaveBeenCalledWith(['/c', 'c1', 'skills']);
  });

  it('removes it from the agents that pin it when asked', async () => {
    await open(editable());
    $<HTMLElement>('[data-testid=delete-skill]')!.click();
    await settle();
    const dialog = document.body.querySelector('[data-testid=delete-dialog]')!;
    const box = dialog.querySelector<HTMLInputElement>('[data-testid=remove-from-agents]')!;
    expect(box.checked).toBe(false);
    expect(dialog.textContent).toContain('Also remove it from the 2 agents that pin it');
    box.click();
    await settle();
    expect(words(dialog.querySelector('[data-testid=delete-consequence]'))).toBe(
      'The 2 agents that pin it lose it at their next turn. It leaves the library: nobody can add it to an agent again, and its name is free again. You can’t undo this.',
    );
    document.body.querySelector<HTMLElement>('[data-testid=confirm-delete]')!.click();
    await settle();
    const request = http.expectOne(
      (r) =>
        r.method === 'DELETE' && r.url === SKILL && r.params.get('removeFromAgents') === 'true',
    );
    request.flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    expect(notify.success).toHaveBeenCalledWith(
      'write-jira-ticket deleted',
      'It was removed from the agents that pinned it.',
    );
  });

  it('says why a delete was refused', async () => {
    await open(editable());
    $<HTMLElement>('[data-testid=delete-skill]')!.click();
    await settle();
    document.body.querySelector<HTMLElement>('[data-testid=confirm-delete]')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: SKILL })
      .flush(problem(403, 'SKILL_NOT_EDITABLE'), { status: 403, statusText: 'Forbidden' });
    await settle();
    expect(document.body.querySelector('[data-testid=delete-error]')?.textContent).toContain(
      'Only the skill’s owner and the company’s admins can change this skill.',
    );
    expect(navigate).not.toHaveBeenCalled();
  });

  it('shows the not-found page for a skill the reader does not see', async () => {
    await open(null);
    expect(el.querySelector('app-not-found')).not.toBeNull();
    expect(el.querySelector('[data-testid=skill-header]')).toBeNull();
  });
});
