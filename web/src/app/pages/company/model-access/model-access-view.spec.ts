import type { ModelSettings, ModelSettingsRequest } from '../../../api/generated/models';
import { aModelAccess, aModelSettings, AT } from '../../../testing/fixtures';
import {
  adminKeyRequired,
  budgetForm,
  budgetMeter,
  budgetPhrase,
  budgetUpdate,
  defaultsRailDetail,
  gatewayRailDetail,
  gatewayState,
  gatewayStatus,
  gatewaySteps,
  keyStatusPhrase,
  modelChoices,
  modelsPhrase,
  namesPhrase,
  parsePeriod,
  parseUsd,
  periodPhrase,
  reachPhrase,
  type SettingsChange,
  type SettingsForm,
  settingsChanges,
  settingsErrors,
  settingsForm,
  settingsRequest,
  settingsWas,
  shortUsd,
} from './model-access-view';

const NOT_CONFIGURED = aModelSettings({
  configured: false,
  relayEnabled: false,
  testedAt: null,
  lastError: null,
});

describe('the gateway’s state', () => {
  it.each<[string, Partial<ModelSettings>, string, string]>([
    [
      'not configured',
      { configured: false, relayEnabled: false, testedAt: null },
      'NOT_CONFIGURED',
      'warning',
    ],
    ['saved, never tested', { relayEnabled: false, testedAt: null }, 'UNTESTED', 'warning'],
    [
      'changed since its test',
      { relayEnabled: false, testedAt: AT.earlier, lastError: null },
      'CHANGED',
      'warning',
    ],
    [
      'failed its last test',
      { relayEnabled: false, testedAt: AT.earlier, lastError: 'connection refused' },
      'FAILED',
      'failed',
    ],
    ['tested and ready', { relayEnabled: true }, 'READY', 'ready'],
  ])('%s', (_, overrides, state, tone) => {
    const settings = aModelSettings(overrides);
    expect(gatewayState(settings)).toBe(state);
    expect(gatewayStatus(settings).tone).toBe(tone);
  });

  it('says every new conversation is refused until it is set up', () => {
    expect(gatewayStatus(NOT_CONFIGURED).text).toContain('every new conversation is refused');
  });

  it.each<[string, Partial<ModelSettings>, string, string, string]>([
    [
      'not set up',
      { configured: false, relayEnabled: false, testedAt: null },
      'Not set up',
      'Model access is not set up',
      'Not set up',
    ],
    [
      'never tested',
      { relayEnabled: false, testedAt: null },
      'Not tested',
      'Test the gateway',
      'Bundled · not tested',
    ],
    [
      'changed',
      { relayEnabled: false, testedAt: AT.earlier },
      'Not tested',
      'Test the gateway again',
      'Bundled · not tested',
    ],
    [
      'failed',
      { relayEnabled: false, lastError: 'down' },
      'Test failed',
      'The last test failed',
      'Bundled · test failed',
    ],
    ['ready', {}, 'Ready', 'Bundled LiteLLM', 'Bundled · ready'],
    ['ready, its own', { gateway: 'EXTERNAL' }, 'Ready', 'Your own LiteLLM', 'Your own · ready'],
  ])(
    'names its chip, its title and its line in the rail: %s',
    (_, overrides, chip, title, rail) => {
      const settings = aModelSettings(overrides);
      expect(gatewayStatus(settings)).toMatchObject({ chip, title });
      expect(gatewayRailDetail(settings)).toBe(rail);
    },
  );

  it.each<[string, Parameters<typeof gatewaySteps>[0], string[]]>([
    ['not set up', 'NOT_CONFIGURED', ['done', 'now', 'todo']],
    ['never tested', 'UNTESTED', ['done', 'done', 'now']],
    ['changed', 'CHANGED', ['done', 'done', 'now']],
    ['failed', 'FAILED', ['done', 'done', 'failed']],
  ])('walks the three steps: %s', (_, state, states) => {
    const steps = gatewaySteps(state, 'BUNDLED', '$50.00 per 30 days, every model');
    expect(steps.map((s) => s.state)).toEqual(states);
    expect(steps.map((s) => s.title)).toEqual(['Choose which LiteLLM', 'Set it up', 'Test it']);
    expect(steps[0].detail).toBe('Bundled, below');
    expect(steps[1].detail).toBe(
      'Saves the gateway and the defaults of every key: $50.00 per 30 days, every model',
    );
  });

  it('names the failure of the last test in a sentence', () => {
    const status = gatewayStatus(
      aModelSettings({
        relayEnabled: false,
        testedAt: AT.earlier,
        lastError: 'HTTP 401 from LiteLLM',
      }),
    );
    expect(status.text).toBe(
      'HTTP 401 from LiteLLM. Nothing is paid by a budget until a test passes.',
    );
  });
});

