import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type { MockInstance } from 'vitest';
import type { Agent, ModelSubscription } from '../../../api/generated/models';
import { EDITOR_MODE_STORAGE_KEY } from '../../../shared/markdown-editor';
import {
  aGroupSummary,
  aJiraSystem,
  aMember,
  aModelAccess,
  anAgent,
  anAgentSkill,
  aSkillSummary,
  aSubscription,
  aSystem,
  aSystemRepository,
  aSystemTool,
  IDS,
} from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  typeInto,
} from '../../../testing/test-helpers';
import { AgentEditor } from './agent-editor';

const API = '/api/companies/c1';

const CONFLUENCE = aSystem({
  id: 'sys-confluence',
  name: 'Confluence',
  handle: 'confluence',
  kind: 'MCP',
  oauthAvailable: false,
  app: null,
});

const TOOLS: Record<string, ReturnType<typeof aSystemTool>[]> = {
  [IDS.github]: [
    aSystemTool({ name: 'get_file_contents', title: 'Read a file', effect: 'READ' }),
    aSystemTool({ name: 'create_pull_request', title: 'Open a pull request', effect: 'WRITE' }),
  ],
  [IDS.jira]: [
    aSystemTool({ name: 'add_comment', title: 'Comment on an issue', effect: 'WRITE' }),
    aSystemTool({ name: 'transition', title: 'Move an issue', effect: 'WRITE' }),
  ],
  [CONFLUENCE.id]: [
    aSystemTool({ name: 'read_page', title: 'Read a page', effect: 'READ', fixed: false }),
    aSystemTool({ name: 'write_page', title: 'Write a page', effect: 'UNKNOWN', fixed: false }),
  ],
};

/** Alice; Bob, who owns the agent; Carol, in no group; and a member without a principal. */
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
  aMember({
    userId: 'u-instance',
    principalId: null,
    displayName: 'Ivan',
    email: 'ivan@acme.test',
  }),
];

const REPOSITORIES = [
  aSystemRepository(),
  aSystemRepository({ id: IDS.docsRepo, name: 'acme/docs', url: 'https://github.com/acme/docs' }),
  aSystemRepository({ id: 'repo-web', name: 'acme/web', url: 'https://github.com/acme/web' }),
];

