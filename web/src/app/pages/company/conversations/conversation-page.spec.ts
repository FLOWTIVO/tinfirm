import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import type {
  Conversation,
  ConversationReader,
  ModelAccessView,
  Role,
  RunEvent,
} from '../../../api/generated/models';
import { fakeEventSources } from '../../../testing/fake-event-source';
import {
  aConversation,
  aConversationIn,
  aMessage,
  anApproval,
  aModelAccess,
  aMyAccount,
  anAgent,
  anAttachment,
  anOutput,
  aRun,
  aWaitingBinding,
  type ConversationStateName,
  IDS,
} from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { CONVERSATION_POLL_TIMING, ConversationStore } from './conversation-store';
import { ConversationPage } from './conversation-page';

const API = '/api/companies/c1';
const URL = `${API}/conversations/${IDS.conversation}`;
const UUID = /^[0-9a-f-]{36}$/;

/** No poll during a spec: the store's own spec covers the timing. */
const NO_POLLS = {
  provide: CONVERSATION_POLL_TIMING,
  useValue: { activeMs: 1e9, idleMs: 1e9, afterActionMs: 0 },
};

describe('ConversationPage', () => {
  let fixture: ComponentFixture<ConversationPage>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];
  let streams: ReturnType<typeof fakeEventSources>;
  let accessOverrides: Partial<ModelAccessView> = {};
  let readers: ConversationReader[] = [];
  /** A finished turn's events, as a read with `types` answers them; and the types each read asked. */
  let runEvents: RunEvent[] = [];
  let eventReads: (string | null)[] = [];

  /** Opens the page on the conversation, as Alice (its person, or a Viewer now) or as the Owner. */
  async function open(
    conversation: Conversation | null,
    as: 'alice' | 'owner' | 'viewer' = 'alice',
    agent = anAgent(),
    access: Partial<ModelAccessView> = {},
    reads: ConversationReader[] = [],
  ): Promise<void> {
    accessOverrides = access;
    readers = reads;
    const role: Role = as === 'owner' ? 'OWNER' : as === 'viewer' ? 'VIEWER' : 'MEMBER';
    const notifier = fakeNotify();
    notify = notifier.notify;
    streams = fakeEventSources();
    TestBed.configureTestingModule({
      imports: [ConversationPage],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany(role).provider,
        notifier.provider,
        ...streams.providers,
        NO_POLLS,
      ],
    });
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ConversationPage);
    fixture.componentRef.setInput('conversationId', IDS.conversation);
    el = fixture.nativeElement;
    await settle();
    for (const req of http.match(`${API}/me/model-access`)) {
      req.flush(
        as === 'owner' ? aModelAccess({ principalId: IDS.owner }) : aModelAccess(accessOverrides),
      );
    }
    const req = http.expectOne(URL);
    if (conversation) req.flush(conversation);
    else req.flush(problem(404), { status: 404, statusText: 'Not Found' });
    await settle();
    answerAround(agent);
    await settle();
  }

  /** The agent, the reader's accounts and a turn's work, whenever the page asks. */
  function answerAround(agent = anAgent()): void {
    for (const req of http.match((r) => /\/runs\/[^/]+\/events$/.test(r.url))) {
      const types = req.request.params.get('types');
      eventReads.push(types);
      const items = types ? runEvents.filter((e) => types.split(',').includes(e.type)) : [];
      req.flush({ items, complete: true });
    }
    for (const req of http.match((r) => /\/runs\/[^/]+$/.test(r.url))) {
      req.flush(aRun({ id: req.request.url.split('/').at(-1), status: 'FAILED' }));
    }
    for (const req of http.match(`${URL}/reads`)) req.flush(readers);
    for (const req of http.match(`${API}/agents/${IDS.agent}`)) req.flush(agent);
    for (const req of http.match(`${API}/me/accounts`)) req.flush({ items: [aMyAccount()] });
    for (const req of http.match(`${API}/me/model-access`))
      req.flush(aModelAccess(accessOverrides));
  }

  afterEach(() => {
    try {
      answerAround();
      http.verify();
    } finally {
      runEvents = [];
      eventReads = [];
      TestBed.resetTestingModule();
    }
  });

  const text = (selector: string) =>
    el.querySelector(selector)?.textContent?.replace(/\s+/g, ' ').trim() ?? null;

  it('shows the not-found page and nothing more for a 404', async () => {
    await open(null);
    expect(el.textContent).toContain('Page not found');
    expect(el.querySelector('[data-testid=thread]')).toBeNull();
  });

  it('draws the header, who acts and who pays in one line, the Owner notice and the thread', async () => {
    await open(aConversation());
    const header = el.querySelector('[data-testid=conversation-header]')!;
    expect(text('[data-testid=conversation-title]')).toBe('Checkout fails for guest users');
    expect(el.querySelector('[data-testid=conversation-title]')?.getAttribute('title')).toBe(
      'Checkout fails for guest users',
    );
    expect(header.querySelector('[data-testid=conversation-back]')?.getAttribute('href')).toBe(
      '/c/c1/conversations',
    );
    expect(text('[data-testid=conversation-key]')).toBe('SHOP-241-1 · agent/SHOP-241-1');
    expect(text('[data-testid=conversation-agent]')).toContain('Business analyst');
    expect(el.querySelector('[data-testid=conversation-person]')).toBeNull();
    expect(el.querySelector('[data-testid=conversation-subject]')?.getAttribute('href')).toBe(
      'https://acme.atlassian.net/browse/SHOP-241',
    );

    // Who acts and who pays: one quiet line, the full panel behind its Details.
    const line = el.querySelector('[data-testid=who-acts-who-pays][data-variant=line]')!;
    expect(words(line.querySelector('[data-part=acts]'))).toBe('Acts as GitHub: your own account');
    expect(words(line.querySelector('[data-part=pays]'))).toBe(
      'Pays from your budget, $38.20 left',
    );
    expect(words(line.querySelector('[data-part=runs]'))).toBe('Runs on office');
    expect(el.querySelector('#who-acts-panel')).toBeNull();
    const details = line.querySelector<HTMLButtonElement>('[data-testid=who-acts-details]')!;
    expect(details.getAttribute('aria-expanded')).toBe('false');
    details.click();
    await settle();
    expect(details.getAttribute('aria-expanded')).toBe('true');
    const panel = el.querySelector('#who-acts-panel')!;
    expect(panel.querySelector('[data-part=pays]')?.textContent).toContain(
      'Your budget: $38.20 of $50.00 left',
    );
    // The panel opens above the thread, in the column the side column stands beside.
    expect(panel.parentElement?.querySelector('[data-testid=thread]')).not.toBeNull();
    // A personal conversation has no place notice, nor its gap: the thread starts level with the side.
    expect(el.querySelector('app-tracker-place-notice')?.classList.contains('hidden')).toBe(true);

    // The standing Owner notice, and who else read it (W7), for its person.
    const notice = el.querySelector<HTMLButtonElement>('[data-testid=owner-notice]')!;
    expect(words(notice)).toBe('The Owner can read this · nobody has');
    expect(el.querySelector('[data-testid=conversation-reads]')).toBeNull();
    notice.click();
    await settle();
    expect(notice.getAttribute('aria-expanded')).toBe('true');
    expect(text('[data-testid=reads-popover]')).toContain(
      'The company’s Owner can read this conversation in full.',
    );
    expect(text('[data-testid=conversation-reads]')).toContain('Nobody but you has read it.');
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    await settle();
    expect(el.querySelector('[data-testid=reads-popover]')).toBeNull();
    expect(document.activeElement).toBe(notice);
    expect(el.querySelector('[data-testid=owner-banner]')).toBeNull();

    const messages = [...el.querySelectorAll('[data-testid=message]')];
    expect(messages.map((m) => m.getAttribute('data-mine'))).toEqual(['true']);
    expect(text('[data-testid=turn-answer]')).toBe(
      'The brief is ready: the guest flow skips the address check.',
    );
    expect(text('[data-testid=turn-status]')).toContain('Completed');
    expect(el.querySelector('[data-testid=turn-run]')?.getAttribute('href')).toBe(
      `/c/c1/runs/${IDS.run}`,
    );
    expect(text('[data-testid=turn-work-toggle]')).toMatch(/^Worked for \S+/);
    expect(el.querySelector('[data-testid=side-workspace]')?.textContent).toContain(
      'Read-only: the agent reads it only',
    );
    // The side column names its sections in sentence case.
    const headings = [...el.querySelectorAll('aside h2')].map((h) => h.textContent?.trim());
    expect(headings).toEqual(['Outputs', 'Files', 'Workspace', 'Network hosts', 'Turns']);
    expect(el.querySelector('aside .uppercase')).toBeNull();
  });

  it('puts a viewer’s note where the composer would be (ADR-0077)', async () => {
    // Alice was a member when she started it; as a Viewer she reads it and writes nothing.
    await open(aConversation(), 'viewer');
    expect(el.querySelector('[data-testid=composer-input]')).toBeNull();
    expect(el.querySelector('[data-testid=owner-banner]')).toBeNull();
    expect(text('[data-testid=composer-dock] [data-testid=viewer-notice]')).toBe(
      'You are a Viewer in this company. Viewers read a conversation, but don’t write in it or answer its requests. An Owner or Admin can make you a Member.',
    );
  });

  it('says who read it, and how often, behind the eye button', async () => {
    await open(aConversation(), 'alice', anAgent(), {}, [
      {
        userId: 'u-olga',
        name: 'Olga Owner',
        firstReadAt: '2026-10-12T09:00:00Z',
        lastReadAt: '2026-10-12T10:00:00Z',
        reads: 2,
        kinds: ['conversation', 'run-events'],
      },
    ]);
    const notice = el.querySelector<HTMLButtonElement>('[data-testid=owner-notice]')!;
    expect(words(notice)).toBe('The Owner can read this · read 2 times');
    notice.click();
    await settle();
    expect(text('[data-reader="Olga Owner"]')).toContain(
      'Olga Owner read the conversation and its transcripts, 2 times.',
    );
    // A click elsewhere closes it.
    document.body.click();
    await settle();
    expect(el.querySelector('[data-testid=reads-popover]')).toBeNull();
  });

  it('opens the details sheet, with what the head leaves out on a phone', async () => {
    await open(aConversation({ outputs: [anOutput()] }));
    el.querySelector<HTMLButtonElement>('[data-testid=conversation-details]')!.click();
    await settle();
    answerAround();
    await settle();
    const sheet = document.querySelector<HTMLElement>('[data-slot=sheet-content]')!;
    expect(sheet.querySelector('[data-testid=conversation-meta]')).not.toBeNull();
    expect(
      words(sheet.querySelector('[data-testid=who-acts-who-pays] [data-part=pays]')),
    ).toContain('Your budget: $38.20 of $50.00 left');
    expect(words(sheet.querySelector('[data-testid=conversation-reads]'))).toContain(
      'Nobody but you has read it.',
    );
    expect(sheet.querySelector('[data-testid=side-workspace]')).not.toBeNull();
    expect(sheet.querySelector('[data-testid=output]')).not.toBeNull();
  });

  it('adds a file from the composer: below lg through the details sheet', async () => {
    await open(aConversation());
    const attach = el.querySelector<HTMLButtonElement>('[data-testid=composer-attach]')!;
    expect(attach.getAttribute('aria-label')).toBe('Add a file');
    attach.click();
    await settle();
    answerAround();
    await settle();
    expect(
      document.querySelector('[data-slot=sheet-content] [data-testid=add-file]'),
    ).not.toBeNull();
  });

  // Plan §4.1's states as the conversation's person (Alice): the chip, the live turn, what the
  // state means, the cards, and what the composer says.
  it.each<
    [
      ConversationStateName,
      {
        chip: string;
        tone: string;
        live?: string | null;
        status?: string | null;
        composer: string | null;
        cards?: boolean;
        retry?: boolean;
      },
    ]
  >([
    [
      'running',
      {
        chip: 'Working',
        tone: 'agent',
        live: 'RUNNING',
        composer:
          'Business analyst is at work: it takes your message after its current step, or in the next turn.',
      },
    ],
    [
      'queued',
      {
        chip: 'Waiting · Host busy',
        tone: 'muted',
        live: 'QUEUED',
        composer:
          'Business analyst is at work: it takes your message after its current step, or in the next turn.',
      },
    ],
    [
      'budget-spent',
      {
        chip: 'Waiting · Budget spent',
        tone: 'muted',
        live: 'QUEUED',
        composer:
          'Business analyst is at work: it takes your message after its current step, or in the next turn.',
      },
    ],
    [
      'question',
      {
        chip: 'Needs you · Question',
        tone: 'human',
        composer: 'Business analyst gets this as a message; its open questions stay open.',
      },
    ],
    [
      'account',
      {
        chip: 'Needs you · Account',
        tone: 'human',
        cards: true,
        composer: 'Business analyst gets this as the next turn.',
      },
    ],
    [
      'failed',
      {
        chip: 'Turn failed',
        tone: 'destructive',
        status: 'failed',
        retry: true,
        composer: 'Business analyst gets this as the next turn.',
      },
    ],
    [
      'cancelled',
      {
        chip: 'Cancelled',
        tone: 'muted',
        status: 'cancelled',
        retry: true,
        composer: 'Business analyst gets this as the next turn.',
      },
    ],
    [
      'paused',
      {
        chip: 'Waiting · Agent paused',
        tone: 'muted',
        live: 'QUEUED',
        composer:
          'Business analyst is at work: it takes your message after its current step, or in the next turn.',
      },
    ],
    ['ended', { chip: 'Ended', tone: 'muted', status: 'ended', composer: null }],
    [
      'idle',
      { chip: 'Idle', tone: 'muted', composer: 'Business analyst gets this as the next turn.' },
    ],
  ])('as its person, %s', async (state, expected) => {
    await open(aConversationIn(state));
    const chip = el.querySelector('[data-testid=conversation-state]')!;
    expect(chip.textContent?.trim()).toBe(expected.chip);
    expect(chip.getAttribute('data-tone')).toBe(expected.tone);
    expect(el.querySelector('[data-testid=live-turn]')?.getAttribute('data-status') ?? null).toBe(
      expected.live ?? null,
    );
    expect(
      el.querySelector('[data-testid=conversation-status]')?.getAttribute('data-phase') ?? null,
    ).toBe(expected.status ?? null);
    expect(text('[data-testid=composer-hint]')).toBe(expected.composer);
    expect(!!el.querySelector('[data-testid=binding-card]')).toBe(!!expected.cards);
    expect(!!el.querySelector('[data-testid=retry]')).toBe(!!expected.retry);
    expect(el.querySelector('[data-testid=owner-banner]')).toBeNull();
  });

  it('says why a queued turn waits, with the link that fixes it', async () => {
    await open(aConversationIn('queued'));
    expect(text('[data-testid=live-turn-queued]')).toContain(
      'Waiting for a free slot on office (2 of 2 in use).',
    );
  });

  it('waits for the budget to reset, neutral, with a link to Accounts › Budget (V7)', async () => {
    await open(aConversationIn('budget-spent'));
    const line = el.querySelector('[data-testid=live-turn-queued]')!;
    expect(line.getAttribute('data-reason')).toBe('BUDGET_SPENT');
    expect(text('[data-testid=live-turn-reason]')).toMatch(
      /^Waiting for your budget to reset on .*2026/,
    );
    const fix = el.querySelector('[data-testid=live-turn-fix]')!;
    expect(fix.textContent?.trim()).toBe('Your budget');
    expect(fix.getAttribute('href')).toBe('/c/c1/accounts?tab=budget');
    expect(line.className).not.toContain('human');
  });

  it('says a paused agent’s turns wait', async () => {
    await open(aConversationIn('paused'));
    expect(text('[data-testid=live-turn-reason]')).toBe(
      'Business analyst is paused; its turns wait.',
    );
  });

  it('shows the Working line live from the run stream', async () => {
    await open(aConversationIn('running'));
    const source = streams.run()!;
    expect(source.url).toBe(`${API}/runs/run2/events/stream`);
    source.open();
    source.send('run-event', {
      seq: 1,
      type: 'TOOL_CALL',
      at: '2026-10-12T09:00:10Z',
      payload: {
        toolCallId: 't1',
        title: 'Read src/checkout.ts',
        kind: 'read',
        status: 'in_progress',
      },
    });
    await new Promise((resolve) => setTimeout(resolve, 5));
    await settle();
    expect(text('[data-testid=live-turn-working]')).toContain('Working');
    expect(text('[data-testid=live-turn-step]')).toBe('· Read src/checkout.ts');
  });

  it('shows what the live turn said while its work is folded, and its work instead once shown', async () => {
    await open(aConversationIn('running'));
    const source = streams.run()!;
    source.open();
    const at = '2026-10-12T09:00:10Z';
    source.send('run-event', { seq: 1, type: 'AGENT_MESSAGE', at, payload: { text: 'Reading ' } });
    source.send('run-event', { seq: 2, type: 'AGENT_MESSAGE', at, payload: { text: 'checkout.' } });
    source.send('run-event', {
      seq: 3,
      type: 'TOOL_CALL',
      at,
      payload: {
        toolCallId: 't1',
        title: 'Read src/checkout.ts',
        kind: 'read',
        status: 'completed',
      },
    });
    source.send('run-event', { seq: 4, type: 'AGENT_MESSAGE', at, payload: { text: 'Found it.' } });
    await new Promise((resolve) => setTimeout(resolve, 5));
    await settle();
    const said = [...el.querySelectorAll('[data-testid=live-turn-said] app-markdown')];
    expect(said.map((m) => m.textContent?.trim())).toEqual(['Reading checkout.', 'Found it.']);

    el.querySelector<HTMLButtonElement>('[data-testid=live-turn-show-work]')!.click();
    await settle();
    expect(el.querySelector('[data-testid=live-turn-said]')).toBeNull();
    expect(text('[data-testid=live-turn-work]')).toContain('Found it.');
  });

  it('shows what a finished turn said on its way while its work is folded, reading only that', async () => {
    const at = '2026-10-12T09:01:00Z';
    runEvents = [
      { seq: 2, type: 'AGENT_MESSAGE', at, payload: { text: 'Reading the ' } },
      { seq: 3, type: 'USAGE', at, payload: { inputTokens: 10 } },
      { seq: 4, type: 'AGENT_MESSAGE', at, payload: { text: 'checkout flow.' } },
      { seq: 8, type: 'AGENT_MESSAGE', at, payload: { text: 'The brief is ready.' } },
    ];
    const c = aConversation();
    c.turns = c.turns.map((r) => ({
      ...r,
      lastEventSeq: 9,
      counts: { toolCalls: 3, messages: 2, thoughts: 0 },
    }));
    await open(c);
    expect(eventReads).toEqual(['AGENT_MESSAGE,USAGE']);
    // Its last message is the answer, said once below.
    expect(text('[data-testid=turn-said]')).toBe('Reading the checkout flow.');
    expect(text('[data-testid=turn-answer]')).toBe(
      'The brief is ready: the guest flow skips the address check.',
    );
    expect(text('[data-testid=turn-work-toggle]')).toContain('· 3 tool calls · 1 message');

    el.querySelector<HTMLButtonElement>('[data-testid=turn-work-toggle]')!.click();
    await settle();
    expect(el.querySelector('[data-testid=turn-said]')).toBeNull();
    expect(el.querySelector('[data-testid=turn-work]')).not.toBeNull();
  });

  it('reads nothing of a folded turn that said nothing besides its answer', async () => {
    const c = aConversation();
    c.turns = c.turns.map((r) => ({
      ...r,
      lastEventSeq: 9,
      counts: { toolCalls: 3, messages: 1, thoughts: 0 },
    }));
    await open(c);
    expect(eventReads).toEqual([]);
    expect(el.querySelector('[data-testid=turn-said]')).toBeNull();
  });

  it('reads the conversation again when the live turn ends', async () => {
    await open(aConversationIn('running'));
    const source = streams.run()!;
    source.open();
    source.send('run-status', { ...aConversationIn('running').turns[1], status: 'SUCCEEDED' });
    await settle();
    const req = http.expectOne(URL);
    req.flush(aConversationIn('idle'));
    await settle();
    expect(el.querySelector('[data-testid=live-turn]')).toBeNull();
    expect(text('[data-testid=thread-announcement]')).toBe('');
    // The live turn's run is a finished turn now: its answer is said once.
    const done = aConversationIn('running', {
      activeRunId: null,
      waitingOn: 'NONE',
      turnStatus: 'SUCCEEDED',
    });
    done.turns = done.turns.map((r) =>
      r.id === 'run2' ? { ...r, status: 'SUCCEEDED', finishedAt: '2026-10-12T09:05:00Z' } : r,
    );
    fixture.debugElement.injector.get(ConversationStore).replace(done);
    await settle();
    expect(text('[data-testid=thread-announcement]')).toBe('Business analyst answered.');
  });

  it('offers the open question’s answer in the composer, and the card in the thread', async () => {
    await open(aConversationIn('question'));
    const card = el.querySelector('[data-testid=turn] app-decision-card[data-decision-id=d1]');
    expect(card).not.toBeNull();
    expect(card?.hasAttribute('data-open-card')).toBe(true);
    const box = el.querySelector<HTMLInputElement>('[data-testid=answers-open-questions]')!;
    expect(box.parentElement?.textContent).toContain('This answers the open question');
    box.click();
    await settle();
    expect(text('[data-testid=composer-hint]')).toBe(
      'Business analyst gets this as the answer to its open questions and goes on.',
    );
  });

  it('answers a question in its card in the thread, and replaces the decision', async () => {
    await open(aConversationIn('question'));
    const card = el.querySelector('app-decision-card[data-decision-id=d1]')!;
    card.querySelector<HTMLButtonElement>('[data-testid=decision-options] button')!.click();
    await settle();
    card.querySelector<HTMLButtonElement>('[data-testid=decide-ANSWERED]')!.click();
    await settle();
    const decide = http.expectOne(`${API}/decisions/d1/decide`);
    expect(decide.request.body).toEqual({ option: 'Yes' });
    expect(decide.request.headers.get('Idempotency-Key')).toMatch(UUID);
    decide.flush({
      ...aConversationIn('question').decisions[0],
      status: 'DECIDED',
      mayDecide: false,
    });
    await settle();
    // The decision as answered, and the conversation read again: the answer starts a turn.
    http.expectOne(URL).flush(aConversationIn('running'));
    await settle();
    expect(el.querySelector('[data-testid=live-turn]')).not.toBeNull();
  });

  it('shows W4c’s outputs and files in the side column, and reads again when a file changes', async () => {
    await open(aConversation({ outputs: [anOutput()], attachments: [anAttachment()] }));
    const side = el.querySelector('aside [data-testid=conversation-side]')!;
    expect(side.querySelector('[data-testid=conversation-outputs]')?.textContent).toContain(
      'Validate the guest address',
    );
    expect(side.querySelector('[data-testid=conversation-files]')?.textContent).toContain(
      'export.csv',
    );
    expect(side.querySelector('[data-testid=add-file]')).not.toBeNull();
  });

  it('shows what it cost so far in the side column, and each turn’s settled cost', async () => {
    await open(
      aConversation({
        cost: {
          costUsd: 1.24,
          tokens: {
            inputTokens: 1150,
            cacheWriteTokens: 210,
            cachedInputTokens: 54_000,
            outputTokens: 325,
          },
          subscriptionTokens: 0,
          turns: [{ runId: IDS.run, costUsd: 1.24, source: 'GATEWAY' }],
        },
      }),
    );
    const side = el.querySelector('aside [data-testid=conversation-side]')!;
    const headings = [...side.querySelectorAll('h2')].map((h) => h.textContent?.trim());
    expect(headings).toEqual(['Outputs', 'Files', 'Workspace', 'Network hosts', 'Cost', 'Turns']);
    expect(text('aside [data-testid=cost-total]')).toBe('$1.24');
    expect(text('aside [data-testid=cost-tokens]')).toBe(
      'Tokens: In 1,150 · Cache write 210 · Cache read 54K · Out 325',
    );
    expect(text('aside [data-testid=cost-note]')).toBe('The settled spend of 1 turn.');
    // The turn's status line says its settled cost, not the runtime's estimate.
    expect(text('[data-testid=turn-cost]')).toBe('$1.24');
    expect(el.querySelector('[data-testid=turn-cost]')?.getAttribute('title')).toContain(
      'Settled from the gateway’s records',
    );
  });

  it('marks a conversation an AI client started', async () => {
    await open(aConversation({ origin: 'CLIENT' }));
    expect(text('[data-testid=conversation-header] [data-testid=client-badge]')).toBe('AI client');
  });

  it('says where to write for a conversation a chat holds, and keeps its cards', async () => {
    await open(aConversationIn('question', { place: 'CHAT_THREAD', origin: 'DIRECT_MESSAGE' }));
    expect(text('[data-testid=chat-place]')).toBe('Direct message in a chat');
    expect(text('[data-testid=composer-external]')).toContain(
      'This conversation lives in your direct message with the Tinfirm bot',
    );
    expect(el.querySelector('[data-testid=composer-input]')).toBeNull();
    expect(el.querySelector('app-decision-card[data-decision-id=d1]')).not.toBeNull();
  });

  it('draws Tinfirm’s note as a left-aligned note, its links by their words', async () => {
    const c = aConversation();
    await open({
      ...c,
      messages: [
        ...c.messages,
        aMessage({
          id: 'm-system',
          kind: 'SYSTEM',
          authorPrincipalId: null,
          authorName: null,
          body: 'Review the result before Tinfirm writes it:\n\nLink SHOP-241 to this conversation in Tinfirm (https://tinfirm.test/c/c1/conversations/x)',
          createdAt: '2026-10-12T09:10:00Z',
        }),
      ],
    });
    const note = el.querySelector<HTMLElement>('[data-author=SYSTEM]')!;
    expect(note.querySelector('ng-icon[name=lucideInfo]')).not.toBeNull();
    expect(note.className).not.toContain('text-center');
    const link = note.querySelector('a')!;
    expect(link.textContent).toBe('Link SHOP-241 to this conversation in Tinfirm');
    expect(link.getAttribute('href')).toBe('https://tinfirm.test/c/c1/conversations/x');
    expect(note.textContent).not.toContain('https://');
  });

  it('follows the conversation’s stream: a change is read, and shown without a reload', async () => {
    await open(aConversation());
    const source = streams.conversation()!;
    expect(source.url).toBe(`${URL}/stream`);
    source.open();
    await settle();
    http.expectOne(URL).flush(aConversation());
    await settle();
    source.send('conversation-changed', { parts: ['TURNS', 'STATUS'] });
    await settle();
    http.expectOne(URL).flush(aConversationIn('running'));
    await settle();
    answerAround();
    await settle();
    expect(el.querySelector('[data-testid=live-turn]')).not.toBeNull();
  });

  describe('a trigger’s conversation (W5)', () => {
    const TRIGGER = {
      triggerId: 't-prepare',
      name: 'Prepare assigned issues',
      authorPrincipalId: IDS.alice,
      chainDepth: 0,
    };
    const ISSUE = {
      personPrincipalId: null,
      personName: null,
      mayPost: false,
      mayAnswer: false,
      place: 'TRACKER_ISSUE' as const,
      trigger: TRIGGER,
      subject: {
        key: 'SHOP-241',
        systemName: 'Jira',
        url: 'https://jira.acme.test/browse/SHOP-241',
      },
    };
    const COMMENT = aMessage({
      id: 'm-ext',
      kind: 'EXTERNAL',
      authorName: 'Petra Jira',
      authorPrincipalId: null,
      body: 'Please also check the refunds.',
      createdAt: '2026-10-12T09:00:00Z',
    });

    it('says people talk on the issue, shows its comments, and has no composer', async () => {
      const c = aConversation(ISSUE);
      await open({ ...c, messages: [...c.messages, COMMENT] });
      expect(text('[data-testid=place-notice]')).toContain(
        'Started by Prepare assigned issues on SHOP-241.',
      );
      expect(
        words(el.querySelector('[data-testid=thread] [data-testid=external-message] p')),
      ).toContain('Petra Jira commented on Jira SHOP-241');
      expect(el.querySelector('[data-testid=composer]')).toBeNull();
    });

    it('takes only answers to the open questions on the issue', async () => {
      await open(aConversationIn('question', { ...ISSUE, mayAnswer: true }));
      expect(el.querySelector('[data-testid=answers-open-questions]')).toBeNull();
      expect(text('[data-testid=composer-hint]')).toBe(
        'Business analyst gets this as the answer to its open questions; it is posted on the issue too.',
      );
      const box = el.querySelector<HTMLTextAreaElement>('[data-testid=composer-input]')!;
      box.value = 'Yes, the mobile app too.';
      box.dispatchEvent(new Event('input'));
      await settle();
      el.querySelector<HTMLButtonElement>('[data-testid=composer-send]')!.click();
      await settle();
      const req = http.expectOne(`${URL}/messages`);
      expect(req.request.body).toEqual({
        body: 'Yes, the mobile app too.',
        answersOpenQuestions: true,
      });
      req.flush(aConversationIn('running', ISSUE));
      await settle();
    });

    it('reviews a result where it was raised, with W5’s card', async () => {
      await open(
        aConversationIn('question', {
          ...ISSUE,
          decisions: [
            anApproval({
              runId: 'run2',
              approverGroupName: 'Analysts',
              subject: {
                type: 'RESULT',
                runId: 'run2',
                summary: 'The brief is ready.',
                writes: ['Move SHOP-241 through “In review”'],
              },
            }),
          ],
        }),
      );
      const card = el.querySelector(
        '[data-testid=turn] app-result-review-card[data-decision-id=d2]',
      );
      expect(card?.hasAttribute('data-open-card')).toBe(true);
      expect(card?.querySelector('[data-testid=approve-result]')).not.toBeNull();
      expect(card?.querySelector('[data-testid=review-writes]')?.textContent).toContain(
        'What will be written',
      );
      expect(el.querySelector('app-decision-card[data-decision-id=d2]')).toBeNull();
    });

    it('folds Tinfirm’s note of an open review to one line, and shows it in full once decided', async () => {
      const review = anApproval({
        runId: 'run2',
        subject: { type: 'RESULT', runId: 'run2', summary: 'Ready.', writes: ['Close SHOP-241'] },
      });
      const note = aMessage({
        id: 'm-review',
        kind: 'SYSTEM',
        authorPrincipalId: null,
        authorName: null,
        decisionId: review.id,
        runId: 'run2',
        body: 'Review the result before Tinfirm writes it:\n\nClose SHOP-241',
        createdAt: '2026-10-12T09:04:00Z',
      });
      const c = aConversationIn('question', { ...ISSUE, decisions: [review] });
      await open({ ...c, messages: [...c.messages, note] });
      const shown = () => el.querySelector('[data-author=SYSTEM]')!;
      expect(text('[data-testid=system-folded]')).toBe(
        'Tinfirm asked for a review before writing; see the card above. Show',
      );
      expect(shown().textContent).not.toContain('Close SHOP-241');
      el.querySelector<HTMLButtonElement>('[data-testid=system-show]')!.click();
      await settle();
      expect(shown().textContent).toContain('Close SHOP-241');

      // Decided: the card is gone, the note shows in full.
      fixture.debugElement.injector.get(ConversationStore).replace({
        ...c,
        messages: [...c.messages, { ...note, id: 'm-review-2' }],
        decisions: [{ ...review, status: 'DECIDED', outcome: 'APPROVED', mayDecide: false }],
      });
      await settle();
      expect(el.querySelector('[data-testid=system-folded]')).toBeNull();
      expect(shown().textContent).toContain('Close SHOP-241');
    });

    it.each<[string, boolean, string, string]>([
      ['one of its team, who decides it', true, 'Waits for Analysts · Review', 'human'],
      ['anyone else', false, 'Waits for Analysts · Review', 'muted'],
    ])('says whom a review waits for, to %s', async (_case, mayDecide, label, tone) => {
      await open(
        aConversationIn('question', {
          ...ISSUE,
          waitingReason: 'REVIEW',
          waitingGroupId: 'g-analysts',
          waitingGroupName: 'Analysts',
          decisions: [
            anApproval({
              runId: 'run2',
              mayDecide,
              subject: { type: 'RESULT', runId: 'run2', summary: 'Ready.', writes: [] },
            }),
          ],
        }),
      );
      const chip = el.querySelector('[data-testid=conversation-state]')!;
      expect(chip.textContent?.trim()).toBe(label);
      expect(chip.getAttribute('data-tone')).toBe(tone);
    });

    it('says a webhook’s delivery started it, and takes messages from its team', async () => {
      const c = aConversation({
        personPrincipalId: null,
        personName: null,
        place: 'WEBHOOK',
        trigger: TRIGGER,
        subject: {
          key: 'ORD-1042',
          title: 'Order 1042',
          systemName: 'Shop events',
          url: 'https://shop.test/orders/1042',
        },
        mayPost: true,
        mayAnswer: true,
      });
      await open({
        ...c,
        messages: [{ ...COMMENT, authorName: null, body: 'Order 1042 failed.' }, ...c.messages],
      });
      expect(text('[data-testid=place-notice]')).toContain(
        'on a webhook’s delivery from Shop events about ORD-1042.',
      );
      expect(words(el.querySelector('[data-testid=external-message] p'))).toContain(
        'Shop events sent about ORD-1042',
      );
      expect(el.querySelector('[data-testid=place-notice] a')?.getAttribute('href')).toBe(
        'https://shop.test/orders/1042',
      );
      expect(el.querySelector('[data-testid=composer-input]')).not.toBeNull();
      // It has no person: its author is not told it is someone else's.
      expect(el.querySelector('app-owner-notice')).toBeNull();
    });

    it('gives the Owner, who is not its author, no composer and no banner', async () => {
      await open(
        aConversation({
          personPrincipalId: null,
          personName: null,
          place: 'SCHEDULE',
          trigger: { ...TRIGGER, authorPrincipalId: 'p-carol' },
          mayPost: false,
          mayAnswer: false,
        }),
        'owner',
      );
      expect(text('[data-testid=place-notice]')).toContain('Started by the schedule');
      expect(el.querySelector('[data-testid=composer]')).toBeNull();
      expect(el.querySelector('app-owner-notice')).toBeNull();
    });
  });

  it('shows the Connect card above the composer for an account to connect', async () => {
    await open(aConversationIn('account'));
    const card = el.querySelector('[data-testid=binding-card]')!;
    expect(card.getAttribute('data-reason')).toBe('NONE');
    expect(card.className).toContain('border-human');
    expect(el.querySelector('#bindings')).not.toBeNull();
  });

  it('says a failed turn in words, with Retry, and retries it with a key', async () => {
    await open(aConversationIn('failed'));
    expect(text('[data-testid=conversation-status]')).toContain(
      'The last turn failed: Infrastructure error.',
    );
    expect(text('[data-testid=failure-detail]')).toBe(
      'The sandbox could not start: no space left on the device.',
    );
    el.querySelector<HTMLButtonElement>('[data-testid=retry]')!.click();
    await settle();
    const req = http.expectOne(`${URL}/retry`);
    expect(req.request.headers.get('Idempotency-Key')).toMatch(UUID);
    req.flush(aConversationIn('running'));
    await settle();
    expect(notify.success).toHaveBeenCalledWith('Business analyst runs the turn again');
    expect(el.querySelector('[data-testid=live-turn]')).not.toBeNull();
  });

  it('says a turn stopped at its time limit as a stop, and continues it with a key', async () => {
    await open(aConversationIn('time-limit'));
    const status = el.querySelector<HTMLElement>('[data-testid=conversation-status]')!;
    expect(status.dataset['failure']).toBe('TIME_LIMIT');
    expect(text('[data-testid=conversation-status]')).toContain(
      'The last turn stopped: Time limit reached.',
    );
    expect(text('[data-testid=conversation-status]')).not.toContain('failed');
    expect(text('[data-testid=failure-detail]')).toBe(
      'The run reached its time limit (60 min). Its work so far is kept: Continue goes on from ' +
        'where it stopped.',
    );
    expect(text('[data-testid=conversation-state]')).toBe('Stopped · Time limit');
    const button = el.querySelector<HTMLButtonElement>('[data-testid=retry]')!;
    expect(button.textContent?.trim()).toBe('Continue');
    button.click();
    await settle();
    const req = http.expectOne(`${URL}/retry`);
    expect(req.request.headers.get('Idempotency-Key')).toMatch(UUID);
    req.flush(aConversationIn('running'));
    await settle();
    expect(notify.success).toHaveBeenCalledWith('Business analyst goes on with the turn');
  });

  it('says a spent budget’s reset for a turn that failed with USAGE_LIMIT (V7)', async () => {
    await open(aConversationIn('usage-limit'), 'alice', anAgent(), {
      keyStatus: 'BUDGET_SPENT',
      spentUsd: 50,
    });
    expect(
      el.querySelector('[data-testid=conversation-status]')?.getAttribute('data-failure'),
    ).toBe('USAGE_LIMIT');
    expect(text('[data-testid=failure-detail]')).toMatch(
      /^Your budget is spent; it resets on .*2026.*\.$/,
    );
    expect(el.querySelector('[data-testid=failure-fix]')?.getAttribute('href')).toBe(
      '/c/c1/accounts?tab=budget',
    );
  });

  it('says the cause of a usage limit the budget does not explain, and when it resets', async () => {
    await open(aConversationIn('usage-limit'));
    expect(text('[data-testid=failure-detail]')).toMatch(
      /^The model gateway refused the request: budget exceeded\. Your budget resets on .*2026/,
    );
  });

  it.each([
    [
      'RUNTIME_REMOVED',
      'Start a new conversation with Business analyst',
      `/c/c1/conversations/new?agentId=${IDS.agent}`,
      false,
    ],
    ['MODEL_NOT_ALLOWED', 'My settings', `/c/c1/agents/${IDS.agent}/settings`, true],
    ['MODEL_REQUIRED', 'My settings', `/c/c1/agents/${IDS.agent}/settings`, true],
    ['MODEL_NOT_SERVED', 'My settings', `/c/c1/agents/${IDS.agent}/settings`, true],
  ])('links a %s failure to its fix', async (failureClass, label, href, retry) => {
    await open(aConversationIn('failed', { failureClass, failureCause: null }));
    const fix = el.querySelector('[data-testid=failure-fix]')!;
    expect(fix.textContent?.trim()).toBe(label);
    expect(fix.getAttribute('href')).toBe(href);
    expect(!!el.querySelector('[data-testid=retry]')).toBe(retry);
  });

  it('says a message the running turn took, and one on its way to it', async () => {
    const c = aConversationIn('running');
    c.messages.push(
      aMessage({
        id: 'm8',
        body: 'Use the staging database.',
        runId: 'run2',
        steering: 'DELIVERED',
        createdAt: '2026-10-12T09:20:00Z',
      }),
      aMessage({
        id: 'm9',
        body: 'Also the coupons.',
        waiting: true,
        runId: null,
        steering: 'SENDING',
        createdAt: '2026-10-12T09:21:00Z',
      }),
    );
    await open(c);
    expect(text('[data-testid=message-steered]')).toBe('Business analyst took it while working');
    expect(text('[data-testid=message-sending]')).toBe('Sending to Business analyst…');
    expect(el.querySelectorAll('[data-testid=message-waiting]')).toHaveLength(0);
    expect(el.querySelector('[data-steering=SENDING]')?.textContent).toContain('Also the coupons.');
  });

  it('keeps a message a turn took while it worked inside that turn', async () => {
    const c = aConversation();
    c.messages.push(
      aMessage({
        id: 'm5',
        body: 'Use the staging database.',
        runId: c.turns[0].id,
        steering: 'DELIVERED',
        createdAt: '2026-10-12T09:30:00Z',
      }),
    );
    await open(c);
    const turn = el.querySelector('[data-testid=turn]')!;
    expect(turn.querySelector('[data-testid=message-steered]')?.textContent?.trim()).toBe(
      'Business analyst took it while working',
    );
    expect(turn.textContent).toContain('Use the staging database.');
  });

  it('marks the message a cancel left unanswered', async () => {
    await open(aConversationIn('cancelled'));
    expect(text('[data-testid=message-unanswered]')).toBe('Not answered: its turn was cancelled');
    expect(text('[data-testid=conversation-status]')).toContain('A message was not answered.');
  });

  it('is read only once ended: no composer and no actions', async () => {
    await open(aConversationIn('ended'));
    expect(el.querySelector('[data-testid=composer]')).toBeNull();
    expect(el.querySelector('[data-testid=conversation-menu]')).toBeNull();
    expect(el.querySelector('[data-testid=rename]')).toBeNull();
    expect(text('[data-testid=conversation-status]')).toMatch(
      /^Ended on .+\. It is read only; its outputs and files stay\.$/,
    );
  });

  it('sends a message as the next turn, with a key, and clears the box', async () => {
    await open(aConversation());
    const box = el.querySelector<HTMLTextAreaElement>('[data-testid=composer-input]')!;
    box.value = 'Add the mobile app too.';
    box.dispatchEvent(new Event('input'));
    await settle();
    el.querySelector<HTMLButtonElement>('[data-testid=composer-send]')!.click();
    await settle();
    const req = http.expectOne(`${URL}/messages`);
    expect(req.request.body).toEqual({ body: 'Add the mobile app too.' });
    expect(req.request.headers.get('Idempotency-Key')).toMatch(UUID);
    req.flush(aConversationIn('running'));
    await settle();
    expect(box.value).toBe('');
    expect(document.activeElement).toBe(box);
    expect(el.querySelector('[data-testid=live-turn]')).not.toBeNull();
  });

  it('keeps the message and its key after a network error', async () => {
    await open(aConversation());
    const box = el.querySelector<HTMLTextAreaElement>('[data-testid=composer-input]')!;
    box.value = 'Add the mobile app too.';
    box.dispatchEvent(new Event('input'));
    await settle();
    const send = el.querySelector<HTMLButtonElement>('[data-testid=composer-send]')!;
    send.click();
    await settle();
    const first = http.expectOne(`${URL}/messages`);
    first.error(new ProgressEvent('error'), { status: 0 });
    await settle();
    expect(text('[data-testid=composer-error]')).toContain('Your message is still here');
    expect(box.value).toBe('Add the mobile app too.');

    send.click();
    await settle();
    const again = http.expectOne(`${URL}/messages`);
    expect(again.request.headers.get('Idempotency-Key')).toBe(
      first.request.headers.get('Idempotency-Key'),
    );
    again.flush(aConversationIn('running'));
    await settle();
  });

  it('renames in place for its person', async () => {
    await open(aConversation());
    el.querySelector<HTMLButtonElement>('[data-testid=rename]')!.click();
    await settle();
    const input = el.querySelector<HTMLInputElement>('[data-testid=rename-input]')!;
    input.value = 'Guest checkout';
    input.dispatchEvent(new Event('input'));
    await settle();
    el.querySelector<HTMLButtonElement>('[data-testid=rename-save]')!.click();
    await settle();
    const req = http.expectOne((r) => r.method === 'PATCH' && r.url === URL);
    expect(req.request.body).toEqual({ title: 'Guest checkout' });
    req.flush(aConversation({ title: 'Guest checkout' }));
    await settle();
    expect(text('[data-testid=conversation-title]')).toBe('Guest checkout');
  });

  it('ends the conversation from the menu, after a confirm', async () => {
    await open(aConversation());
    el.querySelector<HTMLButtonElement>('[data-testid=conversation-menu]')!.click();
    await settle();
    document.querySelector<HTMLButtonElement>('[data-testid=end-conversation]')!.click();
    await settle();
    expect(document.body.textContent).toContain('End this conversation?');
    document.querySelector<HTMLButtonElement>('[data-testid=confirm-end]')!.click();
    await settle();
    http.expectOne(`${URL}/end`).flush(aConversationIn('ended'));
    await settle();
    expect(el.querySelector('[data-testid=composer]')).toBeNull();
    expect(notify.success).toHaveBeenCalledWith(
      'Conversation ended',
      'It is read only now; its outputs and files stay.',
    );
  });

  it('cancels the running turn from the menu', async () => {
    await open(aConversationIn('running'));
    el.querySelector<HTMLButtonElement>('[data-testid=conversation-menu]')!.click();
    await settle();
    document.querySelector<HTMLButtonElement>('[data-testid=cancel-turn]')!.click();
    await settle();
    http.expectOne(`${URL}/cancel`).flush(aConversationIn('cancelled'));
    await settle();
    expect(el.querySelector('[data-testid=conversation-status]')?.getAttribute('data-phase')).toBe(
      'cancelled',
    );
  });

  describe('the pinned cards', () => {
    let observers: { callback: IntersectionObserverCallback; targets: Element[] }[];

    beforeEach(() => {
      observers = [];
      class FakeObserver {
        private readonly entry: { callback: IntersectionObserverCallback; targets: Element[] };
        constructor(callback: IntersectionObserverCallback) {
          this.entry = { callback, targets: [] };
          observers.push(this.entry);
        }
        observe(target: Element) {
          this.entry.targets.push(target);
        }
        disconnect() {
          this.entry.targets = [];
        }
      }
      vi.stubGlobal('IntersectionObserver', FakeObserver);
    });
    afterEach(() => vi.unstubAllGlobals());

    /** Says whether the thread's open cards are in view (the page's first observer). */
    async function cardsInView(visible: boolean): Promise<void> {
      const [cards] = observers;
      cards.callback(
        cards.targets.map(
          (target) => ({ target, isIntersecting: visible }) as IntersectionObserverEntry,
        ),
        cards as unknown as IntersectionObserver,
      );
      await settle();
    }

    /** Says whether the reader has scrolled past the thread's start (the page's second observer). */
    async function inThread(scrolledPast: boolean): Promise<void> {
      const start = observers[1];
      start.callback(
        start.targets.map(
          (target) =>
            ({
              target,
              isIntersecting: false,
              boundingClientRect: { top: scrolledPast ? -40 : 900 },
            }) as IntersectionObserverEntry,
        ),
        start as unknown as IntersectionObserver,
      );
      await settle();
    }

    it('pins the open question above the composer while its card is out of view', async () => {
      await open(aConversationIn('question'));
      expect(observers[0].targets.map((t) => t.getAttribute('data-decision-id'))).toEqual(['d1']);
      await inThread(true);
      await cardsInView(true);
      expect(el.querySelector('[data-testid=pinned-cards]')).toBeNull();
      await cardsInView(false);
      const pinned = el.querySelector('[data-testid=composer-dock] [data-testid=pinned-cards]');
      expect(pinned?.textContent).toContain('Needs you');
      expect(words(pinned?.querySelector('[data-testid=pinned-counts]'))).toBe('· 1 open question');
      // A strip, not a box: the card is the only sheet in it.
      expect(pinned?.className).not.toMatch(/\bborder\b|bg-/);

      // "Where it was raised": the card in the thread, in view and focused.
      const inline = el.querySelector<HTMLElement>('[data-testid=turn] [data-decision-id=d1]')!;
      const scroll = vi.fn();
      inline.scrollIntoView = scroll;
      pinned!.querySelector<HTMLButtonElement>('[data-testid=pinned-jump]')!.click();
      await settle();
      expect(scroll).toHaveBeenCalled();
      expect(inline.contains(document.activeElement)).toBe(true);
    });

    it('pins nothing above the thread: the header says what waits, and the composer stays on screen', async () => {
      await open(aConversationIn('question'));
      expect(observers[1].targets).toEqual([el.querySelector('[data-testid=thread] > div')]);
      await cardsInView(false);
      await inThread(false);
      expect(el.querySelector('[data-testid=pinned-cards]')).toBeNull();
      expect(text('[data-testid=conversation-state]')).toBe('Needs you · Question');
      await inThread(true);
      expect(el.querySelector('[data-testid=pinned-cards]')).not.toBeNull();
    });

    it('pins the Connect card the reader acts on', async () => {
      await open(aConversationIn('account'));
      expect(
        observers[0].targets.map((t) => t.querySelector('[data-testid=binding-card]')?.id),
      ).toEqual([`binding-${IDS.jira}`]);
      await inThread(true);
      await cardsInView(false);
      expect(
        el
          .querySelector('[data-testid=pinned-cards] [data-testid=binding-card]')
          ?.getAttribute('data-reason'),
      ).toBe('NONE');
    });

    it('pins nothing for the Owner', async () => {
      await open(aConversationIn('question'), 'owner');
      await inThread(true);
      await cardsInView(false);
      expect(el.querySelector('[data-testid=pinned-cards]')).toBeNull();
    });
  });

  describe('as the Owner reading someone else’s conversation', () => {
    it.each<ConversationStateName>(['running', 'question', 'account', 'failed', 'idle'])(
      '%s: the banner, no composer, no rename; neutral where Alice must act',
      async (state) => {
        await open(aConversationIn(state), 'owner');
        // Where the composer would be.
        expect(text('[data-testid=composer-dock] [data-testid=owner-banner]')).toBe(
          'Alice Novak’s conversation. Only Alice Novak writes, decides and connects accounts ' +
            'here. Your read is recorded.',
        );
        expect(text('[data-testid=conversation-person]')).toBe('for Alice Novak');
        expect(el.querySelector('[data-testid=owner-notice]')).toBeNull();
        expect(el.querySelector('[data-testid=conversation-reads]')).toBeNull();
        expect(el.querySelector('[data-testid=composer]')).toBeNull();
        expect(el.querySelector('[data-testid=composer-attach]')).toBeNull();
        expect(el.querySelector('[data-testid=rename]')).toBeNull();
        expect(el.querySelector('[data-testid=conversation-menu]')).not.toBeNull();
        expect(
          el.querySelector('[data-testid=conversation-state]')?.getAttribute('data-tone'),
        ).not.toBe('human');
        expect(el.querySelector('[data-testid=binding-card].border-human')).toBeNull();
        // Alice's messages sit on the left, with her name.
        expect(el.querySelector('[data-testid=message][data-mine]')).toBeNull();
        expect(
          el.querySelector('[data-testid=who-acts-who-pays] [data-part=pays]')?.textContent,
        ).toContain('Alice Novak’s budget');
        expect(
          el.querySelector('[data-testid=who-acts-who-pays] [data-part=acts]')?.textContent,
        ).toContain('GitHub: Alice Novak’s own account');
      },
    );

    it('keeps Retry for a failed turn', async () => {
      await open(aConversationIn('failed'), 'owner');
      expect(el.querySelector('[data-testid=retry]')).not.toBeNull();
    });

    it('says the waiting budget is Alice’s, without a link to the Owner’s own', async () => {
      await open(aConversationIn('budget-spent'), 'owner');
      expect(text('[data-testid=live-turn-reason]')).toMatch(
        /^Waiting for Alice Novak’s budget to reset on /,
      );
      expect(el.querySelector('[data-testid=live-turn-fix]')).toBeNull();
    });
  });
});

