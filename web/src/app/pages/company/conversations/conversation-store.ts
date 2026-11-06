import {
  computed,
  DestroyRef,
  DOCUMENT,
  inject,
  Injectable,
  InjectionToken,
  signal,
} from '@angular/core';
import { Api } from '../../../api/generated/api';
import {
  cancelConversation,
  endConversation,
  getConversation,
  postConversationMessage,
  renameConversation,
  retryConversation,
} from '../../../api/generated/functions';
import type {
  Conversation,
  ConversationBinding,
  ConversationStatus,
  Decision,
} from '../../../api/generated/models';
import { toProblem } from '../../../api/problem';
import { SubmissionKey, submitWithKey } from '../../../core/submission-key';
import { EVENT_SOURCE_FACTORY, type EventSourceLike } from '../runs/run-stream';

export interface ConversationPollTiming {
  /** While the conversation waits on a runner or the agent, and for a while after the reader acted. */
  readonly activeMs: number;
  /** While it waits on its person or on nobody. */
  readonly idleMs: number;
  /** How long after the reader acted the conversation is read at `activeMs`. */
  readonly afterActionMs: number;
}

/**
 * Plan §4.1's freshness: every 5 s while a runner or the agent is waited on, else every 30 s; and
 * every 5 s for 30 s after the reader acted, since what an action starts on the server (the turn a
 * connected account wakes) comes a moment after its answer.
 */
export const CONVERSATION_POLL_TIMING = new InjectionToken<ConversationPollTiming>(
  'CONVERSATION_POLL_TIMING',
  {
    providedIn: 'root',
    factory: () => ({ activeMs: 5000, idleMs: 30_000, afterActionMs: 30_000 }),
  },
);

export interface ConversationStreamTiming {
  /** The first wait before opening a stream the browser gave up on again; it doubles each time. */
  readonly retryMs: number;
  /** The longest wait between two openings. */
  readonly maxRetryMs: number;
}

export const CONVERSATION_STREAM_TIMING = new InjectionToken<ConversationStreamTiming>(
  'CONVERSATION_STREAM_TIMING',
  { providedIn: 'root', factory: () => ({ retryMs: 1000, maxRetryMs: 30_000 }) },
);

/** Where the conversation's signals come from: its stream, or polling while that is down. */
export type ConversationStreamState = 'off' | 'connecting' | 'live' | 'down';

/** `EventSource.readyState` once the browser gave up. */
const CLOSED = 2;

/**
 * One conversation as the page shows it (plan §4.1, freshness): the `Conversation` signal, read
 * with `getConversation` and replaced whole by every answer.
 *
 * - **Every mutation answers the conversation and replaces it**: rename, a message, cancel, retry
 *   and end here; a card's answer replaces its decision or binding (`replaceDecision`,
 *   `replaceBinding`) and the conversation is read again, since what settles may start a turn; a
 *   file added or deleted reads it again (`acted`).
 * - **The run stream drives the live turn** (`LiveTurn`, on `streamRunEvents`): each change of the
 *   run's status, and its final `run-status`, reads the conversation again (`refresh`).
 * - **The conversation's stream signals every change** (`streamConversation`, server-sent events,
 *   signals only): a `conversation-status` first, then `conversation-changed {parts}` after each
 *   committed change, and a `conversation-status` when its summary changed. The conversation is read
 *   again (`getConversation`, which holds every part the page shows) when the stream opens or opens
 *   again, on each change, and on a status that differs from the one shown; the stream is closed
 *   once the conversation ended. The live turn follows `activeRunId` from what is read.
 * - **While the stream is down it polls** (a browser without `EventSource`, or one that gave up:
 *   opened again after 1 s, 2 s, 4 s… up to 30 s), while the page is visible: every 5 s while it
 *   waits on a runner or the agent, or for 30 s after the reader acted (what the action starts on
 *   the server, such as the turn a connected account wakes, comes a moment after its answer),
 *   every 30 s otherwise, and once at once when a hidden tab shows again. Polling stops once the
 *   conversation has ended, and while the stream is live.
 * - **An older answer never replaces a newer one**: a read that started before a mutation was
 *   answered is dropped.
 *
 * The message and the retry are idempotent submissions (package R): each carries an
 * `Idempotency-Key` (`core/submission-key.ts`), kept while the same submission is sent again after
 * a failure on the way; the decision cards and the files send their own.
 *
 * Provide it per page; it closes the stream and stops polling when the page is destroyed.
 */
@Injectable()
export class ConversationStore {
  private readonly api = inject(Api);
  private readonly doc = inject(DOCUMENT);
  private readonly timing = inject(CONVERSATION_POLL_TIMING);
  private readonly streamTiming = inject(CONVERSATION_STREAM_TIMING);
  private readonly createSource = inject(EVENT_SOURCE_FACTORY);

