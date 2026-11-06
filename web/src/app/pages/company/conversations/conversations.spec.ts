import { HttpTestingController, type TestRequest } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type {
  AgentSummary,
  ConversationSummary,
  Role,
  Subject,
} from '../../../api/generated/models';
import {
  aConversationSummary,
  aMember,
  aModelAccess,
  anAgentSummary,
  aSubject,
  AT,
  IDS,
} from '../../../testing/fixtures';
import { fakeCurrentCompany, provideTestHttp, settle } from '../../../testing/test-helpers';
import { Conversations } from './conversations';

const BASE = '/api/companies/c1';

interface Open {
  readonly role?: Role;
  readonly query?: Record<string, string>;
  readonly conversations?: ConversationSummary[];
  /** The answer to the pinned section's own read; by default the open ones of `conversations` that wait on a person. */
  readonly needsPerson?: ConversationSummary[];
  readonly needsPersonFails?: boolean;
  readonly subjects?: Subject[];
  readonly agents?: AgentSummary[];
  readonly triggers?: { id: string; name: string }[];
  readonly me?: string;
}

describe('Conversations', () => {
  let fixture: ComponentFixture<Conversations>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let router: Router;
  /** The pinned section's read (`waitingOn=PERSON`), when the page made one. */
  let pinnedReq: TestRequest | undefined;

  /** Opens the page; answers what it asks; returns the list's request. */
  async function open(options: Open = {}): Promise<TestRequest> {
    TestBed.configureTestingModule({
      imports: [Conversations],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany(options.role ?? 'MEMBER').provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(Conversations);
    for (const [name, value] of Object.entries(options.query ?? {})) {
      fixture.componentRef.setInput(name, value);
    }
    el = fixture.nativeElement;
    await settle();
    for (const req of http.match(`${BASE}/me/model-access`)) {
      req.flush(aModelAccess({ principalId: options.me ?? IDS.alice }));
    }
    for (const req of http.match((r) => r.url === `${BASE}/agents`)) {
      req.flush({ items: options.agents ?? [anAgentSummary()] });
    }
    for (const req of http.match((r) => r.url === `${BASE}/members`)) {
      req.flush({
        items: [
          aMember({ userId: IDS.bobUser, displayName: 'Bob Svoboda', principalId: IDS.bob }),
          aMember({ userId: IDS.aliceUser, displayName: 'Alice Novak', principalId: IDS.alice }),
          aMember({ userId: 'u-gone', displayName: 'No principal', principalId: null }),
        ],
      });
    }
    for (const req of http.match((r) => r.url === `${BASE}/triggers`)) {
      req.flush({
        items: (options.triggers ?? []).map((t) => ({ ...t, sentence: '', status: 'ACTIVE' })),
      });
    }
    await settle();
    pinnedReq = undefined;
    if (options.query?.['view'] === 'subjects') {
      const req = http.expectOne((r) => r.url === `${BASE}/subjects`);
      req.flush({ items: options.subjects ?? [], nextCursor: null });
      await settle();
      return req;
    }
    const requests = http.match((r) => r.url === `${BASE}/conversations`);
    pinnedReq = requests.find(
      (r) =>
        r.request.params.get('waitingOn') === 'PERSON' && options.query?.['waitingOn'] !== 'PERSON',
    );
    const req = requests.find((r) => r !== pinnedReq)!;
    expect(requests).toHaveLength(pinnedReq ? 2 : 1);
    req.flush({ items: options.conversations ?? [], nextCursor: null });
    if (options.needsPersonFails) {
      pinnedReq?.flush(null, { status: 500, statusText: 'Server Error' });
    } else {
      pinnedReq?.flush({
        items:
          options.needsPerson ??
          (options.conversations ?? []).filter(
            (c) => c.state === 'OPEN' && c.waitingOn === 'PERSON',
          ),
        nextCursor: null,
      });
    }
    await settle();
    return req;
  }

  afterEach(() => http.verify());

  const by = (testId: string, root: ParentNode = el) =>
    root.querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();
  const rows = () => [...el.querySelectorAll<HTMLElement>('[data-testid=conversation]')];
  const navigatedTo = () =>
    (vi.mocked(router.navigate).mock.calls.at(-1)?.[1] as { queryParams: object }).queryParams;

  it('lists the reader’s conversations with their agent, subject and whom they wait on', async () => {
    const req = await open({
      conversations: [
        aConversationSummary({ waitingOn: 'PERSON', waitingReason: 'QUESTION' }),
        aConversationSummary({
          id: 'cv2',
          key: 'BA-7',
          title: 'Weekly summary',
          subject: null,
          state: 'ENDED',
          endedReason: 'SUBJECT_CLOSED',
          turnCount: 3,
          costUsd: 1.24,
          tokens: {
            inputTokens: 1150,
            cacheWriteTokens: 210,
            cachedInputTokens: 5400,
            outputTokens: 325,
          },
        }),
      ],
    });
    expect(req.request.params.keys()).toEqual(['limit']);

    const [open1, ended] = rows();
    expect(by('conversation-link', open1)?.getAttribute('href')).toBe(
      `/c/c1/conversations/${IDS.conversation}`,
    );
    expect(text(by('conversation-link', open1))).toBe('Checkout fails for guest users');
    expect(text(open1)).toContain('SHOP-241-1');
    expect(text(open1)).toContain('Business analyst');
    expect(text(by('conversation-subject', open1))).toBe('SHOP-241');
    expect(text(by('waiting-on', open1))).toBe('Needs you · Question');
    expect(by('waiting-on', open1)?.className).toContain('human');
    expect(by('conversation-person', open1)).toBeNull();

    expect(ended.dataset['state']).toBe('ENDED');
    expect(text(by('conversation-ended', ended))).toBe('Ended: its issue was closed');
    expect(by('waiting-on', ended)).toBeNull();
    expect(text(ended)).toContain('3 turns');
    // What its settled turns cost; nothing for one without spend.
    expect(text(by('conversation-cost', ended))).toBe('cost $1.24');
    expect(by('conversation-cost', open1)).toBeNull();
    // Its settled tokens together, in, cache write, cache read and out as the title.
    expect(text(by('conversation-tokens', ended))).toBe('7,085 tokens');
    expect(by('conversation-tokens', ended)?.getAttribute('title')).toBe(
      'In 1,150 · Cache write 210 · Cache read 5,400 · Out 325',
    );
    expect(by('conversation-tokens', open1)).toBeNull();
  });

  it('shows the key of a conversation whose content retention removed', async () => {
    await open({
      conversations: [aConversationSummary({ title: '', contentPurgedAt: AT.finished })],
    });
    expect(text(by('conversation-link', rows()[0]))).toBe('SHOP-241-1');
  });

  it.each<[string, Record<string, string>, Record<string, string>]>([
    ['an agent', { agentId: IDS.agent }, { agentId: IDS.agent }],
    ['a state', { state: 'ENDED' }, { state: 'ENDED' }],
    ['whom it waits on', { waitingOn: 'RUNNER' }, { waitingOn: 'RUNNER' }],
    [
      'a subject',
      { subjectSystemId: IDS.jira, subjectKey: 'SHOP-241' },
      { subjectSystemId: IDS.jira, subjectKey: 'SHOP-241' },
    ],
    ['a trigger', { triggerId: 't1' }, { triggerId: 't1' }],
    ['a key without its system, which the API does not take', { subjectKey: 'SHOP-241' }, {}],
    ['a state the API does not know', { state: 'PAUSED' }, {}],
    ['a person, which only the Owner’s “Everyone’s” takes', { personId: IDS.bob }, {}],
  ])('asks the API for %s from the URL', async (_, query, expected) => {
    const req = await open({ query });
    const params = Object.fromEntries(
      req.request.params
        .keys()
        .filter((k) => k !== 'limit')
        .map((k) => [k, req.request.params.get(k)]),
    );
    expect(params).toEqual(expected);
  });

  it('tells a viewer why they start no conversation (ADR-0077)', async () => {
    await open({ role: 'VIEWER' });
    expect(by('new-conversation')).toBeNull();
    expect(by('talk-to-agent')).toBeNull();
    expect(text(by('viewer-notice'))).toContain(
      'Viewers read the conversations shared with their groups, but start none.',
    );
  });

  it('keeps a filter in the URL when it changes', async () => {
    await open({ agents: [anAgentSummary(), anAgentSummary({ id: 'a2', name: 'Release notes' })] });
    const select = el.querySelector<HTMLSelectElement>('#filter-agent')!;
    expect([...select.options].map((o) => o.text)).toEqual([
      'Any',
      'Business analyst',
      'Release notes',
    ]);
    select.value = 'a2';
    select.dispatchEvent(new Event('change'));
    await settle();
    expect(navigatedTo()).toEqual({ agentId: 'a2' });
  });

  it('shows all, the open ones, whom they wait on or the ended ones, as one row of pills', async () => {
    await open({ query: { waitingOn: 'RUNNER' } });
    const show = by('show')!;
    expect([...show.querySelectorAll('label')].map((l) => text(l))).toEqual([
      'All',
      'Open',
      'Needs a person',
      'Working',
      'Waiting for a runner',
      'Ended',
    ]);
    expect(show.getAttribute('data-value')).toBe('runner');

    el.querySelector<HTMLInputElement>('#conversations-show-ended')!.click();
    await settle();
    expect(navigatedTo()).toEqual({ state: 'ENDED', waitingOn: null });
    el.querySelector<HTMLInputElement>('#conversations-show-all')!.click();
    await settle();
    expect(navigatedTo()).toEqual({ state: null, waitingOn: null });
  });

  it('offers a trigger filter only when the reader sees a trigger', async () => {
    await open();
    expect(el.querySelector('#filter-trigger')).toBeNull();
    TestBed.resetTestingModule();
    await open({ triggers: [{ id: 't1', name: 'Triage new SHOP issues' }] });
    expect(el.querySelector('#filter-trigger')).not.toBeNull();
  });

  it('names a trigger’s conversation by its trigger', async () => {
    await open({
      conversations: [
        aConversationSummary({
          personPrincipalId: null,
          personName: null,
          trigger: {
            triggerId: 't1',
            name: 'Triage new SHOP issues',
            authorPrincipalId: IDS.alice,
            authorName: 'Alice Novak',
            chainDepth: 0,
          },
        }),
      ],
    });
    const trigger = by('conversation-trigger', rows()[0])!;
    expect(text(trigger)).toBe('Triage new SHOP issues');
    expect(trigger.querySelector('a')?.getAttribute('href')).toBe('/c/c1/triggers/t1');
  });

  describe('what needs the reader', () => {
    const question = aConversationSummary({
      id: 'cv-q',
      title: 'Which coupon codes apply?',
      waitingOn: 'PERSON',
      waitingReason: 'QUESTION',
    });
    const working = aConversationSummary({
      id: 'cv-w',
      title: 'Weekly summary',
      waitingOn: 'AGENT',
    });

    it('leads the list, read on its own with the same filters, then the rest newest first', async () => {
      const req = await open({
        query: { agentId: IDS.agent },
        conversations: [working, question],
      });
      expect(pinnedReq?.request.params.get('agentId')).toBe(IDS.agent);
      expect(req.request.params.has('waitingOn')).toBe(false);

      expect(text(by('pinned-heading'))).toBe('Needs you 1');
      expect(text(by('rest-heading'))).toBe('Other conversations 1');
      expect(rows().map((r) => r.dataset['conversationId'])).toEqual(['cv-q', 'cv-w']);
      expect(by('pinned')!.querySelectorAll('[data-testid=conversation]')).toHaveLength(1);
      expect(text(el.querySelector('app-page-header'))).toContain(
        'Your conversations with agents. What needs you comes first, then the newest activity.',
      );
    });

    it('is no section when nothing waits on the reader', async () => {
      await open({ conversations: [working] });
      expect(by('pinned-heading')).toBeNull();
      expect(by('rest-heading')).toBeNull();
      expect(by('rest')?.getAttribute('aria-label')).toBe('Conversations');
      expect(rows()).toHaveLength(1);
    });

    it('leaves a trigger’s conversation that waits on its author with the rest', async () => {
      const authors = aConversationSummary({
        id: 'cv-t',
        personPrincipalId: null,
        personName: null,
        waitingOn: 'PERSON',
        waitingReason: 'APPROVAL',
        trigger: {
          triggerId: 't1',
          name: 'Triage new SHOP issues',
          authorPrincipalId: IDS.bob,
          authorName: 'Bob Svoboda',
          chainDepth: 0,
        },
      });
      await open({ conversations: [authors, question] });
      expect(text(by('pinned-heading'))).toBe('Needs you 1');
      expect(rows().map((r) => r.dataset['conversationId'])).toEqual(['cv-q', 'cv-t']);
      expect(text(by('waiting-on', rows()[1]))).toBe('Waits for Bob Svoboda · Approval');
    });

    it('is not read while the pills narrow to whom they wait on, or to the ended ones', async () => {
      for (const query of [
        { waitingOn: 'PERSON' },
        { waitingOn: 'AGENT' },
        { state: 'ENDED' },
      ] as Record<string, string>[]) {
        TestBed.resetTestingModule();
        await open({ query, conversations: [question] });
        expect(pinnedReq).toBeUndefined();
        expect(by('pinned-heading')).toBeNull();
      }
    });

    it('keeps the list one, newest first, when its own read fails', async () => {
      await open({ conversations: [working, question], needsPersonFails: true });
      expect(by('pinned-heading')).toBeNull();
      expect(rows().map((r) => r.dataset['conversationId'])).toEqual(['cv-w', 'cv-q']);
    });
  });

  describe('empty', () => {
    it('offers the agents the reader may talk to', async () => {
      await open({
        agents: [
          anAgentSummary(),
          anAgentSummary({ id: 'a2', name: 'Release notes', usableByMe: false }),
          anAgentSummary({ id: 'a3', name: 'Paused one', status: 'PAUSED' }),
        ],
      });
      const empty = by('conversations-empty')!;
      expect(text(empty)).toContain('Talk to an agent');
      const talk = [...empty.querySelectorAll('[data-testid=talk-to-agent]')];
      expect(talk.map((a) => a.getAttribute('href'))).toEqual([
        '/c/c1/conversations/new?agentId=a-ba',
      ]);
      expect(text(talk[0])).toContain('Talk to Business analyst');
    });

    it('says when no agent is shared with the reader yet', async () => {
      await open({ agents: [] });
      const empty = by('conversations-empty')!;
      expect(text(empty)).toContain('No agent is shared with you yet');
      expect(empty.querySelector('a[href="/c/c1/agents/new"]')).not.toBeNull();
    });

    it('says when filters match nothing, and clears them', async () => {
      await open({ query: { state: 'ENDED', agentId: IDS.agent } });
      expect(by('conversations-empty')).toBeNull();
      expect(text(by('conversations-filtered-empty'))).toContain(
        'No conversation matches these filters',
      );
      by('clear-filters')!.click();
      expect(navigatedTo()).toEqual({
        agentId: null,
        state: null,
        waitingOn: null,
        subjectSystemId: null,
        subjectKey: null,
        triggerId: null,
        personId: null,
      });
    });
  });

  describe('the Owner', () => {
    it('reads their own by default, naming their principal', async () => {
      const req = await open({ role: 'OWNER', me: IDS.owner });
      expect(req.request.params.get('personId')).toBe(IDS.owner);
      expect(by('everyone-banner')).toBeNull();
      expect(by('whose')?.getAttribute('data-value')).toBe('mine');
    });

    it('reads everyone’s under a banner, with each person, and narrows to one', async () => {
      const req = await open({
        role: 'OWNER',
        me: IDS.owner,
        query: { who: 'everyone' },
        conversations: [aConversationSummary({ waitingOn: 'PERSON', waitingReason: 'APPROVAL' })],
      });
      expect(req.request.params.has('personId')).toBe(false);
      expect(text(by('everyone-banner'))).toContain('every conversation you open is recorded');
      expect(text(by('pinned-heading'))).toBe('Needs a person 1');
      const [row] = rows();
      expect(text(by('conversation-person', row))).toBe('Alice Novak');
      // It waits on Alice, not on the Owner: neutral.
      expect(text(by('waiting-on', row))).toBe('Waits for Alice Novak · Approval');
      expect(by('waiting-on', row)?.className).not.toContain('human');

      by('conversation-person', row)!.click();
      expect(navigatedTo()).toEqual({ personId: IDS.alice });
    });

    it('filters everyone’s by one person', async () => {
      const req = await open({
        role: 'OWNER',
        me: IDS.owner,
        query: { who: 'everyone', personId: IDS.alice },
        conversations: [aConversationSummary()],
      });
      expect(req.request.params.get('personId')).toBe(IDS.alice);
      const select = el.querySelector<HTMLSelectElement>('#filter-person')!;
      expect([...select.options].map((o) => o.text)).toEqual([
        'Everyone',
        'Alice Novak',
        'Bob Svoboda',
      ]);
      expect(select.value).toBe(IDS.alice);
      select.value = IDS.bob;
      select.dispatchEvent(new Event('change'));
      await settle();
      expect(navigatedTo()).toEqual({ personId: IDS.bob });
    });

    it('offers no person filter in “Mine”', async () => {
      await open({ role: 'OWNER', me: IDS.owner });
      expect(el.querySelector('#filter-person')).toBeNull();
    });

    it('is the only one with the switch', async () => {
      const req = await open({ role: 'ADMIN', query: { who: 'everyone' } });
      expect(by('whose')).toBeNull();
      expect(by('everyone-banner')).toBeNull();
      expect(req.request.params.has('personId')).toBe(false);
    });
  });

  describe('by subject', () => {
    it('lists the subjects with their counts, and opens one’s conversations', async () => {
      await open({
        query: { view: 'subjects' },
        subjects: [
          aSubject({ conversations: 3, open: 1 }),
          aSubject({
            key: 'acme/shop#57',
            systemId: IDS.github,
            systemName: 'GitHub',
            url: null,
            title: null,
            conversations: 1,
            open: 0,
            lastActivityAt: null,
          }),
        ],
      });
      const subjects = [...el.querySelectorAll<HTMLElement>('[data-testid=subject]')];
      expect(subjects.map((s) => s.dataset['key'])).toEqual(['SHOP-241', 'acme/shop#57']);
      expect(text(by('subject-counts', subjects[0]))).toContain('3 conversations · 1 open');
      expect(text(by('subject-counts', subjects[1]))).toBe('1 conversation · 0 open');
      expect(subjects[0].querySelector('a')?.getAttribute('href')).toBe(
        'https://acme.atlassian.net/browse/SHOP-241',
      );
      by('subject-conversations', subjects[0])!.click();
      expect(navigatedTo()).toEqual({
        view: null,
        subjectSystemId: IDS.jira,
        subjectKey: 'SHOP-241',
      });
    });

    it('says when no conversation has a subject', async () => {
      await open({ query: { view: 'subjects' } });
      expect(text(by('subjects-empty'))).toContain('No conversation has a subject yet');
    });
  });

  it('shows the subject it is narrowed to, and lets it go', async () => {
    await open({
      query: { subjectSystemId: IDS.jira, subjectKey: 'SHOP-241' },
      conversations: [aConversationSummary()],
    });
    expect(text(by('subject-filter'))).toBe('About SHOP-241');
    by('subject-filter')!.querySelector('button')!.click();
    expect(navigatedTo()).toEqual({ subjectSystemId: null, subjectKey: null });
  });
});
