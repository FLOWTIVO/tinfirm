import { Component, signal } from '@angular/core';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { Agent, AgentTool, AgentWidening, ModelSubscription } from '../api/generated/models';
import {
  anAgent,
  anAgentSettings,
  aPersonalMcpServer,
  aSubscription,
  aSystemTool,
  IDS,
} from '../testing/fixtures';
import { problem, settle } from '../testing/test-helpers';
import { HttpErrorResponse } from '@angular/common/http';
import { AgentSettingsForm, type SettingsSubject } from './agent-settings-form';
import {
  askOnEveryWrite,
  modelAllowed,
  modelChoices,
  type OwnServerOption,
  payerOptions,
  type SettingsDraft,
  type SettingsErrorAt,
  settingsDraftOf,
  settingsFailure,
  settingsChanges,
  settingsRequest,
  settingsSystemRows,
  subscriptionGone,
  systemSummary,
  toolOutcome,
  withToolChoice,
} from './agent-settings-model';

/** An agent with one tool on Jira, as each case of the table sets it. */
function agentWith(tool: Partial<AgentTool>): Agent {
  return anAgent({
    systems: [
      {
        systemId: IDS.jira,
        name: 'Jira',
        handle: 'jira',
        kind: 'JIRA_CLOUD',
        tools: [
          {
            name: 'comment',
            title: 'Comment on an issue',
            mode: 'ASK',
            effect: 'WRITE',
            chosenForEffect: 'WRITE',
            offered: true,
            ...tool,
          },
        ],
      },
    ],
  });
}

function widened(overrides: Partial<AgentWidening>): AgentWidening {
  return {
    acceptedVersion: 4,
    currentVersion: 5,
    systems: [],
    tools: [],
    repositories: [],
    hosts: [],
    ...overrides,
  };
}

/**
 * The table of plan §8: a tool the agent asks for, as the reader has it, is never offered
 * "Allowed" (I4), whichever way it came to ask.
 */
const ASKING: [string, Partial<AgentTool>, AgentWidening | null][] = [
  ['asks for every call', { mode: 'ASK', effect: 'WRITE' }, null],
  ['is unconfirmed, so counts as a write', { mode: 'ASK', effect: 'UNKNOWN' }, null],
  [
    'was allowed while it read, and now writes (I19)',
    { mode: 'ALLOW', effect: 'WRITE', chosenForEffect: 'READ' },
    null,
  ],
  [
    'runs in a version the reader has not accepted',
    { mode: 'ALLOW', effect: 'WRITE', chosenForEffect: 'WRITE' },
    widened({ tools: [{ systemId: IDS.jira, tool: 'comment', mode: 'ALLOW', was: 'ASK' }] }),
  ],
];

describe('settingsSystemRows: what the agent does with each tool for the reader', () => {
  it.each(ASKING)(
    'a tool that %s asks the reader, and offers no "Allowed"',
    (_, tool, widening) => {
      const [row] = settingsSystemRows(agentWith(tool), widening)[0].tools;
      expect(row.agentMode).toBe('ASK');
      expect(row.options.map((o) => o.label)).toEqual(['As the agent says', 'Ask me', 'Off']);
      expect(row.options.map((o) => o.value)).not.toContain('ALLOW');
      for (const choice of ['', 'ASK', 'OFF'] as const) {
        expect(toolOutcome(row, choice)).not.toBe('Allowed');
      }
    },
  );

  it('a tool the agent runs runs as the agent says, and may be made to ask or go off', () => {
    const [row] = settingsSystemRows(
      agentWith({ mode: 'ALLOW', effect: 'READ', chosenForEffect: 'READ' }),
      null,
    )[0].tools;
    expect(row.agentMode).toBe('ALLOW');
    expect(row.options.map((o) => o.value)).toEqual(['', 'ASK', 'OFF']);
    expect([toolOutcome(row, ''), toolOutcome(row, 'ASK'), toolOutcome(row, 'OFF')]).toEqual([
      'Allowed',
      'Needs your approval',
      'Off',
    ]);
  });

  it('marks what a version the reader has not accepted adds, and leaves out tools no longer served', () => {
    const agent = agentWith({ mode: 'ALLOW' });
    agent.systems[0].tools.push({
      name: 'gone',
      mode: 'ASK',
      chosenForEffect: 'WRITE',
      offered: false,
      effect: null,
    });
    const rows = settingsSystemRows(
      agent,
      widened({ tools: [{ systemId: IDS.jira, tool: 'comment', mode: 'ALLOW', was: null }] }),
    );
    expect(rows[0].tools.map((t) => [t.name, t.pending])).toEqual([['comment', true]]);
    expect(settingsSystemRows(agent, widened({ systems: [IDS.jira] }))[0].pending).toBe(true);
  });
});