  /** The conversation as last read or answered; null before the first read. */
  readonly conversation = signal<Conversation | null>(null);
  /** The first read failed (a 404 is the not-found page). */
  readonly error = signal<unknown>(null);
  /** A later read failed: the page still shows the conversation, and says it may be out of date. */
  readonly refreshError = signal<unknown>(null);
  /** Whether the stream brings the changes, or polling does. */
  readonly streamState = signal<ConversationStreamState>('off');
  readonly notFound = computed(
    () => !this.conversation() && toProblem(this.error()).status === 404,
  );

  private companyId = '';
  private conversationId = '';
  /** Bumped by every answer applied and every open: a read that started before is dropped. */
  private generation = 0;
  private reading: Promise<void> | null = null;
  private readAgain = false;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private destroyed = false;
  /** Until when the conversation is read every `activeMs`, after the reader acted. */
  private briskUntil = 0;

  private source: EventSourceLike | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  /** Openings of the stream that failed in a row, for the backoff. */
  private failures = 0;

  private readonly messageKey = new SubmissionKey();
  private readonly retryKey = new SubmissionKey();

  constructor() {
    const onVisibility = () => {
      if (this.hidden()) this.clearTimer();
      else void this.refresh();
    };
    this.doc.addEventListener('visibilitychange', onVisibility);
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      this.clearTimer();
      this.closeStream();
      this.doc.removeEventListener('visibilitychange', onVisibility);
    });
  }

  /** Reads the conversation (again from nothing when it is another one) and keeps it fresh. */
  open(companyId: string, conversationId: string): Promise<void> {
    if (companyId !== this.companyId || conversationId !== this.conversationId) {
      this.companyId = companyId;
      this.conversationId = conversationId;
      this.generation++;
      this.conversation.set(null);
      this.error.set(null);
      this.refreshError.set(null);
      this.reading = null;
      this.readAgain = false;
      this.closeStream();
      this.failures = 0;
    }
    const read = this.refresh();
    if (!this.source && this.reconnectTimer === null) this.connect();
    return read;
  }

  /**
   * Reads the conversation now. A read already on its way is followed by one more, so that what
   * changed after it started (the run that just ended) is read too.
   */
  refresh(): Promise<void> {
    if (!this.conversationId) return Promise.resolve();
    if (this.reading) {
      this.readAgain = true;
      return this.reading;
    }
    this.clearTimer();
    const reading = this.read().finally(() => {
      if (this.reading !== reading) return;
      this.reading = null;
      if (this.readAgain) {
        this.readAgain = false;
        void this.refresh();
      } else {
        this.schedule();
      }
    });
    this.reading = reading;
    return reading;
  }

  /**
   * The reader acted elsewhere on the page (a card decided or settled): the conversation is read now,
   * and briskly for a while, as after a mutation here.
   */
  acted(): Promise<void> {
    this.briskUntil = Date.now() + this.timing.afterActionMs;
    return this.refresh();
  }

  /** A mutation's answer: the conversation as it is now. */
  replace(conversation: Conversation): void {
    if (conversation.id !== this.conversationId) return;
    this.briskUntil = Date.now() + this.timing.afterActionMs;
    this.generation++;
    this.conversation.set(conversation);
    this.error.set(null);
    this.refreshError.set(null);
    this.schedule();
  }

  /**
   * A decision card's answer: the decision as it is now; the rest is read again, since the last
   * open decision decided starts the next turn.
   */
  replaceDecision(decision: Decision): void {
    this.briskUntil = Date.now() + this.timing.afterActionMs;
    const current = this.conversation();
    if (current && decision.conversationId === current.id) {
      this.generation++;
      this.conversation.set({
        ...current,
        decisions: current.decisions.map((d) => (d.id === decision.id ? decision : d)),
      });
    }
    void this.refresh();
  }

  /** A binding card's answer: the binding as it is now; the rest is read again. */
  replaceBinding(binding: ConversationBinding): void {
    this.briskUntil = Date.now() + this.timing.afterActionMs;
    const current = this.conversation();
    if (current) {
      this.generation++;
      this.conversation.set({
        ...current,
        bindings: current.bindings.map((b) => (b.systemId === binding.systemId ? binding : b)),
      });
    }
    void this.refresh();
  }

  // --- Mutations: each answers the conversation, which replaces it ----------------------------

  async rename(title: string): Promise<Conversation> {
    return this.apply(
      await this.api.invoke(renameConversation, { ...this.where(), body: { title } }),
    );
  }

  /** Says something: the next turn, or a message that waits for it (`postConversationMessage`). */
  async post(text: string, answersOpenQuestions = false): Promise<Conversation> {
    const where = this.where();
    const body = { body: text, ...(answersOpenQuestions ? { answersOpenQuestions: true } : {}) };
    const answer = await submitWithKey(this.messageKey, { ...where, body }, (key) =>
      this.api.invoke(postConversationMessage, { ...where, 'Idempotency-Key': key, body }),
    );
    return this.apply(answer);
  }

  async cancel(): Promise<Conversation> {
    return this.apply(await this.api.invoke(cancelConversation, this.where()));
  }

  /**
   * Runs the last turn's request again. The key belongs to the turn retried: sent again after a
   * network error it reuses the key, and a later failure of the retried turn is another retry.
   */
  async retry(): Promise<Conversation> {
    const where = this.where();
    const turn = this.conversation()?.turns.at(-1)?.id ?? null;
    const answer = await submitWithKey(this.retryKey, { ...where, turn }, (key) =>
      this.api.invoke(retryConversation, { ...where, 'Idempotency-Key': key }),
    );
    return this.apply(answer);
  }

  async end(): Promise<Conversation> {
    return this.apply(await this.api.invoke(endConversation, this.where()));
  }

  // --- The stream -----------------------------------------------------------------------------

  /** Opens the conversation's stream; without `EventSource` the store polls instead. */
  private connect(): void {
    if (this.destroyed || !this.conversationId || this.conversation()?.state === 'ENDED') return;
    const url = `/api/companies/${encodeURIComponent(this.companyId)}/conversations/${encodeURIComponent(this.conversationId)}/stream`;
    const source = this.createSource(url);
    if (!source) {
      this.streamState.set('off');
      return;
    }
    this.source = source;
    this.streamState.set('connecting');
    source.onopen = () => {
      if (this.source !== source) return;
      this.failures = 0;
      this.streamState.set('live');
      this.clearTimer();
      // What changed while it was down, or before it opened.
      void this.refresh();
    };
    source.onerror = () => {
      if (this.source !== source) return;
      this.streamState.set('down');
      if (source.readyState === CLOSED) {
        // The browser gave up (an HTTP error, or the server closed it at its lifetime): open it
        // again after a while, polling meanwhile.
        this.closeStream();
        this.streamState.set('down');
        const delay = Math.min(
          this.streamTiming.retryMs * 2 ** this.failures++,
          this.streamTiming.maxRetryMs,
        );
        this.reconnectTimer = setTimeout(() => {
          this.reconnectTimer = null;
          this.connect();
        }, delay);
      }
      // Otherwise the browser reconnects on its own; polling covers the gap.
      this.schedule();
    };
    source.addEventListener('conversation-changed', () => {
      if (this.source === source) void this.refresh();
    });
    source.addEventListener('conversation-status', (event) => {
      if (this.source !== source) return;
      const status = parseStatus(event.data);
      if (!status) return;
      if (status.state === 'ENDED') this.closeStream();
      if (differs(status, this.conversation())) void this.refresh();
    });
  }

  private closeStream(): void {
    this.source?.close();
    this.source = null;
    if (this.reconnectTimer !== null) clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
    this.streamState.set('off');
  }

  // --- Reading and polling ----------------------------------------------------------------------

  private where() {
    return { companyId: this.companyId, conversationId: this.conversationId };
  }

  private apply(conversation: Conversation): Conversation {
    this.replace(conversation);
    return conversation;
  }

  private async read(): Promise<void> {
    const generation = this.generation;
    const where = this.where();
    try {
      const conversation = await this.api.invoke(getConversation, where);
      if (generation !== this.generation || this.destroyed) return;
      this.generation++;
      this.conversation.set(conversation);
      this.error.set(null);
      this.refreshError.set(null);
    } catch (error) {
      if (generation !== this.generation || this.destroyed) return;
      if (!this.conversation() || toProblem(error).status === 404) {
        this.conversation.set(null);
        this.error.set(error);
      } else {
        this.refreshError.set(error);
      }
    }
  }

  /** The next read, by what the conversation waits on; none once it ended or when hidden. */
  private schedule(): void {
    this.clearTimer();
    if (this.destroyed || this.hidden() || this.reading) return;
    if (this.streamState() === 'live') return;
    const c = this.conversation();
    if (c?.state === 'ENDED') {
      this.closeStream();
      return;
    }
    if (!c && toProblem(this.error()).status === 404) return;
    const waits =
      c?.waitingOn === 'RUNNER' || c?.waitingOn === 'AGENT' || Date.now() < this.briskUntil;
    this.timer = setTimeout(
      () => {
        this.timer = null;
        void this.refresh();
      },
      waits ? this.timing.activeMs : this.timing.idleMs,
    );
  }

  private clearTimer(): void {
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }

  private hidden(): boolean {
    return this.doc.visibilityState === 'hidden';
  }
}

function parseStatus(data: string): ConversationStatus | null {
  try {
    return JSON.parse(data) as ConversationStatus;
  } catch {
    return null;
  }
}

/** Whether a stream's status says something the conversation shown does not. */
function differs(status: ConversationStatus, shown: Conversation | null): boolean {
  if (!shown) return true;
  return (
    status.state !== shown.state ||
    status.waitingOn !== shown.waitingOn ||
    (status.waitingReason ?? null) !== (shown.waitingReason ?? null) ||
    (status.turnStatus ?? null) !== (shown.turnStatus ?? null) ||
    (status.activeRunId ?? null) !== (shown.activeRunId ?? null) ||
    status.turnCount !== shown.turnCount
  );
}
