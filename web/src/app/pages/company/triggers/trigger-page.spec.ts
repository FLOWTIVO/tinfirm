import { HttpErrorResponse } from '@angular/common/http';
import type { TestRequest } from '@angular/common/http/testing';
import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type { Trigger, TriggerRights } from '../../../api/generated/models';
import {
  anAgent,
  anAgentSettings,
  aModelAccess,
  aTrigger,
  aTriggerFiring,
  aMemoryNote,
  aWidening,
  IDS,
  someMemoryNotes,
} from '../../../testing/fixtures';
import { type Answer, Refuse, serve } from '../../../testing/serve';
import {
  fakeCurrentCompany,
  fakeNotify,
  provideTestHttp,
  settle,
  typeInto,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { runNowRefusal } from './run-now-dialog';
import { TriggerPage } from './trigger-page';

const API = '/api/companies/c1';
const TRIGGER = `${API}/triggers/t-prepare`;

const AUTHOR: TriggerRights = {
  edits: true,
  runs: true,
  pauses: true,
  resumes: true,
  deletes: true,
  reassigns: false,
};
const TEAM: TriggerRights = {
  edits: false,
  runs: true,
  pauses: true,
  resumes: false,
  deletes: false,
  reassigns: false,
};
const ADMIN: TriggerRights = { ...AUTHOR, reassigns: true };

describe('TriggerPage', () => {
  let fixture: ComponentFixture<TriggerPage>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let given: Record<string, Answer>;

  function routes(trigger: Trigger, extra: Record<string, Answer> = {}): Record<string, Answer> {
    return {
      [`GET ${TRIGGER}`]: trigger,
      [`GET ${TRIGGER}/firings`]: { items: [aTriggerFiring()] },
      [`GET ${TRIGGER}/memory`]: someMemoryNotes({ inEffect: false, notes: [] }),
      [`GET ${API}/agents/${IDS.agent}`]: anAgent(),
      [`GET ${API}/me/model-access`]: aModelAccess({ principalId: IDS.alice }),
      ...extra,
    };
  }

  async function open(trigger: Trigger = aTrigger(), extra: Record<string, Answer> = {}) {
    TestBed.configureTestingModule({
      imports: [TriggerPage],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany('MEMBER').provider,
        fakeNotify().provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TriggerPage);
    fixture.componentRef.setInput('triggerId', 't-prepare');
    el = fixture.nativeElement;
    given = routes(trigger, extra);
    await serve(http, given);
  }

  async function answer(extra: Record<string, Answer> = {}): Promise<TestRequest[]> {
    return serve(http, { ...given, ...extra });
  }

  afterEach(() => http.verify());

  const $ = <T extends Element = HTMLElement>(selector: string) => el.querySelector<T>(selector);

  it('reads as one sentence, says who acts and pays, and lists its firings', async () => {
    await open();
    expect(words($('h1'))).toBe('Prepare assigned issues');
    expect(words($('[data-testid=trigger-sentence]'))).toContain('When an issue in SHOP');
    expect(words($('[data-testid=trigger-meta]'))).toBe(
      'Jira · Business analyst, version 4 · by Alice Novak · team Analysts',
    );
    const who = words($('[data-testid=who-acts-who-pays]'));
    expect(who).toContain('Jira: svc-reader with reader-bot (svc-reader)');
    expect(who).toContain('GitHub: not available to this trigger’s runs');
    expect(who).toContain('svc-reader’s budget');
    expect(words($('[data-testid=trigger-results]'))).toContain(
      'Each result and failure is commented on the issue, as svc-reader with reader-bot.',
    );
    const firing = $('[data-firing=f1]')!;
    expect(words(firing)).toContain('SHOP-241 · Assigned to the trigger’s account');
    expect(words(firing)).toContain('Started a conversation');
    expect(firing.querySelector('a')?.getAttribute('href')).toBe('/c/c1/conversations/cv1');
    expect($('[data-testid=trigger-conversations]')?.getAttribute('href')).toBe(
      '/c/c1/conversations?triggerId=t-prepare',
    );
  });

  it.each<[string, TriggerRights, string[]]>([
    ['its author', AUTHOR, ['run-now', 'pause-trigger', 'edit-trigger', 'delete-trigger']],
    ['its team', TEAM, ['run-now', 'pause-trigger']],
    [
      'an admin',
      ADMIN,
      ['run-now', 'pause-trigger', 'edit-trigger', 'reassign-trigger', 'delete-trigger'],
    ],
  ])('offers %s what the server allows', async (_who, rights, buttons) => {
    await open(aTrigger({ rights }));
    const shown = [...el.querySelectorAll('[data-testid=trigger-actions] [data-testid]')].map((b) =>
      b.getAttribute('data-testid'),
    );
    expect(shown).toEqual(buttons);
  });

  it.each<[string, Partial<Trigger>, string, boolean]>([
    [
      'AUTHOR_LEFT',
      { pausedReason: 'AUTHOR_LEFT', rights: ADMIN },
      'An admin reassigns it to another member',
      true,
    ],
    [
      'ACCOUNT_BROKEN',
      { pausedReason: 'ACCOUNT_BROKEN' },
      'Once the account is signed in again',
      true,
    ],
    [
      'SYSTEM_PAUSED',
      { pausedReason: 'SYSTEM_PAUSED' },
      'It resumes when an admin resumes the system.',
      false,
    ],
    [
      'USER',
      { pausedReason: 'USER' },
      'It fires nothing until its author or an admin resumes it.',
      false,
    ],
  ])('says what fixes a pause: %s', async (_reason, overrides, fix, orange) => {
    await open(aTrigger({ status: 'PAUSED', ...overrides }));
    const notice = $('[data-testid=paused-notice]')!;
    expect(words(notice)).toContain(fix);
    expect(notice.className.includes('border-human')).toBe(orange);
    expect($<HTMLButtonElement>('[data-testid=run-now]')!.disabled).toBe(true);
    expect($('[data-testid=resume-trigger]')).not.toBeNull();
  });

  it('says why it waits, neutral', async () => {
    await open(
      aTrigger({ waitingReason: 'COMPANY_STOPPED', waitingSince: '2026-10-12T08:00:00Z' }),
    );
    expect(words($('[data-testid=waiting-notice]'))).toContain(
      'Waits: an admin stopped the company’s runs',
    );
  });

  it('pauses, stopping the running turns when asked, and resumes', async () => {
    await open();
    $<HTMLButtonElement>('[data-testid=pause-trigger]')!.click();
    await settle();
    document.querySelector<HTMLInputElement>('[data-testid=stop-running]')!.click();
    document.querySelector<HTMLButtonElement>('[data-testid=confirm-pause]')!.click();
    const paused = aTrigger({ status: 'PAUSED', pausedReason: 'USER' });
    const sent = await answer({ [`POST ${TRIGGER}/pause`]: paused });
    expect(sent.find((r) => r.request.url.endsWith('/pause'))!.request.body).toEqual({
      stopRunning: true,
    });
    expect(words($('[data-testid=paused-notice]'))).toContain('Paused: Paused by hand');

    $<HTMLButtonElement>('[data-testid=resume-trigger]')!.click();
    await answer({ [`POST ${TRIGGER}/resume`]: aTrigger() });
    expect($('[data-testid=paused-notice]')).toBeNull();
  });

  it('says why a resume was refused, as the gate answered', async () => {
    await open(aTrigger({ status: 'PAUSED', pausedReason: 'ACCOUNT_BROKEN' }));
    $<HTMLButtonElement>('[data-testid=resume-trigger]')!.click();
    await answer({
      [`POST ${TRIGGER}/resume`]: new Refuse(409, 'TRIGGER_PAUSED', {
        detail: 'svc-reader’s Jira account needs signing in again.',
      }),
    });
    expect(words($('[data-testid=trigger-action-error]'))).toBe(
      'It stays paused: svc-reader’s Jira account needs signing in again.',
    );
  });

  it('runs now on an issue found in its scope, and links its conversation', async () => {
    await open();
    $<HTMLButtonElement>('[data-testid=run-now]')!.click();
    await answer({
      [`GET ${TRIGGER}/subjects`]: {
        items: [{ key: 'SHOP-241', kind: 'ISSUE', title: 'Checkout fails', status: 'Open' }],
      },
    });
    typeInto(document.querySelector('#run-now-key'), 'checkout');
    await new Promise((resolve) => setTimeout(resolve, 350));
    const searched = await answer({
      [`GET ${TRIGGER}/subjects`]: {
        items: [{ key: 'SHOP-241', kind: 'ISSUE', title: 'Checkout fails', status: 'Open' }],
      },
    });
    expect(searched.at(-1)!.request.params.get('query')).toBe('checkout');
    document.querySelector<HTMLButtonElement>('[data-subject="SHOP-241"]')!.click();
    await settle();
    document.querySelector<HTMLButtonElement>('[data-testid=confirm-run-now]')!.click();
    const sent = await answer({ [`POST ${TRIGGER}/run`]: aTriggerFiring({ manualBy: 'u-alice' }) });
    expect(sent.find((r) => r.request.url.endsWith('/run'))!.request.body).toEqual({
      subjectKey: 'SHOP-241',
    });
    expect(words($('[data-testid=run-now-result]'))).toContain(
      'Run now on SHOP-241: Started a conversation · Open its conversation',
    );
  });

  it.each([
    [
      'TRIGGER_PAUSED',
      'COMPANY_STOPPED.',
      'The trigger is paused, so it did not run: COMPANY_STOPPED.',
    ],
    [
      'SUBJECT_NOT_VISIBLE',
      undefined,
      'The trigger’s account cannot read this issue, or it does not exist.',
    ],
    [
      'TRIGGER_CONDITIONS_FAIL',
      'status is Done, not one of Open',
      'This issue does not meet the trigger’s rules: status is Done, not one of Open.',
    ],
  ])('words a refused run now: %s', (code, detail, says) => {
    const error = new HttpErrorResponse({
      status: 409,
      error: { type: 'about:blank', title: 'Conflict', status: 409, code, detail },
    });
    expect(runNowRefusal(error)).toContain(says);
  });

  it('turns the review of results on for its author', async () => {
    await open();
    $<HTMLButtonElement>('#trigger-page-review')!.click();
    const sent = await answer({
      [`PATCH ${TRIGGER}`]: aTrigger({
        results: { comment: true, review: true, onSuccess: [], onFailure: [] },
      }),
    });
    expect(sent.find((r) => r.request.method === 'PATCH')!.request.body).toEqual({
      results: { comment: true, review: true, onSuccess: [], onFailure: [] },
    });
  });

  it('offers a widening to accept, naming the version shown', async () => {
    const widened = aTrigger({ wideningPending: true, currentVersion: 6 });
    await open(widened, {
      [`GET ${TRIGGER}/settings`]: anAgentSettings({ widening: aWidening() }),
      [`GET ${API}/agents/${IDS.agent}`]: anAgent(),
    });
    const card = $('[data-testid=trigger-widening]')!;
    expect(words(card)).toContain('Version 6 may do more than version 4');
    expect(card.className).toContain('border-human');
    $<HTMLButtonElement>('[data-testid=accept-trigger-version]')!.click();
    const sent = await answer({
      [`POST ${TRIGGER}/accept`]: aTrigger({ acceptedVersion: 6, currentVersion: 6 }),
    });
    expect(sent.find((r) => r.request.url.endsWith('/accept'))!.request.body).toEqual({
      version: 6,
    });
    expect($('[data-testid=trigger-widening]')).toBeNull();
  });

  it('says a version that changes only what the agent is told as such (ADR-0103)', async () => {
    await open(aTrigger({ wideningPending: true, currentVersion: 6 }), {
      [`GET ${TRIGGER}/settings`]: anAgentSettings({
        widening: aWidening({ systems: [], tools: [], repositories: [], hosts: [] }),
      }),
    });
    const card = words($('[data-testid=trigger-widening]'));
    expect(card).toContain('Version 6 changes what it is told: its instructions or skills.');
    expect(card).toContain('keep those of version 4 until you accept it');
    expect(card).not.toContain('may do more');
  });

  it('shows the team a widening without the button', async () => {
    await open(aTrigger({ wideningPending: true, currentVersion: 6, rights: TEAM }), {
      [`GET ${TRIGGER}/settings`]: anAgentSettings({ widening: aWidening() }),
      [`GET ${API}/agents/${IDS.agent}`]: anAgent(),
      [`GET ${API}/me/model-access`]: aModelAccess({ principalId: IDS.bob }),
    });
    expect(words($('[data-testid=trigger-widening]'))).toContain('until its author accepts it');
    expect($('[data-testid=accept-trigger-version]')).toBeNull();
  });

  it('shows an admin a widening that waits for its author, without the button (H2)', async () => {
    await open(aTrigger({ wideningPending: true, currentVersion: 6, rights: ADMIN }), {
      [`GET ${TRIGGER}/settings`]: anAgentSettings({ widening: aWidening() }),
      [`GET ${API}/me/model-access`]: aModelAccess({ principalId: IDS.owner }),
    });
    const card = $('[data-testid=trigger-widening]')!;
    expect(words(card)).toContain('until its author accepts it');
    expect(card.className).not.toContain('border-human');
    expect($('[data-testid=accept-trigger-version]')).toBeNull();
    expect(words($('[data-testid=widening-waits-for-author]'))).toBe(
      'Waiting for Alice Novak to accept',
    );
  });

  it('lets the new author accept a trigger reassigned to them, the open events once asked (H2)', async () => {
    await open(aTrigger({ status: 'PAUSED', pausedReason: 'TO_ACCEPT', currentVersion: 5 }));
    const notice = $('[data-testid=paused-notice]')!;
    expect(notice.className).toContain('border-human');
    expect(words(notice)).toContain('It was reassigned to you.');
    // Nobody resumes it: its author accepts it, which resumes it.
    expect($('[data-testid=resume-trigger]')).toBeNull();
    const accept = () => $<HTMLButtonElement>('[data-testid=accept-trigger]')!;

    accept().click();
    let sent = await answer({
      [`POST ${TRIGGER}/accept`]: new Refuse(400, 'OPEN_EVENTS_NOT_ACKNOWLEDGED'),
    });
    expect(sent.find((r) => r.request.url.endsWith('/accept'))!.request.body).toEqual({
      version: 5,
      acknowledgeOpenEvents: false,
    });
    expect(words($('[data-testid=accept-ack-needed]'))).toContain('Acknowledge it');
    expect(accept().disabled).toBe(true);

    $<HTMLInputElement>('#trigger-accept-acknowledge')!.click();
    await answer();
    expect(accept().disabled).toBe(false);
    accept().click();
    sent = await answer({
      [`POST ${TRIGGER}/accept`]: aTrigger({ acceptedVersion: 5, currentVersion: 5 }),
    });
    expect(sent.find((r) => r.request.url.endsWith('/accept'))!.request.body).toEqual({
      version: 5,
      acknowledgeOpenEvents: true,
    });
    expect($('[data-testid=paused-notice]')).toBeNull();
  });

  it('shows anyone but the new author a reassigned trigger waiting for them', async () => {
    await open(aTrigger({ status: 'PAUSED', pausedReason: 'TO_ACCEPT', rights: ADMIN }), {
      [`GET ${API}/me/model-access`]: aModelAccess({ principalId: IDS.owner }),
    });
    const notice = $('[data-testid=paused-notice]')!;
    expect(notice.className).not.toContain('border-human');
    expect($('[data-testid=accept-trigger]')).toBeNull();
    expect(words($('[data-testid=to-accept-waits]'))).toBe('Waiting for Alice Novak to accept it.');
  });

  it('says reassigning keeps it paused until the new author accepts it', async () => {
    await open(aTrigger({ status: 'PAUSED', pausedReason: 'AUTHOR_LEFT', rights: ADMIN }), {
      [`GET ${API}/members`]: { items: [] },
    });
    $<HTMLButtonElement>('[data-testid=reassign-trigger]')!.click();
    await answer();
    const dialog = document.querySelector('[data-testid=reassign-to-accept]');
    expect(words(dialog)).toContain('It stays paused until the new author accepts it');
    expect(words(dialog)).toContain('“Reassigned to you: accept it”');
  });

  it('deletes after asking, and goes back to the list', async () => {
    await open();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    $<HTMLButtonElement>('[data-testid=delete-trigger]')!.click();
    await settle();
    document.querySelector<HTMLButtonElement>('[data-testid=confirm-delete]')!.click();
    await answer({ [`DELETE ${TRIGGER}`]: null });
    expect(navigate).toHaveBeenCalledWith(['/c', 'c1', 'triggers']);
  });

  it('is the not-found page for anyone outside its audience (T15)', async () => {
    await open(aTrigger(), { [`GET ${TRIGGER}`]: new Refuse(404, 'NOT_FOUND') });
    expect($('app-not-found')).not.toBeNull();
    expect($('h1')?.textContent).not.toContain('Prepare');
  });

  it('shows its memory’s proposals to its team, with the agent’s names', async () => {
    await open(aTrigger({ rights: TEAM }), {
      [`GET ${TRIGGER}/memory`]: someMemoryNotes({
        notes: [
          aMemoryNote({
            number: 2,
            state: 'PROPOSED',
            about: 'SYSTEM',
            systemId: IDS.jira,
            text: 'SHOP issues use the component field for the team.',
          }),
        ],
      }),
    });
    const memory = el.querySelector<HTMLElement>('#memory')!;
    expect(words(memory.querySelector('[data-testid=memory-proposals]'))).toContain(
      'm2 · about Jira · proposed',
    );
    expect(memory.querySelector('[data-testid=accept-note]')).not.toBeNull();
  });
});
