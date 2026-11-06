import type { TestRequest } from '@angular/common/http/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type { Trigger, TriggerPreview } from '../../../api/generated/models';
import {
  aGroupSummary,
  aJiraSystem,
  anAgent,
  anAgentSettings,
  anAgentSummary,
  anOpenToAnyone,
  aModelAccess,
  aPersonalMcpServer,
  aSystem,
  aTrigger,
  aTriggerPreview,
  IDS,
  someTriggerChoices,
} from '../../../testing/fixtures';
import { EDITOR_MODE_STORAGE_KEY } from '../../../shared/markdown-editor';
import { type Answer, Refuse, serve } from '../../../testing/serve';
import {
  fakeCurrentCompany,
  fakeNotify,
  provideTestHttp,
  settle,
  typeInto,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { TRIGGER_PREVIEW_DEBOUNCE, TriggerEditor } from './trigger-editor';

const API = '/api/companies/c1';
const TRIGGER = `${API}/triggers/t-prepare`;

/** What the editor reads, answered as the server would for Alice. */
function routes(overrides: Record<string, Answer> = {}): Record<string, Answer> {
  return {
    [`GET ${API}/agents`]: { items: [anAgentSummary()] },
    [`GET ${API}/agents/${IDS.agent}`]: anAgent(),
    [`GET ${API}/systems`]: { items: [aJiraSystem(), aSystem(), aPersonalMcpServer()] },
    [`GET ${API}/systems/${IDS.crm}/tools`]: { items: [] },
    [`GET ${API}/groups`]: { items: [aGroupSummary()] },
    [`GET ${API}/settings`]: {
      triggerFiringsPerHour: { default: 30, max: 300 },
      triggerSubscriptions: true,
      version: 1,
    },
    [`GET ${API}/model-settings/models`]: { models: ['claude-sonnet'] },
    [`GET ${API}/me/model-access`]: aModelAccess(),
    [`GET ${API}/triggers/choices`]: someTriggerChoices(),
    [`POST ${API}/triggers/preview`]: aTriggerPreview(),
    ...overrides,
  };
}

describe('TriggerEditor', () => {
  let fixture: ComponentFixture<TriggerEditor>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let served: TestRequest[];
  /** The routes `open` was given: later answers keep them unless they say otherwise. */
  let given: Record<string, Answer> = {};

  async function open(
    options: { triggerId?: string; routes?: Record<string, Answer> } = {},
  ): Promise<void> {
    TestBed.configureTestingModule({
      imports: [TriggerEditor],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        { provide: TRIGGER_PREVIEW_DEBOUNCE, useValue: 0 },
        fakeCurrentCompany('MEMBER').provider,
        fakeNotify().provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TriggerEditor);
    if (options.triggerId) fixture.componentRef.setInput('triggerId', options.triggerId);
    else fixture.componentRef.setInput('agentId', IDS.agent);
    el = fixture.nativeElement;
    given = options.routes ?? {};
    served = await serve(http, routes(given));
  }

  async function answer(overrides: Record<string, Answer> = {}): Promise<TestRequest[]> {
    const more = await serve(http, routes({ ...given, ...overrides }));
    served.push(...more);
    return more;
  }

  // The instruction's editor shows its Markdown source, which a test can type into.
  beforeEach(() => localStorage.setItem(EDITOR_MODE_STORAGE_KEY, 'markdown'));
  afterEach(() => {
    localStorage.removeItem(EDITOR_MODE_STORAGE_KEY);
    http.verify();
  });

  const $ = <T extends Element = HTMLElement>(selector: string) => el.querySelector<T>(selector);
  const save = () => $<HTMLButtonElement>('[data-testid=save-trigger]')!;
  const tab = (id: string) => $<HTMLElement>(`#section-tab-${id}`)!;
  /** What a section's tab says it holds. */
  const summary = (id: string) => words(tab(id).querySelector('[data-summary]'));
  /** The section the page shows: the one panel not hidden. */
  const openSection = () =>
    $('[role=tabpanel][id^="section-panel-"]:not([hidden])')?.id.replace('section-panel-', '');
  const key = (target: HTMLElement, name: string) =>
    target.dispatchEvent(new KeyboardEvent('keydown', { key: name, bubbles: true }));

  /** A narrow screen: the sections and the open one are one screen each, as on a phone. */
  function onAPhone(): () => void {
    const original = window.matchMedia;
    window.matchMedia = vi.fn().mockReturnValue({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    }) as unknown as typeof window.matchMedia;
    return () => (window.matchMedia = original);
  }

  async function fillTheRule(): Promise<void> {
    typeInto($('#trigger-name'), 'Prepare assigned issues');
    await settle();
    const system = $<HTMLSelectElement>('#trigger-system')!;
    system.value = IDS.jira;
    system.dispatchEvent(new Event('change'));
    await settle();
    typeInto($('#trigger-instruction'), 'Prepare it.');
    await answer();
  }

  it('fills the account table from the one choice, service accounts first', async () => {
    await open();
    await fillTheRule();
    const choices = served.filter((r) => r.request.url.endsWith('/triggers/choices'));
    expect(choices.at(-1)?.request.params.get('systemId')).toBe(IDS.jira);
    expect(choices.at(-1)?.request.params.get('agentId')).toBe(IDS.agent);

    // The first choice fills the table: svc-reader's Jira and GitHub accounts, its budget.
    expect($<HTMLInputElement>(`#trigger-choice-${IDS.svcReader}`)!.checked).toBe(true);
    expect($<HTMLSelectElement>(`#trigger-row-${IDS.jira}`)!.value).toBe(
      `${IDS.svcReader}:acc-svc-reader-jira`,
    );
    expect($<HTMLSelectElement>(`#trigger-row-${IDS.github}`)!.value).toBe(
      `${IDS.svcReader}:${IDS.readerGithubAccount}`,
    );
    expect($<HTMLSelectElement>('#trigger-payer')!.value).toBe(IDS.svcReader);
    // The source's row is required: it offers no "not available".
    const jiraOptions = [...$<HTMLSelectElement>(`#trigger-row-${IDS.jira}`)!.options].map(
      (o) => o.value,
    );
    expect(jiraOptions).not.toContain('');

    // Picking "You" fills it again with Alice's own accounts and budget.
    $<HTMLInputElement>(`#trigger-choice-${IDS.alice}`)!.click();
    await answer();
    expect($<HTMLSelectElement>(`#trigger-row-${IDS.jira}`)!.value).toBe(
      `${IDS.alice}:acc-alice-jira`,
    );
    expect($<HTMLSelectElement>('#trigger-payer')!.value).toBe(IDS.alice);
    const preview = served.filter((r) => r.request.url.endsWith('/triggers/preview')).at(-1)!;
    expect(preview.request.body.draft).toMatchObject({
      accounts: [
        { systemId: IDS.jira, accountId: 'acc-alice-jira' },
        { systemId: IDS.github, accountId: 'acc-alice-github' },
      ],
      payerPrincipalId: IDS.alice,
    });

    // The preview: the sentence, under the title and beside the sections, and who acts and pays.
    expect(words($('[data-testid=trigger-sentence]'))).toBe(
      'When an issue in SHOP is assigned to svc-reader, Business analyst prepares it.',
    );
    expect(words($('[data-testid=preview-sentence]'))).toContain(
      'When an issue in SHOP is assigned to svc-reader',
    );
    expect(words($('[data-testid=who-acts-who-pays]'))).toContain(
      'Jira: svc-reader with reader-bot',
    );
    expect(words($('[data-testid=save-status]'))).toBe(
      'It fires from now on; nothing before counts.',
    );
  });

  it('shows one section at a time, each tab saying how it stands and what it holds', async () => {
    await open();
    expect(openSection()).toBe('when');
    expect(tab('when').getAttribute('aria-selected')).toBe('true');
    expect(tab('agent').getAttribute('tabindex')).toBe('-1');
    const rows = () =>
      [...el.querySelectorAll<HTMLElement>('[role=tab][id^="section-tab-"]')].map((t) => [
        t.dataset['state'],
        words(el.querySelector(`#${t.id}-label`)),
        words(t.querySelector('[data-summary]')),
      ]);
    expect(rows()).toEqual([
      ['todo', 'When', 'Pick the system it watches'],
      ['todo', 'The agent does', 'Business analyst · no instruction yet'],
      ['todo', 'Acts as and pays', 'No account yet · nobody pays yet'],
      ['todo', 'Narrowed for its runs', '1 write asks · no model yet'],
      ['done', 'Team', 'Only you'],
      ['done', 'Results', 'Comment on the issue · at most 30 an hour'],
    ]);
    // Create waits, and says for what.
    expect(save().disabled).toBe(true);
    expect(words($('[data-testid=save-status]'))).toBe('Name the trigger.');

    await fillTheRule();
    expect(rows().slice(0, 3)).toEqual([
      ['done', 'When', 'Jira · assigned · all it sees'],
      ['done', 'The agent does', 'Business analyst · instruction written'],
      ['done', 'Acts as and pays', 'svc-reader · svc-reader’s budget'],
    ]);
    // A tab says what is not complete yet to a screen reader too.
    expect(words(el.querySelector('#section-tab-settings-details'))).toBe(
      'Not complete yet: 1 write asks · no model yet',
    );

    // Every section stays in the page while another shows, so nothing typed is lost.
    tab('team').click();
    await settle();
    expect(openSection()).toBe('team');
    expect($<HTMLInputElement>('#trigger-name')!.value).toBe('Prepare assigned issues');

    // Back and Next step along them; the last has no Next.
    const steps = () => $('[data-testid=section-steps]')!;
    expect(words(steps())).toBe('Narrowed for its runs Next: Results');
    steps().querySelectorAll<HTMLElement>('button')[1].click();
    await settle();
    expect(openSection()).toBe('results');
    expect(document.activeElement?.id).toBe('results-title');
    expect(words(steps())).toBe('Team');
    steps().querySelector<HTMLElement>('button')!.click();
    await settle();
    expect(openSection()).toBe('team');
  });

  it('moves along the tabs with the arrow keys, Home and End', async () => {
    await open();
    key(tab('when'), 'ArrowDown');
    await settle();
    expect(openSection()).toBe('agent');
    expect(document.activeElement).toBe(tab('agent'));
    key(tab('agent'), 'End');
    await settle();
    expect(openSection()).toBe('results');
    key(tab('results'), 'ArrowDown');
    await settle();
    expect(openSection()).toBe('when');
    key(tab('when'), 'ArrowUp');
    await settle();
    expect(openSection()).toBe('results');
    key(tab('results'), 'Home');
    await settle();
    expect(openSection()).toBe('when');
  });

  it('picks how it starts from a select, each way with its detail', async () => {
    await open();
    const field = $<HTMLButtonElement>('#trigger-source')!;
    expect(words(field)).toBe('Something happens on an issue');
    expect(words($('#trigger-source-hint'))).toBe('In Jira, GitHub or GitLab.');
    expect(field.getAttribute('aria-labelledby')).toBe('trigger-source-label trigger-source-value');

    field.click();
    await settle();
    const options = [...document.querySelectorAll<HTMLElement>('[role=menuitemradio]')];
    expect(options.map((o) => words(o))).toEqual([
      'Something happens on an issue In Jira, GitHub or GitLab',
      'Someone mentions it in a channel In Slack or Teams',
      'A webhook is delivered Any other software calls Tinfirm',
      'On a schedule Every day, week or month',
    ]);
    expect(options.map((o) => o.getAttribute('aria-checked'))).toEqual([
      'true',
      'false',
      'false',
      'false',
    ]);

    options[3].click();
    await answer();
    expect(document.querySelector('[role=menuitemradio]')).toBeNull();
    expect(words(field)).toBe('On a schedule');
    expect(words($('#trigger-source-hint'))).toBe('Every day, week or month.');
    expect($('#trigger-cron')).not.toBeNull();
    expect($('#trigger-system')).toBeNull();
    expect(summary('when')).toContain('Schedule · 0 9 * * 1');
  });

  it('makes every write ask once the model is picked: the preview refuses a budget without one', async () => {
    const open$ = aTriggerPreview({ openToAnyone: anOpenToAnyone() });
    const preview = (req: TestRequest) =>
      (req.request.body as { draft: { settings?: { model?: string | null } } }).draft.settings
        ?.model
        ? open$
        : new Refuse(400, 'MODEL_REQUIRED');
    await open({ routes: { [`POST ${API}/triggers/preview`]: preview } });
    await fillTheRule();
    expect($('[data-testid=ask-prefilled]')).toBeNull();

    tab('settings').click();
    await answer();
    const model = $<HTMLSelectElement>('[data-field=model] select')!;
    model.value = 'claude-sonnet';
    model.dispatchEvent(new Event('change'));
    await answer();

    expect($('[data-testid=ask-prefilled]')).not.toBeNull();
    expect(summary('settings')).toContain('claude-sonnet');
  });

  it('holds Save until “I understand” while anyone may drive it with the author’s own account (V14)', async () => {
    const open$ = aTriggerPreview({ openToAnyone: anOpenToAnyone() });
    await open({ routes: { [`POST ${API}/triggers/preview`]: open$ } });
    await fillTheRule();
    await answer({ [`POST ${API}/triggers/preview`]: open$ });

    // Every write was made to ask, once, since anyone may drive it.
    expect($('[data-testid=ask-prefilled]')).not.toBeNull();
    const box = $('[data-testid=open-to-anyone]')!;
    expect(words(box)).toContain('Anyone who may comment on issues in SHOP can drive');
    expect(words(box)).toContain('comment on Jira, as you (your own account)');
    expect(box.className).toContain('border-human');
    // The tab whose choice causes it says so, in orange.
    expect(tab('accounts').querySelector('[data-testid=needs-you-dot]')).not.toBeNull();
    expect(words(el.querySelector('#section-tab-accounts-details'))).toContain(', needs you');

    expect(save().disabled).toBe(true);
    expect(words($('[data-testid=save-status]'))).toBe(
      'Tick “I understand” in the warning to create it.',
    );
    expect($('[data-testid=save-status]')!.className).toContain('text-human');

    $<HTMLInputElement>('#trigger-acknowledge')!.click();
    await answer({ [`POST ${API}/triggers/preview`]: open$ });
    expect(save().disabled).toBe(false);
    // Acknowledged, nothing waits on the reader: no orange.
    expect($('[data-testid=open-to-anyone]')!.className).not.toContain('border-human');
    expect(tab('accounts').querySelector('[data-testid=needs-you-dot]')).toBeNull();

    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    save().click();
    const posted = await answer({ [`POST ${API}/triggers`]: aTrigger() });
    const create = posted.find(
      (r) => r.request.method === 'POST' && r.request.url === `${API}/triggers`,
    )!;
    expect(create.request.body).toMatchObject({
      name: 'Prepare assigned issues',
      source: 'TRACKER',
      systemId: IDS.jira,
      agentId: IDS.agent,
      agentVersion: 4,
      acknowledgeOpenEvents: true,
    });
    // The prefill: the agent's write asks.
    expect(create.request.body.settings.toolModes).toContainEqual({
      systemId: IDS.github,
      tool: 'create_pull_request',
      mode: 'ASK',
    });
    expect(navigate).toHaveBeenCalledWith(['/c', 'c1', 'triggers', 't-prepare']);
  });

  it('offers asking before every write as the way out, never a service account instead (D2)', async () => {
    const open$ = aTriggerPreview({ openToAnyone: anOpenToAnyone() });
    await open({ routes: { [`POST ${API}/triggers/preview`]: open$ } });
    await fillTheRule();
    $<HTMLInputElement>(`#trigger-choice-${IDS.alice}`)!.click();
    await answer({ [`POST ${API}/triggers/preview`]: open$ });

    const warning = $('[data-testid=open-to-anyone]')!;
    expect(warning.querySelector('[data-testid=use-service-identity]')).toBeNull();
    expect(warning.querySelector('[data-testid=ask-every-write]')).not.toBeNull();
    expect(words(warning.querySelector('[data-testid=acknowledge]'))).toContain(
      'which then writes without asking, with my own account',
    );
  });

  it('asks the author to acknowledge an open write with a lent credential too, its lender told (D2)', async () => {
    const lent = aTriggerPreview({
      openToAnyone: anOpenToAnyone({
        writes: anOpenToAnyone().writes.map((w) => ({
          ...w,
          own: false,
          holderName: 'svc-reader',
        })),
      }),
    });
    await open({ routes: { [`POST ${API}/triggers/preview`]: lent } });
    await fillTheRule();
    $<HTMLInputElement>(`#trigger-choice-${IDS.alice}`)!.click();
    await answer({ [`POST ${API}/triggers/preview`]: lent });

    expect(words($('[data-testid=open-to-anyone] [data-testid=acknowledge]'))).toContain(
      'with a credential lent to a service account, whose lender is told',
    );
  });

  it.each<[string, Refuse, string, string, string, string]>([
    [
      'ACCOUNT_NOT_ENTITLED marks its row',
      new Refuse(400, 'ACCOUNT_NOT_ENTITLED', { details: { systemId: IDS.jira } }),
      `[data-row-error="${IDS.jira}"]`,
      'may not use this account',
      'accounts',
      `trigger-row-${IDS.jira}`,
    ],
    [
      'MODEL_ACCESS_NOT_ENTITLED marks who pays',
      new Refuse(400, 'MODEL_ACCESS_NOT_ENTITLED'),
      '[data-error=payer]',
      'may not use this budget',
      'accounts',
      'trigger-payer',
    ],
    [
      'INVALID_TRIGGER marks the rule',
      new Refuse(400, 'INVALID_TRIGGER', { detail: 'CLOSED never fires on ONCE' }),
      '[data-error=when]',
      'CLOSED never fires on ONCE',
      'when',
      '',
    ],
    [
      'DUPLICATE_TRIGGER_NAME marks the name',
      new Refuse(409, 'DUPLICATE_TRIGGER_NAME'),
      '[data-error=name]',
      'A trigger with this name already exists.',
      'when',
      'trigger-name',
    ],
    [
      'TRIGGER_LIMIT_TOO_HIGH marks the limit',
      new Refuse(400, 'TRIGGER_LIMIT_TOO_HIGH'),
      '[data-error=results]',
      'more firings per hour than the company allows',
      'results',
      'trigger-firings',
    ],
    [
      'TOO_MANY_TRIGGERS is the form’s',
      new Refuse(409, 'TOO_MANY_TRIGGERS'),
      '[data-testid=editor-error]',
      'as many triggers as it can hold',
      'team',
      '',
    ],
    [
      'OPEN_EVENTS_NOT_ACKNOWLEDGED is the warning’s',
      new Refuse(400, 'OPEN_EVENTS_NOT_ACKNOWLEDGED'),
      '[data-error=acknowledge]',
      'set the write tools to Ask',
      'team',
      'trigger-acknowledge',
    ],
  ])('places a refused save: %s', async (_case, refusal, where, says, section, focused) => {
    const preview = aTriggerPreview({
      openToAnyone: anOpenToAnyone({ writes: [], acknowledgementNeeded: true }),
    });
    await open({ routes: { [`POST ${API}/triggers/preview`]: preview } });
    await fillTheRule();
    $<HTMLInputElement>('#trigger-acknowledge')?.click();
    await answer({ [`POST ${API}/triggers/preview`]: preview });
    // The author is elsewhere when the save is refused: the section it names opens.
    tab('team').click();
    await settle();
    save().click();
    await answer({ [`POST ${API}/triggers`]: refusal, [`POST ${API}/triggers/preview`]: preview });
    expect(words($(where))).toContain(says);
    expect(openSection()).toBe(section);
    if (focused) expect(document.activeElement?.id).toBe(focused);
    if (section !== 'team') expect(tab(section).dataset['state']).toBe('error');
  });

  it('says why the preview refuses, and keeps Save held', async () => {
    await open({
      routes: {
        [`POST ${API}/triggers/preview`]: new Refuse(409, 'CANNOT_RUN_ANYWHERE', {
          details: { reason: 'NO_HOST_FOR_CREDENTIALS' },
        }),
      },
    });
    await fillTheRule();
    await answer({
      [`POST ${API}/triggers/preview`]: new Refuse(409, 'CANNOT_RUN_ANYWHERE', {
        details: { reason: 'NO_HOST_FOR_CREDENTIALS' },
      }),
    });
    expect(words($('[data-testid=preview-error]'))).toContain('No runner host may run this');
    expect(words($('[data-testid=preview-error]'))).toContain(
      'may not hold the credentials a service account borrows',
    );
    expect(save().disabled).toBe(true);
  });

  it('on a phone, lists the sections first, opens each on its own, and saves from a bar', async () => {
    const restore = onAPhone();
    try {
      const open$ = aTriggerPreview({ openToAnyone: anOpenToAnyone() });
      await open({ routes: { [`POST ${API}/triggers/preview`]: open$ } });
      await fillTheRule();
      await answer({ [`POST ${API}/triggers/preview`]: open$ });

      // The first screen: the sections with what each holds, and who acts and who pays.
      const index = $('[data-testid=section-index]')!;
      expect(index.className).not.toContain('max-lg:hidden');
      expect(words($('[data-testid=who-acts-who-pays]'))).toContain(
        'Jira: svc-reader with reader-bot',
      );
      // "Before you save" is a sheet behind the bar, not a column.
      expect($('aside')).toBeNull();
      const bar = $('[data-testid=trigger-save-bar]')!;
      expect(words(bar.querySelector('[data-testid=save-status]'))).toBe(
        'Tick “I understand” in the warning to create it.',
      );
      expect(words(bar.querySelector('[data-testid=save-trigger]'))).toBe('Create');

      // A section opens on its own, its heading taking the focus; the warning is in Acts as.
      tab('accounts').click();
      await settle();
      expect(openSection()).toBe('accounts');
      expect(index.className).toContain('max-lg:hidden');
      expect(document.activeElement?.id).toBe('accounts-title');
      expect($('#section-panel-accounts [data-testid=open-to-anyone]')).not.toBeNull();
      $<HTMLInputElement>('#trigger-acknowledge')!.click();
      await answer({ [`POST ${API}/triggers/preview`]: open$ });
      expect(words(bar.querySelector('[data-testid=preview-summary]'))).toBe(
        'Acts as svc-reader · paid by svc-reader · can run',
      );
      expect(save().disabled).toBe(false);

      // "All sections" goes back to the list, onto the tab of the section left.
      [...el.querySelectorAll<HTMLElement>('button')]
        .find((b) => words(b) === 'All sections')!
        .click();
      await settle();
      expect(index.className).not.toContain('max-lg:hidden');
      expect(document.activeElement).toBe(tab('accounts'));
    } finally {
      restore();
    }
  });

  it('on a phone, picks how it starts from a sheet of the four ways', async () => {
    const restore = onAPhone();
    try {
      await open();
      tab('when').click();
      await settle();
      const field = $<HTMLButtonElement>('#trigger-source')!;
      expect(field.getAttribute('aria-haspopup')).toBe('dialog');
      field.click();
      await settle();
      const options = [...document.querySelectorAll<HTMLElement>('[role=radio][data-source]')];
      expect(options.map((o) => words(o))).toEqual([
        'Something happens on an issue In Jira, GitHub or GitLab',
        'Someone mentions it in a channel In Slack or Teams',
        'A webhook is delivered Any other software calls Tinfirm',
        'On a schedule Every day, week or month',
      ]);
      expect(options[0].getAttribute('aria-checked')).toBe('true');
      // The arrow keys move along the ways, End to the last.
      options[0].focus();
      key(options[0], 'ArrowDown');
      expect(document.activeElement).toBe(options[1]);
      key(options[1], 'End');
      expect(document.activeElement).toBe(options[3]);
      options[2].click();
      await answer();
      expect(document.querySelector('[role=radio][data-source]')).toBeNull();
      expect(words(field)).toBe('A webhook is delivered');
      expect(words($('label[for=trigger-system]'))).toBe('Webhook');
    } finally {
      restore();
    }
  });

  describe('a change', () => {
    const settings = anAgentSettings({ agentId: IDS.agent, stored: true });

    async function openChange(
      trigger: Trigger = aTrigger(),
      preview: TriggerPreview = aTriggerPreview(),
    ) {
      await open({
        triggerId: 't-prepare',
        routes: {
          [`GET ${TRIGGER}`]: trigger,
          [`GET ${TRIGGER}/settings`]: settings,
          [`POST ${API}/triggers/preview`]: preview,
        },
      });
    }

    it('keeps the source and the agent fixed, and sends only what changed', async () => {
      await openChange(aTrigger(), aTriggerPreview({ openConversationsKeepingAccounts: 3 }));
      expect(words($('[data-testid=fixed-system]'))).toContain('Jira');
      expect(words($('[data-testid=fixed-agent]'))).toContain('Business analyst');
      // How it starts is fixed: no select.
      expect($('#trigger-source')).toBeNull();
      expect(words($('[data-testid=trigger-sentence]'))).toBe(
        'When an issue in SHOP is assigned to svc-reader, Business analyst prepares it.',
      );
      expect(words(save())).toBe('Save');
      expect(save().disabled).toBe(true);
      expect(words($('[data-testid=save-status]'))).toBe('No changes');
      expect(words($('[data-testid=who-acts-who-pays]'))).toContain(
        '3 open conversations keep the accounts they started with.',
      );
      expect(summary('team')).toBe('Analysts');

      typeInto($('#trigger-name'), 'Prepare issues');
      await answer({
        [`GET ${TRIGGER}`]: aTrigger(),
        [`GET ${TRIGGER}/settings`]: settings,
      });
      expect(words($('[data-testid=save-status]'))).toBe(
        'Its open conversations keep the accounts they started with.',
      );
      vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      save().click();
      const sent = await answer({ [`PATCH ${TRIGGER}`]: aTrigger({ name: 'Prepare issues' }) });
      const writes = sent.filter((r) => r.request.method !== 'GET' && r.request.method !== 'POST');
      expect(writes.map((r) => `${r.request.method} ${r.request.url}`)).toEqual([
        `PATCH ${TRIGGER}`,
      ]);
      expect(writes[0].request.body).toMatchObject({ name: 'Prepare issues', scope: 'SHOP' });
    });

    it('replaces the table and the payer, and the settings, each when changed', async () => {
      await openChange();
      const payer = $<HTMLSelectElement>('#trigger-payer')!;
      payer.value = IDS.alice;
      payer.dispatchEvent(new Event('change'));
      await answer();
      const off = el.querySelector<HTMLInputElement>(
        `[data-system="${IDS.github}"] [data-tool="create_pull_request"] input[value=OFF]`,
      )!;
      off.click();
      await answer();
      vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      save().click();
      const sent = await answer({
        [`PUT ${TRIGGER}/accounts`]: aTrigger(),
        [`PUT ${TRIGGER}/settings`]: settings,
      });
      const writes = sent.filter((r) => r.request.method === 'PUT');
      expect(writes.map((r) => r.request.url)).toEqual([
        `${TRIGGER}/accounts`,
        `${TRIGGER}/settings`,
      ]);
      expect(writes[0].request.body).toEqual({
        accounts: [
          {
            systemId: IDS.jira,
            accountId: 'acc-svc-reader-jira',
            holderPrincipalId: IDS.svcReader,
          },
        ],
        payerPrincipalId: IDS.alice,
        acknowledgeOpenEvents: false,
      });
      expect(writes[1].request.body.toolModes).toContainEqual({
        systemId: IDS.github,
        tool: 'create_pull_request',
        mode: 'OFF',
      });
    });

    it('needs no new “I understand” for a trigger its author acknowledged before', async () => {
      await openChange(
        aTrigger({ openEventsAcknowledgedAt: '2026-10-05T08:00:00Z' }),
        aTriggerPreview({ openToAnyone: anOpenToAnyone({ acknowledgementMissing: false }) }),
      );
      typeInto($('#trigger-name'), 'Answer mentions');
      await answer();
      expect($('[data-testid=acknowledged-before]')).not.toBeNull();
      expect($('#trigger-acknowledge')).toBeNull();
      expect(save().disabled).toBe(false);
    });

    it('is not editable by its team (TRIGGER_NOT_EDITABLE)', async () => {
      await openChange(
        aTrigger({
          rights: {
            edits: false,
            runs: true,
            pauses: true,
            resumes: false,
            deletes: false,
            reassigns: false,
          },
        }),
      );
      expect(words($('[data-testid=not-editable]'))).toContain(
        'Only the trigger’s author and the company’s admins change it.',
      );
      expect($('[data-testid=trigger-editor]')).toBeNull();
    });

    it('says an admin edits on the author’s behalf, with the author’s choices', async () => {
      await openChange(aTrigger({ authorPrincipalId: IDS.bob, authorName: 'Bob Svoboda' }));
      const choices = served.filter((r) => r.request.url.endsWith('/triggers/choices')).at(-1)!;
      expect(choices.request.params.get('forPrincipalId')).toBe(IDS.bob);
      expect(words($('[data-testid=on-behalf]'))).toContain(
        'You change Bob Svoboda’s trigger on their behalf',
      );
    });
  });
});
