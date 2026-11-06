import { HttpTestingController, type TestRequest } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import type { Conversation } from '../../../api/generated/models';
import {
  aBinding,
  aConversation,
  aConversationIn,
  aWaitingBinding,
  IDS,
} from '../../../testing/fixtures';
import { fakeEventSources } from '../../../testing/fake-event-source';
import { problem, provideTestHttp } from '../../../testing/test-helpers';
import { CONVERSATION_POLL_TIMING, ConversationStore } from './conversation-store';

const URL = `/api/companies/c1/conversations/${IDS.conversation}`;
const UUID = /^[0-9a-f-]{36}$/;

describe('ConversationStore', () => {
  let store: ConversationStore;
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [
        ...provideTestHttp(),
        ConversationStore,
        {
          provide: CONVERSATION_POLL_TIMING,
          useValue: { activeMs: 5000, idleMs: 30_000, afterActionMs: 30_000 },
        },
      ],
    });
    store = TestBed.inject(ConversationStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
  });

  /** Lets the promise chains run, and the clock move by `ms`. */
  async function after(ms = 0): Promise<void> {
    await vi.advanceTimersByTimeAsync(ms);
  }

  async function opened(conversation: Conversation): Promise<void> {
    void store.open('c1', IDS.conversation);
    await after();
    http.expectOne(URL).flush(conversation);
    await after();
  }

  function reads(): TestRequest[] {
    return http.match((req) => req.method === 'GET' && req.url === URL);
  }

  it('reads the conversation when it opens', async () => {
    await opened(aConversation());
    expect(store.conversation()?.id).toBe(IDS.conversation);
  });

  it.each([
    ['running', 5000],
    ['queued', 5000],
    ['question', 30_000],
    ['failed', 30_000],
    ['idle', 30_000],
  ] as const)('polls a %s conversation every %i ms while visible', async (state, ms) => {
    await opened(aConversationIn(state));
    await after(ms - 100);
    expect(reads()).toHaveLength(0);
    await after(100);
    const [poll] = reads();
    expect(poll).toBeDefined();
    poll.flush(aConversationIn(state));
    await after();
  });

  it('polls briskly for a while after the reader acted, then as the state says', async () => {
    await opened(aConversationIn('failed'));
    const retrying = store.retry();
    await after();
    // The retry's answer: an idle conversation, whose turn the server starts a moment later.
    http.expectOne(`${URL}/retry`).flush(aConversationIn('idle'));
    await retrying;
    for (let elapsed = 0; elapsed < 30_000; elapsed += 5000) {
      await after(5000);
      const [poll] = reads();
      expect(poll).toBeDefined();
      poll.flush(aConversationIn('idle'));
      await after();
    }
    // Past the 30 s, the idle conversation is read every 30 s again.
    await after(25_000);
    expect(reads()).toHaveLength(0);
    await after(5000);
    reads()[0].flush(aConversationIn('idle'));
    await after();
  });

  it('stops polling once the conversation has ended', async () => {
    await opened(aConversationIn('ended'));
    await after(60_000);
    expect(reads()).toHaveLength(0);
  });

  it('does not poll while the page is hidden, and reads at once when it shows again', async () => {
    await opened(aConversationIn('running'));
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    await after(20_000);
    expect(reads()).toHaveLength(0);

    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    await after();
    reads()[0].flush(aConversationIn('running'));
    await after();
  });

  it('drops a read that started before a mutation was answered', async () => {
    await opened(aConversationIn('running'));
    void store.refresh();
    await after();
    const stale = reads()[0];

    const cancelled = store.cancel();
    await after();
    http.expectOne(`${URL}/cancel`).flush(aConversationIn('cancelled'));
    await cancelled;
    stale.flush(aConversationIn('running'));
    await after();

    expect(store.conversation()?.turnStatus).toBe('CANCELLED');
    // The read asked for during the stale one follows it; nothing more.
    for (const req of reads()) req.flush(aConversationIn('cancelled'));
    await after();
  });

  it('reads once more after a read on its way when asked again', async () => {
    await opened(aConversationIn('running'));
    void store.refresh();
    void store.refresh();
    await after();
    const first = reads();
    expect(first).toHaveLength(1);
    first[0].flush(aConversationIn('running'));
    await after();
    const again = reads();
    expect(again).toHaveLength(1);
    again[0].flush(aConversationIn('idle'));
    await after();
    expect(store.conversation()?.turnStatus).toBe('SUCCEEDED');
  });

  it('replaces a binding with a card’s answer and reads the rest again', async () => {
    await opened(aConversation({ bindings: [aBinding(), aWaitingBinding()] }));
    store.replaceBinding(aWaitingBinding({ state: 'BOUND', reason: null, how: 'OWN' }));
    expect(store.conversation()?.bindings.map((b) => b.state)).toEqual(['BOUND', 'BOUND']);
    await after();
    reads()[0].flush(aConversation());
    await after();
  });

  it('says not found for a 404, and stops reading', async () => {
    void store.open('c1', IDS.conversation);
    await after();
    http.expectOne(URL).flush(problem(404), { status: 404, statusText: 'Not Found' });
    await after();
    expect(store.notFound()).toBe(true);
    await after(60_000);
    expect(reads()).toHaveLength(0);
  });

  it('keeps the conversation shown when a later read fails, and says so', async () => {
    await opened(aConversationIn('running'));
    await after(5000);
    reads()[0].flush(problem(503), { status: 503, statusText: 'Unavailable' });
    await after();
    expect(store.conversation()).not.toBeNull();
    expect(store.refreshError()).not.toBeNull();
    await after(5000);
    reads()[0].flush(aConversationIn('running'));
    await after();
    expect(store.refreshError()).toBeNull();
  });

  describe('idempotent submissions (package R)', () => {
    async function post(text: string, answer: 'ok' | 'network'): Promise<string | null> {
      const posting = store.post(text).catch(() => null);
      await after();
      const req = http.expectOne(`${URL}/messages`);
      const key = req.request.headers.get('Idempotency-Key');
      if (answer === 'ok')
        req.flush(aConversationIn('running'), { status: 201, statusText: 'Created' });
      else req.error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
      await posting;
      return key;
    }

    it('sends a message with a new key, and the same key when it is sent again after a network error', async () => {
      await opened(aConversation());
      const first = await post('Add the mobile app too.', 'network');
      expect(first).toMatch(UUID);
      const again = await post('Add the mobile app too.', 'ok');
      expect(again).toBe(first);
      const next = await post('Add the mobile app too.', 'ok');
      expect(next).not.toBe(first);
      for (const req of reads()) req.flush(aConversationIn('running'));
    });

    it('sends another message with another key', async () => {
      await opened(aConversation());
      const first = await post('One', 'network');
      const other = await post('Two', 'ok');
      expect(other).not.toBe(first);
      for (const req of reads()) req.flush(aConversationIn('running'));
    });

    it('sends the body and the open questions it answers', async () => {
      await opened(aConversationIn('question'));
      const posting = store.post('Yes, the mobile app too.', true);
      await after();
      const req = http.expectOne(`${URL}/messages`);
      expect(req.request.body).toEqual({
        body: 'Yes, the mobile app too.',
        answersOpenQuestions: true,
      });
      req.flush(aConversationIn('running'));
      await posting;
      expect(store.conversation()?.waitingOn).toBe('AGENT');
    });

    it('retries a turn with a key of its own, kept after a network error', async () => {
      await opened(aConversationIn('failed'));
      const keys: (string | null)[] = [];
      for (const answer of ['network', 'ok'] as const) {
        const retrying = store.retry().catch(() => null);
        await after();
        const req = http.expectOne(`${URL}/retry`);
        keys.push(req.request.headers.get('Idempotency-Key'));
        if (answer === 'ok') req.flush(aConversationIn('running'));
        else req.error(new ProgressEvent('error'), { status: 0 });
        await retrying;
      }
      expect(keys[0]).toMatch(UUID);
      expect(keys[1]).toBe(keys[0]);

      // The retried turn fails too: retrying it is another submission.
      store.replace(
        aConversationIn('failed', {
          turns: [
            ...aConversationIn('failed').turns,
            { ...aConversationIn('failed').turns[1], id: 'run3' },
          ],
        }),
      );
      const retrying = store.retry();
      await after();
      const req = http.expectOne(`${URL}/retry`);
      expect(req.request.headers.get('Idempotency-Key')).not.toBe(keys[0]);
      req.flush(aConversationIn('running'));
      await retrying;
    });

    it('renames and ends without a key, and replaces the conversation with the answer', async () => {
      await opened(aConversation());
      const renaming = store.rename('Guest checkout');
      await after();
      const rename = http.expectOne(URL);
      expect(rename.request.method).toBe('PATCH');
      expect(rename.request.body).toEqual({ title: 'Guest checkout' });
      expect(rename.request.headers.has('Idempotency-Key')).toBe(false);
      rename.flush(aConversation({ title: 'Guest checkout' }));
      await renaming;
      expect(store.conversation()?.title).toBe('Guest checkout');

      const ending = store.end();
      await after();
      http.expectOne(`${URL}/end`).flush(aConversationIn('ended'));
      await ending;
      expect(store.conversation()?.state).toBe('ENDED');
    });
  });

  describe('the conversation’s stream (signals only)', () => {
    let streams: ReturnType<typeof fakeEventSources>;

    beforeEach(() => {
      TestBed.resetTestingModule();
      streams = fakeEventSources();
      TestBed.configureTestingModule({
        providers: [
          ...provideTestHttp(),
          ...streams.providers,
          ConversationStore,
          {
            provide: CONVERSATION_POLL_TIMING,
            useValue: { activeMs: 5000, idleMs: 30_000, afterActionMs: 30_000 },
          },
        ],
      });
      store = TestBed.inject(ConversationStore);
      http = TestBed.inject(HttpTestingController);
    });

    const STATUS = {
      id: IDS.conversation,
      state: 'OPEN',
      waitingOn: 'AGENT',
      waitingReason: 'RUNNING',
      turnStatus: 'RUNNING',
      activeRunId: 'run2',
      turnCount: 2,
    };

    it('opens the stream and, while it is live, reads only on its signals', async () => {
      await opened(aConversationIn('running'));
      const source = streams.conversation()!;
      expect(source.url).toBe(`${URL}/stream`);
      source.open();
      await after();
      // Opened: what changed before it opened is read once.
      reads()[0].flush(aConversationIn('running'));
      await after();
      expect(store.streamState()).toBe('live');
      // No poll while it is live.
      await after(60_000);
      expect(reads()).toHaveLength(0);
      // The status it starts with says what is shown: nothing to read.
      source.send('conversation-status', STATUS);
      await after();
      expect(reads()).toHaveLength(0);
      // A committed change is read.
      source.send('conversation-changed', { parts: ['DECISIONS', 'STATUS'] });
      await after();
      reads()[0].flush(aConversationIn('question'));
      await after();
      expect(store.conversation()?.waitingReason).toBe('QUESTION');
      // A status that differs from the one shown is read too.
      source.send('conversation-status', { ...STATUS, activeRunId: 'run3', turnCount: 3 });
      await after();
      const again = reads();
      expect(again).toHaveLength(1);
      again[0].flush(aConversationIn('running'));
      await after();
    });

    it('closes the stream once the conversation ended', async () => {
      await opened(aConversationIn('running'));
      const source = streams.conversation()!;
      source.open();
      await after();
      reads()[0].flush(aConversationIn('running'));
      await after();
      source.send('conversation-status', { ...STATUS, state: 'ENDED', waitingOn: 'NONE' });
      await after();
      expect(source.closed).toBe(true);
      reads()[0].flush(aConversationIn('ended'));
      await after(60_000);
      expect(reads()).toHaveLength(0);
      expect(streams.sources).toHaveLength(1);
    });

    it('polls while the stream is down, and opens it again with a backoff', async () => {
      await opened(aConversationIn('running'));
      const first = streams.conversation()!;
      first.open();
      await after();
      reads()[0].flush(aConversationIn('running'));
      await after();
      // The browser gave up: closed, polled meanwhile, opened again after a while.
      first.fail(2);
      expect(first.closed).toBe(true);
      expect(store.streamState()).toBe('down');
      await after(1);
      const second = streams.conversation()!;
      expect(second).not.toBe(first);
      second.fail(2);
      await after(1);
      expect(streams.sources).toHaveLength(2);
      await after(1);
      const third = streams.conversation()!;
      expect(streams.sources).toHaveLength(3);
      await after(5000);
      // The poll of a conversation that waits on the agent.
      const [poll] = reads();
      expect(poll).toBeDefined();
      poll.flush(aConversationIn('running'));
      await after();
      // Opened again: read once, and the polling stops.
      third.open();
      await after();
      reads()[0].flush(aConversationIn('running'));
      await after(60_000);
      expect(reads()).toHaveLength(0);
    });

    it('falls back to polling in a browser without EventSource', async () => {
      streams.state.available = false;
      await opened(aConversationIn('running'));
      expect(store.streamState()).toBe('off');
      await after(5000);
      const [poll] = reads();
      expect(poll).toBeDefined();
      poll.flush(aConversationIn('running'));
      await after();
    });
  });
});