describe('the settings request', () => {
  const draft = settingsDraftOf(anAgentSettings());

  it('stores "Ask me" and "Off" and nothing for "As the agent says"; never "Allowed"', () => {
    let next = withToolChoice(draft, IDS.jira, 'comment', 'ASK');
    next = withToolChoice(next, IDS.github, 'get_file_contents', 'OFF');
    next = withToolChoice(next, IDS.jira, 'comment', '');
    expect(settingsRequest(next).toolModes).toEqual([
      { systemId: IDS.github, tool: 'get_file_contents', mode: 'OFF' },
    ]);
  });

  it('asks on every write for a trigger anyone may drive, keeping what is off', () => {
    const agent = anAgent();
    let next = withToolChoice(draft, IDS.jira, 'add_comment', 'OFF');
    next = askOnEveryWrite(agent, next);
    expect(next.toolModes).toEqual([
      { systemId: IDS.jira, tool: 'add_comment', mode: 'OFF' },
      { systemId: IDS.github, tool: 'create_pull_request', mode: 'ASK' },
    ]);
  });

  it('asks on no tool a version not accepted yet brings', () => {
    const agent = anAgent();
    const next = askOnEveryWrite(
      agent,
      draft,
      widened({ tools: [{ systemId: IDS.jira, tool: 'add_comment', mode: 'ALLOW', was: null }] }),
    );
    expect(next.toolModes).toEqual([
      { systemId: IDS.github, tool: 'create_pull_request', mode: 'ASK' },
    ]);
  });

  it('sends a subscription only with SUBSCRIPTION, and the runtime default as null', () => {
    const body = settingsRequest({
      ...draft,
      modelAccess: 'BUDGET',
      subscription: { runnerHostId: IDS.personalHost, label: 'claude-max' },
      model: '  ',
      instructions: ' ',
    });
    expect(body).toMatchObject({
      subscription: null,
      model: null,
      instructions: null,
      runtime: null,
    });
  });
});

describe('a system in a few words, and what changed', () => {
  const draft = settingsDraftOf(anAgentSettings());

  it('counts the tools by what they do for the reader', () => {
    const [github, jira] = settingsSystemRows(anAgent(), null);
    expect(systemSummary(github, draft)).toBe('1 write asks · 1 read runs');
    expect(systemSummary(jira, draft)).toBe('1 write runs');
    let next = withToolChoice(draft, IDS.github, 'get_file_contents', 'ASK');
    next = withToolChoice(next, IDS.jira, 'add_comment', 'OFF');
    expect(systemSummary(github, next)).toBe('1 write asks · 1 read asks');
    expect(systemSummary(jira, next)).toBe('1 off');
    const [pending] = settingsSystemRows(
      anAgent(),
      widened({
        currentVersion: 6,
        tools: [{ systemId: IDS.github, tool: 'create_pull_request', mode: 'ALLOW', was: null }],
      }),
    );
    expect(systemSummary(pending, draft, 6)).toBe('1 read runs · 1 with version 6');
  });

  it('names each change by its setting, and the tools by their system', () => {
    const agent = anAgent();
    expect(settingsChanges(draft, draft, agent)).toEqual([]);
    let next: SettingsDraft = { ...draft, model: 'claude-opus', systemsOff: [IDS.jira] };
    next = withToolChoice(next, IDS.github, 'get_file_contents', 'ASK');
    next = withToolChoice(next, IDS.github, 'create_pull_request', 'OFF');
    expect(settingsChanges(draft, next, agent)).toEqual(['Model', 'Jira off', '2 GitHub tools']);
    expect(settingsChanges(next, draft, agent)).toEqual(['Model', 'Jira on', '2 GitHub tools']);
  });

  it('sees no change in the order of repositories picked', () => {
    const a = { ...draft, defaultRepositoryIds: [IDS.shopRepo, IDS.docsRepo] };
    const b = { ...draft, defaultRepositoryIds: [IDS.docsRepo, IDS.shopRepo] };
    expect(settingsChanges(a, b, anAgent())).toEqual([]);
    expect(settingsChanges(draft, b, anAgent())).toEqual(['Default repositories']);
  });
});

