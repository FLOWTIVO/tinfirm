import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type {
  Conversation,
  ConversationReader,
  KillSwitch,
  RunsStop,
} from '../api/generated/models';
import { aRunsStop, aSuspendedMember } from '../testing/fixtures';
import { problem, provideTestHttp, settle } from '../testing/test-helpers';
import { words } from '../testing/words';
import { ClientBadge } from './client-badge';
import { ConversationReads, readKindsPhrase } from './conversation-reads';
import { KillSwitchBanner } from './kill-switch-banner';

describe('ClientBadge', () => {
  it.each<[Conversation['origin'] | null, boolean]>([
    ['CLIENT', true],
    ['APP', false],
    ['TRIGGER', false],
    ['DIRECT_MESSAGE', false],
    [null, false],
  ])('marks a conversation of origin %s', async (origin, shown) => {
    const fixture = TestBed.createComponent(ClientBadge);
    fixture.componentRef.setInput('origin', origin);
    await fixture.whenStable();
    const badge = fixture.nativeElement.querySelector('[data-testid=client-badge]');
    expect(!!badge).toBe(shown);
    if (shown) expect(badge.textContent.trim()).toBe('AI client');
  });
});

describe('KillSwitchBanner', () => {
  async function render(stop: RunsStop | null, suspension: KillSwitch | null, manages = false) {
    TestBed.configureTestingModule({ imports: [KillSwitchBanner], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(KillSwitchBanner);
    fixture.componentRef.setInput('stop', stop);
    fixture.componentRef.setInput('suspension', suspension);
    fixture.componentRef.setInput('manages', manages);
    fixture.componentRef.setInput('companyId', 'c1');
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows nothing while nothing is on', async () => {
    const el = await render(null, null);
    expect(el.textContent?.trim()).toBe('');
  });

  it.each<[boolean, string | null]>([
    [false, null],
    [true, '/c/c1/kill-switches'],
  ])('says who stopped the runs and why; an admin may go lift it: %s', async (manages, href) => {
    const el = await render(aRunsStop(), null, manages);
    const banner = el.querySelector('[data-testid=runs-stopped-banner]');
    expect(banner?.getAttribute('role')).toBe('status');
    expect(words(banner)).toContain('The company’s runs are stopped');
    expect(words(banner)).toContain('by Adam Admin: “A leaked key”.');
    expect(banner?.querySelector('a')?.getAttribute('href') ?? null).toBe(href);
  });

  it.each<[string, KillSwitch, string]>([
    ['by an admin', aSuspendedMember(), 'by Adam Admin: “Laptop stolen”.'],
    [
      'by the user’s deactivation',
      aSuspendedMember({ cause: 'USER_DEACTIVATED', byName: null, reason: null }),
      'reactivating the user resumes them',
    ],
  ])('tells a suspended member, suspended %s', async (_, suspension, text) => {
    const el = await render(null, suspension);
    expect(words(el.querySelector('[data-testid=my-suspension]'))).toContain(text);
  });
});

describe('what a reader read', () => {
  it.each<[string[], string]>([
    [['conversation'], 'the conversation'],
    [['conversation', 'run-events', 'conversation'], 'the conversation and its transcripts'],
    [
      ['attachment', 'decisions', 'runs'],
      'a file’s content, its questions and approvals and its turns',
    ],
    [['something-new'], 'something new'],
  ])('says what was read: %j', (kinds, text) => {
    expect(readKindsPhrase(kinds)).toBe(text);
  });
});

describe('ConversationReads', () => {
  let http: HttpTestingController;

  async function render(answer: ConversationReader[] | number) {
    TestBed.configureTestingModule({
      imports: [ConversationReads],
      providers: [...provideTestHttp()],
    });
    http = TestBed.inject(HttpTestingController);
    const fixture = TestBed.createComponent(ConversationReads);
    fixture.componentRef.setInput('companyId', 'c1');
    fixture.componentRef.setInput('conversationId', 'cv1');
    await settle();
    const req = http.expectOne('/api/companies/c1/conversations/cv1/reads');
    if (typeof answer === 'number')
      req.flush(problem(answer), { status: answer, statusText: 'No' });
    else req.flush(answer);
    await settle();
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => http.verify());

  it('says when nobody else read it', async () => {
    const el = await render([]);
    expect(words(el.querySelector('[data-testid=no-reads]'))).toBe('Nobody but you has read it.');
  });

  it('lists each reader, how often and what', async () => {
    const el = await render([
      {
        userId: 'u-olga',
        name: 'Olga Owner',
        firstReadAt: '2026-10-11T09:00:00Z',
        lastReadAt: '2026-10-12T09:00:00Z',
        reads: 3,
        kinds: ['conversation', 'run-events'],
      },
      {
        userId: 'u-gone',
        firstReadAt: '2026-10-10T09:00:00Z',
        lastReadAt: '2026-10-10T09:00:00Z',
        reads: 1,
        kinds: ['conversation'],
      },
    ]);
    expect(words(el.querySelector('[data-reader="Olga Owner"]'))).toContain(
      'Olga Owner read the conversation and its transcripts, 3 times.',
    );
    expect(words(el.querySelector('[data-reader=u-gone]'))).toContain(
      'Someone read the conversation, once.',
    );
  });

  it('says only its person sees the list', async () => {
    const el = await render(404);
    expect(words(el)).toContain('Only its person sees who read it.');
  });
});
