import type { QueueReason, Run, RunEvent } from '../../api/generated/models';
import { AT, IDS } from './ids';

/** Tag `Runs`: a conversation's turns and their events (the runner protocol's payloads). */

/** Alice's finished first turn: 6m 12s of work on office, paid by her budget. */
export function aRun(overrides: Partial<Run> = {}): Run {
  return {
    id: IDS.run,
    conversationId: IDS.conversation,
    conversationKey: 'SHOP-241-1',
    agentId: IDS.agent,
    personPrincipalId: IDS.alice,
    personName: 'Alice Novak',
    payerPrincipalId: IDS.alice,
    payerName: 'Alice Novak',
    modelAccess: 'BUDGET',
    subscriptionLabel: null,
    runtime: 'CLAUDE_CODE',
    runnerHostId: IDS.companyHost,
    runnerHostName: 'office',
    runnerHostKind: 'COMPANY',
    status: 'SUCCEEDED',
    failureClass: null,
    failureMessage: null,
    turnReason: 'FIRST',
    turnNumber: 1,
    queueReason: null,
    createdAt: AT.start,
    startedAt: AT.started,
    finishedAt: AT.finished,
    summary: 'The brief is ready: the guest flow skips the address check.',
    result: {
      outcome: 'COMPLETED',
      summary: 'The brief is ready: the guest flow skips the address check.',
      nextSteps: [],
      openQuestions: [],
    },
    branch: 'agent/SHOP-241-1',
    repositories: [],
    usage: { inputTokens: 1200, outputTokens: 340, cachedInputTokens: null, costUsd: 0.42 },
    counts: null,
    lastEventSeq: 0,
    ...overrides,
  };
}

/** Why a queued run waits; `text` is the server's sentence. */
export function aQueueReason(overrides: Partial<QueueReason> = {}): QueueReason {
  return {
    code: 'RUNNER_HOST_FULL',
    text: 'Waiting for a free slot on office (2 of 2 in use).',
    since: AT.start,
    runnerHostId: IDS.companyHost,
    runnerHostName: 'office',
    used: 2,
    limit: 2,
    ...overrides,
  };
}

/** A run that waits in the queue, with its reason. */
export function aQueuedRun(reason: Partial<QueueReason> = {}, overrides: Partial<Run> = {}): Run {
  return aRun({
    status: 'QUEUED',
    startedAt: null,
    finishedAt: null,
    result: null,
    summary: null,
    usage: null,
    runnerHostId: null,
    runnerHostName: null,
    runnerHostKind: null,
    queueReason: aQueueReason(reason),
    ...overrides,
  });
}

/** One event of a run, `seq` seconds after the run started. */
export function aRunEvent(
  seq: number,
  type: RunEvent['type'],
  payload: RunEvent['payload'] = {},
): RunEvent {
  const at = new Date(Date.parse(AT.started) + seq * 1000).toISOString();
  return { seq, type, at, payload };
}

/** Events numbered 1, 2, 3… in the order given. */
export function runEvents(
  ...events: readonly (readonly [RunEvent['type'], RunEvent['payload']])[]
): RunEvent[] {
  return events.map(([type, payload], i) => aRunEvent(i + 1, type, payload));
}

/** A tool call that is done: its TOOL_CALL with the final status. */
export function toolCall(
  toolCallId: string,
  title: string,
  kind = 'execute',
  status = 'completed',
): readonly [RunEvent['type'], RunEvent['payload']] {
  return ['TOOL_CALL', { toolCallId, title, kind, status }];
}