describe('models and who pays', () => {
  it.each([
    ['claude-sonnet', [], true],
    ['claude-sonnet', ['claude-*'], true],
    ['gpt-5', ['claude-*'], false],
    ['gpt-5', ['all-team-models'], true],
    ['gpt-5', ['gpt-5'], true],
  ] as const)('%s with %j allowed: %s', (model, allowed, ok) => {
    expect(modelAllowed(model, allowed)).toBe(ok);
  });

  it('lists the gateway’s models the key allows, and keeps the current one named', () => {
    expect(modelChoices(['claude-sonnet', 'gpt-5'], ['claude-*'], 'old-model')).toEqual([
      { value: 'old-model', label: 'old-model (not offered now)' },
      { value: 'claude-sonnet', label: 'claude-sonnet' },
    ]);
  });

  it('offers the subscriptions that serve the runtime, and says when the saved one is gone', () => {
    const subs: ModelSubscription[] = [
      aSubscription(),
      aSubscription({ label: 'codex', kind: 'CODEX_CHATGPT_AUTH', runtimes: ['CODEX'] }),
    ];
    const draft: SettingsDraft = {
      ...settingsDraftOf(anAgentSettings()),
      modelAccess: 'SUBSCRIPTION',
      subscription: { runnerHostId: null, label: 'old' },
    };
    const options = payerOptions(subs, 'CLAUDE_CODE', draft);
    expect(options.map((o) => [o.label, o.disabled])).toEqual([
      ['Your budget', false],
      ['Claude subscription on alice-macbook', false],
      ['“old”', true],
    ]);
    expect(subscriptionGone(subs, 'CLAUDE_CODE', draft)).toBe(true);
  });

  it('maps the save’s problems onto the fields', () => {
    const fail = (code: string) =>
      settingsFailure(
        new HttpErrorResponse({ status: 400, error: problem(400, code, 'The detail.') }),
      );
    expect(Object.keys(fail('RUNTIME_NOT_LISTED').errors)).toEqual(['runtime']);
    expect(Object.keys(fail('MODEL_NOT_ALLOWED').errors)).toEqual(['model']);
    expect(Object.keys(fail('MODEL_REQUIRED').errors)).toEqual(['model']);
    expect(Object.keys(fail('SUBSCRIPTION_NOT_SERVING').errors)).toEqual(['payer']);
    expect(Object.keys(fail('UNKNOWN_TOOL').errors)).toEqual(['systems']);
    const widen = fail('SETTINGS_WIDEN');
    expect(widen.errors).toEqual({});
    expect(widen.message).toBe('Your settings can narrow the agent, never widen it. The detail.');
  });
});