describe('ConversationPage, back from a sign-in', () => {
  it('continues the waiting binding, once, and says so', async () => {
    const notifier = fakeNotify();
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [{ path: 'c/:companyId/conversations/:conversationId', component: ConversationPage }],
          withComponentInputBinding(),
        ),
        ...provideTestHttp(),
        fakeCurrentCompany('MEMBER').provider,
        notifier.provider,
        ...fakeEventSources().providers,
        NO_POLLS,
      ],
    });
    const http = TestBed.inject(HttpTestingController);
    const harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(
      `/c/c1/conversations/${IDS.conversation}?account=connected&system=${IDS.jira}`,
    );
    await settle();
    for (const req of http.match(`${API}/me/model-access`)) req.flush(aModelAccess());
    http.expectOne(URL).flush(aConversationIn('account'));
    await settle();
    for (const req of http.match(`${API}/agents/${IDS.agent}`)) req.flush(anAgent());
    for (const req of http.match(`${API}/me/accounts`)) req.flush({ items: [aMyAccount()] });
    await settle();

    const cont = http.expectOne(`${URL}/bindings/${IDS.jira}/continue`);
    expect(cont.request.method).toBe('POST');
    cont.flush(
      aWaitingBinding({ state: 'BOUND', reason: null, how: 'OWN', text: 'Jira: your own account' }),
    );
    await settle();
    expect(notifier.notify.success).toHaveBeenCalledWith(
      'Jira: your own account',
      'The conversation goes on with it.',
    );
    for (let round = 0; round < 3; round++) {
      for (const req of http.match(URL)) req.flush(aConversation());
      for (const req of http.match(`${API}/agents/${IDS.agent}`)) req.flush(anAgent());
      for (const req of http.match(`${URL}/reads`)) req.flush([]);
      await settle();
    }
    expect(TestBed.inject(Router).url).toBe(`/c/c1/conversations/${IDS.conversation}`);
    http.verify();
  });
});
