import type { RunEvent, RunEventType, RunFailureClass } from '../../../api/generated/models';
import { plural } from '../../../shared/labels';
import {
  has,
  type LogLevel,
  num,
  type PlanEntry,
  type RateLimitPayload,
  readDiffs,
  readLocations,
  readPlanEntries,
  type RunPhase,
  str,
  type ToolCallDiff,
  type ToolCallKind,
  type ToolCallLocation,
  type ToolCallStatus,
  type UsagePayload,
} from '../../../api/run-events';

/*
 * Turns the flat run-event log into the rows of a transcript, for the run page and for the work
 * of a turn in a thread alike (one model, one renderer: `transcript-items`):
 * - consecutive AGENT_MESSAGE (and AGENT_THOUGHT) chunks become one message (thought);
 * - TOOL_CALL and its TOOL_CALL_UPDATEs become one tool row, keyed by toolCallId;
 * - consecutive LOG events become one log block;
 * - USAGE and RATE_LIMIT feed the summary (meter, banner) instead of adding rows.
 * Then `groupActivity` folds every stretch of tool calls and log blocks into one group, and
 * `workEntries` picks what a thread shows of a turn's work.
 *
 * The builder is incremental: a live run can have 20 000 events, so a batch of new events costs
 * O(batch + rows), not O(all events). Rows are immutable from the outside; a row that changes in
 * a batch is replaced by a new object, so OnPush views see the change.
 */

interface ItemBase {
  /** Stable key for `@for` tracking. */
  readonly id: string;
  /** Sequence number of the first event in the row. */
  readonly seq: number;
  readonly at: string;
}

export interface MessageItem extends ItemBase {
  readonly kind: 'message';
  text: string;
}

export interface ThoughtItem extends ItemBase {
  readonly kind: 'thought';
  text: string;
}

export interface ToolItem extends ItemBase {
  readonly kind: 'tool';
  readonly toolCallId: string;
  title: string;
  toolKind: ToolCallKind;
  status: ToolCallStatus;
  input: Record<string, unknown> | null;
  locations: readonly ToolCallLocation[];
  output: string | null;
  diffs: readonly ToolCallDiff[];
}

export interface PlanItem extends ItemBase {
  readonly kind: 'plan';
  entries: readonly PlanEntry[];
}

export interface StatusItem extends ItemBase {
  readonly kind: 'status';
  readonly phase: RunPhase;
  readonly message: string | null;
}

export interface GitItem extends ItemBase {
  readonly kind: 'git';
  /** The repository's directory under `/workspace`; null on events of single-repository runs. */
  readonly dir: string | null;
  readonly action: string;
  readonly branch: string;
  readonly headSha: string | null;
  readonly commits: number | null;
}

export interface LogLine {
  readonly seq: number;
  readonly at: string;
  readonly level: LogLevel;
  readonly message: string;
}

export interface LogsItem extends ItemBase {
  readonly kind: 'logs';
  lines: LogLine[];
}

export interface ErrorItem extends ItemBase {
  readonly kind: 'error';
  readonly message: string;
  readonly failureClass: RunFailureClass | null;
}

export interface RateLimitItem extends ItemBase {
  readonly kind: 'rate-limit';
  readonly limit: RateLimitPayload;
}

export type TranscriptItem =
  | MessageItem
  | ThoughtItem
  | ToolItem
  | PlanItem
  | StatusItem
  | GitItem
  | LogsItem
  | ErrorItem
  | RateLimitItem;

export interface TranscriptSummary {
  /** The latest USAGE payload. */
  readonly usage: UsagePayload | null;
  /** The latest RATE_LIMIT payload. */
  readonly rateLimit: RateLimitPayload | null;
  /** The latest STATUS phase, and every phase seen so far in order. */
  readonly phase: RunPhase | null;
  readonly phases: readonly RunPhase[];
  /** The latest plan. */
  readonly plan: readonly PlanEntry[] | null;
  readonly eventCount: number;
  readonly lastSeq: number;
}

export const EMPTY_SUMMARY: TranscriptSummary = {
  usage: null,
  rateLimit: null,
  phase: null,
  phases: [],
  plan: null,
  eventCount: 0,
  lastSeq: 0,
};

const TOOL_KINDS: readonly ToolCallKind[] = [
  'read',
  'edit',
  'delete',
  'move',
  'search',
  'execute',
  'think',
  'fetch',
  'switch_mode',
  'other',
];
const TOOL_STATUSES: readonly ToolCallStatus[] = ['pending', 'in_progress', 'completed', 'failed'];
const PHASES: readonly RunPhase[] = [
  'preparing',
  'cloning',
  'starting',
  'running',
  'finalizing',
  'pushing',
  'cancelling',
];
const LEVELS: readonly LogLevel[] = ['debug', 'info', 'warn', 'error'];

