import type {
  Conversation,
  ConversationCost,
  ConversationMessage,
  ConversationTokens,
  Decision,
  Output,
  Run,
} from '../../../api/generated/models';
import {
  changeRequestLabel,
  commitCount,
  formatCost,
  formatTokens,
  isRunActive,
  runDuration,
  shortSha,
  stoppedAtTimeLimit,
  turnReasonPhrase,
  usageTitle,
} from '../../../shared/run-status';
import { waitingBadge } from '../../../shared/waiting-on';

/**
 * The words and the order of the conversation page (plan §4.1), from what the server answers: the
 * state the page is in (its table of states), the state chip of the header, the timeline of
 * messages and turns, and each finished turn's status line. Nothing here decides a right; it puts
 * `waitingOn`, `turnStatus`, `state` and the turns into words.
 */

// --- The state of the page ------------------------------------------------------------------------

/**
 * The rows of plan §4.1's states: running (`waitingOn AGENT`), waiting on a runner (`RUNNER`, the
 * queued run's reason), waiting on the person (`PERSON`: a question, an approval, an account), the
 * last turn failed (with Retry), cancelled (with Retry), the agent paused (its turns wait), ended
 * (read only), and idle (nothing waits: the person writes next).
 */
export type ConversationPhase =
  'running' | 'queued' | 'person' | 'failed' | 'cancelled' | 'paused' | 'ended' | 'idle';

export function conversationPhase(
  c: Pick<Conversation, 'state' | 'waitingOn' | 'waitingReason' | 'turnStatus'>,
  agentPaused = false,
): ConversationPhase {
  if (c.state === 'ENDED') return 'ended';
  switch (c.waitingOn) {
    case 'AGENT':
      return 'running';
    case 'RUNNER':
      return c.waitingReason === 'AGENT_PAUSED' ? 'paused' : 'queued';
    case 'PERSON':
      return c.waitingReason === 'FAILED' ? 'failed' : 'person';
    default:
      if (c.turnStatus === 'FAILED') return 'failed';
      if (c.turnStatus === 'CANCELLED') return 'cancelled';
      return agentPaused ? 'paused' : 'idle';
  }
}

/** A turn is queued or running: a message waits for the next turn, and Cancel applies. */
export function turnActive(c: Pick<Conversation, 'state' | 'activeRunId' | 'turnStatus'>): boolean {
  return (
    c.state === 'OPEN' &&
    (!!c.activeRunId || c.turnStatus === 'QUEUED' || c.turnStatus === 'RUNNING')
  );
}

/** The last turn failed or was cancelled, and nothing runs: Retry applies (`retryConversation`). */
export function retryable(c: Pick<Conversation, 'state' | 'activeRunId' | 'turnStatus'>): boolean {
  return (
    c.state === 'OPEN' &&
    !c.activeRunId &&
    (c.turnStatus === 'FAILED' || c.turnStatus === 'CANCELLED')
  );
}

export type ChipTone = 'human' | 'agent' | 'destructive' | 'muted';

export interface StateChip {
  readonly label: string;
  readonly tone: ChipTone;
  /** A pulsing dot: the agent works now. */
  readonly live: boolean;
}

/**
 * The header's state chip, from `waitingOn` (the shared waiting-on words): orange only when the
 * reader is the person who must act (DESIGN.md, the scarce orange), green while the agent works,
 * destructive for a failed turn, neutral for the rest.
 */
