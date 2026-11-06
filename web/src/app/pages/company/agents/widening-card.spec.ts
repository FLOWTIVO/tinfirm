import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { AgentSettings } from '../../../api/generated/models';
import { anAgent, anAgentSettings, aWidening, IDS } from '../../../testing/fixtures';
import { problem, provideTestHttp, settle } from '../../../testing/test-helpers';
import { skillsSection, WideningCard, wideningSections } from './widening-card';

describe('wideningSections', () => {
  it('names what the new version adds, and what it was', () => {
    const sections = wideningSections(aWidening(), anAgent());
    expect(sections.map((s) => [s.title, s.items.map((i) => i.text)])).toEqual([
      ['Systems', ['Jira']],
      [
        'Tools',
        [
          'Jira · Comment on an issue: Allowed (new)',
          'GitHub · Open a pull request: Allowed, where it needed your approval',
        ],
      ],
      ['Repositories', ['acme/docs: read and write, was read-only']],
      ['Network hosts', ['pypi.org']],
    ]);
  });

  it('leaves out what does not widen, and names what the agent no longer lists', () => {
    const sections = wideningSections(
      aWidening({
        systems: [],
        tools: [{ systemId: 'sys-gone', tool: 'x', mode: 'ALLOW', was: 'ALLOW_FOR_READS' }],
        repositories: [{ repositoryId: 'repo-gone', access: 'READ', was: null }],
        hosts: [],
      }),
      anAgent(),
    );
    expect(sections.map((s) => s.items[0].text)).toEqual([
      'A system · x: Allowed, where it was allowed only while it read',
      'A repository: read-only (new)',
    ]);
  });
});

describe('skillsSection', () => {
  it('names each skill added or moved, with the version it was', () => {
    const section = skillsSection(
      aWidening({
        skills: [
          { skillId: 's1', name: 'release-notes', version: 3, was: null },
          { skillId: 's2', name: 'triage', version: 5, was: 2 },
        ],
      }),
    );
    expect(section?.title).toBe('Skills');
    expect(section?.items.map((i) => i.text)).toEqual([
      'release-notes: version 3 (new)',
      'triage: version 5, was version 2',
    ]);
    expect(skillsSection(aWidening())).toBeNull();
  });

  it('comes last among what widened', () => {
    const sections = wideningSections(
      aWidening({ skills: [{ skillId: 's1', name: 'release-notes', version: 1, was: null }] }),
      anAgent(),
    );
    expect(sections.map((s) => s.key)).toEqual([
      'systems',
      'tools',
      'repositories',
      'hosts',
      'skills',
    ]);
  });
});

describe('WideningCard', () => {
  let fixture: ComponentFixture<WideningCard>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let accepted: AgentSettings[];

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [WideningCard], providers: [...provideTestHttp()] });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(WideningCard);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('agent', anAgent());
    fixture.componentRef.setInput('widening', aWidening());
    accepted = [];
    fixture.componentInstance.accepted.subscribe((s) => accepted.push(s));
    el = fixture.nativeElement;
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  it('is orange, and accepts the version it shows', async () => {
    const card = el.querySelector('[data-testid=widening-card]')!;
    expect(card.classList).toContain('border-human');
    expect(card.querySelector('h3')?.textContent?.trim()).toBe(
      'Business analyst has a new version to accept',
    );
    expect(card.textContent).toContain('may do more than version 4');
    expect(el.querySelector('[data-section=hosts] dd')?.classList).toContain('font-mono');

    const button = el.querySelector<HTMLButtonElement>('[data-testid=accept-version]')!;
    expect(button.textContent?.trim()).toBe('Accept version 6');
    button.click();
    await settle();
    const req = http.expectOne({
      method: 'POST',
      url: `/api/companies/c1/agents/${IDS.agent}/accept`,
    });
    expect(req.request.body).toEqual({ version: 6 });
    const settings = anAgentSettings({ acceptedVersion: 6, currentVersion: 6 });
    req.flush(settings);
    await settle();
    expect(accepted).toEqual([settings]);
  });

  it('shows a change of the instructions as a diff, and says when only what it is told changed', async () => {
    expect(el.querySelector('[data-section=instructions]')).toBeNull();
    fixture.componentRef.setInput(
      'widening',
      aWidening({
        systems: [],
        tools: [],
        repositories: [],
        hosts: [],
        instructions: { accepted: 'Answer in French.', current: 'Answer in German.' },
        skills: [{ skillId: 's1', name: 'release-notes', version: 2, was: 1 }],
      }),
    );
    await fixture.whenStable();
    const section = el.querySelector('[data-section=instructions]')!;
    expect(section.querySelector('dt')?.textContent?.trim()).toBe('Instructions');
    const rows = Array.from(section.querySelectorAll('tr')).map((r) => r.getAttribute('data-type'));
    expect(rows).toEqual(['del', 'add']);
    expect(el.querySelector('[data-section=skills] dd')?.textContent?.trim()).toBe(
      'release-notes: version 2, was version 1',
    );
    expect(el.textContent).toContain('changes what it is told');
    expect(el.textContent).not.toContain('may do more');
  });

  it('says why accepting failed', async () => {
    el.querySelector<HTMLButtonElement>('[data-testid=accept-version]')!.click();
    await settle();
    http
      .expectOne({ method: 'POST', url: `/api/companies/c1/agents/${IDS.agent}/accept` })
      .flush(problem(403, 'AGENT_NOT_USABLE'), { status: 403, statusText: 'Forbidden' });
    await settle();
    expect(el.querySelector('[data-testid=widening-error]')?.textContent).toContain(
      'not shared with you',
    );
    expect(accepted).toEqual([]);
  });
});