describe('the budget period', () => {
  it.each<[string, { count: number; unit: string } | null, string]>([
    ['30d', { count: 30, unit: 'd' }, '30 days'],
    ['1mo', { count: 1, unit: 'mo' }, 'month'],
    ['2w', { count: 2, unit: 'w' }, '2 weeks'],
    ['24h', { count: 24, unit: 'h' }, '24 hours'],
    ['0d', null, '0d'],
    ['30 days', null, '30 days'],
  ])('%s', (value, parsed, phrase) => {
    expect(parsePeriod(value)).toEqual(parsed);
    expect(periodPhrase(value)).toBe(phrase);
  });

  it('reads a budget with its period', () => {
    expect(budgetPhrase(50, '30d')).toBe('$50.00 per 30 days');
  });

  it.each<[number, string]>([
    [50, '$50'],
    [1500, '$1,500'],
    [12.5, '$12.50'],
  ])('says %d as short as it reads: %s', (amount, words) => {
    expect(shortUsd(amount)).toBe(words);
  });
});

describe('amounts as typed', () => {
  it.each<[string, number | null]>([
    ['50', 50],
    ['12.5', 12.5],
    ['$12.50', 12.5],
    [' 0 ', 0],
    ['1000000', 1_000_000],
    ['1000001', null],
    ['12.505', null],
    ['-1', null],
    ['', null],
    ['ten', null],
  ])('%j → %j', (value, amount) => {
    expect(parseUsd(value)).toBe(amount);
  });
});