@Component({
  imports: [AgentSettingsForm],
  template: `<app-agent-settings-form
    [agent]="agent()"
    [widening]="widening()"
    [(draft)]="draft"
    [saved]="saved()"
    [subscriptions]="subscriptions"
    [ownServers]="ownServers()"
    [modelsUnavailable]="unavailable()"
    [budgetDetail]="budgetDetail()"
    [budgetUnavailable]="budgetUnavailable()"
    [gatewayModels]="['claude-sonnet', 'claude-opus']"
    [errorAt]="errorAt()"
    [subject]="subject()"
  />`,
})
class Host {
  readonly agent = signal<Agent>(anAgent());
  readonly widening = signal<AgentWidening | null>(null);
  readonly draft = signal<SettingsDraft>(settingsDraftOf(anAgentSettings()));
  readonly saved = signal<SettingsDraft | null>(null);
  readonly unavailable = signal<string | null>(null);
  readonly budgetDetail = signal<string | null>(null);
  readonly budgetUnavailable = signal<string | null>(null);
  readonly errorAt = signal<SettingsErrorAt | null>(null);
  readonly subject = signal<SettingsSubject>('person');
  readonly subscriptions = [aSubscription()];
  readonly ownServers = signal<OwnServerOption[]>([
    {
      system: aPersonalMcpServer(),
      tools: [
        aSystemTool({ name: 'find_customer', title: 'Find a customer', effect: 'READ' }),
        aSystemTool({ name: 'update_customer', title: 'Update a customer', effect: 'WRITE' }),
      ],
    },
  ]);
}