describe('AgentEditor', () => {
  let fixture: ComponentFixture<AgentEditor>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let navigate: MockInstance<Router['navigate']>;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  /**
   * Opens the editor of `agent`, or a new one, and answers what it reads. A new one also reads
   * the gateway's models (null: no gateway, 409) and the reader's subscriptions.
   */
  async function open(
    agent: Agent | null,
    tab?: string,
    systems = [aSystem(), aJiraSystem(), CONFLUENCE],
    access: { models: string[] | null; subscriptions: ModelSubscription[] } = {
      models: null,
      subscriptions: [],
    },
  ): Promise<void> {
    localStorage.setItem(EDITOR_MODE_STORAGE_KEY, 'markdown');
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [AgentEditor],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany('MEMBER').provider,
        notifier.provider,
      ],
    });
    navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AgentEditor);
    if (agent) fixture.componentRef.setInput('agentId', agent.id);
    if (tab) fixture.componentRef.setInput('tab', tab);
    el = fixture.nativeElement;
    await settle();
    if (agent) {
      http.expectOne(`${API}/agents/${agent.id}`).flush(agent);
    } else {
      const models = http.expectOne(`${API}/model-settings/models`);
      if (access.models) models.flush({ models: access.models });
      else models.flush(problem(409, 'GATEWAY_NOT_CONFIGURED'), { status: 409, statusText: 'No' });
      http
        .expectOne(`${API}/me/model-access`)
        .flush(aModelAccess({ subscriptions: access.subscriptions }));
    }
    http.expectOne((r) => r.url === `${API}/systems`).flush({ items: systems });
    http
      .expectOne((r) => r.url === `${API}/groups`)
      .flush({
        items: [
          aGroupSummary(),
          aGroupSummary({ id: 'g-everyone', name: 'Everyone', memberCount: 6 }),
        ],
      });
    http.expectOne((r) => r.url === `${API}/members`).flush({ items: MEMBERS });
    await settle();
    answerCatalogues();
    await settle();
  }

  /** The tools and repositories the editor reads for each system on the draft. */
  function answerCatalogues(): void {
    for (const req of http.match((r) => /\/systems\/[^/]+\/tools$/.test(r.url))) {
      const systemId = req.request.url.split('/')[5];
      req.flush({ items: TOOLS[systemId] ?? [] });
    }
    for (const req of http.match((r) => /\/systems\/[^/]+\/repositories$/.test(r.url))) {
      req.flush({ items: REPOSITORIES });
    }
  }

  afterEach(() => {
    http.verify();
    localStorage.removeItem(EDITOR_MODE_STORAGE_KEY);
  });

  const editable = () => anAgent({ editableByMe: true });
  const notice = () => el.querySelector<HTMLElement>('[data-testid=widening-notice]');
  const click = (selector: string) => el.querySelector<HTMLElement>(selector)!.click();
  const text = (e: Element | null | undefined) => e?.textContent?.replace(/\s+/g, ' ').trim();
  const tab = (id: string) => el.querySelector<HTMLElement>(`#section-tab-${id}`)!;
  /** What a section's tab says the section holds. */
  const summary = (id: string) =>
    [...tab(id).querySelectorAll('[data-summary]')].map((e) => text(e)).join(' | ');
  /** The section the page shows: the one panel not hidden. */
  const openSection = () =>
    el
      .querySelector('[role=tabpanel][id^="section-panel-"]:not([hidden])')
      ?.id.replace('section-panel-', '');
  /** In "What it may use": the systems whose tools and repositories show. */
  const openSystems = () =>
    [...el.querySelectorAll('[id^="system-body-"]:not([hidden])')].map((e) =>
      e.id.replace('system-body-', ''),
    );
  /** The company's systems to add, when the picker is open. */
  const picker = () => el.querySelector<HTMLElement>('[data-testid=system-picker]');
  /** What a screen reader calls each one: its text, with the hidden initials and badge left out. */
  const pickerNames = () =>
    [...(picker()?.querySelectorAll('[data-add-system]') ?? [])].map((b) => {
      const copy = b.cloneNode(true) as HTMLElement;
      copy.querySelectorAll('[aria-hidden=true]').forEach((hidden) => hidden.remove());
      const walker = document.createTreeWalker(copy, NodeFilter.SHOW_TEXT);
      const parts: string[] = [];
      while (walker.nextNode()) parts.push(walker.currentNode.textContent ?? '');
      return parts.join(' ').replace(/\s+/g, ' ').trim();
    });
  const key = (target: HTMLElement, name: string) =>
    target.dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true }));

  /**
   * Each widening case of the plan (§4.3, §8), made through the page as a person would: the notice
   * names it before the Save, with the version people keep.
   */
  const WIDENING: [string, () => Promise<void>, string][] = [
    [
      'a new system',
      async () => {
        click('#system-search');
        await settle();
        click(`[data-add-system="${CONFLUENCE.id}"]`);
        await settle();
        answerCatalogues();
      },
      'Confluence (new), with 2 tools: 1 allowed, 1 needs your approval',
    ],
    [
      'a new tool',
      async () => click(`[data-system="${IDS.jira}"] [data-add-tool=transition]`),
      'Jira · Move an issue: Needs your approval (new)',
    ],
    [
      'a tool from "Needs approval" to "Allowed"',
      async () => click(`#mode-${IDS.github}-create_pull_request-ALLOW`),
      'GitHub · Open a pull request: Allowed, where it needed your approval',
    ],
    [
      'a new repository',
      async () => click('[data-repository=repo-web] input[type=checkbox]'),
      'acme/web: read-only (new)',
    ],
    [
      'a repository from read-only to read and write',
      async () => click(`#access-${IDS.shopRepo}-WRITE`),
      'acme/shop: read and write, was read-only',
    ],
    [
      'a new network host',
      async () => {
        typeInto(el.querySelector('#new-host'), 'PyPI.org');
        await settle();
        click('[data-testid=add-host]');
      },
      'pypi.org',
    ],
    [
      'the instructions, where it can act (ADR-0103)',
      async () => typeInto(el.querySelector('#agent-instructions'), 'Answer in German.'),
      'Its instructions, changed',
    ],
  ];

  it.each(WIDENING)('names %s before the Save', async (_, act, text) => {
    await open(editable());
    expect(notice()).toBeNull();
    await act();
    await settle();
    expect(notice()?.querySelector('[data-testid=widening-sentence]')?.textContent).toContain(
      'People who use Business analyst keep version 4 until they accept this change; what you remove applies at their next turn.',
    );
    expect([...notice()!.querySelectorAll('li')].map((li) => li.textContent?.trim())).toEqual([
      text,
    ]);
    expect(el.querySelector('[data-testid=save-status]')?.textContent).toContain('widens it');
  });

  const NOT_WIDENING: [string, () => Promise<void>, string][] = [
    [
      'the name and description',
      async () => {
        typeInto(el.querySelector('#agent-name'), 'Analyst');
        typeInto(el.querySelector('#agent-description'), 'Writes briefs.');
      },
      'Who it is and instructions',
    ],
    ['the sharing', async () => click('[data-group=g-everyone] input'), 'Sharing'],
    [
      'a tool from "Allowed" to "Needs approval"',
      async () => click(`#mode-${IDS.github}-get_file_contents-ASK`),
      'Systems and tools',
    ],
    [
      'a removed tool',
      async () => click(`[data-system="${IDS.github}"] [aria-label="Remove Read a file"]`),
      'Systems and tools',
    ],
    [
      'a removed host',
      async () => click('[data-host="registry.npmjs.org"] button'),
      'Network hosts',
    ],
  ];

  it.each(NOT_WIDENING)('says nothing of widening for %s', async (_, act, section) => {
    await open(editable());
    await act();
    await settle();
    expect(notice()).toBeNull();
    expect(el.querySelector('[data-testid=save-status]')?.textContent).toContain(
      `Changed: ${section}`,
    );
  });

  it('says nothing of widening for the instructions of an agent that cannot act', async () => {
    await open(anAgent({ editableByMe: true, systems: [], repositories: [], hosts: [] }));
    typeInto(el.querySelector('#agent-instructions'), 'Answer in German.');
    await settle();
    expect(notice()).toBeNull();
    expect(el.querySelector('[data-testid=save-status]')?.textContent).toContain(
      'Changed: Who it is and instructions',
    );
  });

  it('sends only the changed sections, in order, and opens the agent', async () => {
    await open(editable());
    typeInto(el.querySelector('#agent-instructions'), 'Answer in German.');
    click('[data-repository=repo-web] input[type=checkbox]');
    click('[data-group=g-everyone] input');
    await settle();
    click('[data-testid=save-agent]');
    await settle();

    const about = http.expectOne({ method: 'PATCH', url: `${API}/agents/${IDS.agent}` });
    expect(about.request.body).toEqual({ instructions: 'Answer in German.' });
    about.flush(anAgent({ editableByMe: true, version: 5, instructions: 'Answer in German.' }));
    await settle();
    const repos = http.expectOne({ method: 'PUT', url: `${API}/agents/${IDS.agent}/repositories` });
    expect(
      repos.request.body.repositories.map((r: { repositoryId: string }) => r.repositoryId),
    ).toEqual([IDS.shopRepo, IDS.docsRepo, 'repo-web']);
    repos.flush(anAgent({ editableByMe: true, version: 6 }));
    await settle();
    const shares = http.expectOne({ method: 'PUT', url: `${API}/agents/${IDS.agent}/shares` });
    expect(shares.request.body).toEqual({
      groupIds: [IDS.analysts, 'g-everyone'],
      principalIds: [],
    });
    shares.flush(anAgent({ editableByMe: true, version: 6 }));
    await settle();

    expect(notify.success).toHaveBeenCalledWith('Business analyst saved', 'Version 6.');
    expect(navigate).toHaveBeenCalledWith(['/c', 'c1', 'agents', IDS.agent]);
  });

  it('shares it with a person in no group, listing neither its owner nor a non-member', async () => {
    await open(editable());
    const people = () =>
      [...el.querySelectorAll<HTMLElement>('[data-testid=sharing-people] [data-person]')].map(
        (li) => li.dataset['person'],
      );
    expect(people()).toEqual([IDS.alice, IDS.carol]);
    typeInto(el.querySelector('#sharing-people-filter'), 'CAROL@');
    await settle();
    expect(people()).toEqual([IDS.carol]);
    click(`[data-person="${IDS.carol}"] input`);
    await settle();
    expect(el.querySelector('[data-testid=save-status]')?.textContent).toContain(
      'Changed: Sharing',
    );
    expect(notice()).toBeNull();
    click('[data-testid=save-agent]');
    await settle();

    const shares = http.expectOne({ method: 'PUT', url: `${API}/agents/${IDS.agent}/shares` });
    expect(shares.request.body).toEqual({ groupIds: [IDS.analysts], principalIds: [IDS.carol] });
    shares.flush(
      anAgent({
        editableByMe: true,
        memberShares: [{ principalId: IDS.carol, userId: IDS.carolUser, name: 'Carol Dvořák' }],
      }),
    );
    await settle();
    expect(navigate).toHaveBeenCalledWith(['/c', 'c1', 'agents', IDS.agent]);
  });

  it('puts a refused person on the sharing', async () => {
    await open(editable());
    click(`[data-person="${IDS.carol}"] input`);
    await settle();
    click('[data-testid=save-agent]');
    await settle();
    http
      .expectOne({ method: 'PUT', url: `${API}/agents/${IDS.agent}/shares` })
      .flush(
        problem(400, 'VALIDATION_FAILED', 'The principal p-carol is not a member of this company'),
        {
          status: 400,
          statusText: 'Refused',
        },
      );
    await settle();
    expect(el.querySelector('[data-error=shares]')?.textContent).toContain('not a member');
  });

  it('sends chosenForEffect only for a mode the owner picked', async () => {
    await open(editable());
    click(`#mode-${IDS.github}-create_pull_request-ALLOW`);
    await settle();
    click('[data-testid=save-agent]');
    await settle();
    const req = http.expectOne({ method: 'PUT', url: `${API}/agents/${IDS.agent}/systems` });
    expect(req.request.body).toEqual({
      systems: [
        {
          systemId: IDS.github,
          tools: [
            { name: 'get_file_contents', mode: 'ALLOW' },
            { name: 'create_pull_request', mode: 'ALLOW', chosenForEffect: 'WRITE' },
          ],
        },
        { systemId: IDS.jira, tools: [{ name: 'add_comment', mode: 'ALLOW' }] },
      ],
    });
    req.flush(anAgent({ editableByMe: true, version: 5 }));
    await settle();
  });

  it('confirms "Allowed" for a tool that now writes in one save, naming the widening', async () => {
    const agent = anAgent({ editableByMe: true });
    agent.systems[0].tools[0] = {
      ...agent.systems[0].tools[0],
      effect: 'WRITE',
      chosenForEffect: 'READ',
    };
    await open(agent);
    const row = el.querySelector<HTMLElement>(
      `[data-system="${IDS.github}"] [data-tool=get_file_contents]`,
    )!;
    expect(row.querySelector('[data-testid=asks-since-it-writes]')?.textContent).toContain(
      'one save does it',
    );
    expect(row.querySelector<HTMLInputElement>('input[value=ASK]')?.checked).toBe(true);
    expect(notice()).toBeNull();

    click(`#mode-${IDS.github}-get_file_contents-ALLOW`);
    await settle();
    expect(row.querySelector('[data-testid=asks-since-it-writes]')).toBeNull();
    expect([...notice()!.querySelectorAll('li')].map((li) => li.textContent?.trim())).toEqual([
      'GitHub · Read a file: Allowed, where it needed your approval',
    ]);
    click('[data-testid=save-agent]');
    await settle();
    const req = http.expectOne({ method: 'PUT', url: `${API}/agents/${IDS.agent}/systems` });
    expect(req.request.body.systems[0].tools[0]).toEqual({
      name: 'get_file_contents',
      mode: 'ALLOW',
      chosenForEffect: 'WRITE',
    });
    req.flush(anAgent({ editableByMe: true, version: 5 }));
    await settle();
  });

  it('puts a refused section’s problem on its field, and says what was saved', async () => {
    await open(editable());
    typeInto(el.querySelector('#agent-name'), 'Analyst');
    click(`#mode-${IDS.github}-create_pull_request-ALLOW`);
    await settle();
    click('[data-testid=save-agent]');
    await settle();
    http
      .expectOne({ method: 'PATCH', url: `${API}/agents/${IDS.agent}` })
      .flush(anAgent({ editableByMe: true, name: 'Analyst', version: 5 }));
    await settle();
    http
      .expectOne({ method: 'PUT', url: `${API}/agents/${IDS.agent}/systems` })
      .flush(problem(400, 'UNKNOWN_TOOL', 'The system GitHub does not serve the tool x'), {
        status: 400,
        statusText: 'Bad Request',
      });
    await settle();
    expect(el.querySelector('[data-error=systems]')?.textContent).toContain(
      'This tool is not offered here. The system GitHub does not serve the tool x.',
    );
    expect(el.querySelector('[data-testid=editor-error]')?.textContent).toContain(
      'Saved: Who it is and instructions. Not saved yet: Systems and tools',
    );
    // The name is saved now; only the tool remains to save.
    expect(el.querySelector('[data-testid=save-status]')?.textContent).toContain(
      'Changed: Systems and tools',
    );
    expect(navigate).not.toHaveBeenCalled();
  });

  it('says a refused tool where it stands, by the names in details', async () => {
    await open(editable());
    click(`#mode-${IDS.github}-create_pull_request-ALLOW`);
    await settle();
    click('[data-testid=save-agent]');
    await settle();
    http.expectOne({ method: 'PUT', url: `${API}/agents/${IDS.agent}/systems` }).flush(
      {
        ...problem(400, 'UNKNOWN_TOOL', 'The system sys-github does not serve the tool x'),
        details: { systemId: IDS.github, tool: 'create_pull_request' },
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect(openSection()).toBe('reach');
    expect(openSystems()).toContain(IDS.github);
    const card = el.querySelector(`[data-system="${IDS.github}"]`)!;
    expect(card.querySelector('[data-error-at]')?.textContent?.trim()).toBe(
      'GitHub no longer offers “Open a pull request”, or it is switched off: remove it, then save again.',
    );
    expect(card.querySelector('[data-error-tool]')?.getAttribute('data-tool')).toBe(
      'create_pull_request',
    );
    expect(el.querySelectorAll('[data-error=systems]')).toHaveLength(1);
    // Removing the tool clears what pointed at it.
    click(`[data-system="${IDS.github}"] [aria-label="Remove Open a pull request"]`);
    await settle();
    expect(el.querySelector('[data-error-at]')).toBeNull();
  });

  it('says a refused repository under its row', async () => {
    await open(editable());
    click('[data-repository=repo-web] input[type=checkbox]');
    await settle();
    click('[data-testid=save-agent]');
    await settle();
    http
      .expectOne({ method: 'PUT', url: `${API}/agents/${IDS.agent}/repositories` })
      .flush(
        { ...problem(400, 'SYSTEM_NOT_ON_AGENT'), details: { repositoryId: 'repo-web' } },
        { status: 400, statusText: 'Bad Request' },
      );
    await settle();
    expect(
      el.querySelector('[data-repository=repo-web] + [data-error-at]')?.textContent?.trim(),
    ).toBe(
      'acme/web is not on a code host the agent lists: add its system under What it may use first.',
    );
    expect(el.querySelectorAll('[data-error=repositories]')).toHaveLength(1);
    // The page opens "What it may use", on the repository's system.
    expect(openSection()).toBe('reach');
    expect(openSystems()).toContain(IDS.github);
  });

  it('creates a new agent in one call, as version 1', async () => {
    await open(null);
    expect(el.querySelector('h1')?.textContent?.trim()).toBe('New agent');
    expect(notice()).toBeNull();
    typeInto(el.querySelector('#agent-name'), 'Release notes');
    // Without systems, "What it may use" offers the company's.
    expect(picker()).not.toBeNull();
    expect(el.querySelector('[data-testid=no-systems]')).not.toBeNull();
    click(`[data-add-system="${IDS.jira}"]`);
    await settle();
    answerCatalogues();
    await settle();
    expect(openSystems()).toEqual([IDS.jira]);
    expect(el.querySelector(`[data-add-system="${IDS.jira}"]`)).toBeNull();
    // The picker stays open for the next one.
    expect(picker()).not.toBeNull();
    expect(el.querySelector('[data-testid=no-systems]')).toBeNull();
    click('[data-group=g-analysts] input');
    await settle();
    expect(notice()).toBeNull();
    click('[data-testid=save-agent]');
    await settle();

    const req = http.expectOne({ method: 'POST', url: `${API}/agents` });
    expect(req.request.body).toEqual({
      name: 'Release notes',
      handle: null,
      description: null,
      instructions: null,
      runtimes: ['CLAUDE_CODE'],
      memoryAllowed: true,
      systems: [
        {
          systemId: IDS.jira,
          tools: [
            { name: 'add_comment', mode: 'ASK' },
            { name: 'transition', mode: 'ASK' },
          ],
        },
      ],
      repositories: [],
      hosts: [],
      shareGroupIds: [IDS.analysts],
      sharePrincipalIds: [],
    });
    req.flush(anAgent({ id: 'a-new', name: 'Release notes', version: 1 }));
    await settle();
    expect(navigate).toHaveBeenCalledWith(['/c', 'c1', 'agents', 'a-new']);
  });

  it('keeps the runtimes in place as one is checked or made the default', async () => {
    await open(null);
    const order = () =>
      [...el.querySelectorAll<HTMLElement>('[data-runtime]')].map((li) => li.dataset['runtime']);
    const row = (runtime: string) => el.querySelector<HTMLElement>(`[data-runtime=${runtime}]`)!;
    const before = order();
    expect(before).toEqual(['CLAUDE_CODE', 'CODEX', 'PI', 'OPENCODE']);
    expect(row('CLAUDE_CODE').textContent).toContain('Default');

    click('[data-runtime=PI] input');
    await settle();
    expect(order()).toEqual(before);
    row('PI').querySelector('button')!.click();
    await settle();
    expect(order()).toEqual(before);
    expect(row('PI').textContent).toContain('Default');
    expect(row('CLAUDE_CODE').textContent).not.toContain('Default');
    expect(row('CLAUDE_CODE').querySelector('button')?.textContent).toContain('Make default');

    typeInto(el.querySelector('#agent-name'), 'Analyst');
    await settle();
    click('[data-testid=save-agent]');
    await settle();
    const req = http.expectOne({ method: 'POST', url: `${API}/agents` });
    expect(req.request.body.runtimes).toEqual(['PI', 'CLAUDE_CODE']);
    req.flush(anAgent({ id: 'a-new', name: 'Analyst', version: 1 }));
    await settle();
  });

  it.each([
    ['DUPLICATE_AGENT_NAME', 409, 'name', 'An agent with this name already exists.'],
    ['DUPLICATE_AGENT_HANDLE', 409, 'handle', 'Another agent already has this handle.'],
    ['PERSONAL_SYSTEM', 400, 'systems', 'A personal MCP server serves only its owner'],
    ['SYSTEM_NOT_ON_AGENT', 400, 'repositories', 'The agent does not use this system.'],
  ] as const)('puts %s on its field', async (code, status, field, text) => {
    await open(null);
    typeInto(el.querySelector('#agent-name'), 'Analyst');
    await settle();
    click('[data-testid=save-agent]');
    await settle();
    http
      .expectOne({ method: 'POST', url: `${API}/agents` })
      .flush(problem(status, code), { status, statusText: 'Refused' });
    await settle();
    expect(el.querySelector(`[data-error=${field}]`)?.textContent).toContain(text);
    expect(el.querySelector('[data-testid=editor-error]')).toBeNull();
  });

  it("starts a new agent on the runtime the company's models suit, until the person picks", async () => {
    await open(null, undefined, undefined, {
      models: ['gemini-2.5-pro', 'mistral-large'],
      subscriptions: [aSubscription()],
    });
    const row = (runtime: string) => el.querySelector<HTMLElement>(`[data-runtime=${runtime}]`)!;
    expect(row('PI').textContent).toContain('Default');
    expect(row('CLAUDE_CODE').textContent).toContain('Not used');
    expect(el.querySelector('[data-testid=runtime-reason]')?.textContent?.trim()).toBe(
      "pi is the default: your company's gateway serves other providers' models, which pi works with.",
    );

    click('[data-runtime=CLAUDE_CODE] input');
    await settle();
    expect(el.querySelector('[data-testid=runtime-reason]')).toBeNull();
    typeInto(el.querySelector('#agent-name'), 'Analyst');
    await settle();
    click('[data-testid=save-agent]');
    await settle();
    const req = http.expectOne({ method: 'POST', url: `${API}/agents` });
    expect(req.request.body.runtimes).toEqual(['PI', 'CLAUDE_CODE']);
    req.flush(anAgent({ id: 'a-new', name: 'Analyst', version: 1 }));
    await settle();
  });

  it('sets the time limit of a turn, checks it, and says the default', async () => {
    await open(editable());
    const input = el.querySelector<HTMLInputElement>('#agent-turn-time-limit')!;
    expect(input.value).toBe('');
    expect(input.placeholder).toBe('Default: 60');
    typeInto(input, '3');
    await settle();
    click('[data-testid=save-agent]');
    await settle();
    expect(el.querySelector('[data-error=turnTimeLimitMinutes]')?.textContent).toContain(
      'Enter 5 to 1440 minutes',
    );
    http.expectNone({ method: 'PATCH', url: `${API}/agents/${IDS.agent}` });

    typeInto(input, '240');
    await settle();
    click('[data-testid=save-agent]');
    await settle();
    const about = http.expectOne({ method: 'PATCH', url: `${API}/agents/${IDS.agent}` });
    expect(about.request.body).toEqual({ turnTimeLimitMinutes: 240 });
    about.flush(anAgent({ editableByMe: true, version: 5, turnTimeLimitMinutes: 240 }));
    await settle();
    expect(notify.success).toHaveBeenCalledWith('Business analyst saved', 'Version 5.');
  });

  it('checks the name and handle before it sends anything', async () => {
    await open(null);
    typeInto(el.querySelector('#agent-handle'), '-bad-');
    await settle();
    click('[data-testid=save-agent]');
    await settle();
    expect(el.querySelector('[data-error=name]')?.textContent).toContain('Enter a name.');
    expect(el.querySelector('[data-error=handle]')?.textContent).toContain('lower-case letters');
  });

  it('finds a system to add by its name or kind', async () => {
    await open(null);
    expect(text(el.querySelector('[data-testid=system-catalog]'))).toBe('3 systems in the company');
    // By name, as people know them.
    expect(pickerNames()).toEqual([
      'Add Confluence MCP server',
      'Add GitHub GitHub',
      'Add Jira Jira Cloud',
    ]);
    typeInto(el.querySelector('#system-search'), 'jira');
    await settle();
    expect(pickerNames()).toEqual(['Add Jira Jira Cloud']);
    typeInto(el.querySelector('#system-search'), 'MCP');
    await settle();
    expect(pickerNames()).toEqual(['Add Confluence MCP server']);
    typeInto(el.querySelector('#system-search'), 'nothing like it');
    await settle();
    expect(text(el.querySelector('[data-testid=no-system-matches]'))).toBe(
      'No system left to add matches “nothing like it”.',
    );
    // So few need no filter by kind.
    expect(picker()!.querySelector('[data-system-group]')).toBeNull();
  });

  it('filters a long list of systems by their kind, counting what matches', async () => {
    const mcp = ['Linear', 'Notion', 'Grafana', 'PagerDuty'].map((name) =>
      aSystem({ id: `sys-${name}`, name, handle: name.toLowerCase(), kind: 'MCP', app: null }),
    );
    await open(null, undefined, [aSystem(), aJiraSystem(), CONFLUENCE, ...mcp]);
    expect([...picker()!.querySelectorAll('[data-system-group]')].map((b) => text(b))).toEqual([
      'All 7',
      'Issue trackers 1',
      'Code hosts 1',
      'MCP servers 5',
    ]);
    click('[data-system-group=mcp]');
    await settle();
    expect(el.querySelector('[data-system-group=mcp]')?.getAttribute('aria-pressed')).toBe('true');
    expect(pickerNames()).toEqual([
      'Add Confluence MCP server',
      'Add Grafana MCP server',
      'Add Linear MCP server',
      'Add Notion MCP server',
      'Add PagerDuty MCP server',
    ]);
    // The counts follow what was typed.
    typeInto(el.querySelector('#system-search'), 'li');
    await settle();
    expect(pickerNames()).toEqual(['Add Linear MCP server']);
    expect(text(el.querySelector('[data-system-group=all]'))).toBe('All 1');
    click('[data-system-group=code]');
    await settle();
    expect(text(el.querySelector('[data-testid=no-system-matches]'))).toBe(
      'No system left to add matches “li”.',
    );
  });

  it('lists every kind again once an add leaves none of the picked kind, or hides the filters', async () => {
    const mcp = ['Linear', 'Notion', 'Grafana', 'PagerDuty', 'Sentry'].map((name) =>
      aSystem({ id: `sys-${name}`, name, handle: name.toLowerCase(), kind: 'MCP', app: null }),
    );
    await open(null, undefined, [aSystem(), aJiraSystem(), CONFLUENCE, ...mcp]);
    // Its tools, then a code host's repositories, which the editor reads once it is on the draft.
    const add = async (id: string) => {
      click(`[data-add-system="${id}"]`);
      await settle();
      answerCatalogues();
      await settle();
      answerCatalogues();
      await settle();
    };
    const pressed = () =>
      el.querySelector('[data-system-group][aria-pressed=true]')?.getAttribute('data-system-group');

    // The only code host goes on the agent: its kind is gone, and the list shows every kind.
    click('[data-system-group=code]');
    await settle();
    await add(IDS.github);
    expect(pressed()).toBe('all');
    expect(pickerNames()).toHaveLength(7);

    // An MCP server goes on it: six are left, too few to filter, and none is hidden.
    click('[data-system-group=mcp]');
    await settle();
    expect(pickerNames()).toHaveLength(6);
    await add('sys-Linear');
    expect(picker()!.querySelector('[data-system-group]')).toBeNull();
    expect(pickerNames()).toEqual([
      'Add Confluence MCP server',
      'Add Grafana MCP server',
      'Add Jira Jira Cloud',
      'Add Notion MCP server',
      'Add PagerDuty MCP server',
      'Add Sentry MCP server',
    ]);
  });

  it('folds the picker away with Done or Escape, and opens it from the search', async () => {
    await open(null);
    // On the Reach section, where the picker is: a section that is hidden takes no focus.
    click('#section-tab-reach');
    await settle();
    expect(el.querySelector('[data-testid=no-hosts]')).not.toBeNull();
    click('[data-testid=close-system-picker]');
    await settle();
    expect(picker()).toBeNull();
    expect(document.activeElement?.id).toBe('system-search');
    click('#system-search');
    await settle();
    expect(picker()).not.toBeNull();
    key(el.querySelector<HTMLElement>('#system-search')!, 'Escape');
    await settle();
    expect(picker()).toBeNull();
    // Typing opens it on what matches.
    typeInto(el.querySelector('#system-search'), 'git');
    await settle();
    expect(pickerNames()).toEqual(['Add GitHub GitHub']);
  });

  it('refuses a host the server would refuse, before adding it', async () => {
    await open(editable());
    typeInto(el.querySelector('#new-host'), 'https://pypi.org/simple');
    await settle();
    click('[data-testid=add-host]');
    await settle();
    expect(el.querySelector('[data-error=new-host]')?.textContent).toContain('without a scheme');
    expect(notice()).toBeNull();
  });

  it('shows one section at a time, each tab saying what its section holds', async () => {
    await open(editable());
    expect(openSection()).toBe('who');
    expect(tab('who').getAttribute('aria-selected')).toBe('true');
    // Each tab is named by its section, and described by what the section holds.
    expect(
      [...el.querySelectorAll<HTMLElement>('[role=tab][id^="section-tab-"]')].map((t) => [
        t.dataset['state'],
        text(el.querySelector(`#${t.id}-label`)),
        text(el.querySelector(`#${t.id}-details`)),
      ]),
    ).toEqual([
      ['done', 'Who it is', 'Claude Code, then Codex'],
      ['done', 'Instructions', 'Written'],
      ['todo', 'Skills', 'None yet'],
      [
        'done',
        'What it may use',
        '2 systems · 3 tools · 2 repositories · 1 host 2 allowed, 1 needs approval',
      ],
      ['done', 'Whom you share it with', 'Analysts'],
    ]);

    click('#section-tab-reach');
    await settle();
    expect(openSection()).toBe('reach');
    expect(tab('reach').getAttribute('aria-selected')).toBe('true');
    // Its first system shows its tools; each row opens and closes on its own.
    expect(openSystems()).toEqual([IDS.github]);
    const toggle = (id: string) => el.querySelector<HTMLElement>(`#system-toggle-${id}`)!;
    expect(toggle(IDS.github).getAttribute('aria-expanded')).toBe('true');
    expect(toggle(IDS.jira).getAttribute('aria-expanded')).toBe('false');
    toggle(IDS.jira).click();
    await settle();
    expect(openSystems()).toEqual([IDS.github, IDS.jira]);
    toggle(IDS.github).click();
    await settle();
    expect(openSystems()).toEqual([IDS.jira]);
    // Each says what it holds; a kind its name already says is left out.
    expect(text(el.querySelector(`#system-${IDS.github}-meta`))).toBe(
      '2 tools · 1 needs approval · 2 repositories',
    );
    expect(text(el.querySelector(`#system-${IDS.jira}-meta`))).toBe('Jira Cloud · 1 tool');
    // With systems on it, the picker waits for the search; the hosts have their own list.
    expect(picker()).toBeNull();
    expect(el.querySelector('[data-host="registry.npmjs.org"]')).not.toBeNull();

    // Next steps on; the last section's step is the Save itself.
    const steps = () => el.querySelector('[data-testid=section-steps]')!;
    expect(text(steps())).toBe('Skills Next: Whom you share it with');
    steps().querySelectorAll<HTMLElement>('button')[1].click();
    await settle();
    expect(openSection()).toBe('sharing');
    expect(steps().querySelector('button[type=submit]')?.textContent?.trim()).toBe('Save');
    steps().querySelector<HTMLElement>('button')!.click();
    await settle();
    expect(openSection()).toBe('reach');
  });

  it('moves along the tabs with the arrow keys, Home and End', async () => {
    await open(editable());
    key(tab('who'), 'ArrowDown');
    await settle();
    expect(openSection()).toBe('instructions');
    expect(document.activeElement).toBe(tab('instructions'));
    key(tab('instructions'), 'End');
    await settle();
    expect(openSection()).toBe('sharing');
    key(tab('sharing'), 'ArrowDown');
    await settle();
    expect(openSection()).toBe('who');
    key(tab('who'), 'ArrowUp');
    await settle();
    expect(openSection()).toBe('sharing');
    key(tab('sharing'), 'Home');
    await settle();
    expect(openSection()).toBe('who');
  });

  it('beside the tabs, keeps the focus on the tab it opens', async () => {
    await open(editable());
    tab('reach').focus();
    tab('reach').click();
    await settle();
    expect(openSection()).toBe('reach');
    expect(document.activeElement).toBe(tab('reach'));
  });

  it('on a phone, where the tabs hide, brings the heading of the section it opens', async () => {
    const original = window.matchMedia;
    // A narrow screen: the tabs and the open section are one screen each.
    window.matchMedia = vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }) as unknown as typeof window.matchMedia;
    try {
      await open(editable());
      tab('reach').focus();
      tab('reach').click();
      await settle();
      expect(openSection()).toBe('reach');
      expect(document.activeElement?.id).toBe('reach-title');
    } finally {
      window.matchMedia = original;
    }
  });

  it('says what a new agent still needs, and opens the section of a problem', async () => {
    await open(null);
    expect(tab('who').dataset['state']).toBe('todo');
    expect(summary('instructions')).toBe('None yet');
    expect(summary('reach')).toBe('Only its workspace');
    expect(tab('sharing').dataset['state']).toBe('private');
    expect(summary('sharing')).toBe('Private to you');
    expect(text(el.querySelector('[data-testid=save-status]'))).toBe(
      'Saved as version 1, private to you until you share it.',
    );

    click('#section-tab-sharing');
    await settle();
    click('[data-group=g-analysts] input');
    await settle();
    expect(summary('sharing')).toBe('Analysts');
    expect(text(el.querySelector('[data-testid=save-status]'))).toBe(
      'Saved as version 1, shared with Analysts.',
    );

    // Saved without a name: "Who it is" opens, says so, and its name takes the focus.
    click('[data-testid=save-agent]');
    await settle();
    expect(openSection()).toBe('who');
    expect(tab('who').dataset['state']).toBe('error');
    expect(text(el.querySelector('#section-tab-who-details'))).toBe('Has a problem: Claude Code');
    expect(document.activeElement?.id).toBe('agent-name');
  });

  it('says who may edit an agent the reader may not', async () => {
    await open(anAgent({ editableByMe: false }));
    expect(el.querySelector('[data-testid=not-editable]')?.textContent).toContain(
      'Only its owner and the company’s admins can change it.',
    );
    expect(el.querySelector('[data-testid=agent-editor]')).toBeNull();
  });

  describe('its skills (ADR-0064)', () => {
    const WIKI = 'sk-ask-the-wiki';
    /** Pins write-jira-ticket at 2 of 3, and keeps ask-the-wiki, deleted from the library. */
    const withSkills = () =>
      anAgent({
        editableByMe: true,
        skills: [
          anAgentSkill({
            skillId: WIKI,
            name: 'ask-the-wiki',
            description: 'Finds the answer in the wiki.',
            version: 1,
            latestVersion: 1,
            deleted: true,
          }),
          anAgentSkill(),
        ],
      });
    const LIBRARY = [
      aSkillSummary(),
      aSkillSummary({
        id: IDS.otherSkill,
        name: 'release-notes',
        description: 'Writes the release notes of a version.',
        latestVersion: 1,
      }),
    ];
    /** The library, which the editor reads once the Skills section opens. */
    const answerLibrary = () =>
      http.expectOne((r) => r.url === `${API}/skills`).flush({ items: LIBRARY });
    const skillRow = (id: string) => el.querySelector<HTMLElement>(`[data-skill="${id}"]`)!;

    it('reads the library only once the section opens, and says what it holds', async () => {
      await open(withSkills());
      http.expectNone((r) => r.url === `${API}/skills`);
      expect(summary('skills')).toBe('2 skills | 1 update in the library');
      click('#section-tab-skills');
      await settle();
      answerLibrary();
      await settle();
      expect(openSection()).toBe('skills');
      // What the agent has already is not offered again.
      expect(
        [...el.querySelectorAll<HTMLElement>('[data-add-skill]')].map((b) => b.dataset['addSkill']),
      ).toEqual([IDS.otherSkill]);
      // A deleted skill stays as it is pinned, and cannot move.
      expect(text(skillRow(WIKI).querySelector('[data-testid=skill-deleted]'))).toBe(
        'Deleted from the library',
      );
      expect(skillRow(WIKI).querySelector('select')).toBeNull();
      expect(
        [...skillRow(IDS.skill).querySelectorAll('option')].map((o) => o.textContent?.trim()),
      ).toEqual(['Version 3 (latest)', 'Version 2', 'Version 1']);
    });

    it('updates a pin, adds a skill and saves the whole list, a widening where it can act', async () => {
      await open(withSkills(), 'skills');
      answerLibrary();
      await settle();
      expect(openSection()).toBe('skills');

      skillRow(IDS.skill).querySelector<HTMLElement>('[data-testid=update-skill]')!.click();
      click(`[data-add-skill="${IDS.otherSkill}"]`);
      await settle();
      expect(text(skillRow(IDS.skill).querySelector('[data-testid=skill-moved]'))).toBe('Was v2');
      expect(text(skillRow(IDS.otherSkill).querySelector('[data-testid=skill-new]'))).toBe('New');
      expect(el.querySelector('[data-testid=save-status]')?.textContent).toContain(
        'Changed: Skills',
      );
      // The agent can act: a moved pin and a new skill wait for acceptance (ADR-0103).
      const items = [...notice()!.querySelectorAll('li')].map((li) => li.textContent?.trim());
      expect(items).toHaveLength(2);
      expect(items).toContain('Skill release-notes: version 1 (new)');
      expect(items.some((item) => item?.endsWith('version 3, was version 2'))).toBe(true);

      click('[data-testid=save-agent]');
      await settle();
      const put = http.expectOne({ method: 'PUT', url: `${API}/agents/${IDS.agent}/skills` });
      // By name; the pins that stay go without a version, and a deleted one never names one.
      expect(put.request.body).toEqual({
        skills: [
          { skillId: WIKI },
          { skillId: IDS.otherSkill, version: 1 },
          { skillId: IDS.skill, version: 3 },
        ],
      });
      put.flush(anAgent({ editableByMe: true, version: 5 }));
      await settle();
      expect(notify.success).toHaveBeenCalledWith('Business analyst saved', 'Version 5.');
      expect(navigate).toHaveBeenCalledWith(['/c', 'c1', 'agents', IDS.agent]);
    });

    it('moves a pin down, removes a skill, and puts a refusal on the section', async () => {
      await open(withSkills(), 'skills');
      answerLibrary();
      await settle();
      const select = skillRow(IDS.skill).querySelector<HTMLSelectElement>('select')!;
      select.value = '1';
      select.dispatchEvent(new Event('change'));
      skillRow(WIKI).querySelector<HTMLElement>('[data-testid=remove-skill]')!.click();
      await settle();
      expect(skillRow(WIKI)).toBeNull();

      click('[data-testid=save-agent]');
      await settle();
      const put = http.expectOne({ method: 'PUT', url: `${API}/agents/${IDS.agent}/skills` });
      expect(put.request.body).toEqual({ skills: [{ skillId: IDS.skill, version: 1 }] });
      put.flush(problem(400, 'SKILL_DELETED'), { status: 400, statusText: 'Bad Request' });
      await settle();
      expect(openSection()).toBe('skills');
      expect(el.querySelector('[data-error=skills]')?.textContent).toContain(
        'This skill was deleted from the library',
      );
      expect(tab('skills').dataset['state']).toBe('error');
    });

    it('gives a new agent its skills right after creating it', async () => {
      await open(null);
      typeInto(el.querySelector('#agent-name'), 'Release notes');
      click('#section-tab-skills');
      await settle();
      answerLibrary();
      await settle();
      click(`[data-add-skill="${IDS.skill}"]`);
      await settle();
      click('[data-testid=save-agent]');
      await settle();

      http
        .expectOne({ method: 'POST', url: `${API}/agents` })
        .flush(anAgent({ id: 'a-new', name: 'Release notes', version: 1 }));
      await settle();
      const put = http.expectOne({ method: 'PUT', url: `${API}/agents/a-new/skills` });
      expect(put.request.body).toEqual({ skills: [{ skillId: IDS.skill, version: 3 }] });
      put.flush(anAgent({ id: 'a-new', name: 'Release notes', version: 2 }));
      await settle();
      expect(navigate).toHaveBeenCalledWith(['/c', 'c1', 'agents', 'a-new']);
    });
  });
});