function oneOf<T extends string>(values: readonly T[], value: unknown, fallback: T): T {
  return values.includes(value as T) ? (value as T) : fallback;
}

function objectOrNull(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

export class TranscriptBuilder {
  private items: TranscriptItem[] = [];
  private readonly toolIndex = new Map<string, number>();
  /** Rows created or copied during the current batch; only these may be mutated in place. */
  private readonly fresh = new Set<TranscriptItem>();
  private summaryState: TranscriptSummary = EMPTY_SUMMARY;

  get lastSeq(): number {
    return this.summaryState.lastSeq;
  }

  /** Applies a batch of events in sequence order; events at or before `lastSeq` are ignored. */
  apply(events: readonly RunEvent[]): void {
    let summary = this.summaryState;
    for (const event of events) {
      if (typeof event?.seq !== 'number' || event.seq <= summary.lastSeq) continue;
      summary = this.applyOne(event, {
        ...summary,
        eventCount: summary.eventCount + 1,
        lastSeq: event.seq,
      });
    }
    this.summaryState = summary;
    this.fresh.clear();
  }

  /** The rows, as a new array (for a signal). */
  snapshot(): readonly TranscriptItem[] {
    return this.items.slice();
  }

  summary(): TranscriptSummary {
    return this.summaryState;
  }

  private applyOne(event: RunEvent, summary: TranscriptSummary): TranscriptSummary {
    const p = event.payload ?? {};
    const base = { seq: event.seq, at: event.at };
    switch (event.type) {
      case 'AGENT_MESSAGE':
      case 'AGENT_THOUGHT': {
        const text = str(p, 'text') ?? '';
        const kind = event.type === 'AGENT_MESSAGE' ? 'message' : 'thought';
        const last = this.items[this.items.length - 1];
        if (last?.kind === kind) {
          const row = this.editable(this.items.length - 1) as MessageItem | ThoughtItem;
          row.text += text;
        } else {
          this.push({ kind, id: `${kind}-${event.seq}`, ...base, text });
        }
        return summary;
      }
      case 'TOOL_CALL':
      case 'TOOL_CALL_UPDATE': {
        const toolCallId = str(p, 'toolCallId') ?? `unknown-${event.seq}`;
        const index = this.toolIndex.get(toolCallId);
        const row =
          index === undefined
            ? this.push({
                kind: 'tool',
                id: `tool-${toolCallId}`,
                ...base,
                toolCallId,
                title: 'Tool call',
                toolKind: 'other',
                status: 'pending',
                input: null,
                locations: [],
                output: null,
                diffs: [],
              } satisfies ToolItem)
            : (this.editable(index) as ToolItem);
        if (index === undefined) this.toolIndex.set(toolCallId, this.items.length - 1);
        const title = str(p, 'title');
        if (title) row.title = title;
        if (has(p, 'status')) row.status = oneOf(TOOL_STATUSES, p['status'], row.status);
        if (event.type === 'TOOL_CALL') {
          row.toolKind = oneOf(TOOL_KINDS, p['kind'], 'other');
          if (has(p, 'input')) row.input = objectOrNull(p['input']);
          if (has(p, 'locations')) row.locations = readLocations(p);
        } else {
          if (has(p, 'output')) row.output = str(p, 'output') ?? null;
          if (has(p, 'diffs')) row.diffs = readDiffs(p);
        }
        return summary;
      }
      case 'PLAN': {
        const entries = readPlanEntries(p);
        const last = this.items[this.items.length - 1];
        if (last?.kind === 'plan') {
          (this.editable(this.items.length - 1) as PlanItem).entries = entries;
        } else {
          this.push({ kind: 'plan', id: `plan-${event.seq}`, ...base, entries });
        }
        return { ...summary, plan: entries };
      }
      case 'STATUS': {
        const phase = oneOf(PHASES, p['phase'], 'running');
        this.push({
          kind: 'status',
          id: `status-${event.seq}`,
          ...base,
          phase,
          message: str(p, 'message') ?? null,
        });
        return {
          ...summary,
          phase,
          phases: summary.phases.includes(phase) ? summary.phases : [...summary.phases, phase],
        };
      }
      case 'GIT':
        this.push({
          kind: 'git',
          id: `git-${event.seq}`,
          ...base,
          dir: str(p, 'dir') || null,
          action: str(p, 'action') ?? 'unknown',
          branch: str(p, 'branch') ?? '',
          headSha: str(p, 'headSha') ?? null,
          commits: num(p, 'commits') ?? null,
        });
        return summary;
      case 'LOG': {
        const line: LogLine = {
          ...base,
          level: oneOf(LEVELS, p['level'], 'info'),
          message: str(p, 'message') ?? '',
        };
        const last = this.items[this.items.length - 1];
        if (last?.kind === 'logs') {
          (this.editable(this.items.length - 1) as LogsItem).lines.push(line);
        } else {
          this.push({ kind: 'logs', id: `logs-${event.seq}`, ...base, lines: [line] });
        }
        return summary;
      }
      case 'ERROR':
        this.push({
          kind: 'error',
          id: `error-${event.seq}`,
          ...base,
          message: str(p, 'message') ?? 'Unknown error',
          failureClass: (str(p, 'failureClass') as RunFailureClass | undefined) ?? null,
        });
        return summary;
      case 'USAGE':
        return { ...summary, usage: { ...(summary.usage ?? {}), ...(p as UsagePayload) } };
      case 'RATE_LIMIT': {
        const limit: RateLimitPayload = {
          status: oneOf(['allowed', 'allowed_warning', 'rejected'], p['status'], 'allowed'),
          utilization: num(p, 'utilization') ?? null,
          resetsAt: str(p, 'resetsAt') ?? null,
          window: str(p, 'window') ?? null,
        };
        if (limit.status !== 'allowed') {
          this.push({ kind: 'rate-limit', id: `rate-limit-${event.seq}`, ...base, limit });
        }
        return { ...summary, rateLimit: limit };
      }
      default:
        return summary;
    }
  }

  private push<T extends TranscriptItem>(item: T): T {
    this.items.push(item);
    this.fresh.add(item);
    return item;
  }

  /** The row at `index`, copied once per batch so earlier snapshots stay untouched. */
  private editable(index: number): TranscriptItem {
    const item = this.items[index];
    if (this.fresh.has(item)) return item;
    const copy: TranscriptItem =
      item.kind === 'logs' ? { ...item, lines: [...item.lines] } : { ...item };
    this.items[index] = copy;
    this.fresh.add(copy);
    return copy;
  }
}

/** A row that the thread folds into an activity group: the work between two things said. */
export type ActivityItem = ToolItem | LogsItem;

/** Consecutive tool calls and log blocks, shown as one collapsible row. */
export interface ActivityGroup {
  readonly kind: 'activity';
  /** The first row's id, so the group keeps its place (and open state) as it grows. */
  readonly id: string;
  readonly seq: number;
  readonly at: string;
  readonly items: readonly ActivityItem[];
}

export type TranscriptRowItem = TranscriptItem | ActivityGroup;

function isActivity(item: TranscriptItem): item is ActivityItem {
  return item.kind === 'tool' || item.kind === 'logs';
}

/**
 * Folds each stretch of consecutive activity rows (tool calls, log blocks) into one group, even a
 * lone call: DESIGN.md's "one muted sentence per stretch". Messages, thoughts and the other rows
 * stay between the groups. `previous` is the last result: a group whose rows did not change is
 * reused, so OnPush views skip it.
 */
export function groupActivity(
  items: readonly TranscriptItem[],
  previous: readonly TranscriptRowItem[] = [],
): TranscriptRowItem[] {
  const before = new Map<string, ActivityGroup>();
  for (const row of previous) if (row.kind === 'activity') before.set(row.id, row);

  const rows: TranscriptRowItem[] = [];
  let run: ActivityItem[] = [];
  const flush = () => {
    if (run.length > 0) {
      const id = `activity-${run[0].id}`;
      const old = before.get(id);
      const same =
        old !== undefined &&
        old.items.length === run.length &&
        old.items.every((item, i) => item === run[i]);
      rows.push(same ? old : { kind: 'activity', id, seq: run[0].seq, at: run[0].at, items: run });
    }
    run = [];
  };
  for (const item of items) {
    if (isActivity(item)) {
      run.push(item);
    } else {
      flush();
      rows.push(item);
    }
  }
  flush();
  return rows;
}

// --- The work of a turn in a thread -----------------------------------------------------------

export interface WorkOptions {
  /** The run is still going: its latest plan shows. */
  readonly live: boolean;
  /** The last message is the turn's answer, shown under the work: leave it out of the work. */
  readonly dropLastMessage: boolean;
  /** The error the turn's failure shows already. */
  readonly failureMessage?: string | null;
}

/**
 * A run's work as a thread shows it (DESIGN.md, The Thread): what the agent said and thought,
 * every stretch of tool calls and log lines as one group, the checkout, and while live its plan.
 * Phases and commit or push results are not in it: the turn's status line carries them.
 * `previous` keeps unchanged groups as they were.
 */
export function workEntries(
  items: readonly TranscriptItem[],
  options: WorkOptions,
  previous: readonly TranscriptRowItem[] = [],
): TranscriptRowItem[] {
  let lastMessage = -1;
  let lastPlan = -1;
  items.forEach((item, i) => {
    if (item.kind === 'message') lastMessage = i;
    if (item.kind === 'plan') lastPlan = i;
  });
  const kept = items.filter((item, i) => {
    switch (item.kind) {
      case 'status':
        return false;
      case 'git':
        return item.action === 'cloned';
      case 'plan':
        return options.live && i === lastPlan;
      case 'message':
        return !(options.dropLastMessage && i === lastMessage);
      case 'error':
        return item.message !== options.failureMessage;
      default:
        return true;
    }
  });
  return groupActivity(kept, previous);
}

/** What a folded turn reads of its run to show what the agent said (`listRunEvents` `types`). */
export const SAID_TYPES = ['AGENT_MESSAGE', 'USAGE'] as const satisfies readonly RunEventType[];

/**
 * What the agent said in a run, from its events of `SAID_TYPES` alone: chunks are one message, as
 * in the transcript, until a gap in the sequence numbers says something else happened between
 * them. USAGE is read along because it lands between chunks and adds no row to a transcript.
 */
export function agentMessages(events: readonly RunEvent[]): MessageItem[] {
  const messages: MessageItem[] = [];
  let open = false;
  let lastSeq = Number.NaN;
  for (const event of events) {
    const next = event.seq === lastSeq + 1;
    lastSeq = event.seq;
    if (event.type !== 'AGENT_MESSAGE') {
      open &&= next;
      continue;
    }
    const text = str(event.payload ?? {}, 'text') ?? '';
    const last = messages.at(-1);
    if (open && next && last) {
      last.text += text;
    } else {
      messages.push({
        kind: 'message',
        id: `message-${event.seq}`,
        seq: event.seq,
        at: event.at,
        text,
      });
    }
    open = true;
  }
  return messages;
}

/** What the folded "Worked for" row counts. */
export interface WorkCounts {
  readonly toolCalls: number;
  readonly messages: number;
  readonly thoughts: number;
}

/** The calls, messages and thoughts of a run; `answered` leaves out the last message. */
export function workCounts(items: readonly TranscriptItem[], answered: boolean): WorkCounts {
  let toolCalls = 0;
  let messages = 0;
  let thoughts = 0;
  for (const item of items) {
    if (item.kind === 'tool') toolCalls++;
    else if (item.kind === 'message') messages++;
    else if (item.kind === 'thought') thoughts++;
  }
  return { toolCalls, messages: answered ? Math.max(0, messages - 1) : messages, thoughts };
}

/**
 * The counts of a finished run as the server counted them when it ended (`Run.counts`); `answered`
 * leaves out the last message, as `workCounts` does.
 */
export function runCounts(counts: WorkCounts, answered: boolean): WorkCounts {
  return {
    toolCalls: counts.toolCalls,
    messages: answered ? Math.max(0, counts.messages - 1) : counts.messages,
    thoughts: counts.thoughts,
  };
}

/** "· 14 tool calls · 2 messages · 1 thought", without the parts that are zero. */
export function countsText(counts: WorkCounts): string {
  return [
    counts.toolCalls > 0 ? plural(counts.toolCalls, 'tool call') : '',
    counts.messages > 0 ? plural(counts.messages, 'message') : '',
    counts.thoughts > 0 ? plural(counts.thoughts, 'thought') : '',
  ]
    .filter(Boolean)
    .map((part) => `· ${part}`)
    .join(' ');
}

/** The last item that matches, like `Array.prototype.findLast`. */
export function findLast<T, S extends T>(
  items: readonly T[],
  test: (item: T) => item is S,
): S | undefined;
export function findLast<T>(items: readonly T[], test: (item: T) => boolean): T | undefined;
export function findLast<T>(items: readonly T[], test: (item: T) => boolean): T | undefined {
  for (let i = items.length - 1; i >= 0; i--) if (test(items[i])) return items[i];
  return undefined;
}