export function stateChip(c: Conversation, mine: boolean, agentPaused = false): StateChip {
  if (c.state === 'ENDED') return { label: 'Ended', tone: 'muted', live: false };
  if (c.waitingOn === 'PERSON' && c.waitingGroupName) {
    // A group holds the decision (a trigger's team's approval or review): "Waits for Analysts ·
    // Review", orange when the reader is one who decides it.
    const badge = waitingBadge(c, false, c.waitingGroupName);
    const decides = c.decisions.some((d) => d.status === 'OPEN' && d.mayDecide);
    if (badge) return { label: badge.label, tone: decides ? 'human' : 'muted', live: false };
  }
  const badge = waitingBadge(c, mine, c.personName);
  if (badge) return { label: badge.label, tone: badge.tone, live: c.waitingOn === 'AGENT' };
  if (c.turnStatus === 'FAILED') {
    return stoppedAtTimeLimit(c.failureClass)
      ? { label: 'Stopped · Time limit', tone: 'muted', live: false }
      : { label: 'Turn failed', tone: 'destructive', live: false };
  }
  if (c.turnStatus === 'CANCELLED') return { label: 'Cancelled', tone: 'muted', live: false };
  if (agentPaused) return { label: 'Agent paused', tone: 'muted', live: false };
  return { label: c.turnCount > 0 ? 'Idle' : 'New', tone: 'muted', live: false };
}

// --- The timeline -------------------------------------------------------------------------------

/** One row of the thread, in order. */
export type TimelineEntry =
  /** A person's message, an EXTERNAL comment, Tinfirm's note, or the agent speaking outside a turn. */
  | { readonly kind: 'message'; readonly id: string; readonly message: ConversationMessage }
  /**
   * A finished turn: what the agent said in it, the decisions it raised, its status line, and the
   * person's messages it took while it worked (`steering` DELIVERED).
   */
  | {
      readonly kind: 'turn';
      readonly id: string;
      readonly run: Run;
      readonly messages: readonly ConversationMessage[];
      readonly decisions: readonly Decision[];
      readonly steered: readonly ConversationMessage[];
    }
  /** A decision raised outside a finished turn (by the turn running now, or without a turn). */
  | { readonly kind: 'decision'; readonly id: string; readonly decision: Decision };

export interface Timeline {
  readonly entries: readonly TimelineEntry[];
  /**
   * The turn running now (`activeRunId`), drawn live after the entries, with the person's messages
   * it took while it works (`steering` DELIVERED); null when none.
   */
  readonly live: {
    readonly runId: string;
    readonly run: Run | null;
    readonly steered: readonly ConversationMessage[];
  } | null;
  /**
   * The person's messages that wait for the next turn, drawn after the live turn: also those on
   * their way to the running turn (`steering` SENDING), which the next turn takes if it does not.
   */
  readonly waiting: readonly ConversationMessage[];
}

function time(value: string | null | undefined): number {
  const ms = value ? Date.parse(value) : Number.NaN;
  return Number.isNaN(ms) ? 0 : ms;
}

/** At one instant, a person's message comes before the turn it started, and that before its cards. */
const RANK: Record<TimelineEntry['kind'], number> = { message: 0, turn: 1, decision: 2 };

/**
 * The thread in order (plan §4.1, `MessageList`): people's messages and Tinfirm's notes by time;
 * each finished turn at the time it was queued, with what the agent said in it and the decisions
 * it raised; the turn running now apart, drawn live; and the messages that wait for the next turn
 * after it. A message a turn took while it worked (steering) goes with that turn. A turn that ended
 * without saying anything (failed, cancelled) still has its entry, so that it speaks from its run.
 */
