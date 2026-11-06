import type { Provider } from '@angular/core';
import { CONVERSATION_STREAM_TIMING } from '../pages/company/conversations/conversation-store';
import {
  EVENT_SOURCE_FACTORY,
  type EventSourceLike,
  RUN_STREAM_TIMING,
} from '../pages/company/runs/run-stream';

/** A scriptable stand-in for the browser's EventSource, as `RunStream` and `ConversationStore` use it. */
export class FakeEventSource implements EventSourceLike {
  readyState = 0;
  onopen: ((event: Event) => unknown) | null = null;
  onerror: ((event: Event) => unknown) | null = null;
  closed = false;
  private readonly listeners = new Map<string, ((event: MessageEvent<string>) => void)[]>();

  constructor(readonly url: string) {}

  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener]);
  }

  close(): void {
    this.closed = true;
    this.readyState = 2;
  }

  open(): void {
    this.readyState = 1;
    this.onopen?.(new Event('open'));
  }

  send(
    type: 'run-event' | 'run-status' | 'conversation-status' | 'conversation-changed',
    data: unknown,
    id?: string,
  ): void {
    const event = new MessageEvent<string>(type, { data: JSON.stringify(data), lastEventId: id });
    for (const listener of this.listeners.get(type) ?? []) listener(event);
  }

  /** `readyState` 0 = the browser reconnects itself; 2 = it gave up. */
  fail(readyState: 0 | 2): void {
    this.readyState = readyState;
    this.onerror?.(new Event('error'));
  }
}

/**
 * The SSE connections `RunStream` opens in a spec, newest last, with timings of a millisecond.
 * Set `available` to false for a browser without EventSource (the stream then polls).
 */
export function fakeEventSources() {
  const sources: FakeEventSource[] = [];
  const state = { available: true };
  const providers: Provider[] = [
    {
      provide: EVENT_SOURCE_FACTORY,
      useValue: (url: string) => {
        if (!state.available) return null;
        const source = new FakeEventSource(url);
        sources.push(source);
        return source;
      },
    },
    { provide: RUN_STREAM_TIMING, useValue: { flushMs: 1, pollMs: 1, retryMs: 1 } },
    { provide: CONVERSATION_STREAM_TIMING, useValue: { retryMs: 1, maxRetryMs: 4 } },
  ];
  const last = (part: string) => [...sources].reverse().find((s) => s.url.includes(part));
  const lastConversation = () =>
    [...sources].reverse().find((s) => /\/conversations\/[^/]+\/stream$/.test(s.url));
  return {
    sources,
    state,
    providers,
    latest: (): FakeEventSource | undefined => sources[sources.length - 1],
    /** The newest stream of a run. */
    run: (): FakeEventSource | undefined => last('/runs/'),
    /** The newest stream of a conversation. */
    conversation: (): FakeEventSource | undefined => lastConversation(),
  };
}
