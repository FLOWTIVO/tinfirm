import { DestroyRef, inject, Injectable, InjectionToken, signal } from '@angular/core';
import { Api } from '../../../api/generated/api';
import { getRun, listRunEvents } from '../../../api/generated/functions';
import type { Run, RunEvent } from '../../../api/generated/models';
import { toProblem } from '../../../api/problem';
import { isRunActive } from '../../../shared/run-status';
import {
  EMPTY_SUMMARY,
  TranscriptBuilder,
  type TranscriptItem,
  type TranscriptSummary,
} from './transcript-model';

/** The part of `EventSource` the stream uses; tests pass a fake. */
export interface EventSourceLike {
  readonly readyState: number;
  onopen: ((event: Event) => unknown) | null;
  onerror: ((event: Event) => unknown) | null;
  addEventListener(type: string, listener: (event: MessageEvent<string>) => void): void;
  close(): void;
}

/** Creates the SSE connection; null when the browser has no `EventSource`. */
export type EventSourceFactory = (url: string) => EventSourceLike | null;

export const EVENT_SOURCE_FACTORY = new InjectionToken<EventSourceFactory>('EVENT_SOURCE_FACTORY', {
  providedIn: 'root',
  // Same-origin, so the session cookie goes along; `connect-src 'self'` covers it (CONVENTIONS §5).
  factory: () => (url) =>
    typeof EventSource === 'function' ? (new EventSource(url) as EventSourceLike) : null,
});

export interface RunStreamTiming {
  /** Batching window for incoming events, so a replay of 20 000 events renders a few times. */
  readonly flushMs: number;
  /** Interval of the `listRunEvents` fallback. */
  readonly pollMs: number;
  /** Base delay before re-opening a stream the browser gave up on (doubles per attempt). */
  readonly retryMs: number;
}

export const RUN_STREAM_TIMING = new InjectionToken<RunStreamTiming>('RUN_STREAM_TIMING', {
  providedIn: 'root',
  factory: () => ({ flushMs: 80, pollMs: 2000, retryMs: 1000 }),
});

/** `EventSource.readyState` values. */
const CONNECTING = 0;
const CLOSED = 2;
/** Re-opens of a stream the browser closed, before falling back to polling. */
const MAX_REOPENS = 2;
/** Automatic browser reconnects without any message in between, before falling back. */
const MAX_SILENT_RECONNECTS = 3;
const MAX_POLL_ERRORS = 5;
const PAGE_LIMIT = 1000;

export type StreamState =
  | 'idle'
  /** Opening the SSE stream. */
  | 'connecting'
  /** Receiving events over SSE. */
  | 'live'
  /** The SSE connection dropped; reconnecting from the last event. */
  | 'reconnecting'
  /** SSE didn't work; reading `listRunEvents` every few seconds instead. */
  | 'polling'
  /** The run finished and every event is here. */
  | 'ended'
  /** Gave up (e.g. the run is gone or access was lost). */
  | 'failed';

/**
 * Live events of one run (`streamRunEvents`): opens `GET …/runs/{runId}/events/stream` (SSE) and
 * folds the events into transcript rows. The run page follows its run with it, a thread its active
 * turn, and a turn's folded work reads a finished run's events with it when opened.
 *
 * - The browser reconnects a dropped stream on its own and sends `Last-Event-ID`, so the server
 *   resumes after the last event. When the browser gives up (HTTP error), the stream is re-opened
 *   with `afterSeq` = the last sequence number seen.
 * - When SSE keeps failing (or `EventSource` is missing) it falls back to polling
 *   `listRunEvents(afterSeq)` until the page says `complete`.
 * - It stops at the final `run-status` (a finished run). Duplicate events (seq ≤ last) are dropped.
 * - A run known to be finished at the start is read with `listRunEvents` only.
 *
 * Provide it per component; it cleans up when that component is destroyed.
 */
@Injectable()
export class RunStream {
  private readonly api = inject(Api);
  private readonly createSource = inject(EVENT_SOURCE_FACTORY);
  private readonly timing = inject(RUN_STREAM_TIMING);

  readonly state = signal<StreamState>('idle');
  readonly items = signal<readonly TranscriptItem[]>([]);
  readonly summary = signal<TranscriptSummary>(EMPTY_SUMMARY);
  /** The run as last reported by the stream (`run-status`) or by polling. */
  readonly run = signal<Run | null>(null);
  readonly error = signal<unknown>(null);

  private companyId = '';
  private runId = '';
  /** Bumped by start()/stop(); callbacks from an older session are ignored. */
  private session = 0;
  private builder = new TranscriptBuilder();
  private pending: RunEvent[] = [];
  private lastSeq = 0;
  private source: EventSourceLike | null = null;
  private reopens = 0;
  private silentReconnects = 0;
  private flushTimer: ReturnType<typeof setTimeout> | null = null;
  private waitTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  /** Starts (or restarts) following a run. `run` is shown until the stream reports its own. */
  start(companyId: string, runId: string, run: Run | null = null): void {
    this.stop();
    const session = this.session;
    this.companyId = companyId;
    this.runId = runId;
    this.builder = new TranscriptBuilder();
    this.pending = [];
    this.lastSeq = 0;
    this.reopens = 0;
    this.silentReconnects = 0;
    this.items.set([]);
    this.summary.set(EMPTY_SUMMARY);
    this.error.set(null);
    this.run.set(run);
    // A finished run has nothing to follow: read its events page by page, without holding one of
    // the browser's few connections per origin (a work item shows many turns at once).
    if (run && !isRunActive(run.status)) void this.poll(session);
    else this.open(session);
  }

