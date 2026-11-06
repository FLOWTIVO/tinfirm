import { HttpTestingController, type TestRequest } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type {
  Member,
  ModelAccessView,
  ModelSettings,
  ServiceIdentity,
} from '../../../api/generated/models';
import {
  aMember,
  aModelAccess,
  aModelSettings,
  aServiceIdentity,
  aSpendReport,
  AT,
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
import { words } from '../../../testing/words';
import { ModelAccess } from './model-access';

const BASE = '/api/companies/c1';
const SERVED = ['claude-sonnet-4-5', 'claude-haiku-4-5', 'gpt-5.4'];

describe('ModelAccess', () => {
  let fixture: ComponentFixture<ModelAccess>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  /** Opens the page and answers its reads: the settings, this month's spend, the budgets, the models. */
  async function open(
    settings: ModelSettings,
    options: {
      tab?: string;
      served?: string[] | 'unconfigured';
      members?: Member[];
      identities?: ServiceIdentity[];
      access?: (id: string) => ModelAccessView;
      /** The company key's model access; on the default by default. */
      companyKey?: ModelAccessView;
      spent?: number;
    } = {},
  ) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [ModelAccess],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany('ADMIN').provider,
        notifier.provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ModelAccess);
    if (options.tab) fixture.componentRef.setInput('tab', options.tab);
    el = fixture.nativeElement;
    await settle();
    http.expectOne(`${BASE}/model-settings`).flush(settings);
    answerSpend(options.spent ?? 212.8);
    answerCompanyKey(options.companyKey);
    await answerBudgets(options.members ?? [], options.identities ?? [], options.access);
    await settle();
    answerServed(options.served ?? SERVED);
    await settle();
    await fixture.whenStable();
  }

  /** The company's own key (ADR-0065): no principal's. */
  function aCompanyKey(overrides: Partial<ModelAccessView> = {}): ModelAccessView {
    return { ...aModelAccess({ spentUsd: 0.5, ...overrides }), principalId: undefined };
  }

  function answerCompanyKey(view: ModelAccessView = aCompanyKey()) {
    for (const req of http.match(`${BASE}/model-access/company-key`)) req.flush(view);
  }

  function answerSpend(costUsd: number) {
    const report = aSpendReport();
    http
      .expectOne((r) => r.url === `${BASE}/spend` && !r.params.has('groupBy'))
      .flush({ ...report, totals: { ...report.totals, costUsd } });
  }

  /** Answers the members' and the service accounts' reads, and each principal's model access. */
  async function answerBudgets(
    members: Member[] = [],
    identities: ServiceIdentity[] = [],
    access: (id: string) => ModelAccessView = (id) => aModelAccess({ principalId: id }),
  ) {
    http.expectOne((r) => r.url === `${BASE}/members`).flush({ items: members });
    http.expectOne((r) => r.url === `${BASE}/service-identities`).flush({ items: identities });
    answerCompanyKey();
    await settle();
    const principals = [
      ...members.flatMap((m) => (m.principalId ? [m.principalId] : [])),
      ...identities.map((i) => i.id),
    ];
    for (const id of principals) {
      http.expectOne(`${BASE}/principals/${id}/model-access`).flush(access(id));
    }
    await settle();
  }

  function answerServed(served: string[] | 'unconfigured') {
    const req: TestRequest = http.expectOne(`${BASE}/model-settings/models`);
    if (served === 'unconfigured') {
      req.flush(problem(409, 'GATEWAY_NOT_CONFIGURED'), { status: 409, statusText: 'Conflict' });
    } else {
      req.flush({ models: served });
    }
  }

  async function render() {
    await settle();
    await fixture.whenStable();
  }

  const q = (testId: string, root: ParentNode = el) =>
    root.querySelector<HTMLElement>(`[data-testid="${testId}"]`);
  const text = words;
  const button = (label: string, root: ParentNode = el) =>
    Array.from(root.querySelectorAll('button')).find((b) => text(b) === label);
  const panel = (id: string) => el.querySelector<HTMLElement>(`[data-panel="${id}"]`)!;
  const railItem = (id: string) =>
    el.querySelector<HTMLAnchorElement>(
      `[data-testid="model-access-sections"] [data-section="${id}"]`,
    )!;
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;

  afterEach(() => {
    answerCompanyKey();
    http.verify();
    overlay()?.replaceChildren();
  });

  it.each<[string, Partial<ModelSettings>, string, string, string, 'unconfigured' | string[]]>([
    [
      'not set up',
      { configured: false, relayEnabled: false, testedAt: null },
      'NOT_CONFIGURED',
      'Not set up Model access is not set up',
      'Until a gateway is set up and passes its test, every new conversation is refused',
      'unconfigured',
    ],
    [
      'saved and never tested',
      { relayEnabled: false, testedAt: null },
      'UNTESTED',
      'Not tested Test the gateway',
      'It is saved but not tested yet.',
      'unconfigured',
    ],
    [
      'changed since its test',
      { relayEnabled: false, testedAt: AT.earlier },
      'CHANGED',
      'Not tested Test the gateway again',
      'changed since its last test',
      'unconfigured',
    ],
    [
      'failed its test',
      { relayEnabled: false, testedAt: AT.earlier, lastError: 'connection refused' },
      'FAILED',
      'Test failed The last test failed',
      'connection refused. Nothing is paid by a budget until a test passes.',
      'unconfigured',
    ],
    [
      'ready',
      { relayEnabled: true },
      'READY',
      'Ready Bundled LiteLLM',
      'The gateway passed its test: turns paid by a budget may start.',
      SERVED,
    ],
  ])('says where the gateway stands: %s', async (_, overrides, state, title, line, served) => {
    await open(aModelSettings(overrides), { served });
    const status = q('gateway-status')!;
    expect(status.getAttribute('data-state')).toBe(state);
    expect(text(status)).toContain(title);
    expect(text(status)).toContain(line);
    // Never orange: the steps say what to do, in warning or destructive.
    expect(el.querySelector('.text-human, .border-human, .bg-human')).toBeNull();
    if (state === 'READY') {
      expect(q('gateway-steps')).toBeNull();
      expect(text(q('served'))).toBe('Serves claude-sonnet-4-5 claude-haiku-4-5 gpt-5.4');
    } else {
      expect(
        Array.from(q('gateway-steps')!.querySelectorAll('li')).map((li) => li.dataset['step']),
      ).toHaveLength(3);
    }
  });

  it('sets up a company that has no gateway yet: Test waits for the first save', async () => {
    await open(aModelSettings({ configured: false, relayEnabled: false, testedAt: null }), {
      served: 'unconfigured',
    });
    expect((q('test-gateway') as HTMLButtonElement).disabled).toBe(true);
    expect(text(q('setup-bar'))).toBe(
      'Not set up yet Bundled LiteLLM · defaults $50.00 per 30 days Set up model access',
    );
    expect(text(q('save-model-settings'))).toBe('Set up model access');
    expect(text(q('models-note'))).toBe('The gateway lists its models once it passes its test.');
    expect(
      Array.from(q('gateway-steps')!.querySelectorAll('li')).map((li) => li.dataset['step']),
    ).toEqual(['done', 'now', 'todo']);
    expect(text(railItem('gateway'))).toBe('Gateway Not set up');
    expect(text(railItem('budgets'))).toBe('Members’ budgets No keys until a test passes');

    submitForm(el.querySelector('#model-settings-form'));
    await settle();
    const put = http.expectOne({ method: 'PUT', url: `${BASE}/model-settings` });
    expect(put.request.body).toEqual({
      gateway: 'BUNDLED',
      defaultBudgetUsd: 50,
      budgetPeriod: '30d',
      defaultModels: [],
    });
    put.flush(aModelSettings({ relayEnabled: false, testedAt: null }));
    await settle();
    await answerBudgets();
    await render();
    expect(notify.success).toHaveBeenCalledWith(
      'Model access saved',
      'Test the gateway: nothing is paid by a budget until a test passes.',
    );
    expect(q('gateway-status')!.getAttribute('data-state')).toBe('UNTESTED');
    expect(q('setup-bar')).toBeNull();
    expect((q('test-gateway') as HTMLButtonElement).disabled).toBe(false);
  });

  it('asks an external gateway for its URL and key once a save is tried', async () => {
    await open(aModelSettings());
    el.querySelector<HTMLInputElement>('#gateway-EXTERNAL')!.click();
    await render();
    expect(text(q('admin-key-state'))).toContain('Kept in the company’s vault');
    expect(text(q('was', panel('gateway')))).toBe('was Bundled');
    // Nothing is marked before a save is tried.
    expect(el.querySelector('#gateway-admin-url-error')).toBeNull();
    expect(text(q('unsaved-names'))).toBe('Gateway');
    expect(railItem('gateway').querySelector('[data-testid=unsaved-dot]')).not.toBeNull();
    // The test waits for the change to be saved.
    expect((q('test-gateway') as HTMLButtonElement).disabled).toBe(true);
    expect(text(q('gateway-status'))).toContain('Save your changes, then test them.');

    submitForm(el.querySelector('#model-settings-form'));
    await render();
    expect(text(el.querySelector('#gateway-admin-url-error'))).toBe(
      'Enter the admin URL of your LiteLLM.',
    );
    expect(text(el.querySelector('#gateway-admin-key-error'))).toBe(
      'Enter the master or admin key: it goes with the URL.',
    );
    expect(text(q('unsaved-names'))).toBe('Fix the marked fields first.');
    expect((q('save-model-settings') as HTMLButtonElement).disabled).toBe(true);

    typeInto(el.querySelector('#gateway-admin-url'), 'https://litellm.acme.test');
    typeInto(el.querySelector('#gateway-admin-key'), 'sk-master');
    await render();
    expect(text(q('unsaved-names'))).toBe('Gateway · Admin URL · Admin key');
    submitForm(el.querySelector('#model-settings-form'));
    await settle();
    const put = http.expectOne({ method: 'PUT', url: `${BASE}/model-settings` });
    expect(put.request.body).toMatchObject({
      gateway: 'EXTERNAL',
      adminUrl: 'https://litellm.acme.test',
      adminKey: 'sk-master',
    });
    put.flush(problem(400, 'INVALID_BASE_URL', 'The admin URL is not usable: a private address'), {
      status: 400,
      statusText: 'Bad Request',
    });
    await render();
    expect(text(q('save-error'))).toBe(
      'Tinfirm may not connect to this address. The admin URL is not usable: a private address.',
    );
  });

  it('limits the default models to those picked, and says which keys the change reaches', async () => {
    await open(aModelSettings(), {
      tab: 'defaults',
      members: [aMember({ principalId: IDS.alice })],
      identities: [aServiceIdentity()],
      access: (id) =>
        id === IDS.svcReader
          ? aModelAccess({ principalId: id, budgetUsd: 10, budgetOverridden: true })
          : aModelAccess({ principalId: id }),
    });
    expect(railItem('defaults').getAttribute('aria-current')).toBe('page');
    expect(panel('gateway').hidden).toBe(true);
    expect(panel('defaults').hidden).toBe(false);
    el.querySelector<HTMLInputElement>('#default-models-only')!.click();
    await render();
    const boxes = Array.from(
      el.querySelectorAll<HTMLInputElement>('[aria-label="Default models"] input'),
    );
    expect(boxes).toHaveLength(3);
    boxes[2].click();
    typeInto(el.querySelector('#default-budget'), '20');
    await render();
    expect(text(q('unsaved-names'))).toBe('Default budget · Default models');
    expect(Array.from(panel('defaults').querySelectorAll('[data-testid=was]')).map(text)).toEqual([
      'was $50.00',
      'was every model',
    ]);
    expect(text(q('reach'))).toBe(
      'Saving reaches 3 keys at once: Alice Novak, svc-reader and Company key.',
    );
    expect(text(railItem('defaults'))).toBe(
      'Default budget, unsaved changes $20 per 30 days · alerts at 80%, 100%',
    );

    submitForm(el.querySelector('#model-settings-form'));
    await settle();
    const put = http.expectOne({ method: 'PUT', url: `${BASE}/model-settings` });
    expect(put.request.body).toEqual({
      gateway: 'BUNDLED',
      defaultBudgetUsd: 20,
      budgetPeriod: '30d',
      defaultModels: ['gpt-5.4'],
    });
    put.flush(aModelSettings({ defaultBudgetUsd: 20, defaultModels: ['gpt-5.4'] }));
    await settle();
    await answerBudgets([aMember({ principalId: IDS.alice })], [aServiceIdentity()], (id) =>
      aModelAccess({ principalId: id }),
    );
    await render();
    expect(notify.success).toHaveBeenCalledWith(
      'Model access saved',
      'Every key without its own values follows the new defaults.',
    );
    expect(q('unsaved-count')).toBeNull();
  });

  it('discards what was typed', async () => {
    await open(aModelSettings());
    typeInto(el.querySelector('#default-budget'), '75');
    await render();
    expect(text(q('unsaved-count'))).toBe('1 unsaved change');
    button('Discard')!.click();
    await render();
    expect(el.querySelector<HTMLInputElement>('#default-budget')!.value).toBe('50');
    expect(q('unsaved-count')).toBeNull();
  });

  it.each<[string, (req: TestRequest) => void, Partial<ModelSettings>, string]>([
    [
      'passes',
      (req) => req.flush({ ok: true, models: SERVED }),
      { testedAt: AT.start },
      'LiteLLM answered and serves 3 models. Turns paid by a budget may start.',
    ],
    [
      'fails',
      (req) =>
        req.flush(
          problem(
            502,
            'GATEWAY_UNREACHABLE',
            'LiteLLM did not answer as expected: LiteLLM is down',
          ),
          { status: 502, statusText: 'Bad Gateway' },
        ),
      { relayEnabled: false, testedAt: AT.start, lastError: 'LiteLLM is down' },
      'Tinfirm could not reach the model gateway. LiteLLM did not answer as expected: LiteLLM is down. Nothing is paid by a budget until a test passes.',
    ],
  ])(
    'tests the gateway, which %s, and reads the settings again',
    async (_, answer, after, line) => {
      await open(aModelSettings());
      typeInto(el.querySelector('#monthly-alert'), '900');
      await render();
      q('test-gateway')!.click();
      await settle();
      answer(http.expectOne({ method: 'POST', url: `${BASE}/model-settings/test` }));
      await settle();
      // A test changes `testedAt`, and the models are read again.
      http.expectOne(`${BASE}/model-settings`).flush(aModelSettings(after));
      await answerBudgets();
      answerServed(after.relayEnabled === false ? 'unconfigured' : SERVED);
      await render();
      expect(text(q('test-result'))).toBe(line);
      // What was typed in another section stays.
      expect(el.querySelector<HTMLInputElement>('#monthly-alert')!.value).toBe('900');
    },
  );

  it('lists every member and service account in one list, and changes a budget', async () => {
    await open(aModelSettings(), {
      tab: 'budgets',
      members: [
        aMember({ principalId: IDS.alice }),
        aMember({
          userId: IDS.bobUser,
          displayName: 'Bob Marek',
          email: 'bob@acme.test',
          role: 'ADMIN',
          principalId: IDS.bob,
          suspended: true,
        }),
        // Without a principal there is no key, so no budget to show.
        aMember({
          userId: IDS.carolUser,
          displayName: 'Carol Dvorak',
          email: 'carol@acme.test',
          principalId: null,
        }),
      ],
      identities: [
        aServiceIdentity(),
        aServiceIdentity({ id: 'p-svc-ops', name: 'svc-ops', status: 'SUSPENDED' }),
      ],
      access: (id) => {
        switch (id) {
          case IDS.alice:
            return aModelAccess({ principalId: id, spentUsd: 45 });
          case IDS.bob:
            return aModelAccess({
              principalId: id,
              budgetUsd: 5,
              budgetOverridden: true,
              keyStatus: 'BLOCKED',
              spentUsd: 1,
            });
          case 'p-svc-ops':
            return aModelAccess({
              principalId: id,
              budgetUsd: 10,
              budgetOverridden: true,
              spentUsd: 10,
              keyStatus: 'BUDGET_SPENT',
              models: ['gpt-5.4'],
              modelsOverridden: true,
            });
          default:
            return aModelAccess({ principalId: id, spentUsd: 4.2 });
        }
      },
    });
    const budgets = q('budgets')!;
    expect(budgets.hidden).toBe(false);
    expect(text(budgets)).toContain('Service accounts are listed here too, and the company key');
    expect(q('member-budget-carol@acme.test')).toBeNull();
    // Near or past the limit first, then their own, then the default.
    expect(
      Array.from(budgets.querySelectorAll('li')).map((li) => li.getAttribute('data-testid')),
    ).toEqual([
      'identity-budget-svc-ops',
      'member-budget-alice@acme.test',
      'member-budget-bob@acme.test',
      'company-budget',
      'identity-budget-svc-reader',
    ]);
    const ops = q('identity-budget-svc-ops')!;
    expect(ops.getAttribute('data-state')).toBe('spent');
    expect(text(q('budget', ops))).toBe('Own budget');
    expect(text(q('models', ops))).toBe('gpt-5.4 only');
    expect(text(q('spend', ops))).toBe('$10.00 of $10.00');
    expect(text(q('key-status', ops))).toBe('New turns refused until 1 Nov');
    expect(q('key-status', ops)!.classList).toContain('text-destructive');
    expect(text(ops)).toContain('Suspended');
    const alice = q('member-budget-alice@acme.test')!;
    expect(alice.getAttribute('data-state')).toBe('near');
    expect(text(alice)).toContain('Member · every model');
    expect(text(q('budget-note', alice))).toBe('Near the limit · resets 1 Nov');
    expect(alice.querySelector('[role=meter]')!.getAttribute('aria-valuenow')).toBe('90');
    const bob = q('member-budget-bob@acme.test')!;
    expect(text(bob)).toContain('Admin · every model');
    expect(text(q('key-status', bob))).toBe('Blocked while suspended');
    expect(text(railItem('budgets'))).toBe(
      'Members’ budgets 2 of their own · 1 near its limit · 1 spent',
    );

    const reader = q('identity-budget-svc-reader')!;
    expect(text(q('budget', reader))).toBe('The default');
    button('Change budget', reader)!.click();
    await render();
    expect(text(document.querySelector('[hlmDialogTitle]'))).toBe('Budget of svc-reader');
    document.querySelector<HTMLInputElement>('#budget-own')!.click();
    await render();
    typeInto(document.querySelector('#budget-amount'), '25');
    submitForm(document.querySelector('#principal-budget-form'));
    await settle();
    const patch = http.expectOne({
      method: 'PATCH',
      url: `${BASE}/principals/${IDS.svcReader}/model-access`,
    });
    expect(patch.request.body).toEqual({ budgetUsd: 25 });
    patch.flush(
      aModelAccess({
        principalId: IDS.svcReader,
        budgetUsd: 25,
        budgetOverridden: true,
        spentUsd: 4.2,
      }),
    );
    await render();
    expect(text(q('budget', q('identity-budget-svc-reader')!))).toBe('Own budget');
    expect(text(q('spend', q('identity-budget-svc-reader')!))).toBe('$4.20 of $25.00');
    expect(notify.success).toHaveBeenCalledWith(
      'Budget of svc-reader saved',
      '$25.00 per 30 days.',
    );
  });

  it('lists the company key with its own budget, and changes it', async () => {
    await open(aModelSettings(), {
      tab: 'budgets',
      companyKey: aCompanyKey({
        spentUsd: 1.5,
        models: ['claude-haiku-4-5'],
        modelsOverridden: true,
      }),
    });
    const row = q('company-budget')!;
    expect(text(row)).toContain('Company key');
    expect(text(row)).toContain('Tinfirm’s own requests · claude-haiku-4-5 only');
    expect(text(q('budgets')!)).toContain('the company key, which pays for Tinfirm’s own requests');
    expect(q('identity-budget-Company key')).toBeNull();

    button('Change budget', row)!.click();
    await render();
    expect(text(document.querySelector('[hlmDialogTitle]'))).toBe('Budget of the company key');
    document.querySelector<HTMLInputElement>('#budget-own')!.click();
    await render();
    typeInto(document.querySelector('#budget-amount'), '5');
    submitForm(document.querySelector('#principal-budget-form'));
    await settle();
    const patch = http.expectOne({ method: 'PATCH', url: `${BASE}/model-access/company-key` });
    expect(patch.request.body).toEqual({ budgetUsd: 5 });
    patch.flush(aCompanyKey({ budgetUsd: 5, budgetOverridden: true, spentUsd: 1.5 }));
    await render();
    expect(text(q('budget', q('company-budget')!))).toBe('Own budget');
    expect(text(q('spend', q('company-budget')!))).toBe('$1.50 of $5.00');
    expect(notify.success).toHaveBeenCalledWith(
      'Budget of the company key saved',
      '$5.00 per 30 days.',
    );
  });

  it('filters the budgets and finds a name', async () => {
    await open(aModelSettings(), {
      tab: 'budgets',
      members: [
        aMember({ principalId: IDS.alice }),
        aMember({
          userId: IDS.bobUser,
          displayName: 'Bob Marek',
          email: 'bob@acme.test',
          principalId: IDS.bob,
        }),
      ],
      access: (id) =>
        id === IDS.bob
          ? aModelAccess({ principalId: id, budgetUsd: 20, budgetOverridden: true })
          : aModelAccess({ principalId: id }),
    });
    const listed = () =>
      Array.from(q('budgets')!.querySelectorAll('li')).map((li) => li.getAttribute('data-testid'));
    const filters = Array.from(q('budget-filter')!.querySelectorAll('label')).map(text);
    expect(filters).toEqual(['All 3', 'Their own budget 1', 'Near the limit 0', 'Spent 0']);

    el.querySelector<HTMLInputElement>('#budget-filter-own')!.click();
    await render();
    expect(listed()).toEqual(['member-budget-bob@acme.test']);

    el.querySelector<HTMLInputElement>('#budget-filter-all')!.click();
    typeInto(q('budget-search'), 'alice');
    await render();
    expect(listed()).toEqual(['member-budget-alice@acme.test']);

    typeInto(q('budget-search'), 'nobody');
    await render();
    expect(text(q('budgets-filtered-empty'))).toContain('No budget matches');
    button('Clear', q('budgets-filtered-empty')!)!.click();
    await render();
    expect(listed()).toHaveLength(3);
  });

  it('checks the budget alerts as they are typed, and sends the changed ones', async () => {
    await open(aModelSettings({ budgetAlertThresholds: [80, 100], monthlyAlertUsd: 2000 }), {
      tab: 'defaults',
    });
    const thresholds = el.querySelector<HTMLInputElement>('#alert-thresholds')!;
    expect(thresholds.value).toBe('80, 100');
    expect(el.querySelector<HTMLInputElement>('#monthly-alert')!.value).toBe('2000');
    expect(text(q('month-so-far'))).toBe('This month so far: $212.80');

    typeInto(thresholds, '80, 80');
    await render();
    expect(text(q('alerts'))).toContain('Enter 1 to 3 different percentages');
    expect(text(q('unsaved-names'))).toBe('Fix the marked fields first.');
    expect((q('save-model-settings') as HTMLButtonElement).disabled).toBe(true);
    http.expectNone({ method: 'PUT', url: `${BASE}/model-settings` });

    typeInto(thresholds, '50, 100');
    typeInto(el.querySelector('#monthly-alert'), '');
    await render();
    expect(text(q('unsaved-names'))).toBe('Alert thresholds · Monthly alert');
    expect(Array.from(q('alerts')!.querySelectorAll('[data-testid=was]')).map(text)).toEqual([
      'was 80, 100',
      'was $2,000.00',
    ]);
    // The alerts reach no key.
    expect(q('reach')).toBeNull();
    q('save-model-settings')!.click();
    await settle();
    const req = http.expectOne({ method: 'PUT', url: `${BASE}/model-settings` });
    expect(req.request.body).toEqual({
      gateway: 'BUNDLED',
      defaultBudgetUsd: 50,
      budgetPeriod: '30d',
      defaultModels: [],
      budgetAlertThresholds: [50, 100],
      clearMonthlyAlert: true,
    });
    req.flush(aModelSettings({ budgetAlertThresholds: [50, 100], monthlyAlertUsd: null }));
    await settle();
    await answerBudgets();
    await render();
  });

  it('says how each section stands in the rail, and links each by its tab', async () => {
    await open(aModelSettings({ monthlyAlertUsd: null }), { spent: 0 });
    expect(
      Array.from(el.querySelectorAll('[data-testid="model-access-sections"] a')).map((a) => [
        text(a),
        a.getAttribute('href'),
        a.getAttribute('aria-current'),
      ]),
    ).toEqual([
      ['Gateway Bundled · ready', '/', 'page'],
      ['Default budget $50 per 30 days · alerts at 80%, 100%', '/?tab=defaults', null],
      ['Members’ budgets 0 of their own', '/?tab=budgets', null],
      ['Spend Nothing spent this month', '/?tab=spend', null],
    ]);
  });

  it('opens the spend from a link, ?tab=spend', async () => {
    TestBed.configureTestingModule({
      imports: [ModelAccess],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany('ADMIN').provider,
        fakeNotify().provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ModelAccess);
    fixture.componentRef.setInput('tab', 'spend');
    el = fixture.nativeElement;
    // The page's reads and the spend view's, each answered by what it asks.
    for (let round = 0; round < 4; round++) {
      await settle();
      for (const req of http.match(() => true)) {
        const url = req.request.url;
        if (url.endsWith('/model-settings')) req.flush(aModelSettings());
        else if (url.endsWith('/model-settings/models')) req.flush({ models: SERVED });
        else if (url.endsWith('/spend')) req.flush(aSpendReport());
        else if (url.endsWith('/model-access/company-key')) req.flush(aCompanyKey());
        else req.flush({ items: [] });
      }
    }
    await render();
    expect(railItem('spend').getAttribute('aria-current')).toBe('page');
    expect(panel('spend').hidden).toBe(false);
    expect(text(panel('spend'))).toContain('What the agents’ turns cost in this company');
    expect(q('spend-table', panel('spend'))).not.toBeNull();
    expect(panel('gateway').hidden).toBe(true);
    expect(q('budgets')!.hidden).toBe(true);
  });

  it('reads the spend only once its section is opened', async () => {
    await open(aModelSettings());
    expect(el.querySelector('app-spend-view')).toBeNull();
  });

  it('says how service accounts get a budget when there is none', async () => {
    await open(aModelSettings(), { tab: 'budgets' });
    expect(text(q('no-identities'))).toContain('No service accounts yet.');
  });

  it('shows the problem when the settings cannot be read', async () => {
    TestBed.configureTestingModule({
      imports: [ModelAccess],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany('ADMIN').provider,
        fakeNotify().provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ModelAccess);
    el = fixture.nativeElement;
    await settle();
    http
      .expectOne(`${BASE}/model-settings`)
      .flush(problem(403), { status: 403, statusText: 'Forbidden' });
    answerSpend(0);
    http.expectOne((r) => r.url === `${BASE}/members`).flush({ items: [] });
    http.expectOne((r) => r.url === `${BASE}/service-identities`).flush({ items: [] });
    answerCompanyKey();
    await render();
    expect(text(el)).toContain('Only owners and admins see model access.');
  });
});
