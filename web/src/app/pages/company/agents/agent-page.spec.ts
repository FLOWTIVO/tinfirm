import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type { Agent, Role } from '../../../api/generated/models';
import {
  aMyAccount,
  anAgent,
  anAgentSettings,
  anAgentSkill,
  aModelAccess,
  aReadiness,
  aWidening,
  IDS,
} from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
} from '../../../testing/test-helpers';
import { AgentPage } from './agent-page';

const API = '/api/companies/c1';
const AGENT = `${API}/agents/${IDS.agent}`;

describe('AgentPage', () => {
  let fixture: ComponentFixture<AgentPage>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(agent: Agent | null, role: Role = 'MEMBER'): Promise<void> {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [AgentPage],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany(role).provider,
        notifier.provider,
      ],
    });
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AgentPage);
    fixture.componentRef.setInput('agentId', IDS.agent);
    el = fixture.nativeElement;
    await settle();
    const req = http.expectOne(AGENT);
    if (agent) req.flush(agent);
    else req.flush(problem(404, undefined), { status: 404, statusText: 'Not Found' });
    await settle();
    answerAround();
    await settle();
  }

  /** The reader's readiness, accounts and model access, whenever the page asks. */
  function answerAround(): void {
    for (const req of http.match(`${AGENT}/readiness`)) req.flush(aReadiness());
    for (const req of http.match(`${API}/me/accounts`)) {
      req.flush({ items: [aMyAccount()] });
    }
    for (const req of http.match(`${API}/me/model-access`)) req.flush(aModelAccess());
  }

  afterEach(() => http.verify());

  const body = () => document.body;

  it('says what it may use, and who acts and who pays when the reader uses it', async () => {
    await open(anAgent());
    expect(el.querySelector('h1')?.textContent?.trim()).toBe('Business analyst');
    const when = el.querySelector('[data-testid=when-you-use-it]')!;
    expect(when.textContent).toContain(
      'An agent holds no credentials: it acts as whoever uses it.',
    );
    expect(
      [...when.querySelectorAll('[data-part=acts] li')].map((li) => li.textContent?.trim()),
    ).toEqual(['GitHub: your own account', 'Jira: connect when first needed']);
    expect(when.querySelector('[data-part=pays]')?.textContent).toContain(
      'Your budget: $38.20 of $50.00 left',
    );

    const modes = [...el.querySelectorAll('[data-system] [data-tool]')].map((li) => [
      li.getAttribute('data-tool'),
      li.querySelector('[data-mode]')?.textContent?.trim(),
    ]);
    expect(modes).toEqual([
      ['get_file_contents', 'Allowed'],
      ['create_pull_request', 'Needs your approval'],
      ['add_comment', 'Allowed'],
    ]);
    expect(el.querySelector(`[data-repository="${IDS.shopRepo}"] [data-access]`)?.textContent).toBe(
      'read-only',
    );
    expect(el.querySelector('[data-testid=agent-sharing]')?.textContent).toContain(
      'Shared with Analysts.',
    );
    expect(el.querySelector('[data-testid=edit-agent]')).toBeNull();
    expect(el.querySelector('[data-testid=widening-card]')).toBeNull();
  });

  it('shows the orange widening card, and reads the agent again once accepted', async () => {
    await open(
      anAgent({ version: 6, acceptedVersion: 4, wideningPending: true, widening: aWidening() }),
    );
    expect(
      el.querySelector('[data-testid=agent-meta]')?.textContent?.replace(/\s+/g, ' '),
    ).toContain('Version 6 (you use 4)');
    const card = el.querySelector('[data-testid=widening-card]')!;
    expect(card.classList).toContain('border-human');
    el.querySelector<HTMLButtonElement>('[data-testid=accept-version]')!.click();
    await settle();
    const accept = http.expectOne({ method: 'POST', url: `${AGENT}/accept` });
    expect(accept.request.body).toEqual({ version: 6 });
    accept.flush(anAgentSettings({ acceptedVersion: 6, currentVersion: 6 }));
    await settle();
    http.expectOne(AGENT).flush(anAgent({ version: 6, acceptedVersion: 6 }));
    await settle();
    answerAround();
    await settle();
    expect(el.querySelector('[data-testid=widening-card]')).toBeNull();
    expect(notify.success).toHaveBeenCalledWith(
      'Version accepted',
      'Your conversations use it from their next turn.',
    );
  });

  it('names the people it is shared with on their own beside its groups', async () => {
    await open(
      anAgent({
        memberShares: [{ principalId: IDS.carol, userId: IDS.carolUser, name: 'Carol Dvořák' }],
      }),
    );
    const sharing = el.querySelector('[data-testid=agent-sharing]');
    expect(sharing?.querySelector(`[data-person="${IDS.carol}"]`)?.textContent).toContain(
      'Carol Dvořák',
    );
    expect(sharing?.textContent).toContain('Shared with Analysts and Carol Dvořák.');
  });

  it('tells a reader who may not use it whom to ask, and reads no readiness', async () => {
    await open(anAgent({ usableByMe: false, acceptedVersion: null }), 'ADMIN');
    expect(el.querySelector('[data-testid=not-usable]')?.textContent).toContain(
      'Shared with Analysts; ask its owner Bob Svoboda to share it with you.',
    );
    expect(el.querySelector('[data-testid=when-you-use-it]')).toBeNull();
    expect(el.querySelector('[data-testid=talk-to]')).toBeNull();
  });

  it('pauses the agent, stopping the turns that run when asked to', async () => {
    await open(anAgent({ editableByMe: true }));
    el.querySelector<HTMLButtonElement>('[data-testid=pause-agent]')!.click();
    await settle();
    body().querySelector<HTMLInputElement>('[data-testid=stop-running]')!.click();
    body().querySelector<HTMLButtonElement>('[data-testid=confirm-pause]')!.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: `${AGENT}/pause` });
    expect(req.request.body).toEqual({ stopRunning: true });
    req.flush(anAgent({ editableByMe: true, status: 'PAUSED', pausedAt: '2026-10-12T08:00:00Z' }));
    await settle();
    answerAround();
    await settle();
    expect(el.querySelector('[data-testid=paused-banner]')?.textContent).toContain(
      'Business analyst is paused; its turns wait.',
    );
    expect(el.querySelector('[data-testid=paused-banner]')?.className).not.toContain('human');

    el.querySelector<HTMLButtonElement>('[data-testid=resume-agent]')!.click();
    await settle();
    http
      .expectOne({ method: 'POST', url: `${AGENT}/resume` })
      .flush(anAgent({ editableByMe: true }));
    await settle();
    answerAround();
    await settle();
    expect(el.querySelector('[data-testid=paused-banner]')).toBeNull();
  });

  it('keeps the agent while a run of it is active, and says why', async () => {
    await open(anAgent({ editableByMe: true }));
    el.querySelector<HTMLButtonElement>('[data-testid=delete-agent]')!.click();
    await settle();
    body().querySelector<HTMLButtonElement>('[data-testid=confirm-delete]')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: AGENT })
      .flush(problem(409, 'AGENT_HAS_ACTIVE_RUNS'), { status: 409, statusText: 'Conflict' });
    await settle();
    expect(body().querySelector('[data-testid=delete-error]')?.textContent).toContain(
      'A run of this agent is queued or running.',
    );
  });

  it('shows the not-found page and nothing more for an agent the reader does not see', async () => {
    await open(null);
    expect(el.querySelector('app-not-found')).not.toBeNull();
    expect(el.querySelector('[data-testid=agent-header]')).toBeNull();
  });

  describe('its skills (ADR-0064)', () => {
    const skills = [
      anAgentSkill(),
      anAgentSkill({
        skillId: 'sk-ask-the-wiki',
        name: 'ask-the-wiki',
        description: 'Finds the answer in the wiki.',
        version: 1,
        latestVersion: 1,
        deleted: true,
      }),
    ];
    const row = (id: string) =>
      el.querySelector<HTMLElement>(`[data-testid=agent-skills] [data-skill="${id}"]`)!;
    const words = (node: Element | null | undefined) =>
      node?.textContent?.replace(/\s+/g, ' ').trim() ?? null;

    it('lets whoever sees the agent download each skill at the version it pins', async () => {
      await open(anAgent({ skills }));
      const jira = row(IDS.skill);
      expect(words(jira.querySelector('[data-testid=skill-name]'))).toBe('write-jira-ticket');
      // A reader who does not edit the agent is not sent to the library, nor told of updates.
      expect(jira.querySelector('a[data-testid=skill-name]')).toBeNull();
      expect(words(jira.querySelector('[data-testid=skill-pinned]'))).toBe('v2');
      expect(jira.querySelector('[data-testid=skill-update]')).toBeNull();
      expect(jira.textContent).toContain('Writes a Jira ticket from a brief');
      const download = jira.querySelector<HTMLAnchorElement>('[data-testid=skill-download]')!;
      expect(download.getAttribute('href')).toBe(
        `/api/companies/c1/agents/${IDS.agent}/skills/${IDS.skill}/content`,
      );
      expect(download.getAttribute('download')).toBe('write-jira-ticket-v2.zip');
      expect(words(row('sk-ask-the-wiki').querySelector('[data-testid=skill-deleted]'))).toBe(
        'Deleted from the library',
      );
      expect(el.querySelector('[data-testid=edit-skills]')).toBeNull();
    });

    it('tells its editors of an update, and where to move the pin', async () => {
      await open(anAgent({ editableByMe: true, skills }));
      const update = row(IDS.skill).querySelector('[data-testid=skill-update]')!;
      expect(words(update)).toBe('Update to v3 available');
      expect(update.getAttribute('href')).toBe(`/c/c1/agents/${IDS.agent}/edit?tab=skills`);
      expect(row(IDS.skill).querySelector('a[data-testid=skill-name]')?.getAttribute('href')).toBe(
        `/c/c1/skills/${IDS.skill}`,
      );
      // A deleted skill has no update and no page in the library.
      expect(row('sk-ask-the-wiki').querySelector('[data-testid=skill-update]')).toBeNull();
      expect(row('sk-ask-the-wiki').querySelector('a[data-testid=skill-name]')).toBeNull();
      expect(el.querySelector('[data-testid=edit-skills]')?.getAttribute('href')).toBe(
        `/c/c1/agents/${IDS.agent}/edit?tab=skills`,
      );
    });

    it('says when it has none', async () => {
      await open(anAgent());
      expect(words(el.querySelector('[data-testid=no-skills]'))).toBe(
        'None: it works from its instructions alone.',
      );
    });
  });
});