describe('the settings form', () => {
  const external = aModelSettings({
    gateway: 'EXTERNAL',
    adminUrl: 'https://litellm.acme.test',
    adminKeySet: true,
  });

  it.each<[string, ModelSettings, Partial<SettingsForm>, boolean]>([
    ['the bundled gateway never takes a key', aModelSettings(), {}, false],
    [
      'an external one entered first',
      NOT_CONFIGURED,
      { gateway: 'EXTERNAL', adminUrl: 'https://x.test' },
      true,
    ],
    [
      'switched to from the bundled one',
      aModelSettings(),
      { gateway: 'EXTERNAL', adminUrl: 'https://x.test' },
      true,
    ],
    ['the same URL with a stored key', external, {}, false],
    ['a new URL', external, { adminUrl: 'https://other.acme.test' }, true],
    ['no key stored', { ...external, adminKeySet: false }, {}, true],
  ])('needs the admin key: %s', (_, settings, change, required) => {
    expect(adminKeyRequired({ ...settingsForm(settings), ...change }, settings)).toBe(required);
  });

  it('starts from the stored settings, the period split in two', () => {
    expect(
      settingsForm(aModelSettings({ budgetPeriod: '2w', defaultModels: ['gpt-5.4'] })),
    ).toEqual({
      gateway: 'BUNDLED',
      adminUrl: '',
      adminKey: '',
      budget: '50',
      periodCount: '2',
      periodUnit: 'w',
      modelsLimited: true,
      models: ['gpt-5.4'],
      thresholds: '80, 100',
      monthlyAlert: '',
    });
  });

  it('starts an unconfigured company without a bundled LiteLLM on its own', () => {
    expect(settingsForm({ ...NOT_CONFIGURED, bundledAvailable: false }).gateway).toBe('EXTERNAL');
  });

  it.each<[string, Partial<SettingsForm>, string[]]>([
    ['a valid form', {}, []],
    ['a budget that is no amount', { budget: 'lots' }, ['budget']],
    ['a period of zero', { periodCount: '0' }, ['period']],
    ['only some models, none picked', { modelsLimited: true, models: [] }, ['models']],
    [
      'an external gateway without a URL or key',
      { gateway: 'EXTERNAL', adminUrl: '' },
      ['adminUrl', 'adminKey'],
    ],
    [
      'an external gateway at a URL that is none',
      { gateway: 'EXTERNAL', adminUrl: 'litellm', adminKey: 'k' },
      ['adminUrl'],
    ],
  ])('checks %s', (_, change, fields) => {
    const settings = aModelSettings();
    expect(Object.keys(settingsErrors({ ...settingsForm(settings), ...change }, settings))).toEqual(
      fields,
    );
  });

  it('sends the defaults, and the URL and key only for an external gateway', () => {
    const stored = aModelSettings();
    const form = settingsForm(stored);
    expect(
      settingsRequest({ ...form, budget: '75.5', periodCount: '1', periodUnit: 'mo' }, stored),
    ).toEqual({
      gateway: 'BUNDLED',
      defaultBudgetUsd: 75.5,
      budgetPeriod: '1mo',
      defaultModels: [],
    });
    expect(
      settingsRequest(
        {
          ...form,
          gateway: 'EXTERNAL',
          adminUrl: ' https://litellm.acme.test ',
          adminKey: 'sk-master',
          modelsLimited: true,
          models: ['gpt-5.4'],
        },
        stored,
      ),
    ).toEqual({
      gateway: 'EXTERNAL',
      adminUrl: 'https://litellm.acme.test',
      adminKey: 'sk-master',
      defaultBudgetUsd: 50,
      budgetPeriod: '30d',
      defaultModels: ['gpt-5.4'],
    });
  });

  it.each<[string, Partial<ModelSettings>, Partial<SettingsForm>, Partial<ModelSettingsRequest>]>([
    ['nothing changed', {}, {}, {}],
    ['new thresholds, sorted', {}, { thresholds: '100, 50' }, { budgetAlertThresholds: [50, 100] }],
    ['a monthly alert set', {}, { monthlyAlert: '2,000' }, { monthlyAlertUsd: 2000 }],
    ['the monthly alert kept', { monthlyAlertUsd: 2000 }, { monthlyAlert: '2000' }, {}],
    [
      'the monthly alert emptied',
      { monthlyAlertUsd: 2000 },
      { monthlyAlert: ' ' },
      { clearMonthlyAlert: true },
    ],
    ['the thresholds as stored', { budgetAlertThresholds: [90] }, { thresholds: '90%' }, {}],
  ])('sends the budget alerts only when they change: %s', (_, stored, change, sent) => {
    const settings = aModelSettings(stored);
    const request = settingsRequest({ ...settingsForm(settings), ...change }, settings);
    expect(request).toEqual({
      gateway: 'BUNDLED',
      defaultBudgetUsd: 50,
      budgetPeriod: '30d',
      defaultModels: [],
      ...sent,
    });
  });

  it.each<[string, Partial<SettingsForm>, string[]]>([
    ['three thresholds', { thresholds: '50, 80, 100' }, []],
    ['four thresholds', { thresholds: '25, 50, 80, 100' }, ['thresholds']],
    ['none', { thresholds: '' }, ['thresholds']],
    ['one twice', { thresholds: '80, 80' }, ['thresholds']],
    ['above 100', { thresholds: '120' }, ['thresholds']],
    ['a monthly alert that is no amount', { monthlyAlert: 'lots' }, ['monthlyAlert']],
    ['no monthly alert', { monthlyAlert: '' }, []],
  ])('checks the alerts: %s', (_, change, fields) => {
    const settings = aModelSettings();
    const errors = settingsErrors({ ...settingsForm(settings), ...change }, settings);
    expect(Object.keys(errors).sort()).toEqual(fields);
  });

  it('keeps a stored key when none is typed, and every model when not limited', () => {
    const form = settingsForm(aModelSettings({ gateway: 'EXTERNAL', adminUrl: 'https://l.test' }));
    const request = settingsRequest({ ...form, models: ['gpt-5.4'], modelsLimited: false });
    expect(request.adminKey).toBeUndefined();
    expect(request.defaultModels).toEqual([]);
  });

  it('offers the served models, then the stored ones the gateway no longer serves', () => {
    expect(modelChoices(['a', 'b'], ['b', 'gone'])).toEqual([
      { name: 'a', served: true },
      { name: 'b', served: true },
      { name: 'gone', served: false },
    ]);
    expect(modelsPhrase([])).toBe('Every model the gateway serves');
    expect(modelsPhrase(['a', 'b'])).toBe('a, b');
  });
});