export function timeline(c: Conversation): Timeline {
  const runs = new Map(c.turns.map((r) => [r.id, r]));
  const activeId = c.state === 'OPEN' ? (c.activeRunId ?? null) : null;
  const finished = (runId: string | null | undefined): Run | null => {
    const run = runId ? runs.get(runId) : undefined;
    return run && run.id !== activeId ? run : null;
  };

  const spoken = new Map<string, ConversationMessage[]>();
  const took = new Map<string, ConversationMessage[]>();
  const steeredLive: ConversationMessage[] = [];
  const raised = new Map<string, Decision[]>();
  const rows: { entry: TimelineEntry; at: number; order: number }[] = [];
  const waiting: ConversationMessage[] = [];
  let order = 0;

  for (const m of c.messages) {
    const steered = m.kind === 'PERSON' && m.steering === 'DELIVERED' && !!m.runId;
    if (m.kind === 'AGENT' && finished(m.runId)) {
      spoken.set(m.runId!, [...(spoken.get(m.runId!) ?? []), m]);
    } else if (steered && finished(m.runId)) {
      took.set(m.runId!, [...(took.get(m.runId!) ?? []), m]);
    } else if (steered && m.runId === activeId) {
      steeredLive.push(m);
    } else if (m.kind === 'PERSON' && m.waiting && c.state === 'OPEN') {
      waiting.push(m);
    } else {
      rows.push({
        entry: { kind: 'message', id: `message-${m.id}`, message: m },
        at: time(m.createdAt),
        order: order++,
      });
    }
  }
  for (const d of c.decisions) {
    if (finished(d.runId)) {
      raised.set(d.runId!, [...(raised.get(d.runId!) ?? []), d]);
    } else {
      rows.push({
        entry: { kind: 'decision', id: `decision-${d.id}`, decision: d },
        at: time(d.createdAt),
        order: order++,
      });
    }
  }
  for (const run of c.turns) {
    if (run.id === activeId) continue;
    rows.push({
      entry: {
        kind: 'turn',
        id: `turn-${run.id}`,
        run,
        messages: spoken.get(run.id) ?? [],
        decisions: raised.get(run.id) ?? [],
        steered: took.get(run.id) ?? [],
      },
      at: time(run.createdAt),
      order: order++,
    });
  }
  rows.sort((a, b) => a.at - b.at || RANK[a.entry.kind] - RANK[b.entry.kind] || a.order - b.order);
  return {
    entries: rows.map((r) => r.entry),
    live: activeId
      ? { runId: activeId, run: runs.get(activeId) ?? null, steered: steeredLive }
      : null,
    waiting,
  };
}

/** The open questions a message can answer (`answersOpenQuestions`). */
export function openQuestions(c: Pick<Conversation, 'decisions'>): number {
  return c.decisions.filter((d) => d.kind === 'QUESTION' && d.status === 'OPEN').length;
}

// --- A turn's status line -------------------------------------------------------------------------

export type LineTone = 'success' | 'human' | 'destructive' | 'warning' | 'muted';

/**
 * What the line under a finished turn says (DESIGN.md, The Thread: the status line): the outcome,
 * the duration, the push and its change requests, the commit and the cost.
 */
export interface TurnLine {
  readonly label: string;
  readonly tone: LineTone;
  readonly icon: string | null;
  readonly failureClass: string | null;
  readonly duration: string | null;
  /** "Pushed 2 commits to" `agent/SHOP-241-1`, or "No changes". */
  readonly push: { readonly text: string; readonly branch: string | null } | null;
  readonly changeRequests: readonly { readonly url: string; readonly label: string }[];
  /**
   * "4e40758 · $0.420": the pushed commit of a one-repository turn, and the cost: its settled
   * spend, else the runtime's estimate, marked "~"; "subscription" for a subscription's turn.
   */
  readonly shaAndCost: string | null;
  readonly usageTitle: string | null;
}

/** "acme/docs#12": a change request the turn opened, by its repository and number. */
function changeRequestText(output: Output): string {
  if (output.repositoryName && output.externalNumber) {
    return `${output.repositoryName}#${output.externalNumber}`;
  }
  return output.url ? changeRequestLabel(output.url) : output.title;
}

/** Where a settled turn's cost comes from, for the cost's title. */
const SETTLED_FROM: Record<string, string> = {
  GATEWAY: 'LiteLLM’s records',
  RELAY: 'Tinfirm’s own count',
};

/**
 * A turn's cost and its title: its settled spend (`Conversation.cost`) when it has one, else the
 * runtime's estimate, marked "~" as an estimate (PRODUCT.md, honest numbers); a subscription's turn
 * no amount, since it costs nothing; the tokens either way.
 */
