import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type { Agent, AgentReadiness, AgentSummary } from '../../../api/generated/models';
import {
  aConversation,
  aJiraSystem,
  aModelAccess,
  aMyAccount,
  anAgent,
  anAgentSummary,
  aReadiness,
  aSystem,
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
import {
  conversationTitle,
  NewConversation,
  readinessNotes,
  startProblem,
} from './new-conversation';
import { HttpErrorResponse } from '@angular/common/http';

const API = '/api/companies/c1';
const UUID = /^[0-9a-f-]{36}$/;

describe('NewConversation', () => {
  let fixture: ComponentFixture<NewConversation>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let navigate: ReturnType<typeof vi.spyOn>;

  async function open(
    options: {
      agents?: AgentSummary[];
      agent?: Agent;
      readiness?: AgentReadiness;
      query?: Record<string, string>;
    } = {},
  ): Promise<void> {
    TestBed.configureTestingModule({
      imports: [NewConversation],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany('MEMBER').provider,
        fakeNotify().provider,
      ],
    });
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(NewConversation);
    for (const [name, value] of Object.entries(options.query ?? { agentId: IDS.agent })) {
      fixture.componentRef.setInput(name, value);
    }
    el = fixture.nativeElement;
    await settle();
    const list = http.expectOne((r) => r.url === `${API}/agents`);
    expect(list.request.params.get('usable')).toBe('true');
    list.flush({ items: options.agents ?? [anAgentSummary()] });
    await settle();
    answer(options);
    await settle();
  }

  function answer(options: { agent?: Agent; readiness?: AgentReadiness } = {}): void {
    for (const req of http.match(`${API}/agents/${IDS.agent}`))
      req.flush(options.agent ?? anAgent());
    for (const req of http.match(`${API}/agents/${IDS.agent}/readiness`)) {
      req.flush(options.readiness ?? aReadiness());
    }
    for (const req of http.match(`${API}/me/accounts`)) {
      req.flush({
        items: [
          aMyAccount(),
          aMyAccount({
            system: { ...aMyAccount().system, id: IDS.jira, name: 'Jira', kind: 'JIRA_CLOUD' },
            oauthAvailable: false,
            account: null,
            binding: 'CONNECT',
          }),
        ],
      });
    }
    for (const req of http.match(`${API}/me/model-access`)) req.flush(aModelAccess());
    for (const req of http.match((r) => r.url === `${API}/systems`)) {
      req.flush({ items: [aSystem(), aJiraSystem()] });
    }
  }

  afterEach(() => {
    try {
      answer();
      http.verify();
    } finally {
      TestBed.resetTestingModule();
    }
  });

  const text = (selector: string) =>
    el.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim() ?? null;

  function type(selector: string, value: string): void {
    const input = el.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector)!;
    input.value = value;
    input.dispatchEvent(new Event('input'));
  }

  it('says that no agent is shared with the reader yet', async () => {
    await open({ agents: [], query: {} });
    expect(text('[data-testid=no-agents]')).toContain('No agent is shared with you yet');
    expect(el.querySelector('[data-testid=no-agents] a')?.getAttribute('href')).toBe(
      '/c/c1/agents/new',
    );
  });

  it('fills who acts and who pays from the agent’s readiness, before the first message', async () => {
    await open();
    const panel = el.querySelector('[data-testid=readiness] [data-testid=who-acts-who-pays]')!;
    expect(
      [...panel.querySelectorAll('[data-part=acts] li')].map((li) => li.textContent?.trim()),
    ).toEqual(['GitHub: your own account', 'Jira: connect when first needed']);
    expect(panel.querySelector('[data-part=pays]')?.textContent).toContain(
      'Your budget: $38.20 of $50.00 left',
    );
    // Jira would be connected first: optional, in orange.
    const connect = el.querySelector('[data-testid=connect-box]')!;
    expect(connect.textContent).toContain('Connect now, or when the agent first needs it');
    expect(
      connect.querySelector(`[data-system="${IDS.jira}"] [data-testid=connect-sign-in]`),
    ).toBeNull();
    // The workspace: every repository of the agent, without defaults in the settings.
    const repos = [...el.querySelectorAll<HTMLInputElement>('[data-repository] input')];
    expect(repos.map((r) => r.checked)).toEqual([true, true]);
    expect(el.querySelector<HTMLButtonElement>('[data-testid=start-conversation]')!.disabled).toBe(
      true,
    );
  });

  it('presets the subject from the query, with its fields open', async () => {
    await open({
      query: { agentId: IDS.agent, subjectSystemId: IDS.jira, subjectKey: 'SHOP-241' },
    });
    expect(el.querySelector<HTMLInputElement>('[data-testid=subject-key]')!.value).toBe('SHOP-241');
    expect(el.querySelector('[data-testid=subject-open]')).toBeNull();
  });

  it('opens what it is about from its chip, and clears it again', async () => {
    await open();
    expect(el.querySelector('[data-testid=subject]')).toBeNull();
    el.querySelector<HTMLButtonElement>('[data-testid=subject-open]')!.click();
    await settle();
    expect(el.querySelector('[data-testid=subject-key]')).not.toBeNull();

    el.querySelector<HTMLButtonElement>('[data-testid=subject-close]')!.click();
    await settle();
    expect(el.querySelector('[data-testid=subject]')).toBeNull();
    el.querySelector<HTMLButtonElement>('[data-testid=subject-open]')!.click();
    await settle();
    expect(el.querySelector<HTMLInputElement>('[data-testid=subject-key]')!.value).toBe('');
  });

  it('says the title as the first message is typed, and whether it starts now', async () => {
    await open();
    expect(text('[data-testid=readiness-state]')).toBe('Ready to start');
    expect(text('[data-testid=title-preview]')).toBe(
      'Markdown. Its first line becomes the conversation’s title.',
    );
    type('[data-testid=first-message]', '\n## Prepare SHOP-241\n\nWith its acceptance criteria.');
    await settle();
    expect(text('[data-testid=title-preview]')).toBe('Title: Prepare SHOP-241');
  });

  it('starts with Ctrl+Enter in the message', async () => {
    await open();
    type('[data-testid=first-message]', 'Prepare SHOP-241, please.');
    await settle();
    el.querySelector('[data-testid=first-message]')!.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Enter', ctrlKey: true, cancelable: true }),
    );
    await settle();
    const req = http.expectOne(`${API}/conversations`);
    expect(req.request.body.message).toBe('Prepare SHOP-241, please.');
    req.flush(aConversation(), { status: 201, statusText: 'Created' });
    await settle();
  });

  it('starts the conversation with a key, and opens it', async () => {
    await open({
      readiness: aReadiness({
        settings: { ...aReadiness().settings, defaultRepositoryIds: [IDS.docsRepo] },
      }),
    });
    expect(
      [...el.querySelectorAll<HTMLInputElement>('[data-repository] input')].map((r) => r.checked),
    ).toEqual([false, true]);
    type('[data-testid=first-message]', 'Prepare SHOP-241, please.');
    await settle();
    const start = el.querySelector<HTMLButtonElement>('[data-testid=start-conversation]')!;
    expect(start.disabled).toBe(false);
    start.click();
    await settle();
    const req = http.expectOne(`${API}/conversations`);
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Idempotency-Key')).toMatch(UUID);
    expect(req.request.body).toEqual({
      agentId: IDS.agent,
      message: 'Prepare SHOP-241, please.',
      repositoryIds: [IDS.docsRepo],
    });
    req.flush(aConversation(), { status: 201, statusText: 'Created' });
    await settle();
    expect(navigate).toHaveBeenCalledWith(['/c', 'c1', 'conversations', IDS.conversation]);
  });

  it('starts without a repository when none is ticked', async () => {
    await open();
    for (const box of el.querySelectorAll<HTMLInputElement>('[data-repository] input')) {
      box.checked = false;
      box.dispatchEvent(new Event('change'));
    }
    type('[data-testid=first-message]', 'Explain the checkout.');
    await settle();
    expect(text('[data-testid=no-repository]')).toBe(
      'No repository: the agent asks you if it needs one.',
    );
    const start = el.querySelector<HTMLButtonElement>('[data-testid=start-conversation]')!;
    expect(start.disabled).toBe(false);
    start.click();
    await settle();
    const req = http.expectOne(`${API}/conversations`);
    expect(req.request.body).toEqual({
      agentId: IDS.agent,
      message: 'Explain the checkout.',
      repositoryIds: [],
    });
    req.flush(aConversation(), { status: 201, statusText: 'Created' });
    await settle();
  });

  it('offers no repository of a system the settings switch off, and starts without one', async () => {
    await open({
      readiness: aReadiness({
        settings: { ...aReadiness().settings, systemsOff: [IDS.github] },
      }),
    });
    expect(el.querySelector('[data-testid=repositories]')).toBeNull();
    type('[data-testid=first-message]', 'Prepare SHOP-241, please.');
    await settle();
    const start = el.querySelector<HTMLButtonElement>('[data-testid=start-conversation]')!;
    expect(start.disabled).toBe(false);
    start.click();
    await settle();
    const req = http.expectOne(`${API}/conversations`);
    expect(req.request.body).toEqual({ agentId: IDS.agent, message: 'Prepare SHOP-241, please.' });
    req.flush(aConversation(), { status: 201, statusText: 'Created' });
    await settle();
  });

  it('offers the repositories as the accepted version has them', async () => {
    await open({
      agent: anAgent({
        version: 6,
        acceptedVersion: 4,
        wideningPending: true,
        widening: aWidening({
          repositories: [
            { repositoryId: IDS.shopRepo, access: 'READ', was: null },
            { repositoryId: IDS.docsRepo, access: 'WRITE', was: 'READ' },
          ],
        }),
      }),
    });
    const chips = [...el.querySelectorAll('[data-repository]')];
    expect(chips.map((c) => c.getAttribute('data-repository'))).toEqual([IDS.docsRepo]);
    expect(chips[0].textContent).toContain('read-only');
    type('[data-testid=first-message]', 'Prepare SHOP-241, please.');
    await settle();
    el.querySelector<HTMLButtonElement>('[data-testid=start-conversation]')!.click();
    await settle();
    const req = http.expectOne(`${API}/conversations`);
    expect(req.request.body.repositoryIds).toEqual([IDS.docsRepo]);
    req.flush(aConversation(), { status: 201, statusText: 'Created' });
    await settle();
  });

  it('sends the same key again after a network error, and says why a start is refused', async () => {
    await open();
    type('[data-testid=first-message]', 'Prepare SHOP-241, please.');
    await settle();
    const start = el.querySelector<HTMLButtonElement>('[data-testid=start-conversation]')!;
    start.click();
    await settle();
    const first = http.expectOne(`${API}/conversations`);
    first.error(new ProgressEvent('error'), { status: 0 });
    await settle();
    expect(text('[data-testid=start-error]')).toContain(
      'Start again: the conversation is started once.',
    );

    start.click();
    await settle();
    const again = http.expectOne(`${API}/conversations`);
    expect(again.request.headers.get('Idempotency-Key')).toBe(
      first.request.headers.get('Idempotency-Key'),
    );
    again.flush(
      { ...problem(409, 'NO_HOST_FOR_CONVERSATION'), details: { reason: 'NO_RUNNER_HOST' } },
      { status: 409, statusText: 'Conflict' },
    );
    await settle();
    expect(text('[data-testid=start-error]')).toBe(
      'No runner host of the company runs this agent’s runtime.',
    );
  });

  it('turns Start off while nobody can pay', async () => {
    await open({
      readiness: aReadiness({
        payer: {
          state: 'REFUSED',
          modelAccess: 'BUDGET',
          models: [],
          reason: 'GATEWAY_NOT_CONFIGURED',
          until: null,
        },
      }),
    });
    type('[data-testid=first-message]', 'Prepare SHOP-241, please.');
    await settle();
    expect(text('[data-testid=readiness-refused]')).toContain(
      'An admin must set up model access first',
    );
    expect(text('[data-testid=readiness-state]')).toBe('Can’t start yet');
    expect(el.querySelector<HTMLButtonElement>('[data-testid=start-conversation]')!.disabled).toBe(
      true,
    );
  });

  it('shows the widening card of a new version to accept', async () => {
    await open({
      agent: anAgent({
        version: 6,
        acceptedVersion: 4,
        wideningPending: true,
        widening: aWidening(),
      }),
    });
    expect(el.querySelector('[data-testid=widening-card]')).not.toBeNull();
  });

  it('connects an account now with a token, and reads the readiness again', async () => {
    await open();
    const row = el.querySelector(`[data-testid=connect-now][data-system="${IDS.jira}"]`)!;
    row.querySelector<HTMLButtonElement>('[data-testid=connect-paste-token]')!.click();
    await settle();
    const input = row.querySelector<HTMLInputElement>('[data-testid=connect-token]')!;
    input.value = 'ann@acme.test:token';
    input.dispatchEvent(new Event('input'));
    await settle();
    row.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    const store = http.expectOne(`${API}/me/accounts/${IDS.jira}/token`);
    expect(store.request.body).toEqual({ token: 'ann@acme.test:token' });
    store.flush({});
    await settle();
    // The agent, its readiness and the accounts are read again.
    expect(http.match(`${API}/agents/${IDS.agent}/readiness`)).toHaveLength(1);
  });
});