describe('a principal’s budget', () => {
  it.each<[string, Parameters<typeof aModelAccess>[0], string | null, string | null]>([
    ['a key in order', { keyStatus: 'OK' }, null, null],
    [
      'spent until its reset',
      { keyStatus: 'BUDGET_SPENT' },
      'destructive',
      'New turns refused until 1 Nov',
    ],
    ['blocked', { keyStatus: 'BLOCKED' }, 'muted', 'Blocked while suspended'],
    [
      'an error',
      { keyStatus: 'ERROR', lastError: 'key not found' },
      'destructive',
      'Key error: key not found',
    ],
    ['no key yet', { keyStatus: null }, 'muted', 'No key yet'],
    [
      'no gateway yet',
      { keyStatus: null, gatewayConfigured: false },
      'muted',
      'No key until the gateway passes its test',
    ],
  ])('reads %s', (_, overrides, tone, text) => {
    const phrase = keyStatusPhrase(aModelAccess(overrides));
    expect(phrase?.tone ?? null).toBe(tone);
    expect(phrase?.text ?? null).toBe(text);
  });

  it.each<[string, Parameters<typeof aModelAccess>[0], object]>([
    [
      'below 80 %',
      {},
      {
        state: 'ok',
        percent: 24,
        amount: '$11.80 of $50.00',
        label: '24%',
        note: 'Resets 1 Nov',
        keyNote: false,
      },
    ],
    [
      'from 80 %',
      { spentUsd: 40 },
      { state: 'near', percent: 80, note: 'Near the limit · resets 1 Nov', noteTone: 'warning' },
    ],
    [
      'spent, as the gateway counts it',
      { spentUsd: 50 },
      { state: 'spent', percent: 100, label: 'Spent', noteTone: 'destructive', keyNote: false },
    ],
    [
      'spent, as the key says',
      { spentUsd: 49.5, keyStatus: 'BUDGET_SPENT' },
      {
        state: 'spent',
        percent: 100,
        note: 'New turns refused until 1 Nov',
        noteTone: 'destructive',
        keyNote: true,
      },
    ],
    [
      'nothing spent yet',
      { spentUsd: null },
      { state: 'ok', percent: 0, amount: 'Nothing spent yet', label: '0%' },
    ],
    [
      'a budget of nothing',
      { budgetUsd: 0, spentUsd: 0 },
      { state: 'spent', amount: '$0.00 of $0.00' },
    ],
    [
      'a suspended key',
      { keyStatus: 'BLOCKED' },
      { state: 'ok', note: 'Blocked while suspended', noteTone: 'muted', keyNote: true },
    ],
    [
      'a key in error',
      { keyStatus: 'ERROR', lastError: 'key not found' },
      { note: 'Key error: key not found', noteTone: 'destructive', keyNote: true },
    ],
  ])('draws its spend bar: %s', (_, overrides, meter) => {
    expect(budgetMeter(aModelAccess(overrides))).toMatchObject(meter);
  });

  it('draws the bar against another budget, as a dialog types it', () => {
    expect(budgetMeter(aModelAccess(), 12)).toMatchObject({
      state: 'near',
      amount: '$11.80 of $12.00',
    });
  });

  const own = aModelAccess({
    budgetUsd: 20,
    budgetOverridden: true,
    models: ['gpt-5.4'],
    modelsOverridden: true,
  });

  it.each<
    [string, ReturnType<typeof aModelAccess>, Partial<ReturnType<typeof budgetForm>>, object | null]
  >([
    ['nothing changed on the default', aModelAccess(), {}, null],
    ['nothing changed on its own', own, {}, null],
    ['an own budget', aModelAccess(), { budgetOwn: true, budget: '20' }, { budgetUsd: 20 }],
    ['another own budget', own, { budget: '25.50' }, { budgetUsd: 25.5 }],
    ['back to the default budget', own, { budgetOwn: false }, { clearBudget: true }],
    ['own models', aModelAccess(), { modelsOwn: true, models: ['a'] }, { models: ['a'] }],
    [
      'the same own models in another order',
      { ...own, models: ['a', 'b'] },
      { models: ['b', 'a'] },
      null,
    ],
    ['back to the default models', own, { modelsOwn: false }, { clearModels: true }],
    [
      'both back to the defaults',
      own,
      { budgetOwn: false, modelsOwn: false },
      { clearBudget: true, clearModels: true },
    ],
  ])('sends %s', (_, view, change, body) => {
    expect(budgetUpdate(view, { ...budgetForm(view), ...change })).toEqual(body);
  });
});