function turnCost(
  run: Run,
  cost: ConversationCost | null | undefined,
): { text: string | null; title: string | null } {
  const usage = run.usage;
  const tokens = usage ? usageTitle(usage) : null;
  if (run.modelAccess === 'SUBSCRIPTION') {
    return {
      text: 'subscription',
      title: [tokens, 'On a subscription, at no cost'].filter(Boolean).join(' · '),
    };
  }
  const settled = cost?.turns.find((t) => t.runId === run.id);
  const exact = formatCost(settled?.costUsd);
  if (settled && exact) {
    const from = SETTLED_FROM[settled.source];
    return {
      text: exact,
      title: [tokens, from ? `Settled from ${from}` : null].filter(Boolean).join(' · '),
    };
  }
  const estimate = formatCost(usage?.costUsd);
  if (!estimate) return { text: null, title: tokens };
  return {
    text: `~${estimate}`,
    title: [tokens, 'Estimated by the runtime'].filter(Boolean).join(' · '),
  };
}

/**
 * The status line of a finished turn; `outputs` are the conversation's, for its change requests,
 * and `cost` its cost, for the turn's settled spend.
 */
export function turnLine(
  run: Run,
  outputs: readonly Output[] = [],
  cost: ConversationCost | null = null,
): TurnLine {
  const pushed = run.repositories.filter((r) => r.pushed);
  const commits = pushed.reduce((n, r) => n + (r.commits ?? 0), 0);
  const push =
    run.repositories.length === 0
      ? null
      : pushed.length === 0
        ? run.status === 'SUCCEEDED'
          ? { text: 'No changes', branch: null }
          : null
        : {
            text: commits > 0 ? `Pushed ${commitCount(commits)} to` : 'Pushed',
            branch: run.branch ?? null,
          };
  const spent = turnCost(run, cost);
  const base = {
    failureClass: null,
    duration: runDuration(run.startedAt, run.finishedAt),
    push,
    changeRequests: outputs
      .filter((o) => o.kind === 'CHANGE_REQUEST' && o.runId === run.id && !!o.url)
      .map((o) => ({ url: o.url!, label: changeRequestText(o) })),
    shaAndCost:
      [pushed.length === 1 ? shortSha(pushed[0].headSha) : '', spent.text ?? '']
        .filter(Boolean)
        .join(' · ') || null,
    usageTitle: spent.title,
  };
  switch (run.status) {
    case 'SUCCEEDED':
      return run.result?.outcome === 'BLOCKED'
        ? { ...base, label: 'Blocked', tone: 'human', icon: 'lucideOctagonPause' }
        : { ...base, label: 'Completed', tone: 'success', icon: 'lucideCircleCheck' };
    case 'FAILED':
      // Stopped at its time limit, its work kept: Retry goes on from where it stopped.
      return stoppedAtTimeLimit(run.failureClass)
        ? {
            ...base,
            label: 'Stopped at its time limit',
            tone: 'warning',
            icon: 'lucideTimerOff',
            failureClass: run.failureClass ?? null,
          }
        : {
            ...base,
            label: 'Failed',
            tone: 'destructive',
            icon: 'lucideCircleX',
            failureClass: run.failureClass ?? null,
          };
    case 'LOST':
      return {
        ...base,
        label: 'Lost: the runner stopped reporting',
        tone: 'warning',
        icon: 'lucideTriangleAlert',
      };
    default:
      return {
        ...base,
        label: run.startedAt ? 'Cancelled' : 'Cancelled before it started',
        tone: 'muted',
        icon: null,
      };
  }
}

/** "Turn 2 · a new message · office": the name line of a turn. */
export function turnMeta(run: Pick<Run, 'turnNumber' | 'turnReason' | 'runnerHostName'>): string {
  return [`Turn ${run.turnNumber}`, turnReasonPhrase(run.turnReason), run.runnerHostName ?? '']
    .filter(Boolean)
    .join(' · ');
}

