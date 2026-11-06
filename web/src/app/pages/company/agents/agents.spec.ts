import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type { AgentSummary, Role } from '../../../api/generated/models';
import { anAgentSummary } from '../../../testing/fixtures';
import { fakeCurrentCompany, provideTestHttp, settle } from '../../../testing/test-helpers';
import { Agents } from './agents';

const URL = '/api/companies/c1/agents';

describe('Agents', () => {
  let fixture: ComponentFixture<Agents>;
  let http: HttpTestingController;
  let el: HTMLElement;

  async function open(
    items: AgentSummary[],
    options: { role?: Role | null; show?: string } = {},
  ): Promise<string> {
    TestBed.configureTestingModule({
      imports: [Agents],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany(options.role === undefined ? 'MEMBER' : options.role).provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Agents);
    if (options.show) fixture.componentRef.setInput('show', options.show);
    el = fixture.nativeElement;
    await settle();
    const req = http.expectOne((r) => r.url === URL);
    req.flush({ items });
    await settle();
    return req.request.urlWithParams;
  }

  afterEach(() => http.verify());

  const row = (id: string) => el.querySelector<HTMLElement>(`[data-testid="agent-${id}"]`)!;

  it('lists the agents the reader may use, with what each lets them do', async () => {
    const url = await open([
      anAgentSummary({ wideningPending: true }),
      anAgentSummary({
        id: 'a2',
        name: 'Release notes',
        handle: 'rn',
        status: 'PAUSED',
        editableByMe: true,
      }),
    ]);
    expect(url).toContain('usable=true');

    const ba = row('a-ba');
    expect(ba.querySelector('[data-testid=agent-widening]')?.textContent?.trim()).toBe(
      'New version to review',
    );
    // The list's chip stays neutral: orange is for the agent's own page, where one accepts.
    expect(ba.querySelector('[data-testid=agent-widening]')?.className).not.toContain('human');
    expect(ba.querySelector('[data-testid=talk-to]')?.getAttribute('href')).toBe(
      '/c/c1/conversations/new?agentId=a-ba',
    );
    expect(ba.querySelector('[data-testid=my-settings]')?.getAttribute('href')).toBe(
      '/c/c1/agents/a-ba/settings',
    );
    expect(ba.querySelector('[data-testid=edit-agent]')).toBeNull();
    expect(ba.textContent?.replace(/\s+/g, ' ')).toContain(
      'Claude Code, then Codex · version 4 · owned by Bob Svoboda',
    );

    const rn = row('a2');
    expect(rn.querySelector('[data-testid=agent-paused]')?.textContent?.trim()).toBe('Paused');
    expect(rn.querySelector('[data-testid=edit-agent]')?.getAttribute('href')).toBe(
      '/c/c1/agents/a2/edit',
    );
  });

  it('tells an agent’s editors how many of its skills have an update', async () => {
    await open([
      anAgentSummary({ editableByMe: true, skillUpdates: 2 }),
      anAgentSummary({ id: 'a2', name: 'Release notes', handle: 'rn', skillUpdates: 0 }),
      anAgentSummary({ id: 'a3', name: 'Reader', handle: 'reader', skillUpdates: 1 }),
    ]);
    expect(
      row('a-ba')
        .querySelector('[data-testid=agent-skill-updates]')
        ?.textContent?.replace(/\s+/g, ' ')
        .trim(),
    ).toBe('2 skill updates');
    expect(row('a2').querySelector('[data-testid=agent-skill-updates]')).toBeNull();
    // The server says 0 to whoever does not edit it; the list never shows it to them.
    expect(row('a3').querySelector('[data-testid=agent-skill-updates]')).toBeNull();
  });

  it('shows all the reader sees, and which they may not use', async () => {
    const url = await open([anAgentSummary({ usableByMe: false, editableByMe: true })], {
      role: 'ADMIN',
      show: 'all',
    });
    expect(url).toContain('usable=false');
    const ba = row('a-ba');
    expect(ba.querySelector('[data-testid=agent-not-usable]')?.textContent).toBe(
      'not shared with you',
    );
    expect(ba.querySelector('[data-testid=talk-to]')).toBeNull();
    expect(ba.querySelector('[data-testid=edit-agent]')).not.toBeNull();
  });

  it('switches between "I can use" and "All I see" in the address', async () => {
    await open([anAgentSummary()]);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    el.querySelector<HTMLInputElement>('#agents-shown-all')!.click();
    expect(navigate).toHaveBeenCalledWith([], {
      queryParams: { show: 'all' },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  });

  it.each([
    [undefined, 'No agent is shared with you yet'],
    ['all', 'No agents yet'],
  ])('says what to do when nothing is listed (%s)', async (show, title) => {
    await open([], { show });
    const empty = el.querySelector('[data-testid=agents-empty]')!;
    expect(empty.textContent).toContain(title);
    expect(empty.querySelector('a')?.textContent).toContain('Create an agent');
    expect(el.querySelector('[data-testid=new-agent]')).not.toBeNull();
  });

  it('tells a viewer why they create and talk to no agent (ADR-0077)', async () => {
    await open([anAgentSummary()], { role: 'VIEWER' });
    expect(el.querySelector('[data-testid=new-agent]')).toBeNull();
    expect(row('a-ba').querySelector('[data-testid=talk-to]')).toBeNull();
    expect(el.querySelector('[data-testid=viewer-notice]')?.textContent).toContain(
      'Viewers read the agents, but don’t talk to them, create or edit them.',
    );
  });

  it('offers no new agent to an instance admin who is not a member', async () => {
    await open([anAgentSummary({ usableByMe: false })], { role: null, show: 'all' });
    expect(el.querySelector('[data-testid=new-agent]')).toBeNull();
    expect(row('a-ba').querySelector('[data-testid=talk-to]')).toBeNull();
  });
});
