import { HttpTestingController } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import type { ExternalSystem, Role, SystemKind } from '../../../api/generated/models';
import {
  aJiraSystem,
  aPersonalMcpServer,
  aSystem,
  aWebhookSystem,
  IDS,
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
import {
  createBody,
  hasSettings,
  movesEndpoint,
  stepFields,
  SystemDialog,
  systemChanges,
} from './system-dialog';
import { registrableKinds } from './system-kinds';
import { systemSections, Systems } from './systems';

@Component({ selector: 'app-system-page', template: '<h1>System</h1>' })
class SystemPageStub {}

const SYSTEMS = '/api/companies/c1/systems';

const NOTES = aSystem({
  id: 'sys-notes',
  name: 'Notes',
  handle: 'notes',
  kind: 'MCP',
  baseUrl: 'https://notes.acme.test/mcp',
  status: 'NEW',
  oauthAvailable: false,
  app: null,
});
const GIT = aSystem({
  id: 'sys-git',
  name: 'Internal git',
  handle: 'git',
  kind: 'GIT',
  baseUrl: null,
  oauthAvailable: false,
  app: null,
});

describe('systemSections', () => {
  it('puts the company’s systems by group, the caller’s own servers last, and leaves out empty ones', () => {
    const sections = systemSections([NOTES, aPersonalMcpServer(), GIT, aJiraSystem(), aSystem()]);
    expect(sections.map((s) => [s.key, s.systems.map((x) => x.name)])).toEqual([
      ['tracker', ['Jira']],
      ['code', ['Internal git', 'GitHub']],
      ['mcp', ['Notes']],
      ['personal', ['My CRM']],
    ]);
    expect(systemSections([])).toEqual([]);
  });
});

describe('the register dialog’s bodies', () => {
  const model = (overrides = {}) => ({
    kind: '' as const,
    name: ' Support Jira ',
    baseUrl: ' https://jira.acme.test ',
    authHeader: ' X-Api-Key ',
    transport: 'SOCKET_MODE' as const,
    tenantId: ' 72f988bf-86f1-41af-91ab-2d7cd011db47 ',
    appId: '0b9c1f5e-3d4a-4b8c-9e2f-6a7b8c9d0e1f',
    scheme: 'SECRET_HEADER' as const,
    secretHeader: ' X-Ops-Token ',
    maxPerMinute: '120',
    pollIntervalSeconds: '',
    accountsRequired: true,
    ...overrides,
  });

  it.each`
    kind         | body
    ${'JIRA_DC'} | ${{ name: 'Support Jira', kind: 'JIRA_DC', baseUrl: 'https://jira.acme.test' }}
    ${'GIT'}     | ${{ name: 'Support Jira', kind: 'GIT' }}
    ${'MCP'}     | ${{ name: 'Support Jira', kind: 'MCP', baseUrl: 'https://jira.acme.test', authHeader: 'X-Api-Key' }}
    ${'SLACK'}   | ${{ name: 'Support Jira', kind: 'SLACK', slack: { transport: 'SOCKET_MODE' } }}
    ${'TEAMS'}   | ${{ name: 'Support Jira', kind: 'TEAMS', teams: { tenantId: '72f988bf-86f1-41af-91ab-2d7cd011db47', appId: '0b9c1f5e-3d4a-4b8c-9e2f-6a7b8c9d0e1f' } }}
    ${'WEBHOOK'} | ${{ name: 'Support Jira', kind: 'WEBHOOK', webhook: { scheme: 'SECRET_HEADER', mapping: {}, secretHeader: 'X-Ops-Token', maxPerMinute: 120 } }}
  `('a new $kind sends only what the kind takes', ({ kind, body }) => {
    expect(createBody(model(), kind as SystemKind)).toEqual(body);
  });

  it('never sends a handle, which the server makes, and only the changes of a change', () => {
    for (const kind of ['JIRA_DC', 'GIT', 'MCP', 'SLACK', 'WEBHOOK'] as SystemKind[]) {
      expect(createBody(model(), kind)).not.toHaveProperty('handle');
    }
    expect(systemChanges(model({ name: 'GitHub', authHeader: '' }), aSystem())).toEqual({});
    // A rename keeps the handle: the change sends the name alone.
    expect(systemChanges(model({ name: 'Code' }), aSystem())).toEqual({ name: 'Code' });
    expect(
      systemChanges(model({ name: 'Notes', authHeader: '', baseUrl: NOTES.baseUrl }), {
        ...NOTES,
        authHeader: 'X-Api-Key',
      }),
    ).toEqual({ authHeader: '' });
    // An MCP server set to need no account sends that alone (ADR-0071); another kind never does.
    expect(
      systemChanges(
        model({ name: 'Notes', authHeader: '', baseUrl: NOTES.baseUrl, accountsRequired: false }),
        NOTES,
      ),
    ).toEqual({ accountsRequired: false });
    expect(
      systemChanges(model({ name: 'GitHub', authHeader: '', accountsRequired: false }), aSystem()),
    ).toEqual({});
  });

  it('sends an MCP server’s new endpoint alone, and never another kind’s base URL (ADR-0079)', () => {
    const notes = { name: 'Notes', authHeader: '' };
    expect(
      systemChanges(model({ ...notes, baseUrl: ' https://notes.example.test/mcp ' }), NOTES),
    ).toEqual({ baseUrl: 'https://notes.example.test/mcp' });
    // The same endpoint, with a trailing slash as typed, or left empty, is no change.
    expect(
      systemChanges(model({ ...notes, baseUrl: 'https://notes.acme.test/mcp/' }), NOTES),
    ).toEqual({});
    expect(systemChanges(model({ ...notes, baseUrl: '' }), NOTES)).toEqual({});
    const jira = aJiraSystem();
    expect(
      systemChanges(model({ name: jira.name, baseUrl: 'https://other.example.test' }), jira),
    ).not.toHaveProperty('baseUrl');
    expect(movesEndpoint(model({ baseUrl: 'https://other.example.test' }), jira)).toBe(false);
  });

  it.each<[SystemKind, string, number | undefined]>([
    ['JIRA_DC', '120', 120],
    ['GITHUB', ' 30 ', 30],
    ['JIRA_CLOUD', '', undefined],
    // Only the kinds whose issues are polled take one.
    ['MCP', '120', undefined],
  ])('a new %s sends the poll interval “%s” as %s', (kind, typed, sent) => {
    expect(createBody(model({ pollIntervalSeconds: typed }), kind).pollIntervalSeconds).toBe(sent);
  });

  it.each<[string, number | null, string, number | undefined]>([
    ['unchanged', 60, '60', undefined],
    ['changed', 60, '300', 300],
    ['emptied (the default stays)', 120, '', undefined],
  ])('a change sends the poll interval only when %s', (_, stored, typed, sent) => {
    const github = aSystem({ pollIntervalSeconds: stored });
    expect(
      systemChanges(model({ name: github.name, pollIntervalSeconds: typed }), github)
        .pollIntervalSeconds,
    ).toBe(sent);
  });

  it('a new signed webhook sends no header, and no rate when none was typed', () => {
    expect(createBody(model({ scheme: 'STANDARD', maxPerMinute: '' }), 'WEBHOOK').webhook).toEqual({
      scheme: 'STANDARD',
      mapping: {},
    });
  });

  it('changes a webhook’s scheme, header and rate, and nothing it keeps', () => {
    const hook = aWebhookSystem();
    expect(systemChanges(model({ name: hook.name, maxPerMinute: '120' }), hook)).toEqual({});
    expect(systemChanges(model({ name: hook.name, secretHeader: 'X-Alert-Key' }), hook)).toEqual({
      webhook: { secretHeader: 'X-Alert-Key' },
    });
    expect(
      systemChanges(model({ name: hook.name, scheme: 'STANDARD', maxPerMinute: '60' }), hook),
    ).toEqual({ webhook: { scheme: 'STANDARD', maxPerMinute: 60 } });
  });

  it('changes a Slack system’s transport, and nothing else of a chat', () => {
    const slack = aSystem({
      kind: 'SLACK',
      name: 'Slack',
      handle: 'slack',
      chat: { transport: 'EVENTS_API', botSet: false, signingSecretSet: false, appTokenSet: false },
    });
    expect(systemChanges(model({ name: 'Slack' }), slack)).toEqual({
      slack: { transport: 'SOCKET_MODE' },
    });
    expect(systemChanges(model({ name: 'Slack', transport: 'EVENTS_API' }), slack)).toEqual({});
  });
});

describe('the register dialog’s steps', () => {
  it('gives every registrable kind a settings step but Git', () => {
    const kinds = registrableKinds().flatMap((group) => group.kinds.map((kind) => kind.value));
    expect(kinds.filter((kind) => !hasSettings(kind))).toEqual(['GIT']);
  });

  it.each<[SystemKind, 'STANDARD' | 'SECRET_HEADER', string[], string[]]>([
    ['JIRA_DC', 'STANDARD', ['name', 'baseUrl'], ['pollIntervalSeconds']],
    ['GIT', 'STANDARD', ['name'], []],
    ['MCP', 'STANDARD', ['name', 'baseUrl'], ['authHeader']],
    ['SLACK', 'STANDARD', ['name'], []],
    ['TEAMS', 'STANDARD', ['name'], ['tenantId', 'appId']],
    ['WEBHOOK', 'STANDARD', ['name'], ['maxPerMinute']],
    ['WEBHOOK', 'SECRET_HEADER', ['name'], ['secretHeader', 'maxPerMinute']],
  ])('Next on a %s (%s) checks %j, then %j', (kind, scheme, details, settings) => {
    expect(stepFields('kind', { kind, scheme })).toEqual([]);
    expect(stepFields('details', { kind, scheme })).toEqual(details);
    expect(stepFields('settings', { kind, scheme })).toEqual(settings);
  });
});

describe('Systems', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(items: ExternalSystem[], role: Role = 'ADMIN') {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'c/:companyId/systems', component: Systems },
          { path: 'c/:companyId/systems/:systemId', component: SystemPageStub },
        ]),
        ...provideTestHttp(),
        fakeCurrentCompany(role).provider,
        notifier.provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl('/c/c1/systems');
    await settle();
    const req = http.expectOne((r) => r.url === SYSTEMS);
    expect(req.request.params.get('limit')).toBe('200');
    req.flush({ items });
    await settle();
    harness.detectChanges();
  }

  const el = () => harness.routeNativeElement as HTMLElement;
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('lists each system with its kind, status and how accounts connect, by section', async () => {
    await open([
      aJiraSystem({ status: 'ERROR', lastError: 'Connection refused' }),
      aSystem(),
      NOTES,
    ]);

    const jira = el().querySelector('[data-testid=systems-tracker] [data-testid=system-row-jira]')!;
    expect(jira.getAttribute('href')).toBe(`/c/c1/systems/${IDS.jira}`);
    expect(text(jira)).toContain('Jira Cloud');
    expect(jira.querySelector('[data-testid=system-status]')?.getAttribute('data-status')).toBe(
      'ERROR',
    );
    expect(jira.querySelector('[data-testid=system-status]')?.getAttribute('title')).toBe(
      'Connection refused',
    );
    expect(text(jira)).toContain('Tokens only, no app yet');
    const github = el().querySelector(
      '[data-testid=systems-code] [data-testid=system-row-github]',
    )!;
    expect(text(github)).toContain('Sign-in or token');
    expect(text(el().querySelector('[data-testid=systems-mcp]'))).toContain('Not tested yet');
    // Nobody waits on a person here: no orange.
    expect(el().querySelector('.text-human')).toBeNull();
  });

  it('says what a system is for when there is none yet', async () => {
    await open([]);
    expect(text(el().querySelector('[data-testid=systems-empty]'))).toContain('No systems yet');
  });

  it('shows no Register button to a member who reached the list', async () => {
    await open([aSystem()], 'MEMBER');
    expect(el().querySelector('[data-testid=register-system]')).toBeNull();
  });

  const dialog = () => overlay().querySelector<HTMLElement>('[data-testid=system-dialog]')!;
  const byTestId = (id: string) =>
    overlay().querySelector<HTMLButtonElement>(`[data-testid=${id}]`);
  const field = (id: string) => dialog().querySelector<HTMLInputElement>(`#${id}`);
  const currentStep = () =>
    text(dialog().querySelector('[data-testid=system-steps] [aria-current=step]'));
  const stepCount = () => text(dialog().querySelector('[data-testid=system-step-count]'));
  const focused = () => (document.activeElement as HTMLElement | null)?.id;

  async function startRegistering(): Promise<void> {
    el().querySelector<HTMLButtonElement>('[data-testid=register-system]')!.click();
    await settle();
  }

  async function pick(kind: SystemKind): Promise<void> {
    field(`system-kind-${kind}`)!.click();
    await settle();
  }

  async function next(): Promise<void> {
    byTestId('system-next')!.click();
    await settle();
  }

  async function back(): Promise<void> {
    byTestId('system-back')!.click();
    await settle();
  }

  async function register(): Promise<void> {
    submitForm(dialog().querySelector('form'));
    await settle();
  }

  it('registers a webhook in steps, shows its secret once, and opens its page only once it is copied', async () => {
    await open([]);
    await startRegistering();
    await pick('WEBHOOK');
    await next();
    // Its details: a name, no handle, no base URL.
    expect(currentStep()).toContain('Details');
    expect(field('system-handle')).toBeNull();
    expect(field('system-base-url')).toBeNull();
    typeInto(field('system-name'), 'Ops alerts');
    await next();
    // Its settings: the scheme, the signed one first, and the rate.
    expect(stepCount()).toBe('Step 3 of 3');
    expect(focused()).toBe('webhook-scheme-STANDARD');
    expect(text(dialog().querySelector('[data-testid=webhook-scheme]'))).toContain(
      'Secret in a header (weaker)',
    );
    expect(field('webhook-header')).toBeNull();
    typeInto(field('webhook-rate'), '9000');
    await register();
    expect(text(dialog())).toContain('A whole number from 1 to 6000.');
    http.expectNone(SYSTEMS);

    typeInto(field('webhook-rate'), '');
    await register();
    const req = http.expectOne((r) => r.url === SYSTEMS && r.method === 'POST');
    expect(req.request.body).toEqual({
      name: 'Ops alerts',
      kind: 'WEBHOOK',
      webhook: { scheme: 'STANDARD', mapping: {} },
    });
    const created = aWebhookSystem({
      id: 'sys-new',
      webhook: { ...aWebhookSystem().webhook!, scheme: 'STANDARD', secret: 'whsec_c2VjcmV0' },
    });
    req.flush(created);
    await settle();
    // The secret, once, before anything else.
    expect(TestBed.inject(Router).url).toBe('/c/c1/systems');
    expect(dialog().querySelector('[data-testid=system-steps]')).toBeNull();
    expect(text(dialog().querySelector('[data-testid=webhook-secret]'))).toBe('whsec_c2VjcmV0');
    byTestId('webhook-secret-done')!.click();
    await settle();
    expect(TestBed.inject(Router).url).toBe('/c/c1/systems/sys-new');
    expect(notify.success).toHaveBeenCalledWith('Ops alerts is registered');
  });

  it('registers a system in steps: its kind, its details, its settings, and opens its page', async () => {
    await open([]);
    await startRegistering();
    // Nothing but the kinds until one is picked, and Next waits for one.
    expect(field('system-name')).toBeNull();
    expect(stepCount()).toBe('Step 1 of 3');
    expect(currentStep()).toContain('Kind');
    expect(byTestId('system-next')!.disabled).toBe(true);
    expect(byTestId('system-save')).toBeNull();
    expect(byTestId('system-back')).toBeNull();

    await pick('JIRA_DC');
    expect(byTestId('system-next')!.disabled).toBe(false);
    await next();
    expect(currentStep()).toContain('Details');
    expect(focused()).toBe('system-name');
    expect(text(dialog().querySelector('[data-testid=system-kind-chosen]'))).toContain(
      'Jira Data Center',
    );
    // This step's fields only: the poll interval comes after.
    expect(field('system-poll-interval')).toBeNull();
    // No handle to type: the server makes it from the name (CONVENTIONS §15.2).
    expect(field('system-handle')).toBeNull();
    typeInto(field('system-name'), 'Support Jira');
    // Enter is Next: it checks this step's fields, says what is wrong and stays.
    submitForm(dialog().querySelector('form'));
    await settle();
    expect(text(dialog())).toContain('Enter the address.');
    expect(currentStep()).toContain('Details');
    expect(focused()).toBe('system-base-url');
    http.expectNone(SYSTEMS);

    typeInto(field('system-base-url'), 'https://jira.acme.test');
    await next();
    expect(currentStep()).toContain('Settings');
    expect(focused()).toBe('system-poll-interval');
    expect(byTestId('system-next')).toBeNull();
    expect(text(byTestId('system-save'))).toBe('Register');

    // Back keeps what was typed, and the steps passed stay done.
    await back();
    expect(field('system-name')!.value).toBe('Support Jira');
    expect(field('system-base-url')!.value).toBe('https://jira.acme.test');
    expect(text(dialog().querySelector('[data-testid=system-steps]'))).toContain('Settings, done');
    await next();
    await register();
    const req = http.expectOne((r) => r.url === SYSTEMS && r.method === 'POST');
    expect(req.request.body).toEqual({
      name: 'Support Jira',
      kind: 'JIRA_DC',
      baseUrl: 'https://jira.acme.test',
    });
    req.flush(aJiraSystem({ id: 'sys-new', name: 'Support Jira', kind: 'JIRA_DC' }));
    await settle();
    expect(TestBed.inject(Router).url).toBe('/c/c1/systems/sys-new');
    expect(notify.success).toHaveBeenCalledWith('Support Jira is registered');
  });

  it('registers a kind without settings from its details, and starts another kind’s fields over', async () => {
    await open([]);
    await startRegistering();
    await pick('MCP');
    await next();
    expect(text(dialog().querySelector('label[for=system-base-url]'))).toBe('MCP endpoint');
    typeInto(field('system-name'), 'Notes');
    typeInto(field('system-base-url'), 'https://notes.acme.test/mcp');
    await next();
    expect(field('system-auth-header')).not.toBeNull();

    // Another kind: its address and settings start over, the name stays.
    dialog().querySelector<HTMLButtonElement>('[data-testid=system-kind-chosen] button')!.click();
    await settle();
    expect(currentStep()).toContain('Kind');
    expect(focused()).toBe('system-kind-MCP');
    expect(text(dialog().querySelector('[data-testid=kind-change-hint]'))).toContain(
      'clears its address and settings',
    );
    await pick('GIT');
    expect(stepCount()).toBe('Step 1 of 2');
    await next();
    expect(stepCount()).toBe('Step 2 of 2');
    expect(field('system-name')!.value).toBe('Notes');
    expect(field('system-base-url')).toBeNull();
    // Git has no settings: Details is the last step.
    expect(byTestId('system-next')).toBeNull();
    expect(text(byTestId('system-save'))).toBe('Register');

    await back();
    await pick('MCP');
    await next();
    expect(field('system-base-url')!.value).toBe('');
  });

  it('asks a Teams tenant and app on the settings step, and checks them there', async () => {
    await open([]);
    await startRegistering();
    await pick('TEAMS');
    await next();
    expect(field('system-handle')).toBeNull();
    expect(field('system-tenant-id')).toBeNull();
    typeInto(field('system-name'), 'Teams');
    await next();
    expect(focused()).toBe('system-tenant-id');
    await register();
    expect(text(field('system-tenant-id')!.closest('[hlmField]'))).toContain(
      'Enter the tenant ID.',
    );
    expect(text(field('system-app-id')!.closest('[hlmField]'))).toContain(
      'Enter the Microsoft App ID.',
    );
    expect(focused()).toBe('system-tenant-id');
    http.expectNone(SYSTEMS);
  });

  it('asks a tracker how often to poll, checks the range, and puts a refusal on the field', async () => {
    await open([]);
    await startRegistering();
    await pick('MCP');
    await next();
    typeInto(field('system-name'), 'Notes');
    typeInto(field('system-base-url'), 'https://notes.acme.test/mcp');
    await next();
    expect(field('system-poll-interval')).toBeNull();

    await back();
    await back();
    await pick('GITHUB');
    await next();
    typeInto(field('system-name'), 'GitHub');
    typeInto(field('system-base-url'), 'https://api.github.com');
    await next();
    const poll = field('system-poll-interval')!;
    expect(poll.placeholder).toBe('60');
    expect(text(poll.closest('[hlmField]'))).toContain(
      'from 30 seconds to an hour. Empty: every minute.',
    );
    typeInto(poll, '10');
    await register();
    expect(text(poll.closest('[hlmField]'))).toContain(
      'A whole number of seconds from 30 to 3600 (an hour).',
    );
    http.expectNone(SYSTEMS);

    typeInto(poll, '300');
    await register();
    const req = http.expectOne((r) => r.url === SYSTEMS && r.method === 'POST');
    expect(req.request.body.pollIntervalSeconds).toBe(300);
    req.flush(
      {
        ...problem(400, 'VALIDATION_FAILED'),
        errors: [{ field: 'pollIntervalSeconds', message: 'must be between 30 and 3600' }],
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    // The refused field is on this step: it stays.
    expect(currentStep()).toContain('Settings');
    expect(text(field('system-poll-interval')!.closest('[hlmField]'))).toContain(
      'must be between 30 and 3600',
    );
  });

  async function refusedFromSettings(code: string): Promise<void> {
    await open([]);
    await startRegistering();
    await pick('GITHUB');
    await next();
    typeInto(field('system-name'), 'GitHub');
    typeInto(field('system-base-url'), 'https://api.github.com');
    await next();
    await register();
    const status = code === 'INVALID_BASE_URL' ? 400 : 409;
    http
      .expectOne((r) => r.url === SYSTEMS && r.method === 'POST')
      .flush(problem(status, code, 'Private addresses are refused'), {
        status,
        statusText: 'Error',
      });
    await settle();
  }

  it.each`
    code                       | id                   | message
    ${'DUPLICATE_SYSTEM_NAME'} | ${'system-name'}     | ${'A system with this name already exists.'}
    ${'INVALID_BASE_URL'}      | ${'system-base-url'} | ${'Tinfirm may not connect to this address. Private addresses are refused.'}
  `(
    'opens the details step on $code, with the message on its field',
    async ({ code, id, message }) => {
      await refusedFromSettings(code);
      expect(currentStep()).toContain('Details');
      expect(text(field(id)!.closest('[hlmField]'))).toContain(message);
      expect(focused()).toBe(id);
      expect(byTestId('system-form-error')).toBeNull();
    },
  );

  it('registers from the step the server sent the person back to', async () => {
    await refusedFromSettings('DUPLICATE_SYSTEM_NAME');
    // Register is offered there, so the fixed name needs no second walk through the steps.
    expect(byTestId('system-next')).toBeNull();
    expect(text(byTestId('system-save'))).toBe('Register');
    expect(text(dialog().querySelector('[data-testid=system-steps]'))).toContain('Settings, done');
    typeInto(field('system-name'), 'Support GitHub');
    await register();
    const req = http.expectOne((r) => r.url === SYSTEMS && r.method === 'POST');
    expect(req.request.body.name).toBe('Support GitHub');
    req.flush(aSystem({ id: 'sys-new', name: 'Support GitHub' }));
    await settle();
    expect(TestBed.inject(Router).url).toBe('/c/c1/systems/sys-new');
  });

  it('keeps a refusal without a field in the alert, on the step it came from', async () => {
    await open([]);
    await startRegistering();
    await pick('GIT');
    await next();
    typeInto(field('system-name'), 'Internal git');
    await register();
    http
      .expectOne((r) => r.url === SYSTEMS && r.method === 'POST')
      .flush(problem(403, 'FORBIDDEN'), { status: 403, statusText: 'Forbidden' });
    await settle();
    expect(currentStep()).toContain('Details');
    expect(text(byTestId('system-form-error'))).toBe(
      'Only owners and admins can register or change the company’s systems.',
    );
  });
});

