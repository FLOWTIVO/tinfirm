import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Agent, AgentSettings } from '../../../api/generated/models';
import {
  aMyAccount,
  anAgent,
  anAgentSettings,
  anAgentSkill,
  aModelAccess,
  aReadiness,
  aReadinessSystem,
  aWidening,
  IDS,
  someMemoryNotes,
} from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
} from '../../../testing/test-helpers';
import { AgentSettingsPage } from './agent-settings-page';

const API = '/api/companies/c1';
const AGENT = `${API}/agents/${IDS.agent}`;
const SETTINGS = `${AGENT}/settings/me`;

describe('AgentSettingsPage', () => {
  let fixture: ComponentFixture<AgentSettingsPage>;
  let http: HttpTestingController;
  let el: HTMLElement;

  async function open(
    settings: AgentSettings = anAgentSettings(),
    models: 'ok' | 'not-configured' = 'ok',
    agent: Agent = anAgent(),
  ): Promise<void> {
    TestBed.configureTestingModule({
      imports: [AgentSettingsPage],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany('MEMBER').provider,
        fakeNotify().provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AgentSettingsPage);
    fixture.componentRef.setInput('agentId', IDS.agent);
    el = fixture.nativeElement;
    await settle();
    http.expectOne(AGENT).flush(agent);
    http.expectOne(SETTINGS).flush(settings);
    const gateway = http.expectOne(`${API}/model-settings/models`);
    if (models === 'ok') gateway.flush({ models: ['claude-sonnet', 'claude-opus'] });
    else {
      gateway.flush(problem(409, 'GATEWAY_NOT_CONFIGURED'), {
        status: 409,
        statusText: 'Conflict',
      });
    }
    http.expectOne((r) => r.url === `${API}/systems`).flush({ items: [] });
    http.expectOne(`${API}/me/accounts`).flush({ items: [aMyAccount()] });
    http.expectOne(`${API}/me/model-access`).flush(aModelAccess());
    answerReadiness();
    await settle();
    answerReadiness();
    await settle();
  }

  /**
   * Who acts and who pays, and the memory, as the settings stand: read at the start and after each
   * save. Answers how many readiness reads there were.
   */
  function answerReadiness(readiness = aReadiness()): number {
    const reqs = http.match(`${AGENT}/readiness`);
    for (const req of reqs) req.flush(readiness);
    for (const req of http.match(`${SETTINGS}/memory`)) req.flush(someMemoryNotes());
    return reqs.length;
  }

  afterEach(() => http.verify());

  const $ = <T extends Element = HTMLElement>(selector: string) => el.querySelector<T>(selector);
  const words = (node: Element | null | undefined) =>
    node?.textContent?.replace(/\s+/g, ' ').trim() ?? null;
  const toolRow = (name: string) =>
    el.querySelector<HTMLElement>(`[data-system] [data-tool="${name}"]`)!;
  const bar = () => $('[role=region][aria-label="Unsaved changes"]');
  const save = () => $<HTMLButtonElement>('[data-testid=save-settings]')!;

  /** Opens GitHub's tools and switches "Open a pull request" off. */
  async function pullRequestsOff(): Promise<void> {
    $<HTMLButtonElement>(`[data-testid="system-expand-${IDS.github}"]`)!.click();
    await settle();
    toolRow('create_pull_request').querySelector<HTMLInputElement>('input[value=OFF]')!.click();
    await settle();
  }

  it('starts from the defaults, says so, and shows who acts and who pays beside them', async () => {
    await open();
    expect(words($('h1'))).toBe('My settings for Business analyst');
    expect(words($('h1 + p'))).toBe(
      'Narrows Business analyst for your own conversations. It never lets it do more than its owner allowed.',
    );
    expect(words($('[data-testid=defaults-note]'))).toBe(
      'Nothing saved yet: these are the defaults.',
    );
    // Nothing to save, and nothing saved to go back from.
    expect(bar()).toBeNull();
    expect(save()).toBeNull();
    expect($<HTMLButtonElement>('[data-testid=reset-settings]')?.disabled).toBe(true);
    const who = $('[data-testid=settings-who-acts]');
    expect(who?.textContent).toContain('GitHub: your own account');
    expect(who?.textContent).toContain('Your budget: $38.20 of $50.00 left');
    expect(who?.querySelector('[data-testid=read-again]')).toBeNull();
  });

  it('sums who acts and who pays up in one line that opens, for a phone', async () => {
    await open();
    const toggle = $<HTMLButtonElement>('[data-testid=who-toggle]')!;
    expect(words(toggle)).toBe(
      'What this means for youYour budget · GitHub: your own account · Jira: connect when first needed',
    );
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    const body = $(`#${toggle.getAttribute('aria-controls')}`)!;
    expect(body.classList).toContain('hidden');
    toggle.click();
    await settle();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(body.classList).not.toContain('hidden');
  });

  it('names what changed in the unsaved-changes bar, and discards it', async () => {
    await open();
    await pullRequestsOff();
    expect(words(bar()?.querySelector('[data-testid=unsaved-count]'))).toBe('1 unsaved change');
    expect(words(bar()?.querySelector('[data-testid=unsaved-names]'))).toBe('1 GitHub tool');
    expect($('[data-testid=read-again]')?.textContent).toContain('Read again after you save');
    // The page has no Save of its own: the bar's submits the form.
    expect(save().getAttribute('form')).toBe($('form')?.id);

    [...bar()!.querySelectorAll('button')]
      .find((b) => b.textContent?.trim() === 'Discard')!
      .click();
    await settle();
    expect(bar()).toBeNull();
    expect(toolRow('create_pull_request').dataset['choice']).toBe('');
  });

  it('lists the notes of the reader’s memory of the agent below the form, by its names', async () => {
    await open();
    const memory = el.querySelector<HTMLElement>('[data-testid=memory-notes]')!;
    expect(memory.textContent).toContain('What Business analyst noted in your conversations');
    expect(
      Array.from(memory.querySelectorAll('[data-group] h3')).map((h) => h.textContent?.trim()),
    ).toEqual(['About you', 'About Jira', 'About acme/shop']);
  });

  it('saves the settings as a whole, then reads who acts and who pays again', async () => {
    await open();
    await pullRequestsOff();
    $('form')!.dispatchEvent(new Event('submit', { cancelable: true }));
    await settle();
    const put = http.expectOne({ method: 'PUT', url: SETTINGS });
    expect(put.request.body).toMatchObject({
      runtime: null,
      modelAccess: 'BUDGET',
      toolModes: [{ systemId: IDS.github, tool: 'create_pull_request', mode: 'OFF' }],
    });
    put.flush(
      anAgentSettings({
        stored: true,
        toolModes: [{ systemId: IDS.github, tool: 'create_pull_request', mode: 'OFF' }],
      }),
    );
    await settle();
    expect(
      answerReadiness(
        aReadiness({
          systems: [
            aReadinessSystem({ binding: 'GRANTED', access: 'READ', grantId: IDS.analystsGrant }),
          ],
        }),
      ),
    ).toBe(1);
    await settle();
    expect($('[data-testid=settings-who-acts]')?.textContent).toContain(
      'GitHub: via a grant to your group, read-only',
    );
    expect($('[data-testid=defaults-note]')).toBeNull();
    expect(bar()).toBeNull();
    expect($<HTMLButtonElement>('[data-testid=reset-settings]')?.disabled).toBe(false);
  });

  it('goes back to the defaults once confirmed, and reads who acts again', async () => {
    await open(anAgentSettings({ stored: true, effort: 'HIGH' }));
    $<HTMLButtonElement>('[data-testid=reset-settings]')!.click();
    await settle();
    const dialog = document.body.querySelector('[data-testid=reset-dialog]');
    expect(dialog?.textContent).toContain('Back to the defaults?');
    expect(dialog?.textContent).toContain('The version you accepted stays.');
    document.body.querySelector<HTMLButtonElement>('[data-testid=confirm-reset]')!.click();
    await settle();
    http.expectOne({ method: 'DELETE', url: SETTINGS }).flush(anAgentSettings());
    await settle();
    expect(answerReadiness()).toBe(1);
    await settle();
    expect($('[data-testid=defaults-note]')).not.toBeNull();
  });

  it.each([
    ['MODEL_NOT_ALLOWED', 'model', 'Your model access does not allow this model.'],
    [
      'MODEL_REQUIRED',
      'model',
      'Choose a model: a budget pays only for a model the company’s gateway serves.',
    ],
    ['RUNTIME_NOT_LISTED', 'runtime', 'The agent does not list this runtime.'],
    ['SUBSCRIPTION_NOT_SERVING', 'payer', 'This subscription does not serve the runtime'],
  ])('puts %s on its field', async (code, field, text) => {
    await open();
    const effort = $<HTMLSelectElement>('[data-field=effort] select')!;
    effort.value = 'HIGH';
    effort.dispatchEvent(new Event('change'));
    await settle();
    expect(words(bar()?.querySelector('[data-testid=unsaved-names]'))).toBe('Effort');
    save().click();
    await settle();
    http
      .expectOne({ method: 'PUT', url: SETTINGS })
      .flush(problem(400, code), { status: 400, statusText: 'Bad Request' });
    await settle();
    expect($(`[data-error=${field}]`)?.textContent).toContain(text);
    expect($('[data-testid=settings-error]')).toBeNull();
  });

  it('says a refused tool on its system, by the names in details', async () => {
    await open();
    await pullRequestsOff();
    save().click();
    await settle();
    http.expectOne({ method: 'PUT', url: SETTINGS }).flush(
      {
        ...problem(400, 'UNKNOWN_TOOL'),
        details: { systemId: IDS.github, tool: 'create_pull_request' },
      },
      { status: 400, statusText: 'Bad Request' },
    );
    await settle();
    expect($(`[data-system="${IDS.github}"] [data-error-at]`)?.textContent).toContain(
      'Business analyst no longer lists “Open a pull request” on GitHub',
    );
    expect($('[data-testid=settings-error]')).toBeNull();
  });

  it('says why no model can be picked, and why the budget cannot pay, without a gateway', async () => {
    await open(anAgentSettings(), 'not-configured');
    expect($<HTMLSelectElement>('[data-field=model] select')?.disabled).toBe(true);
    expect($('[data-testid=models-unavailable]')?.textContent).toContain('not set up yet');
    const budget = $('[data-payer=BUDGET]');
    expect(budget?.textContent).toContain('Needs the company’s model gateway');
    expect(budget?.querySelector<HTMLInputElement>('input')?.disabled).toBe(true);
  });

  it('shows the widening card while a version waits, and says when it is accepted', async () => {
    await open(anAgentSettings({ currentVersion: 6, widening: aWidening() }));
    const card = $('[data-testid=widening-card]');
    expect(card?.textContent).toContain('Accept version 6');
    // What it adds is shown, not the reader's yet.
    expect($(`[data-system="${IDS.jira}"] [data-summary]`)?.textContent).toContain(
      'Yours after you accept version 6',
    );
    card!.querySelector<HTMLButtonElement>('[data-testid=accept-version]')!.click();
    await settle();
    http
      .expectOne({ method: 'POST', url: `${AGENT}/accept` })
      .flush(anAgentSettings({ acceptedVersion: 6, currentVersion: 6 }));
    await settle();
    http.expectOne(AGENT).flush(anAgent({ version: 6, acceptedVersion: 6 }));
    answerReadiness();
    await settle();
    expect($('[data-testid=widening-card]')).toBeNull();
    expect(words($('[data-testid=accepted-note]'))).toBe(
      'Version 6 accepted. Your conversations use it from their next turn.',
    );
  });

  it('switches a skill of the agent off, and saves it with the rest (ADR-0064)', async () => {
    await open(anAgentSettings(), 'ok', anAgent({ skills: [anAgentSkill()] }));
    const sheet = $('[data-testid=settings-skills]')!;
    const skill = sheet.querySelector(`[data-skill="${IDS.skill}"]`)!;
    expect(skill.textContent).toContain('write-jira-ticket');
    expect(skill.textContent).toContain('v2');
    expect(words(skill.querySelector('[data-summary]'))).toBe('Read when a task fits');
    skill.querySelector<HTMLElement>('[role=switch]')!.click();
    await settle();
    expect(words(skill.querySelector('[data-summary]'))).toBe('Off for your conversations');
    expect(words(bar()?.querySelector('[data-testid=unsaved-names]'))).toBe(
      'write-jira-ticket off',
    );

    save().click();
    await settle();
    const put = http.expectOne({ method: 'PUT', url: SETTINGS });
    expect(put.request.body).toMatchObject({ skillsOff: [IDS.skill], toolModes: [] });
    put.flush(anAgentSettings({ stored: true, skillsOff: [IDS.skill] }));
    await settle();
    answerReadiness();
    await settle();
    expect(bar()).toBeNull();
    expect(words(skill.querySelector('[data-summary]'))).toBe('Off for your conversations');
  });

  it('says a skill that left the agent meanwhile, and shows no sheet without skills', async () => {
    await open(
      anAgentSettings({ skillsOff: [IDS.skill] }),
      'ok',
      anAgent({ skills: [anAgentSkill()] }),
    );
    $<HTMLElement>(`[data-skill="${IDS.skill}"] [role=switch]`)!.click();
    await settle();
    save().click();
    await settle();
    http
      .expectOne({ method: 'PUT', url: SETTINGS })
      .flush(problem(400, 'SKILL_NOT_ON_AGENT'), { status: 400, statusText: 'Bad Request' });
    await settle();
    expect($('[data-error=skills]')?.textContent).toContain(
      'The agent no longer has a skill your settings switch off.',
    );

    TestBed.resetTestingModule();
    await open();
    expect($('[data-testid=settings-skills]')).toBeNull();
  });
});