/** A turn that may still change: the live turn draws it, not the timeline. */
export function liveRun(run: Run | null | undefined): boolean {
  return !!run && isRunActive(run.status);
}

// --- What it cost ---------------------------------------------------------------------------------

/**
 * What a conversation cost so far: the settled spend and tokens of its turns (`Conversation.cost`,
 * from LiteLLM's records or Tinfirm's own count, as the reader sees spend), and the runtime's
 * estimate and reported tokens of its finished turns on a budget whose spend has not settled yet,
 * which settles minutes after a turn ends. A subscription's turns cost nothing; their tokens count
 * in the tokens, and are told apart too.
 */
export interface CostSummary {
  /** Settled and estimated together, in USD. */
  readonly total: number;
  readonly settled: number;
  /** The runtime's estimate of the pending turns' cost; 0 when it gave none. */
  readonly estimated: number;
  /** The finished turns on a budget not settled yet, counted by what the runtime reported. */
  readonly pendingTurns: number;
  readonly settledTurns: number;
  /** The settled turns' tokens and those the runtime reported for the pending ones. */
  readonly tokens: ConversationTokens;
  readonly subscriptionTokens: number;
}

/** What the conversation cost so far; null when the server sends no cost. */
export function conversationCost(
  c: Pick<Conversation, 'cost' | 'turns' | 'state' | 'activeRunId'>,
): CostSummary | null {
  const cost = c.cost;
  if (!cost) return null;
  const settled = new Set(cost.turns.map((t) => t.runId));
  const activeId = c.state === 'OPEN' ? (c.activeRunId ?? null) : null;
  let estimated = 0;
  let pendingTurns = 0;
  const tokens = { ...cost.tokens };
  for (const run of c.turns) {
    if (settled.has(run.id) || run.id === activeId || isRunActive(run.status)) continue;
    if (run.modelAccess !== 'BUDGET') continue;
    const used: ConversationTokens = {
      inputTokens: run.usage?.inputTokens ?? 0,
      cacheWriteTokens: run.usage?.cacheWriteTokens ?? 0,
      cachedInputTokens: run.usage?.cachedInputTokens ?? 0,
      outputTokens: run.usage?.outputTokens ?? 0,
    };
    const estimate = run.usage?.costUsd;
    const priced = typeof estimate === 'number' && Number.isFinite(estimate) && estimate > 0;
    // A turn whose requests the relay passed settles, whatever its runtime reported (tokens
    // without a cost, or nothing); one that relayed none never does. Without the relay's count,
    // what the runtime reported tells.
    const relayed = run.relay?.requests;
    if (relayed === 0 || (relayed == null && !priced && tokenTotal(used) === 0)) continue;
    pendingTurns++;
    if (priced) estimated += estimate;
    tokens.inputTokens += used.inputTokens;
    tokens.cacheWriteTokens += used.cacheWriteTokens;
    tokens.cachedInputTokens += used.cachedInputTokens;
    tokens.outputTokens += used.outputTokens;
  }
  return {
    total: cost.costUsd + estimated,
    settled: cost.costUsd,
    estimated,
    pendingTurns,
    settledTurns: cost.turns.length,
    tokens,
    subscriptionTokens: cost.subscriptionTokens,
  };
}

/** All four token counts together. */
export function tokenTotal(t: ConversationTokens): number {
  return t.inputTokens + t.cacheWriteTokens + t.cachedInputTokens + t.outputTokens;
}

/** "In 1,150 · Cache write 210 · Cache read 5,400 · Out 325". */
export function tokenBreakdown(t: ConversationTokens): string {
  return [
    `In ${formatTokens(t.inputTokens)}`,
    `Cache write ${formatTokens(t.cacheWriteTokens)}`,
    `Cache read ${formatTokens(t.cachedInputTokens)}`,
    `Out ${formatTokens(t.outputTokens)}`,
  ].join(' · ');
}