  stop(): void {
    this.session++;
    this.closeSource();
    this.clearTimers();
    if (this.state() !== 'ended' && this.state() !== 'failed') this.state.set('idle');
  }

  /** Applies buffered events now (normally they are batched for `flushMs`). */
  flush(): void {
    if (this.flushTimer !== null) clearTimeout(this.flushTimer);
    this.flushTimer = null;
    if (this.pending.length === 0) return;
    this.builder.apply(this.pending);
    this.pending = [];
    this.items.set(this.builder.snapshot());
    this.summary.set(this.builder.summary());
  }

  // --- SSE ----------------------------------------------------------------------------------

  private url(): string {
    const base = `/api/companies/${encodeURIComponent(this.companyId)}/runs/${encodeURIComponent(this.runId)}/events/stream`;
    return this.lastSeq > 0 ? `${base}?afterSeq=${this.lastSeq}` : base;
  }

  private open(session: number): void {
    let created: EventSourceLike | null;
    try {
      created = this.createSource(this.url());
    } catch {
      created = null;
    }
    if (!created) {
      void this.poll(session);
      return;
    }
    const source = created;
    this.source = source;
    this.state.set(this.lastSeq > 0 ? 'reconnecting' : 'connecting');

    source.onopen = () => {
      if (session !== this.session) return;
      this.state.set('live');
      this.reopens = 0;
      this.silentReconnects = 0;
    };
    source.addEventListener('run-event', (event) => {
      if (session !== this.session) return;
      this.silentReconnects = 0;
      this.accept(parse<RunEvent>(event.data));
    });
    source.addEventListener('run-status', (event) => {
      if (session !== this.session) return;
      const run = parse<Run>(event.data);
      if (run) this.onRunStatus(run, session);
    });
    source.onerror = () => {
      if (session !== this.session) return;
      if (source.readyState === CONNECTING) {
        // The browser retries by itself and sends Last-Event-ID.
        this.state.set('reconnecting');
        if (++this.silentReconnects > MAX_SILENT_RECONNECTS) {
          this.closeSource();
          void this.poll(session);
        }
        return;
      }
      if (source.readyState === CLOSED) {
        // The browser gave up (non-200 answer or wrong content type).
        this.closeSource();
        if (++this.reopens > MAX_REOPENS) {
          void this.poll(session);
          return;
        }
        this.state.set('reconnecting');
        this.wait(this.timing.retryMs * 2 ** (this.reopens - 1), session).then(() =>
          this.open(session),
        );
      }
    };
  }

  private onRunStatus(run: Run, session: number): void {
    this.flush();
    this.run.set(run);
    if (isRunActive(run.status)) return;
    // The final status: the server closes the stream now; don't let the browser reconnect.
    this.closeSource();
    if (run.lastEventSeq > this.lastSeq) {
      // Events were missed (e.g. dropped while reconnecting): read the rest once.
      void this.poll(session);
    } else {
      this.state.set('ended');
    }
  }

  private accept(event: RunEvent | null): void {
    if (!event || typeof event.seq !== 'number' || event.seq <= this.lastSeq) return;
    this.lastSeq = event.seq;
    this.pending.push(event);
    this.flushTimer ??= setTimeout(() => this.flush(), this.timing.flushMs);
  }

  // --- Polling fallback ---------------------------------------------------------------------

  private async poll(session: number): Promise<void> {
    this.state.set('polling');
    let failures = 0;
    while (session === this.session) {
      try {
        const [page, run] = await Promise.all([
          this.api.invoke(listRunEvents, {
            companyId: this.companyId,
            runId: this.runId,
            afterSeq: this.lastSeq,
            limit: PAGE_LIMIT,
          }),
          this.api.invoke(getRun, { companyId: this.companyId, runId: this.runId }),
        ]);
        if (session !== this.session) return;
        failures = 0;
        this.error.set(null);
        for (const event of page.items) this.accept(event);
        this.flush();
        this.run.set(run);
        if (page.complete) {
          this.state.set('ended');
          return;
        }
        if (page.items.length >= PAGE_LIMIT) continue; // catching up: no pause
      } catch (error) {
        if (session !== this.session) return;
        this.error.set(error);
        const status = toProblem(error).status;
        if ([401, 403, 404].includes(status) || ++failures >= MAX_POLL_ERRORS) {
          this.state.set('failed');
          return;
        }
      }
      await this.wait(this.timing.pollMs, session);
    }
  }

  // --- Helpers ------------------------------------------------------------------------------

  private wait(ms: number, session: number): Promise<void> {
    return new Promise((resolve) => {
      this.waitTimer = setTimeout(() => {
        this.waitTimer = null;
        if (session === this.session) resolve();
      }, ms);
    });
  }

  private closeSource(): void {
    if (!this.source) return;
    this.source.onopen = null;
    this.source.onerror = null;
    this.source.close();
    this.source = null;
  }

  private clearTimers(): void {
    this.flush();
    if (this.waitTimer !== null) clearTimeout(this.waitTimer);
    this.waitTimer = null;
  }
}

function parse<T>(data: string): T | null {
  try {
    const value: unknown = JSON.parse(data);
    return typeof value === 'object' && value !== null ? (value as T) : null;
  } catch {
    return null;
  }
}
