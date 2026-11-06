import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type { Role, SkillSummary } from '../../../api/generated/models';
import { aGroupSummary, aSkillSummary, IDS } from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import { Skills } from './skills';

const API = '/api/companies/c1';

describe('Skills', () => {
  let fixture: ComponentFixture<Skills>;
  let http: HttpTestingController;
  let el: HTMLElement;

  /** Opens the library, answers its skills and the groups, and returns the list's address. */
  async function open(
    items: SkillSummary[],
    options: { role?: Role | null; q?: string; nextCursor?: string } = {},
  ): Promise<string> {
    TestBed.configureTestingModule({
      imports: [Skills],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany(options.role === undefined ? 'MEMBER' : options.role).provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Skills);
    if (options.q) fixture.componentRef.setInput('q', options.q);
    el = fixture.nativeElement;
    await settle();
    const req = http.expectOne((r) => r.url === `${API}/skills`);
    req.flush({ items, nextCursor: options.nextCursor ?? null });
    http.expectOne((r) => r.url === `${API}/groups`).flush({ items: [aGroupSummary()] });
    await settle();
    return req.request.urlWithParams;
  }

  afterEach(() => http.verify());

  const row = (id: string) => el.querySelector<HTMLElement>(`[data-testid="skill-${id}"]`)!;
  const words = (node: Element | null | undefined) =>
    node?.textContent?.replace(/\s+/g, ' ').trim() ?? null;

  it('lists the skills the reader sees, each with its version, owner, sharing and use', async () => {
    const url = await open([
      aSkillSummary({ editableByMe: true, agentCount: 2, sharePrincipalIds: [IDS.carol] }),
      aSkillSummary({
        id: IDS.otherSkill,
        name: 'release-notes',
        description: 'Writes the release notes of a version.',
        latestVersion: 1,
        ownerPrincipalId: IDS.carol,
        ownerName: 'Carol Dvořák',
        shareGroupIds: [],
      }),
    ]);
    expect(url).toContain('limit=100');
    expect(url).not.toContain('q=');

    const jira = row(IDS.skill);
    expect(jira.querySelector('[data-testid=skill-link]')?.getAttribute('href')).toBe(
      `/c/c1/skills/${IDS.skill}`,
    );
    expect(words(jira.querySelector('[data-testid=skill-version]'))).toBe('v3');
    expect(jira.textContent).toContain('Writes a Jira ticket from a brief');
    expect(words(jira.querySelector('[data-testid=skill-meta]'))).toBe(
      'Owned by Bob Svoboda · shared with Analysts and 1 person · used by 2 agents',
    );
    // Only whoever edits a skill learns how many agents pin it.
    expect(words(row(IDS.otherSkill).querySelector('[data-testid=skill-meta]'))).toBe(
      'Owned by Carol Dvořák · not shared',
    );
    expect(el.querySelector('[data-testid=new-skill]')?.getAttribute('href')).toBe(
      '/c/c1/skills/new',
    );
  });

  it('searches on name and description, in the address', async () => {
    await open([aSkillSummary()]);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    typeInto(el.querySelector('#skills-search'), ' jira ');
    submitForm(el.querySelector('[data-testid=skills-search]'));
    expect(navigate).toHaveBeenCalledWith([], {
      queryParams: { q: 'jira' },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  });

  it('asks the server for what `q` names, and offers every skill when nothing matches', async () => {
    const url = await open([], { q: 'nothing' });
    expect(url).toContain('q=nothing');
    const empty = el.querySelector('[data-testid=skills-empty]')!;
    expect(empty.textContent).toContain('No skill matches');
    expect(empty.textContent).toContain('No skill you see has “nothing” in its name');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    empty.querySelector<HTMLButtonElement>('button')!.click();
    expect(navigate).toHaveBeenCalledWith([], {
      queryParams: { q: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  });

  it('says what a skill is when the library is empty', async () => {
    await open([]);
    const empty = el.querySelector('[data-testid=skills-empty]')!;
    expect(empty.textContent).toContain('No skills yet');
    expect(empty.querySelector('a')?.textContent).toContain('Create a skill');
  });

  it('pages like the agents', async () => {
    await open([aSkillSummary()], { nextCursor: 'next' });
    const more = [...el.querySelectorAll<HTMLButtonElement>('button')].find(
      (b) => b.textContent?.trim() === 'Load more',
    )!;
    more.click();
    await settle();
    const req = http.expectOne((r) => r.url === `${API}/skills`);
    expect(req.request.params.get('cursor')).toBe('next');
    req.flush({
      items: [aSkillSummary({ id: IDS.otherSkill, name: 'release-notes' })],
      nextCursor: null,
    });
    await settle();
    expect(el.querySelectorAll('[data-testid=skill-link]').length).toBe(2);
  });

  it('offers a viewer no new skill (ADR-0077)', async () => {
    await open([aSkillSummary()], { role: 'VIEWER' });
    expect(el.querySelector('[data-testid=new-skill]')).toBeNull();
  });

  it('offers no new skill to an instance admin who is not a member', async () => {
    await open([aSkillSummary({ editableByMe: true })], { role: null });
    expect(el.querySelector('[data-testid=new-skill]')).toBeNull();
  });
});