describe('the change dialog', () => {
  @Component({
    imports: [SystemDialog],
    template: `<app-system-dialog [(open)]="open" [system]="system" />`,
  })
  class Host {
    open = true;
    system = aJiraSystem({ pollIntervalSeconds: 120 });
  }

  afterEach(() => document.querySelector('.cdk-overlay-container')?.replaceChildren());

  it('stays one form: no steps, no kind, the editable fields together and Save', async () => {
    TestBed.configureTestingModule({
      providers: [...provideTestHttp(), fakeCurrentCompany('ADMIN').provider],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.detectChanges();
    await settle();
    const dialog = document.querySelector<HTMLElement>('[data-testid=system-dialog]')!;
    expect(dialog.querySelector('[data-testid=system-steps]')).toBeNull();
    expect(dialog.querySelector('[data-testid=kind-picker]')).toBeNull();
    expect(dialog.querySelector('#system-name')).not.toBeNull();
    // The handle never changes: no field, and the tools keep their names whatever the name.
    expect(dialog.querySelector('#system-handle')).toBeNull();
    expect(dialog.querySelector('[data-testid=system-tools-prefix]')?.textContent).toContain(
      'jira_…',
    );
    expect(dialog.querySelector<HTMLInputElement>('#system-poll-interval')!.value).toBe('120');
    expect(document.querySelector('[data-testid=system-next]')).toBeNull();
    expect(document.querySelector('[data-testid=system-back]')).toBeNull();
    expect(document.querySelector('[data-testid=system-save]')?.textContent?.trim()).toBe('Save');
    // A tracker's base URL never changes: no field.
    expect(dialog.querySelector('#system-base-url')).toBeNull();
  });

  it('changes an MCP server’s endpoint, warning that its accounts and credentials go', async () => {
    TestBed.configureTestingModule({
      providers: [...provideTestHttp(), fakeCurrentCompany('ADMIN').provider],
    });
    const fixture = TestBed.createComponent(Host);
    fixture.componentInstance.system = NOTES;
    fixture.detectChanges();
    await settle();
    const dialog = document.querySelector<HTMLElement>('[data-testid=system-dialog]')!;
    const endpoint = dialog.querySelector<HTMLInputElement>('#system-base-url')!;
    expect(endpoint.value).toBe('https://notes.acme.test/mcp');
    expect(dialog.textContent).toContain('A new one later removes the server’s accounts');
    expect(dialog.querySelector('[data-testid=system-endpoint-warning]')).toBeNull();

    typeInto(endpoint, 'https://notes.example.test/mcp');
    fixture.detectChanges();
    await settle();
    const warning = dialog.querySelector('[data-testid=system-endpoint-warning]');
    expect(warning?.textContent).toContain('removes every account and credential');
    expect(warning?.getAttribute('role')).toBe('status');

    // Back to the endpoint it has: nothing to warn about.
    typeInto(endpoint, 'https://notes.acme.test/mcp');
    fixture.detectChanges();
    await settle();
    expect(dialog.querySelector('[data-testid=system-endpoint-warning]')).toBeNull();
  });
});