describe('readinessNotes', () => {
  it('says that the first turn waits for a spent budget', () => {
    const notes = readinessNotes(
      aReadiness({
        payer: {
          state: 'WAITING',
          modelAccess: 'BUDGET',
          models: [],
          reason: 'BUDGET_SPENT',
          until: '2026-11-01T00:00:00Z',
        },
      }),
    );
    expect(notes).toHaveLength(1);
    expect(notes[0].text).toMatch(
      /^You can start; the first turn waits until your budget resets on /,
    );
  });

  it('refuses a budget whose settings name no model, and says where to choose one', () => {
    const notes = readinessNotes(
      aReadiness({
        payer: { state: 'REFUSED', modelAccess: 'BUDGET', models: [], reason: 'MODEL_REQUIRED' },
      }),
    );
    expect(notes).toEqual([
      {
        key: 'payer',
        tone: 'refused',
        text: 'Choose a model in your settings of the agent: your budget pays only for a model the company’s gateway serves.',
      },
    ]);
  });

  it('says that no runner host can take it now', () => {
    const notes = readinessNotes(
      aReadiness({ hosts: { runnable: false, eligible: 0, reason: 'No runner host is online.' } }),
    );
    expect(notes.map((n) => n.text)).toEqual([
      'No runner host can take it now: No runner host is online. You can start; the first turn waits.',
    ]);
  });
});

describe('conversationTitle', () => {
  it.each([
    ['', null],
    ['  \n\n', null],
    ['Prepare SHOP-241\nWith its tests.', 'Prepare SHOP-241'],
    ['\n  # Prepare SHOP-241  \n', 'Prepare SHOP-241'],
    ['###\nmore', 'Conversation'],
  ])('makes %j the title %j, as the server does', (message, title) => {
    expect(conversationTitle(message)).toBe(title);
  });
});

describe('startProblem', () => {
  it.each([
    [
      'MODEL_ACCESS_UNAVAILABLE',
      'GATEWAY_NOT_CONFIGURED',
      'An admin must set up model access first',
    ],
    ['MODEL_ACCESS_UNAVAILABLE', 'MODEL_REQUIRED', 'Choose a model in your settings of the agent'],
    ['NO_HOST_FOR_CONVERSATION', 'NO_HOST_FOR_CREDENTIALS', 'No runner host may carry'],
  ])('explains %s with its reason', (code, reason, words) => {
    const error = new HttpErrorResponse({
      status: 409,
      error: { status: 409, code, details: { reason } },
    });
    expect(startProblem(error)).toContain(words);
  });
});