describe('AgentSettingsForm', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;
  let el: HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({ imports: [Host] });
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
    el = fixture.nativeElement;
    await settle();
  });

  const system = (id: string) => el.querySelector<HTMLElement>(`[data-system="${id}"]`)!;
  const expander = (id: string) =>
    el.querySelector<HTMLButtonElement>(`[data-testid="system-expand-${id}"]`)!;
  /** Opens a system's tools, as its chevron does. */
  async function openSystem(id: string): Promise<void> {
    expander(id).click();
    await settle();
  }
  const toolRow = (name: string) =>
    el.querySelector<HTMLElement>(`[data-system] [data-tool="${name}"]`)!;
  const labels = (row: Element) =>
    [...row.querySelectorAll('app-segmented label')].map((l) => l.textContent?.trim());
  const words = (node: Element | null | undefined) =>
    node?.textContent?.replace(/\s+/g, ' ').trim() ?? null;
  const text = (selector: string) => words(el.querySelector(selector));
  const headings = () =>
    [...el.querySelectorAll('[role=heading]')].map((h) => [words(h), h.getAttribute('aria-level')]);

  it('is three sheets: what a conversation keeps from its start, its next turns, the tools', () => {
    expect(el.querySelectorAll('section.bg-card')).toHaveLength(3);
    expect(headings()).toEqual([
      ['Runtime and who pays', '2'],
      ['Model, instructions and defaults', '2'],
      ['Systems and tools', '2'],
    ]);
    expect(text('[data-testid=fixed-note]')).toBe(
      'Fixed when a conversation starts: your open conversations keep the ones they started with.',
    );
    // Each setting is a row: its label names its control.
    const model = el.querySelector('[data-field=model] label');
    expect(model?.textContent?.trim()).toBe('Model');
    expect(el.querySelector(`#${model?.getAttribute('for')}`)?.tagName).toBe('SELECT');
  });

  it('says it of the trigger’s conversations, under the editor’s heading, with its tools open', async () => {
    expect(el.querySelector('[data-field=instructions]')).not.toBeNull();
    host.subject.set('trigger');
    await settle();
    expect(headings()).toEqual([
      ['Runtime and who pays', '3'],
      ['Model and defaults', '3'],
      ['Systems and tools', '3'],
    ]);
    expect(text('[data-testid=fixed-note]')).toBe(
      'Fixed when a conversation starts: the trigger’s open conversations keep the ones they started with.',
    );
    // A trigger has its own instruction, in every turn.
    expect(el.querySelector('[data-field=instructions]')).toBeNull();
    // Its tools are open as the editor had them, and ask its team rather than "me".
    expect(expander(IDS.github).getAttribute('aria-expanded')).toBe('true');
    expect(labels(toolRow('create_pull_request'))).toEqual(['As the agent says', 'Ask', 'Off']);
    expect(words(toolRow('create_pull_request'))).toContain('Business analyst asks first');
    // The editor offers "Ask before every write" itself.
    expect(el.querySelector('[data-testid=settings-ask-every-write]')).toBeNull();
  });

  it('names the only runtime, and the budget as the one payer where nothing else serves', async () => {
    host.agent.set(anAgent({ runtimes: ['CODEX'] }));
    await settle();
    expect(text('[data-testid=only-runtime]')).toBe('Codex · Business analyst’s only runtime');
    // The subscription serves Claude Code only: the budget is the one payer.
    expect(el.querySelectorAll('[data-payer]')).toHaveLength(1);
  });

  it('keeps each system’s tools behind a chevron, with what they do in a few words', async () => {
    expect(el.querySelector('[data-tool]')).toBeNull();
    expect(text(`[data-system="${IDS.github}"] [data-summary]`)).toBe('1 write asks · 1 read runs');
    expect(text(`[data-system="${IDS.jira}"] [data-summary]`)).toBe('1 write runs');
    expect(expander(IDS.github).getAttribute('aria-expanded')).toBe('false');
    expect(expander(IDS.github).getAttribute('aria-label')).toBe('Show GitHub tools');

    await openSystem(IDS.github);
    expect(expander(IDS.github).getAttribute('aria-expanded')).toBe('true');
    expect(expander(IDS.github).getAttribute('aria-label')).toBe('Hide GitHub tools');
    const list = el.querySelector(`#${expander(IDS.github).getAttribute('aria-controls')}`);
    expect(list?.querySelectorAll('[data-tool]')).toHaveLength(2);
    expect(words(toolRow('create_pull_request'))).toContain(
      'Open a pull requestWrites · Business analyst asks you',
    );
    expect(words(toolRow('get_file_contents'))).toContain('Reads · Business analyst runs it');
    expect(system(IDS.jira).querySelector('[data-tool]')).toBeNull();
  });

  it.each(ASKING)('never shows "Allowed" on a tool that %s', async (_, tool, widening) => {
    host.agent.set(agentWith(tool));
    host.widening.set(widening);
    await settle();
    await openSystem(IDS.jira);
    const row = toolRow('comment');
    expect(row.dataset['agentMode']).toBe('ASK');
    expect(labels(row)).toEqual(['As the agent says', 'Ask me', 'Off']);
    expect(words(row)).toContain('Business analyst asks you');
    expect(text(`[data-system="${IDS.jira}"] [data-summary]`)).not.toContain('runs');
    for (const input of row.querySelectorAll<HTMLInputElement>('input[type=radio]')) {
      expect(input.value).not.toBe('ALLOW');
    }
  });

  it('stores the reader’s choices on the agent’s tools, and only ASK and OFF', async () => {
    await openSystem(IDS.github);
    const row = toolRow('get_file_contents');
    expect(row.querySelector('[data-testid=narrowed]')).toBeNull();
    row.querySelector<HTMLInputElement>('input[value=ASK]')!.click();
    await settle();
    expect(host.draft().toolModes).toEqual([
      { systemId: IDS.github, tool: 'get_file_contents', mode: 'ASK' },
    ]);
    expect(row.dataset['choice']).toBe('ASK');
    expect(text('[data-testid=narrowed]')).toBe('Narrowed');
    expect(text(`[data-system="${IDS.github}"] [data-summary]`)).toBe('1 write asks · 1 read asks');
    row.querySelector<HTMLInputElement>('input[value=""]')!.click();
    await settle();
    expect(host.draft().toolModes).toEqual([]);
    expect(row.querySelector('[data-testid=narrowed]')).toBeNull();
  });

  it('turns memory on in the draft and its request, and says whose notes they are', async () => {
    expect(host.draft().memory).toBe(false);
    expect(settingsRequest(host.draft()).memory).toBe(false);
    expect(text('[data-testid=memory-hint]')).toContain('Only you see them');
    el.querySelector<HTMLElement>('[data-testid=memory-switch] [role=switch]')!.click();
    await settle();
    expect(host.draft().memory).toBe(true);
    expect(settingsRequest(host.draft()).memory).toBe(true);

    host.subject.set('trigger');
    await settle();
    expect(text('[data-testid=memory-hint]')).toContain(
      'Each note waits for the trigger’s team to accept it before any conversation reads it.',
    );
  });

  it('cannot turn memory on where the agent’s editor forbids it, and says the notes stay', async () => {
    host.agent.set(anAgent({ memoryAllowed: false }));
    host.draft.set({ ...host.draft(), memory: true });
    await settle();
    const control = el.querySelector<HTMLElement>('[data-testid=memory-switch] [role=switch]')!;
    control.click();
    await settle();
    expect(host.draft().memory).toBe(true);
    expect(text('[data-testid=memory-hint]')?.replace(/\s+/g, ' ')).toBe(
      'Business analyst’s editor does not allow memory, so none is read or written. The notes it kept stay until they are deleted.',
    );
  });

  it('turns automatic retries on in the draft and its request, and says whose turns they are', async () => {
    expect(host.draft().autoRetry).toBe(false);
    expect(settingsRequest(host.draft()).autoRetry).toBe(false);
    expect(text('[data-testid=auto-retry-hint]')?.replace(/\s+/g, ' ')).toContain(
      'A turn of your conversations that fails because the model’s provider is overloaded or unavailable',
    );
    el.querySelector<HTMLElement>('[data-testid=auto-retry-switch] [role=switch]')!.click();
    await settle();
    expect(host.draft().autoRetry).toBe(true);
    expect(settingsRequest(host.draft()).autoRetry).toBe(true);

    host.subject.set('trigger');
    await settle();
    expect(text('[data-testid=auto-retry-hint]')?.replace(/\s+/g, ' ')).toContain(
      'A turn of the trigger’s conversations that fails',
    );
  });

  it('switches a system off for the reader, which closes and hides its tools', async () => {
    await openSystem(IDS.jira);
    el.querySelector<HTMLElement>(
      `[data-testid="system-switch-${IDS.jira}"] [role=switch]`,
    )!.click();
    await settle();
    expect(host.draft().systemsOff).toEqual([IDS.jira]);
    expect(system(IDS.jira).querySelector('[data-tool]')).toBeNull();
    expect(text(`[data-system="${IDS.jira}"] [data-summary]`)).toBe('Off for your conversations');
    expect(expander(IDS.jira).disabled).toBe(true);
    expect(expander(IDS.jira).getAttribute('aria-expanded')).toBe('false');
  });

  it('asks before every write in one click, and then has nothing left to do', async () => {
    const button = el.querySelector<HTMLButtonElement>('[data-testid=settings-ask-every-write]')!;
    expect(button.textContent?.trim()).toBe('Ask before every write');
    button.click();
    await settle();
    expect(host.draft().toolModes).toEqual([
      { systemId: IDS.github, tool: 'create_pull_request', mode: 'ASK' },
      { systemId: IDS.jira, tool: 'add_comment', mode: 'ASK' },
    ]);
    expect(text(`[data-system="${IDS.jira}"] [data-summary]`)).toBe('1 write asks');
    expect(button.disabled).toBe(true);
  });

  it('shows what a version not accepted yet adds, disabled, as the reader’s once accepted', async () => {
    host.widening.set(
      widened({
        currentVersion: 6,
        tools: [{ systemId: IDS.github, tool: 'create_pull_request', mode: 'ALLOW', was: null }],
        systems: [IDS.jira],
        repositories: [{ repositoryId: IDS.docsRepo, access: 'WRITE', was: null }],
      }),
    );
    await settle();
    expect(text(`[data-system="${IDS.jira}"] [data-summary]`)).toBe(
      'Yours after you accept version 6',
    );
    expect(
      el.querySelector<HTMLButtonElement>(`[data-testid="system-switch-${IDS.jira}"] [role=switch]`)
        ?.disabled,
    ).toBe(true);
    expect(text(`[data-system="${IDS.github}"] [data-summary]`)).toBe(
      '1 read runs · 1 with version 6',
    );
    await openSystem(IDS.github);
    const pr = toolRow('create_pull_request');
    expect(words(pr)).toContain('yours after you accept version 6');
    expect(pr.querySelector('app-segmented')?.getAttribute('aria-disabled')).toBe('true');
    expect(pr.querySelector('[data-testid=narrowed]')).toBeNull();

    const docs = el.querySelector(`[data-repository="${IDS.docsRepo}"]`)!;
    expect(docs.querySelector<HTMLInputElement>('input')?.disabled).toBe(true);
    expect(words(docs)).toContain('read and write · yours after you accept version 6');
    expect(
      el.querySelector<HTMLInputElement>(`[data-repository="${IDS.shopRepo}"] input`)?.disabled,
    ).toBe(false);
  });

  it('says what a changed field was, as saved', async () => {
    host.saved.set(host.draft());
    host.draft.update((d) => ({ ...d, model: 'claude-opus', effort: 'HIGH' }));
    await settle();
    expect(text('[data-testid=model-was]')).toBe('was the runtime’s default');
    expect(text('[data-testid=effort-was]')).toBe('was the runtime’s default');
    expect(el.querySelector('[data-testid=payer-was]')).toBeNull();
    el.querySelector<HTMLInputElement>(
      `[data-payer="sub:${IDS.personalHost}:claude-max"] input`,
    )!.click();
    await settle();
    expect(text('[data-testid=payer-was]')).toBe('was your budget');
  });

  it('offers "Allowed" and "Needs your approval" on one’s own MCP server once added', async () => {
    const server = el.querySelector<HTMLElement>(`[data-own-server="${IDS.crm}"]`)!;
    expect(server.querySelector('[data-tool]')).toBeNull();
    server.querySelector<HTMLElement>('[role=switch]')!.click();
    await settle();
    expect(settingsRequest(host.draft()).ownServers).toEqual([
      {
        systemId: IDS.crm,
        tools: [
          { name: 'find_customer', mode: 'ALLOW' },
          { name: 'update_customer', mode: 'ASK' },
        ],
      },
    ]);
    const update = server.querySelector<HTMLElement>('[data-tool=update_customer]')!;
    expect(labels(update)).toEqual(['Allowed', 'Needs your approval']);

    // The reader's pick carries the effect the row shows.
    update.querySelector<HTMLInputElement>('input[value=ALLOW]')!.click();
    await settle();
    expect(settingsRequest(host.draft()).ownServers?.[0].tools).toEqual([
      { name: 'find_customer', mode: 'ALLOW' },
      { name: 'update_customer', mode: 'ALLOW', chosenForEffect: 'WRITE' },
    ]);
  });

  it('says where to register one’s own MCP server when there is none', async () => {
    host.ownServers.set([]);
    await settle();
    expect(text('[data-testid=no-own-servers]')).toBe(
      'You have no MCP server of your own. Register one under Accounts.',
    );
  });

  it('confirms an own tool that asks since it writes in one save', async () => {
    host.draft.set({
      ...settingsDraftOf(
        anAgentSettings({
          stored: true,
          ownServers: [
            {
              systemId: IDS.crm,
              name: 'My CRM',
              tools: [{ name: 'find_customer', mode: 'ALLOW', chosenForEffect: 'READ' }],
            },
          ],
        }),
      ),
    });
    host.ownServers.set([
      {
        system: aPersonalMcpServer(),
        tools: [aSystemTool({ name: 'find_customer', title: 'Find a customer', effect: 'WRITE' })],
      },
    ]);
    await settle();
    const row = el.querySelector<HTMLElement>(
      `[data-own-server="${IDS.crm}"] [data-tool=find_customer]`,
    )!;
    expect(row.querySelector('[data-testid=own-asks-since-it-writes]')).not.toBeNull();
    expect(row.querySelector<HTMLInputElement>('input[value=ASK]')?.checked).toBe(true);
    // Saved as it is, nothing is chosen anew.
    expect(settingsRequest(host.draft()).ownServers?.[0].tools).toEqual([
      { name: 'find_customer', mode: 'ALLOW' },
    ]);
    row.querySelector<HTMLInputElement>('input[value=ALLOW]')!.click();
    await settle();
    expect(settingsRequest(host.draft()).ownServers?.[0].tools).toEqual([
      { name: 'find_customer', mode: 'ALLOW', chosenForEffect: 'WRITE' },
    ]);
    expect(row.querySelector('[data-testid=own-asks-since-it-writes]')).toBeNull();
  });

  it.each([
    [
      'a tool the agent no longer lists',
      { code: 'UNKNOWN_TOOL', systemId: IDS.github, tool: 'create_pull_request' } as const,
      `[data-system="${IDS.github}"]`,
      'Business analyst no longer lists “Open a pull request” on GitHub',
    ],
    [
      'a tool one’s own server no longer serves',
      { code: 'UNKNOWN_TOOL', systemId: IDS.crm, tool: 'update_customer' } as const,
      `[data-own-server="${IDS.crm}"]`,
      'My CRM no longer serves “Update a customer”: switch it off and on again',
    ],
    [
      'a system the agent no longer uses',
      { code: 'SYSTEM_NOT_ON_AGENT', systemId: 'sys-gone', tool: null } as const,
      '[data-field=systems]',
      'Business analyst no longer uses a system your settings name.',
    ],
  ])('says %s where it stands', async (_, at, where, text) => {
    host.errorAt.set(at);
    await settle();
    expect(el.querySelector(`${where} [data-error]`)?.textContent).toContain(text);
    expect(el.querySelectorAll('[data-error]')).toHaveLength(1);
  });

  it('disables the model, with the reason, while the gateway is not configured', async () => {
    host.unavailable.set('The company’s model gateway is not set up yet.');
    await settle();
    expect(el.querySelector<HTMLSelectElement>('[data-field=model] select')?.disabled).toBe(true);
    expect(el.querySelector('[data-testid=models-unavailable]')?.textContent).toContain(
      'not set up yet',
    );
  });

  it('says what the budget has left, or why it cannot pay', async () => {
    host.budgetDetail.set('$38.20 of $50.00 left, resets 1 Nov');
    await settle();
    const budget = () => el.querySelector('[data-payer=BUDGET]')!;
    expect(words(budget())).toBe('Your budget$38.20 of $50.00 left, resets 1 Nov');
    host.budgetUnavailable.set('Needs the company’s model gateway, which is not set up yet');
    await settle();
    expect(words(budget())).toContain('which is not set up yet');
    expect(budget().querySelector<HTMLInputElement>('input')?.disabled).toBe(true);
  });

  it('lets a subscription on the reader’s host pay', async () => {
    el.querySelector<HTMLInputElement>(
      `[data-payer="sub:${IDS.personalHost}:claude-max"] input`,
    )!.click();
    await settle();
    expect(host.draft()).toMatchObject({
      modelAccess: 'SUBSCRIPTION',
      subscription: { runnerHostId: IDS.personalHost, label: 'claude-max' },
    });
  });

  it('offers the runtime’s default to a subscription only: a budget needs a model', async () => {
    const empty = () =>
      el.querySelector<HTMLOptionElement>('[data-field=model] select option[value=""]')!;
    expect(host.draft().modelAccess).toBe('BUDGET');
    expect(empty().textContent?.trim()).toBe('Choose a model');
    expect(empty().disabled).toBe(true);
    expect(el.querySelector('[data-field=model]')?.textContent).toContain('a model is required');

    el.querySelector<HTMLInputElement>(
      `[data-payer="sub:${IDS.personalHost}:claude-max"] input`,
    )!.click();
    await settle();
    expect(empty().textContent?.trim()).toBe('The runtime’s default');
    expect(empty().disabled).toBe(false);
    expect(el.querySelector('[data-field=model]')?.textContent).not.toContain(
      'a model is required',
    );
  });
});
