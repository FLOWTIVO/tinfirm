import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { LegalHold, Licence, RetentionSettings, Role } from '../../../api/generated/models';
import { Session } from '../../../core/session';
import {
  aHold,
  aMember,
  aCommunityLicence,
  aLicence,
  anEnterpriseLicence,
  anErasure,
  anErasurePreview,
  aRetention,
  aRetentionPreview,
  aSystem,
  IDS,
  noSweep,
} from '../../../testing/fixtures';
import {
  currentUser,
  fakeCurrentCompany,
  fakeLicence,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
  submitForm,
  typeInto,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import {
  askPhrase,
  confirmLead,
  confirmMatches,
  type EraseTarget,
  goesCounts,
  heldTitle,
  holdOn,
  remainsReview,
  reviewOf,
} from './erasure-view';
import { holdSetPhrase } from './holds-panel';
import { personChoices } from './person-choice';
import { RETENTION_PREVIEW_DEBOUNCE, RetentionPage } from './retention';
import {
  duePhrase,
  forecastOf,
  periodErrors,
  periodsForm,
  periodsRequest,
  periodsSummary,
  samePeriods,
  shortenedPeriods,
  shorteningPhrase,
  sweepWords,
} from './retention-view';

const BASE = '/api/companies/c1';
const CONVERSATION = '0192f0c4-0000-7c3a-9b4e-2f1a3c5d7e9f';

describe('the retention periods', () => {
  const form = periodsForm(aRetention());

  it.each<[string, Partial<typeof form>, string[]]>([
    ['the defaults', {}, []],
    ['content kept for good', { contentDays: '', filesDays: '' }, []],
    ['transcripts for under a week', { runEventsDays: '6' }, ['runEventsDays']],
    // Kept for good (ADR-0077): transcripts in any case, chats' words only with all content.
    ['transcripts kept for good', { runEventsDays: '' }, []],
    ['chats kept while content has a period', { chatContentDays: '' }, ['chatContentDays']],
    ['chats and content kept for good', { chatContentDays: '', contentDays: '' }, []],
    ['content for under a month', { contentDays: '29' }, ['contentDays']],
    ['files beyond ten years', { filesDays: '3651' }, ['filesDays']],
    ['chats kept longer than content', { chatContentDays: '400' }, ['chatContentDays']],
    ['chats long, content kept for good', { chatContentDays: '400', contentDays: '' }, []],
    ['a period that is no number', { chatContentDays: 'a month' }, ['chatContentDays']],
    ['memory notes kept for good', { memoryDays: '' }, []],
    ['memory notes for under a month', { memoryDays: '29' }, ['memoryDays']],
  ])('checks %s', (_, change, fields) => {
    expect(Object.keys(periodErrors({ ...form, ...change })).sort()).toEqual(fields);
  });

  it('sends empty periods as kept for good', () => {
    expect(periodsRequest({ ...form, contentDays: ' ', filesDays: '30' })).toEqual({
      runEventsDays: 90,
      chatContentDays: 30,
      contentDays: null,
      filesDays: 30,
      memoryDays: 730,
    });
  });

  it('keeps memory notes for good with 0, and reads 0 back as kept', () => {
    expect(periodsRequest({ ...form, memoryDays: '' }).memoryDays).toBe(0);
    expect(periodsRequest({ ...form, runEventsDays: '' }).runEventsDays).toBeNull();
    expect(periodsForm(aRetention({ memoryDays: 0 })).memoryDays).toBe('');
    expect(periodsForm(aRetention({ memoryDays: 365 })).memoryDays).toBe('365');
  });

  it.each<[Parameters<typeof noSweep>[0], string]>([
    [{}, 'The next daily sweep removes nothing.'],
    [{ conversations: 1 }, 'The next daily sweep removes the content of 1 conversation.'],
    [
      { conversations: 2, messages: 1204, files: 3, runEvents: 5 },
      'The next daily sweep removes 1,204 messages, 3 files, the content of 2 conversations and the transcripts of 5 turns.',
    ],
    // A chat's words alone: no conversation's whole content goes, its messages do.
    [
      { messages: 2, decisions: 1 },
      'The next daily sweep removes 2 messages and 1 question or approval.',
    ],
  ])('says what the next sweep removes, as the preview counts it: %o', (next, text) => {
    expect(duePhrase({ nextSweep: noSweep(next) })).toBe(text);
  });

  it.each<[string, Parameters<typeof noSweep>[0], string | null]>([
    ['nothing', {}, null],
    [
      'messages and conversations, thousands marked',
      { messages: 1204, conversations: 31, decisions: 40, outputs: 12, runs: 31 },
      '1,204 messages, 40 questions and approvals and the content of 31 conversations',
    ],
    [
      'one of each',
      { messages: 1, files: 1, runEvents: 1 },
      '1 message, 1 file and the transcripts of 1 turn',
    ],
    ['outputs alone', { outputs: 2 }, '2 outputs’ titles'],
    [
      'agents’ memory notes',
      { files: 2, memoryNotes: 14 },
      '2 files and 14 memory notes',
    ],
  ])('says what a sweep deletes: %s', (_, counts, text) => {
    expect(sweepWords(noSweep(counts))).toBe(text);
  });

  it('says what the changed periods delete, beside the saved ones and what the holds keep', () => {
    expect(forecastOf(aRetentionPreview())).toEqual({
      proposed:
        'With these periods, the next sweep deletes 1,204 messages, 40 questions and approvals, 9 files and the content of 31 conversations.',
      current: 'With the saved ones, nothing.',
      held: 'Legal holds keep 40 messages and the content of 1 conversation from it.',
      some: true,
    });
    expect(
      forecastOf(
        aRetentionPreview({
          proposed: noSweep(),
          current: noSweep({ runEvents: 3 }),
          held: noSweep(),
        }),
      ),
    ).toEqual({
      proposed: 'With these periods, the next sweep deletes nothing.',
      current: 'With the saved ones, the transcripts of 3 turns.',
      held: null,
      some: false,
    });
  });

  it('compares periods by value, kept for good as null', () => {
    const a = periodsRequest(form);
    expect(samePeriods(a, { ...a })).toBe(true);
    expect(samePeriods(a, { ...a, contentDays: 180 })).toBe(false);
    expect(samePeriods(a, { ...a, memoryDays: 60 })).toBe(false);
    expect(samePeriods({ ...a, filesDays: null }, { ...a, filesDays: undefined })).toBe(true);
    expect(samePeriods(a, null)).toBe(false);
  });

  it('sums the saved periods up for the rail, in the order of the page', () => {
    expect(periodsSummary(aRetention())).toBe('365 · 365 · 30 · 90 · 730');
    expect(periodsSummary(aRetention({ contentDays: null, filesDays: 30 }))).toBe(
      'keep · 30 · 30 · 90 · 730',
    );
  });

  it.each<[string, Partial<typeof form>, string[]]>([
    ['nothing changed', {}, []],
    ['a longer period', { runEventsDays: '120', filesDays: '' }, []],
    ['a shorter period', { contentDays: '180' }, ['contentDays']],
    ['shorter by spaces only', { contentDays: ' 365 ' }, []],
  ])('finds the periods made shorter: %s', (_, change, fields) => {
    expect(shortenedPeriods({ ...form, ...change }, form)).toEqual(fields);
  });

  it('counts a number for a period kept for good as shorter, and says so', () => {
    const saved = { ...form, filesDays: '' };
    const typed = { ...saved, filesDays: '30' };
    expect(shortenedPeriods(typed, saved)).toEqual(['filesDays']);
    expect(shorteningPhrase('filesDays', typed, saved)).toBe('Files: 30 days, was kept for good');
    expect(shorteningPhrase('contentDays', { ...form, contentDays: '180' }, form)).toBe(
      'Conversations’ content: 180 days, was 365',
    );
  });

  it('offers the member who left first, then every member by name', () => {
    expect(
      personChoices(
        [
          aMember({ userId: 'u-b', principalId: 'p-b', displayName: 'Bob' }),
          aMember({ userId: 'u-a', principalId: 'p-a', displayName: 'Alice' }),
          // A member without a principal in the company names nobody to hold or erase.
          aMember({ userId: 'u-x', principalId: null, displayName: 'Xavier' }),
        ],
        { principalId: 'p-dave', name: 'Dave' },
      ),
    ).toEqual([
      { value: 'p-dave', label: 'Dave (left)', name: 'Dave', left: true },
      { value: 'p-a', label: 'Alice', name: 'Alice', left: false },
      { value: 'p-b', label: 'Bob', name: 'Bob', left: false },
    ]);
  });

  it('says who set a hold and since when, or since when alone', () => {
    const names = new Map([[IDS.ownerUser, 'Olga Owner']]);
    expect(holdSetPhrase(aHold({ setByUserId: IDS.ownerUser }), names)).toMatch(
      /^Set by Olga Owner · since \S/,
    );
    expect(holdSetPhrase(aHold({ setByUserId: 'u-gone' }), names)).toMatch(/^Set since \S/);
  });
});

describe('what an erasure says before it is made', () => {
  const left: EraseTarget = { kind: 'person', principalId: 'p-dave', name: 'Dave', left: true };
  const here: EraseTarget = { kind: 'person', principalId: IDS.alice, name: 'Alice', left: false };
  const outside: EraseTarget = {
    kind: 'identity',
    systemId: IDS.slack,
    systemName: 'Slack',
    externalId: 'U123',
  };
  const whats = (items: readonly { what: string }[]) => items.map((i) => i.what);
  const noErasure = {
    conversations: 0,
    messages: 0,
    decisions: 0,
    outputs: 0,
    files: 0,
    runs: 0,
    runEvents: 0,
  };

  it('takes everything of a member who left, and leaves their account to the instance', () => {
    const review = reviewOf(left);
    expect(whats(review.goes)).toEqual([
      'Their personal conversations',
      'What they said in triggers’ conversations',
      'The files they added',
      'Their answers',
    ]);
    expect(whats(review.stays)).toEqual([
      'What each conversation was',
      'Its spend',
      'The audit log',
    ]);
    expect(whats(review.elsewhere)).toContain('Their Tinfirm account');
  });

  it('takes only the ended conversations of a member still here, and keeps their open ones', () => {
    const review = reviewOf(here);
    expect(whats(review.goes)).toEqual(['Their ended personal conversations', 'The files in them']);
    expect(whats(review.stays)[0]).toBe('Their open personal conversations');
    expect(whats(review.elsewhere)).toEqual(['Runner hosts’ sessions']);
  });

  it('takes the words of someone outside Tinfirm, and names the system that keeps them', () => {
    const review = reviewOf(outside);
    expect(whats(review.goes)).toEqual([
      'Their words in every conversation',
      'The transcripts that quoted them',
      'Their name',
    ]);
    expect(review.elsewhere.map((e) => e.detail)).toContain(
      'Their messages stay in Slack: remove them there.',
    );
  });

  it('knows a hold on the person before the dry run answers, never one on a conversation', () => {
    const onAlice = aHold();
    const onConversation = aHold({ id: 'h2', principalId: null, conversationId: CONVERSATION });
    expect(holdOn([onConversation, onAlice], here)).toBe(onAlice);
    expect(holdOn([aHold({ liftedAt: '2026-10-01T00:00:00Z' })], here)).toBeNull();
    expect(holdOn([onAlice, onConversation], outside)).toBeNull();
  });

  it('heads the holds that stop an erasure by what they hold', () => {
    const onAlice = aHold();
    const onConversation = aHold({ id: 'h2', principalId: null, conversationId: CONVERSATION });
    expect(heldTitle(here, [onAlice])).toBe('A legal hold covers Alice’s content');
    expect(heldTitle(here, [onConversation])).toBe('A legal hold covers some of it');
    expect(heldTitle(outside, [onAlice, onConversation])).toBe('2 legal holds cover some of it');
  });

  it.each<[string, (string | undefined)[], string]>([
    [
      'another member who set it',
      ['u-olga'],
      'Ask Olga Owner, who set it, whether it can be lifted.',
    ],
    ['the caller, who set it', ['u-me'], 'You set it: lift it under Legal holds, then erase.'],
    [
      'two members',
      ['u-olga', 'u-bob'],
      'Ask Olga Owner and Bob Svoboda, who set them, whether they can be lifted.',
    ],
    ['a setter no longer here', ['u-gone'], 'Ask whoever asked for it whether it can be lifted.'],
    ['nobody named', [undefined], 'Ask whoever asked for it whether it can be lifted.'],
  ])('says whom to ask when %s', (_, setters, text) => {
    const names = new Map([
      ['u-olga', 'Olga Owner'],
      ['u-bob', 'Bob Svoboda'],
    ]);
    const holds = setters.map((setByUserId, i) => aHold({ id: `h${i}`, setByUserId }));
    expect(askPhrase(holds, names, 'u-me')).toBe(text);
  });

  it('counts what goes, by kind, in the words of the target', () => {
    const counted = goesCounts(left, anErasurePreview().erases);
    expect(counted.map((c) => [c.count, c.what])).toEqual([
      [2, 'Conversations'],
      [14, 'Messages'],
      [1, 'Questions and approvals'],
      [1, 'Files'],
      [3, 'Turns’ summaries'],
      [3, 'Transcripts'],
    ]);
    expect(counted[1]!.detail).toContain('what they said in others’');
    const words = goesCounts(outside, {
      ...anErasurePreview().erases,
      conversations: 0,
      messages: 0,
      decisions: 0,
      files: 0,
      runs: 0,
      runEvents: 2,
      other: { messages: 4, chatUsers: 1, somethingNew: 2 },
    });
    expect(words.map((c) => [c.count, c.what])).toEqual([
      [4, 'Their messages'],
      [1, 'Their chat profile'],
      [2, 'Something new'],
      [2, 'Transcripts that quoted them'],
    ]);
  });

  it('says what remains as the dry run names it, in the review’s words and order', () => {
    const remains = remainsReview(left, anErasurePreview().remains);
    expect(remains.stays.map((s) => s.what)).toEqual([
      'What each conversation was',
      'The audit log',
    ]);
    expect(remains.elsewhere.map((s) => s.what)).toEqual([
      'Runner hosts’ sessions',
      'The source system',
      'The agent’s own words · 2',
      'Their Tinfirm account',
    ]);
    const open = remainsReview(here, [
      { what: 'AUDIT', where: 'w', removedBy: 'r' },
      { what: 'OPEN_CONVERSATIONS', where: 'w', removedBy: 'r', count: 1204 },
      { what: 'SOMETHING_NEW', where: 'Somewhere', removedBy: 'Someone' },
    ]);
    expect(open.stays.map((s) => s.what)).toEqual([
      'Their open personal conversations · 1,204',
      'The audit log',
    ]);
    expect(open.elsewhere).toEqual([{ what: 'something new', detail: 'Somewhere. Someone.' }]);
  });

  it('asks for the name or the id, whatever its case, and says what goes', () => {
    expect(confirmMatches(here, ' alice ')).toBe(true);
    expect(confirmMatches(here, 'Alic')).toBe(false);
    expect(confirmMatches(outside, 'u123')).toBe(true);
    expect(confirmLead(here)).toBe(
      'Alice’s content goes now: their ended personal conversations and the files in them.',
    );
    expect(confirmLead(outside)).toBe(
      'The words of U123 on Slack go now: their words in every conversation, the transcripts that quoted them and their name where it was kept.',
    );
    expect(confirmLead(left, anErasurePreview().erases)).toBe(
      'Dave’s content goes now: 2 conversations, 14 messages, 1 decision, 1 file, the texts of 3 turns and the transcripts of 3 turns.',
    );
    expect(confirmLead(here, noErasure)).toBe('Alice’s content goes now: nothing of it is left.');
  });
});

describe('RetentionPage', () => {
  let fixture: ComponentFixture<RetentionPage>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(
    role: Role,
    settings: RetentionSettings = aRetention(),
    holds: LegalHold[] = [],
    query: Record<string, string> = {},
    licence: Licence = anEnterpriseLicence(),
  ) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      imports: [RetentionPage],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany(role).provider,
        notifier.provider,
        fakeLicence(licence).provider,
        { provide: RETENTION_PREVIEW_DEBOUNCE, useValue: 0 },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(RetentionPage);
    for (const [key, value] of Object.entries(query)) fixture.componentRef.setInput(key, value);
    el = fixture.nativeElement;
    await settle();
    http.expectOne(`${BASE}/retention`).flush(settings);
    if (role === 'OWNER') {
      http
        .expectOne((r) => r.url === `${BASE}/retention/open-conversations`)
        .flush({
          items: settings.openOlder
            ? [
                {
                  id: 'cv-old',
                  key: 'SHOP-9-1',
                  place: 'TRACKER_ISSUE',
                  createdAt: '2025-01-01T00:00:00Z',
                  ageDays: 400,
                },
              ]
            : [],
        });
      http.expectOne((r) => r.url === `${BASE}/holds`).flush({ items: holds });
      http
        .expectOne((r) => r.url === `${BASE}/members`)
        .flush({
          items: [
            aMember({ userId: IDS.aliceUser, principalId: IDS.alice, displayName: 'Alice' }),
            aMember({
              userId: IDS.ownerUser,
              principalId: IDS.owner,
              displayName: 'Olga Owner',
              role: 'OWNER',
            }),
          ],
        });
      await settle();
      http
        .expectOne((r) => r.url === `${BASE}/systems`)
        .flush({
          items: [aSystem({ id: IDS.jira, name: 'Jira' })],
        });
    }
    await settle();
    await fixture.whenStable();
  }

  async function idle() {
    await settle();
    await fixture.whenStable();
  }

  const q = (id: string) => el.querySelector<HTMLElement>(`[data-testid=${id}]`);
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const inOverlay = (id: string) => overlay().querySelector<HTMLElement>(`[data-testid=${id}]`);
  const tab = (id: string) => el.querySelector<HTMLButtonElement>(`#retention-tab-${id}`)!;
  const panel = (id: string) => el.querySelector<HTMLElement>(`#retention-panel-${id}`)!;
  const detail = (id: string) => words(tab(id).querySelector('[data-testid=section-detail]'));

  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('under Community lets the Owner read the periods and lift holds, never set them (ADR-0077)', async () => {
    await open('OWNER', aRetention({ mode: 'KEEP_EVERYTHING' }), [aHold()], {}, aCommunityLicence());

    expect(el.querySelector('input#period-runEventsDays')).toBeNull();
    expect(words(q('periods-edition'))).toContain(
      'Under Community nothing is removed by retention: everything is kept. Periods of your own need a Pro licence.',
    );
    expect(q('set-hold')).toBeNull();
    expect(words(q('holds-edition'))).toBe(
      'Setting a hold needs an Enterprise licence. The holds already set stay in force, and can be lifted.',
    );
  });

  it('under Pro lets the Owner change the periods, but sets no hold', async () => {
    await open('OWNER', aRetention(), [], {}, aLicence());

    expect(el.querySelector('input#period-runEventsDays')).not.toBeNull();
    expect(q('periods-edition')).toBeNull();
    expect(q('set-hold')).toBeNull();
    expect(q('holds-edition')).not.toBeNull();
  });

  it('follows the server’s mode over the licence’s features: frozen periods', async () => {
    await open('OWNER', aRetention({ mode: 'FROZEN' }), [], {}, anEnterpriseLicence());
    expect(el.querySelector('input#period-runEventsDays')).toBeNull();
    expect(words(q('periods-edition'))).toContain('the periods in force keep running');
  });

  it('shows Community’s periods as kept for good', async () => {
    await open(
      'OWNER',
      aRetention({
        mode: 'KEEP_EVERYTHING',
        runEventsDays: null,
        chatContentDays: null,
        contentDays: null,
        filesDays: null,
      }),
      [],
      {},
      aCommunityLicence(),
    );
    expect(el.querySelector('input#period-contentDays')).toBeNull();
    expect(words(q('periods'))).toContain('Transcripts What the agent did in each turn');
    expect(words(q('periods-edition'))).toContain('Under Community nothing is removed by retention');
  });

  it('says the periods in force keep running once the licence ran out', async () => {
    await open('OWNER', aRetention({ mode: 'FROZEN' }), [], {}, aCommunityLicence({ state: 'EXPIRED' }));
    expect(words(q('periods-edition'))).toContain(
      'The licence ran out: the periods in force keep running, and changing them needs a Pro licence again.',
    );
  });

  it('shows an admin the periods to read, and no sections, holds or erasure', async () => {
    await open('ADMIN', aRetention({ contentDays: null }));
    expect(el.querySelector('input#period-runEventsDays')).toBeNull();
    expect(q('retention-sections')).toBeNull();
    expect(words(q('periods'))).toContain(
      'Conversations’ content Messages, titles, questions and answers, results and outputs’ titles. Kept for good',
    );
    expect(words(q('periods'))).toContain(
      'Transcripts What the agent did in each turn: its steps, tool calls and their results. 90 days after the turn ended',
    );
    expect(words(q('periods'))).toContain('Agents’ memory notes');
    expect(words(q('periods'))).toContain('730 days after their last use');
    expect(words(q('periods'))).toContain('Only the Owner changes retention');
    expect(q('holds')).toBeNull();
    expect(q('erasure')).toBeNull();
  });

  it('names each section with its state, and opens one at a time', async () => {
    await open('OWNER', aRetention(), [aHold()]);
    expect(detail('periods')).toBe('365 · 365 · 30 · 90 · 730 days');
    expect(detail('holds')).toBe('1 active');
    expect(detail('erase')).toBe('Removes content now');
    expect(tab('periods').getAttribute('aria-selected')).toBe('true');
    expect(panel('periods').hidden).toBe(false);
    expect(panel('holds').hidden).toBe(true);

    tab('periods').dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    await idle();
    expect(tab('holds').getAttribute('aria-selected')).toBe('true');
    expect(tab('holds').tabIndex).toBe(0);
    expect(tab('periods').tabIndex).toBe(-1);
    expect(panel('holds').hidden).toBe(false);

    tab('holds').dispatchEvent(new KeyboardEvent('keydown', { key: 'End' }));
    await idle();
    expect(panel('erase').hidden).toBe(false);
    tab('erase').click();
    tab('periods').click();
    await idle();
    expect(panel('periods').hidden).toBe(false);
  });

  /** The preview of the periods as typed, answered. */
  async function previewed(answer = aRetentionPreview()) {
    const req = http.expectOne({ method: 'POST', url: `${BASE}/retention/preview` });
    req.flush(answer);
    await idle();
    return req.request.body;
  }

  it('saves a longer period without asking, and says what the next sweep removes', async () => {
    await open('OWNER');
    typeInto(el.querySelector('#period-runEventsDays'), '120');
    await settle();
    expect(words(q('unsaved-count'))).toBe('1 unsaved change');
    expect(words(q('unsaved-names'))).toBe('Transcripts');
    expect(words(q('period-was'))).toBe('was 90');
    expect(detail('periods')).toBe('365 · 365 · 30 · 90 · 730 days · unsaved');
    expect(await previewed(aRetentionPreview({ proposed: noSweep(), held: noSweep() }))).toEqual({
      runEventsDays: 120,
      chatContentDays: 30,
      contentDays: 365,
      filesDays: 365,
      memoryDays: 730,
    });
    expect(words(q('due'))).toContain(
      'With these periods, the next sweep deletes nothing. With the saved ones, nothing.',
    );

    submitForm(el.querySelector('#periods-form'));
    await settle();
    const req = http.expectOne({ method: 'PUT', url: `${BASE}/retention` });
    expect(req.request.body).toEqual({
      runEventsDays: 120,
      chatContentDays: 30,
      contentDays: 365,
      filesDays: 365,
      memoryDays: 730,
    });
    req.flush(aRetention({ runEventsDays: 120, defaults: false }));
    await idle();
    expect(notify.success).toHaveBeenCalledWith(
      'Retention saved',
      'The next daily sweep removes nothing.',
    );
    expect(q('unsaved-count')).toBeNull();
    expect(q('periods-saved')).not.toBeNull();
    expect(detail('periods')).toBe('365 · 365 · 30 · 120 · 730 days');
  });

  it('asks before saving a shorter period, and says what the next sweep then deletes', async () => {
    await open('OWNER');
    typeInto(el.querySelector('#period-contentDays'), '30');
    await settle();
    await previewed();
    submitForm(el.querySelector('#periods-form'));
    await idle();
    http.expectNone({ method: 'PUT', url: `${BASE}/retention` });
    expect(words(inOverlay('shorter-periods-dialog'))).toContain('Save the shorter periods?');
    expect(words(inOverlay('shorter-periods'))).toBe('Conversations’ content: 30 days, was 365');
    expect(words(inOverlay('shorter-periods-count'))).toBe(
      'With these periods, the next sweep deletes 1,204 messages, 40 questions and approvals, 9 files and the content of 31 conversations. Legal holds keep 40 messages and the content of 1 conversation from it.',
    );

    inOverlay('confirm-save-retention')!.click();
    await settle();
    const req = http.expectOne({ method: 'PUT', url: `${BASE}/retention` });
    expect(req.request.body).toEqual({
      runEventsDays: 90,
      chatContentDays: 30,
      contentDays: 30,
      filesDays: 365,
      memoryDays: 730,
    });
    req.flush(
      aRetention({
        contentDays: 30,
        defaults: false,
        due: { conversations: 3, runs: 0, files: 0 },
        nextSweep: noSweep({ conversations: 3 }),
      }),
    );
    await idle();
    expect(notify.success).toHaveBeenCalledWith(
      'Retention saved',
      'The next daily sweep removes the content of 3 conversations.',
    );
    expect(words(q('due'))).toContain(
      'The next daily sweep removes the content of 3 conversations.',
    );
  });

  it('counts what the changed periods delete once the typing pauses, beside the saved ones', async () => {
    await open('OWNER');
    const content = el.querySelector('#period-contentDays');
    // Typed in a row: one question, for the periods as they ended up.
    typeInto(content, '20');
    typeInto(content, '200');
    typeInto(content, '180');
    await settle();
    expect(await previewed()).toEqual({
      runEventsDays: 90,
      chatContentDays: 30,
      contentDays: 180,
      filesDays: 365,
      memoryDays: 730,
    });
    http.expectNone({ method: 'POST', url: `${BASE}/retention/preview` });
    expect(words(q('due-proposed'))).toBe(
      'With these periods, the next sweep deletes 1,204 messages, 40 questions and approvals, 9 files and the content of 31 conversations.',
    );
    expect(words(q('due'))).toContain('With the saved ones, nothing.');
    expect(words(q('due-held'))).toBe(
      'Legal holds keep 40 messages and the content of 1 conversation from it.',
    );

    // A marked period asks nothing; back to the saved periods, the line counts them again.
    typeInto(content, '10');
    await idle();
    http.expectNone({ method: 'POST', url: `${BASE}/retention/preview` });
    expect(words(q('due'))).toContain(
      'The next daily sweep removes nothing. Counted for the saved periods.',
    );
    typeInto(content, '365');
    await idle();
    http.expectNone({ method: 'POST', url: `${BASE}/retention/preview` });
    expect(q('due-proposed')).toBeNull();
  });

  it('says when what the changed periods delete could not be counted, and counts again', async () => {
    await open('OWNER');
    typeInto(el.querySelector('#period-filesDays'), '30');
    await settle();
    http
      .expectOne({ method: 'POST', url: `${BASE}/retention/preview` })
      .flush(problem(500), { status: 500, statusText: 'Server Error' });
    await idle();
    expect(words(q('due-preview-failed'))).toContain(
      'Counted for the saved periods: what the new ones delete couldn’t be counted.',
    );
    submitForm(el.querySelector('#periods-form'));
    await idle();
    expect(words(inOverlay('shorter-periods-count'))).toBe(
      'What goes couldn’t be counted now; the line under the periods says it once they are saved.',
    );
    overlay().querySelector<HTMLButtonElement>('[hlmAlertDialogCancel]')!.click();
    await idle();

    q('due-preview-retry')!.click();
    await settle();
    await previewed(aRetentionPreview({ proposed: noSweep({ files: 3 }), held: noSweep() }));
    expect(words(q('due-proposed'))).toBe('With these periods, the next sweep deletes 3 files.');
  });

  it('discards the changes', async () => {
    await open('OWNER');
    const files = el.querySelector<HTMLInputElement>('#period-filesDays')!;
    typeInto(files, '');
    await settle();
    await previewed();
    expect(words(q('periods'))).toContain('kept for good was 365');
    q('unsaved-count')!.closest('app-unsaved-changes-bar')!.querySelector('button')!.click();
    await idle();
    expect(files.value).toBe('365');
    expect(q('unsaved-count')).toBeNull();
  });

  it('checks the periods as they are typed, and explains RETENTION_INVALID', async () => {
    await open('OWNER');
    typeInto(el.querySelector('#period-chatContentDays'), '400');
    await idle();
    expect(words(q('periods'))).toContain(
      'Chats’ words cannot be kept longer than conversations’ content.',
    );
    expect(words(q('unsaved-names'))).toBe('Fix the marked period first.');
    expect(q('save-retention')!.hasAttribute('disabled')).toBe(true);
    submitForm(el.querySelector('#periods-form'));
    await settle();
    http.expectNone({ method: 'PUT', url: `${BASE}/retention` });

    typeInto(el.querySelector('#period-chatContentDays'), '40');
    await settle();
    await previewed();
    submitForm(el.querySelector('#periods-form'));
    await settle();
    http
      .expectOne({ method: 'PUT', url: `${BASE}/retention` })
      .flush(problem(400, 'RETENTION_INVALID'), { status: 400, statusText: 'Bad Request' });
    await idle();
    expect(words(q('periods-error'))).toContain('A period is out of range');
  });

  it('lists the open conversations older than their period by key, never by title', async () => {
    await open('OWNER', aRetention({ openOlder: 1 }));
    expect(words(q('open-conversations'))).toContain(
      '1 open conversation is older than its period.',
    );
    const row = q('open-conversations')!.querySelector('li');
    expect(words(row)).toBe('SHOP-9-1, on an issue, open for 400 days');
    expect(row?.querySelector('a')?.getAttribute('href')).toBe('/c/c1/conversations/cv-old');
  });

  async function openSetHold() {
    tab('holds').click();
    await idle();
    q('set-hold')!.click();
    await idle();
  }

  it('holds a conversation by its link, with a reason', async () => {
    await open('OWNER');
    await openSetHold();
    overlay().querySelector<HTMLInputElement>('#hold-kind-conversation')!.click();
    await idle();
    expect(words(inOverlay('hold-untold'))).toBe(
      'Nobody in the conversation is told. It goes on as before.',
    );
    typeInto(
      overlay().querySelector('#hold-conversation'),
      `https://t.test/c/c1/conversations/${CONVERSATION}`,
    );
    typeInto(overlay().querySelector('textarea'), 'A customer’s claim');
    await settle();
    inOverlay('hold-dialog-confirm')!.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: `${BASE}/holds` });
    expect(req.request.body).toEqual({
      reason: 'A customer’s claim',
      conversationId: CONVERSATION,
    });
    req.flush(
      aHold({
        principalId: null,
        principalName: null,
        conversationId: CONVERSATION,
        conversationKey: 'SHOP-1-1',
      }),
    );
    await settle();
    http.expectOne((r) => r.url === `${BASE}/holds`).flush({ items: [] });
    http.expectOne(`${BASE}/retention`).flush(aRetention());
    await settle();
    expect(notify.success).toHaveBeenCalledWith('SHOP-1-1 is on hold');
  });

  it('holds a conversation by its key, looked up without reading it', async () => {
    await open('OWNER');
    await openSetHold();
    overlay().querySelector<HTMLInputElement>('#hold-kind-conversation')!.click();
    await idle();
    typeInto(overlay().querySelector('#hold-conversation'), ' SHOP-12-1 ');
    typeInto(overlay().querySelector('textarea'), 'A claim');
    await settle();
    inOverlay('hold-dialog-confirm')!.click();
    await settle();
    http
      .expectOne(`${BASE}/holds/conversations/SHOP-12-1`)
      .flush({ conversationId: CONVERSATION, key: 'SHOP-12-1' });
    await settle();
    const req = http.expectOne({ method: 'POST', url: `${BASE}/holds` });
    expect(req.request.body).toEqual({ reason: 'A claim', conversationId: CONVERSATION });
    req.flush(
      aHold({
        principalId: null,
        principalName: null,
        conversationId: CONVERSATION,
        conversationKey: 'SHOP-12-1',
      }),
    );
    await settle();
    http.expectOne((r) => r.url === `${BASE}/holds`).flush({ items: [] });
    http.expectOne(`${BASE}/retention`).flush(aRetention());
    await settle();
    expect(notify.success).toHaveBeenCalledWith('SHOP-12-1 is on hold');
  });

  it('says when no conversation has the key', async () => {
    await open('OWNER');
    await openSetHold();
    overlay().querySelector<HTMLInputElement>('#hold-kind-conversation')!.click();
    await idle();
    typeInto(overlay().querySelector('#hold-conversation'), 'NOPE-1');
    typeInto(overlay().querySelector('textarea'), 'A claim');
    await settle();
    inOverlay('hold-dialog-confirm')!.click();
    await settle();
    http
      .expectOne(`${BASE}/holds/conversations/NOPE-1`)
      .flush(problem(404), { status: 404, statusText: 'Not Found' });
    await idle();
    expect(words(inOverlay('hold-dialog'))).toContain(
      'No conversation of this company has this key.',
    );
  });

  it('holds a person by their principal, and says they are not told', async () => {
    await open('OWNER');
    await openSetHold();
    expect(words(inOverlay('hold-untold'))).toBe(
      'Alice is not told. Their conversations go on as before.',
    );
    typeInto(overlay().querySelector('textarea'), 'Litigation');
    await settle();
    inOverlay('hold-dialog-confirm')!.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: `${BASE}/holds` });
    expect(req.request.body).toEqual({ reason: 'Litigation', principalId: IDS.alice });
    req.flush(aHold());
    await settle();
    http
      .expectOne((r) => r.url === `${BASE}/holds`)
      .flush({ items: [aHold({ setByUserId: IDS.ownerUser })] });
    http.expectOne(`${BASE}/retention`).flush(aRetention());
    await idle();
    const row = words(el.querySelector('[data-hold=Alice]'));
    expect(row).toContain('Alice · Person “Litigation with a customer” Set by Olga Owner · since');
    expect(detail('holds')).toBe('1 active');
  });

  it('lifts a hold', async () => {
    await open('OWNER', aRetention(), [aHold()]);
    tab('holds').click();
    await idle();
    el.querySelector<HTMLButtonElement>('button[aria-label="Lift the hold on Alice"]')!.click();
    await idle();
    expect(words(inOverlay('lift-hold-dialog'))).toContain('Nobody is told.');
    inOverlay('confirm-lift-hold')!.click();
    await settle();
    http
      .expectOne({ method: 'DELETE', url: `${BASE}/holds/hold-1` })
      .flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    http.expectOne((r) => r.url === `${BASE}/holds`).flush({ items: [] });
    http.expectOne(`${BASE}/retention`).flush(aRetention());
    await idle();
    expect(notify.success).toHaveBeenCalledWith('The hold on Alice is lifted');
    expect(words(q('no-holds'))).toContain('No hold is set.');
    expect(detail('holds')).toBe('None');
  });

  /** The dry run of Dave's erasure, answered. */
  async function dryRun(answer: object, failed?: { status: number; statusText: string }) {
    const req = http.expectOne({ method: 'POST', url: `${BASE}/principals/p-dave/erase/preview` });
    expect(req.request.body).toEqual({});
    req.flush(answer, failed);
    await idle();
  }

  /** From the checklist's link: Dave, who left, picked; reviewed, counted, then confirmed. */
  async function toConfirm() {
    q('erase-review')!.click();
    await settle();
    await dryRun(anErasurePreview());
    q('erase-next')!.click();
    await idle();
  }

  async function erase(answer: (url: string) => void | Promise<void>) {
    typeInto(el.querySelector('#erase-typed'), ' dave ');
    await idle();
    submitForm(q('confirm-erase')!.closest('form'));
    await settle();
    await answer(`${BASE}/principals/p-dave/erase`);
    await idle();
  }

  it('erases a person who left in three steps and says what remains', async () => {
    await open('OWNER', aRetention(), [], { erase: 'p-dave', name: 'Dave' });
    expect(panel('erase').hidden).toBe(false);
    expect(el.querySelector<HTMLSelectElement>('#erase-person')!.value).toBe('p-dave');
    expect(words(q('erase-person-note'))).toBe('Dave left Acme: everything of theirs goes.');

    q('erase-review')!.click();
    await settle();
    expect(detail('erase')).toBe('Step 2 of 3 · Dave');
    expect(words(q('erasure-target'))).toContain('Dave Left the company · everything of theirs');
    // Until the dry run answers, the review says it in words.
    expect(words(q('erasure-counting'))).toBe('Counting what goes…');
    expect(words(q('erasure-goes'))).toContain('Their personal conversations');

    await dryRun(anErasurePreview());
    expect(words(q('erasure-counts'))).toContain(
      '2 Conversations Their personal conversations, erased whole. 14 Messages',
    );
    expect(words(q('erasure-goes'))).toContain('Goes now');
    expect(words(q('erasure-stays'))).toContain(
      'Stays What each conversation was Its key, agent, place and dates, and the spend of every turn, without its words. The audit log',
    );
    expect(words(q('erasure-elsewhere'))).toContain('The agent’s own words · 2');
    expect(words(q('erasure-counted'))).toContain('Counted now, as the erasure counts');
    expect(q('erasure-held')).toBeNull();

    q('erase-next')!.click();
    await idle();
    // The confirmation counts what goes; the dry run is not asked again.
    expect(words(q('erase-lead'))).toBe(
      'Dave’s content goes now: 2 conversations, 14 messages, 1 decision, 1 file, the texts of 3 turns and the transcripts of 3 turns.',
    );
    expect(q('confirm-erase')!.hasAttribute('disabled')).toBe(true);
    await erase((url) => http.expectOne({ method: 'POST', url }).flush(anErasure()));
    const result = words(q('erasure-result'));
    expect(result).toContain('Dave: erased');
    expect(result).toContain('Erased 2 conversations, 14 messages');
    expect(result).toContain('Recorded in the audit log as principal.erased.');
    expect(words(q('erasure-remains'))).toBe(
      'The audit log The company’s audit log, ids only. Nobody: the audit log is kept for good The source system The issue’s comments on Jira. The company, at the system',
    );
    expect(detail('erase')).toBe('Done · Dave');

    q('erase-again')!.click();
    await idle();
    expect(q('erase-review')).not.toBeNull();
    expect(detail('erase')).toBe('Removes content now');
  });

  it('stops at the review when a hold covers the person, naming it', async () => {
    const hold = aHold({
      id: 'hold-dave',
      principalId: 'p-dave',
      principalName: 'Dave',
      reason: 'Audit 2026',
      setByUserId: IDS.ownerUser,
    });
    await open('OWNER', aRetention(), [hold], { erase: 'p-dave', name: 'Dave' });
    q('erase-review')!.click();
    await settle();
    // Known from the holds loaded before the dry run answers, and named by it.
    expect(words(q('erasure-held'))).toContain('A legal hold covers Dave’s content');
    await dryRun(anErasurePreview({ holds: [hold] }));
    expect(words(q('erasure-held'))).toContain(
      'A legal hold covers Dave’s content “Audit 2026” Dave · Set by Olga Owner · since',
    );
    expect(words(q('erasure-ask'))).toBe(
      'Nothing of it can be erased while the hold stands. Ask Olga Owner, who set it, whether it can be lifted.',
    );
    expect(words(q('erasure-goes'))).toContain('Would go, once the hold is lifted 2 Conversations');
    // No way on to the erasure while the hold stands.
    expect(q('erase-next')).toBeNull();
    expect(detail('erase')).toBe('Held · Dave');

    q('erase-to-holds')!.click();
    await idle();
    expect(panel('holds').hidden).toBe(false);
    expect(document.activeElement).toBe(tab('holds'));
  });

  it('names the holds that refuse an erasure, found only as it is made', async () => {
    const hold = aHold({
      id: 'hold-conv',
      principalId: null,
      principalName: null,
      conversationId: CONVERSATION,
      conversationKey: 'SHOP-4-1',
      reason: 'Audit 2026',
    });
    await open('OWNER', aRetention(), [hold], { erase: 'p-dave', name: 'Dave' });
    await toConfirm();
    await erase(async (url) => {
      http
        .expectOne(url)
        .flush(
          { ...problem(409, 'ON_HOLD'), holdIds: ['hold-conv'] },
          { status: 409, statusText: 'Conflict' },
        );
      await settle();
      // The holds are read again: one may have been set meanwhile; the dry run then names it.
      http.expectOne((r) => r.url === `${BASE}/holds`).flush({ items: [hold] });
      http.expectOne(`${BASE}/retention`).flush(aRetention());
      await settle();
      await dryRun(anErasurePreview({ holds: [hold] }));
    });
    expect(words(q('erasure-refused'))).toContain(
      'Dave: nothing was erased A legal hold covers some of it. Lift the hold first:',
    );
    expect(words(q('erasure-holds'))).toBe('SHOP-4-1: “Audit 2026”');
    expect(q('confirm-erase')!.hasAttribute('disabled')).toBe(true);
  });

  it('stops at the review when the dry run finds a hold on a conversation it reaches', async () => {
    const hold = aHold({
      id: 'hold-conv',
      principalId: null,
      principalName: null,
      conversationId: CONVERSATION,
      conversationKey: 'SHOP-4-1',
      reason: 'Audit 2026',
      setByUserId: IDS.ownerUser,
    });
    await open('OWNER', aRetention(), [], { erase: 'p-dave', name: 'Dave' });
    // The Owner who set it is the one reviewing.
    TestBed.inject(Session).setUser(currentUser({ id: IDS.ownerUser }));
    q('erase-review')!.click();
    await settle();
    expect(q('erasure-held')).toBeNull();
    await dryRun(anErasurePreview({ holds: [hold] }));
    expect(words(q('erasure-held'))).toContain(
      'A legal hold covers some of it “Audit 2026” SHOP-4-1 · Set by Olga Owner · since',
    );
    expect(words(q('erasure-ask'))).toBe(
      'Nothing of it can be erased while the hold stands. You set it: lift it under Legal holds, then erase.',
    );
    expect(q('erase-next')).toBeNull();
    expect(detail('erase')).toBe('Held · Dave');
  });

  it('reviews in words when the dry run fails, lets the erasure go on, and counts again', async () => {
    await open('OWNER', aRetention(), [], { erase: 'p-dave', name: 'Dave' });
    q('erase-review')!.click();
    await settle();
    await dryRun(problem(500), { status: 500, statusText: 'Server Error' });
    expect(words(q('erasure-preview-failed'))).toContain(
      'Couldn’t count what goes: Something went wrong on the server. Try again in a moment. The erasure still refuses if a legal hold covers any of it',
    );
    expect(words(q('erasure-goes'))).toContain('Goes now Their personal conversations');
    expect(words(q('erasure-stays'))).toContain('Stays What each conversation was');
    expect(q('erase-next')!.hasAttribute('disabled')).toBe(false);

    q('erasure-preview-retry')!.click();
    await settle();
    const nothing = {
      conversations: 0,
      messages: 0,
      decisions: 0,
      files: 0,
      runs: 0,
      runEvents: 0,
    };
    await dryRun(anErasurePreview({ erases: { ...anErasurePreview().erases, ...nothing } }));
    expect(q('erasure-preview-failed')).toBeNull();
    expect(words(q('erasure-nothing'))).toBe('Nothing of theirs is left to erase.');
  });

  it('explains ERASURE_NOT_ALLOWED', async () => {
    await open('OWNER', aRetention(), [], { erase: 'p-dave', name: 'Dave' });
    await toConfirm();
    await erase((url) =>
      http
        .expectOne(url)
        .flush(problem(409, 'ERASURE_NOT_ALLOWED'), { status: 409, statusText: 'Conflict' }),
    );
    expect(words(q('erasure-refused'))).toContain('One of their conversations is still open');
  });

  it('erases an outside identity by its id at the system', async () => {
    await open('OWNER');
    tab('erase').click();
    await idle();
    el.querySelector<HTMLInputElement>('#erase-who-outside')!.click();
    await idle();
    expect(q('erase-review')!.hasAttribute('disabled')).toBe(true);
    typeInto(el.querySelector('#erase-external'), ' jdoe ');
    await idle();
    q('erase-review')!.click();
    await settle();
    expect(words(q('erasure-target'))).toContain('jdoe on Jira');
    const dry = http.expectOne({
      method: 'POST',
      url: `${BASE}/systems/${IDS.jira}/identities/jdoe/erase/preview`,
    });
    dry.flush(
      anErasurePreview({
        erases: {
          conversations: 0,
          messages: 0,
          decisions: 0,
          outputs: 0,
          files: 0,
          runs: 0,
          runEvents: 2,
          other: { messages: 3, decisions: 1 },
        },
        remains: anErasurePreview().remains.filter((r) => r.what !== 'USER_ACCOUNT'),
      }),
    );
    await idle();
    expect(words(q('erasure-counts'))).toBe(
      '3 Their messages Replaced by the removal marker, open conversations included. 1 Their answers The questions they answered lose their texts and their name. 2 Transcripts that quoted them The turns whose prompts quoted their words.',
    );
    expect(words(q('erasure-elsewhere'))).toContain(
      'Their messages stay in Jira: remove them there.',
    );
    q('erase-next')!.click();
    await idle();
    expect(words(q('erase-lead'))).toBe(
      'The words of jdoe on Jira go now: the transcripts of 2 turns, 3 messages and 1 decision.',
    );
    typeInto(el.querySelector('#erase-typed'), 'JDOE');
    await idle();
    submitForm(q('confirm-erase')!.closest('form'));
    await settle();
    http
      .expectOne({ method: 'POST', url: `${BASE}/systems/${IDS.jira}/identities/jdoe/erase` })
      .flush(anErasure({ remains: [] }));
    await idle();
    expect(words(q('erasure-result'))).toContain('jdoe on Jira: erased');
    expect(words(q('erasure-result'))).toContain('identity.erased');
  });
});