describe('what changed', () => {
  const settings = aModelSettings({ budgetAlertThresholds: [80, 100], monthlyAlertUsd: 2000 });
  const form = settingsForm(settings);

  it.each<[string, Partial<SettingsForm>, SettingsChange[]]>([
    ['nothing', {}, []],
    [
      'the same amounts, typed otherwise',
      { budget: '50.00', thresholds: '100,80', monthlyAlert: '$2,000' },
      [],
    ],
    [
      'the gateway, its URL and its key',
      { gateway: 'EXTERNAL', adminUrl: 'https://l.test', adminKey: 'k' },
      ['gateway', 'adminUrl', 'adminKey'],
    ],
    ['the budget', { budget: '75' }, ['budget']],
    ['a budget that is no amount', { budget: 'lots' }, ['budget']],
    ['the period', { periodUnit: 'w' }, ['period']],
    ['a period of zero', { periodCount: '0' }, ['period']],
    ['the models', { modelsLimited: true, models: ['gpt-5.4'] }, ['models']],
    ['the thresholds', { thresholds: '50, 100' }, ['thresholds']],
    ['the monthly alert emptied', { monthlyAlert: '' }, ['monthlyAlert']],
    ['a monthly alert that is no amount', { monthlyAlert: 'lots' }, ['monthlyAlert']],
  ])('%s', (_, change, changes) => {
    expect(settingsChanges({ ...form, ...change }, settings)).toEqual(changes);
  });

  it('says what each changed setting was', () => {
    const changes: SettingsChange[] = [
      'gateway',
      'budget',
      'period',
      'models',
      'thresholds',
      'monthlyAlert',
    ];
    expect(settingsWas(changes, settings)).toEqual({
      gateway: 'Bundled',
      budget: '$50.00',
      period: '30 days',
      models: 'every model',
      thresholds: '80, 100',
      monthlyAlert: '$2,000.00',
    });
    expect(settingsWas(['monthlyAlert'], aModelSettings())).toEqual({ monthlyAlert: 'no alert' });
    expect(settingsWas(['budget'], NOT_CONFIGURED)).toEqual({});
  });

  it('sums the default budget up for the rail, as typed where it may be saved', () => {
    expect(defaultsRailDetail(form, settings)).toBe('$50 per 30 days · alerts at 80%, 100%');
    expect(
      defaultsRailDetail(
        { ...form, budget: '12.5', periodCount: '1', periodUnit: 'mo', thresholds: '90' },
        settings,
      ),
    ).toBe('$12.50 per month · alerts at 90%');
    expect(
      defaultsRailDetail({ ...form, budget: 'lots', periodCount: '0', thresholds: '' }, settings),
    ).toBe('$50 per 30 days · alerts at 80%, 100%');
  });
});

describe('the keys a change reaches', () => {
  const people = [
    { name: 'Alice', member: true, access: aModelAccess() },
    { name: 'Bob', member: true, access: aModelAccess({ budgetOverridden: true }) },
    { name: 'svc-reader', member: false, access: aModelAccess({ modelsOverridden: true }) },
    { name: 'Carol', member: true, access: aModelAccess({ keyStatus: null }) },
  ];

  it.each<[string, SettingsChange[], string | null]>([
    ['no change of the defaults', ['thresholds'], null],
    [
      'a new budget',
      ['budget'],
      'Saving reaches 2 keys at once: Alice and svc-reader. Bob keeps their own.',
    ],
    ['a new period', ['period'], 'Saving reaches 3 keys at once: Alice, Bob and svc-reader.'],
    [
      'new models',
      ['models'],
      'Saving reaches 2 keys at once: Alice and Bob. svc-reader keeps its own.',
    ],
  ])('%s', (_, changes, text) => {
    expect(reachPhrase(changes, people)).toBe(text);
  });

  it('reaches none while nobody has a key', () => {
    expect(reachPhrase(['budget'], [people[3]])).toBeNull();
  });

  it.each<[string[], string]>([
    [[], ''],
    [['A'], 'A'],
    [['A', 'B'], 'A and B'],
    [['A', 'B', 'C', 'D'], 'A, B, C and D'],
    [['A', 'B', 'C', 'D', 'E', 'F'], 'A, B, C and 3 more'],
  ])('names %j', (names, words) => {
    expect(namesPhrase(names)).toBe(words);
  });
});
