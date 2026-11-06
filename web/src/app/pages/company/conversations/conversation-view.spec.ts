import {
  aConversation,
  aConversationIn,
  aDecision,
  aMessage,
  anOutput,
  aRun,
  type ConversationStateName,
} from '../../../testing/fixtures';
import { costNotes, costTotal } from './conversation-side';
import {
  conversationCost,
  conversationPhase,
  tokenBreakdown,
  tokenTotal,
  openQuestions,
  retryable,
  stateChip,
  timeline,
  turnActive,
  turnLine,
  turnMeta,
} from './conversation-view';

describe('conversationPhase, stateChip, turnActive and retryable', () => {
  // The rows of plan §4.1's states: the page's phase, the header's chip for its person (orange
  // only for what they must do) and for the Owner reading it, Cancel and Retry.
  it.each<[ConversationStateName, string, string, string, string, boolean, boolean]>([
    ['running', 'running', 'Working', 'agent', 'agent', true, false],
    ['queued', 'queued', 'Waiting · Host busy', 'muted', 'muted', true, false],
    ['budget-spent', 'queued', 'Waiting · Budget spent', 'muted', 'muted', true, false],
    ['question', 'person', 'Needs you · Question', 'human', 'muted', false, false],
    ['account', 'person', 'Needs you · Account', 'human', 'muted', false, false],
    ['failed', 'failed', 'Turn failed', 'destructive', 'destructive', false, true],
    ['usage-limit', 'failed', 'Turn failed', 'destructive', 'destructive', false, true],
    ['cancelled', 'cancelled', 'Cancelled', 'muted', 'muted', false, true],
    ['paused', 'paused', 'Waiting · Agent paused', 'muted', 'muted', true, false],
    ['ended', 'ended', 'Ended', 'muted', 'muted', false, false],
    ['idle', 'idle', 'Idle', 'muted', 'muted', false, false],
  ])('%s: phase %s, chip “%s”', (state, phase, label, tone, ownerTone, active, retry) => {
    const c = aConversationIn(state);
    expect(conversationPhase(c)).toBe(phase);
    expect(stateChip(c, true)).toMatchObject({ label: label, tone });
    expect(stateChip(c, false).tone).toBe(ownerTone);
    expect(turnActive(c)).toBe(active);
    expect(retryable(c)).toBe(retry);
  });

  it('says the Owner waits for the person, not that they are needed', () => {
    expect(stateChip(aConversationIn('question'), false).label).toBe(
      'Waits for Alice Novak · Question',
    );
  });

  it('marks a running turn live', () => {
    expect(stateChip(aConversationIn('running'), true).live).toBe(true);
    expect(stateChip(aConversationIn('queued'), true).live).toBe(false);
  });

  it('reads an idle conversation of a paused agent as paused', () => {
    expect(conversationPhase(aConversation(), true)).toBe('paused');
    expect(stateChip(aConversation(), true, true).label).toBe('Agent paused');
  });
});

describe('timeline', () => {
  it('puts a turn after the message that started it, with what its agent said', () => {
    const view = timeline(aConversation());
    expect(view.entries.map((e) => e.kind)).toEqual(['message', 'turn']);
    const turn = view.entries[1];
    expect(turn.kind === 'turn' && turn.messages.map((m) => m.id)).toEqual(['m2']);
    expect(view.live).toBeNull();
    expect(view.waiting).toEqual([]);
  });

  it('draws the turn running now apart, and the messages that wait for the next one after it', () => {
    const c = aConversationIn('running');
    c.messages.push(aMessage({ id: 'm9', body: 'Also…', waiting: true, runId: null }));
    const view = timeline(c);
    expect(view.live).toEqual({ runId: 'run2', run: c.turns[1], steered: [] });
    expect(view.entries.map((e) => e.id)).toEqual(['message-m1', 'turn-run1', 'message-m3']);
    expect(view.waiting.map((m) => m.id)).toEqual(['m9']);
  });

  it('draws what the running turn took with it, and what goes to it with the waiting messages', () => {
    const c = aConversationIn('running');
    c.messages.push(
      aMessage({
        id: 'm8',
        body: 'Use staging.',
        runId: 'run2',
        steering: 'DELIVERED',
        createdAt: '2026-10-12T09:20:00Z',
      }),
      aMessage({ id: 'm9', body: 'Also…', waiting: true, runId: null, steering: 'SENDING' }),
    );
    const view = timeline(c);
    expect(view.live?.steered.map((m) => m.id)).toEqual(['m8']);
    expect(view.entries.map((e) => e.id)).toEqual(['message-m1', 'turn-run1', 'message-m3']);
    expect(view.waiting.map((m) => m.id)).toEqual(['m9']);
  });

  it('keeps what a finished turn took while it worked with that turn', () => {
    const c = aConversation();
    const runId = c.turns[0].id;
    c.messages.push(
      aMessage({
        id: 'm5',
        body: 'Use staging.',
        runId,
        steering: 'DELIVERED',
        createdAt: '2026-10-12T09:30:00Z',
      }),
    );
    const view = timeline(c);
    expect(view.entries.map((e) => e.kind)).toEqual(['message', 'turn']);
    const turn = view.entries[1];
    expect(turn.kind === 'turn' && turn.steered.map((m) => m.id)).toEqual(['m5']);
    expect(turn.kind === 'turn' && turn.messages.map((m) => m.id)).toEqual(['m2']);
  });

  it('keeps a turn that said nothing, and the decisions a turn raised with it', () => {
    const failed = timeline(aConversationIn('failed'));
    const last = failed.entries.at(-1)!;
    expect(last.kind === 'turn' && last.run.status).toBe('FAILED');
    expect(last.kind === 'turn' && last.messages).toEqual([]);

    const question = timeline(aConversationIn('question'));
    const raised = question.entries.at(-1)!;
    expect(raised.kind === 'turn' && raised.decisions.map((d) => d.id)).toEqual(['d1']);
  });

  it('puts a decision of no finished turn where it was raised', () => {
    const c = aConversation({ decisions: [aDecision({ runId: null })] });
    expect(timeline(c).entries.map((e) => e.kind)).toEqual(['message', 'turn', 'decision']);
  });

  it('shows an ended conversation’s waiting messages in place', () => {
    const c = aConversationIn('ended');
    c.messages.push(
      aMessage({
        id: 'm9',
        waiting: true,
        unanswered: true,
        runId: null,
        createdAt: '2026-10-12T09:30:00Z',
      }),
    );
    const view = timeline(c);
    expect(view.waiting).toEqual([]);
    expect(view.entries.at(-1)?.id).toBe('message-m9');
  });

  it('counts the open questions', () => {
    expect(openQuestions(aConversationIn('question'))).toBe(1);
    expect(openQuestions(aConversation())).toBe(0);
  });
});

describe('turnLine and turnMeta', () => {
  it('says a completed turn, its duration, its estimated cost and its change requests', () => {
    const run = aRun({
      repositories: [
        {
          dir: 'shop',
          repositoryName: 'acme/docs',
          readOnly: false,
          pushed: true,
          commits: 2,
          headSha: '4e40758abcdef',
        },
      ],
    });
    const line = turnLine(run, [anOutput()]);
    expect(line).toMatchObject({
      label: 'Completed',
      tone: 'success',
      duration: '6m 12s',
      push: { text: 'Pushed 2 commits to', branch: 'agent/SHOP-241-1' },
      changeRequests: [{ url: 'https://github.com/acme/docs/pull/12', label: 'acme/docs#12' }],
      shaAndCost: '4e40758 · ~$0.420',
      usageTitle:
        'Input 1,200, cache writes included · output 340 tokens · Estimated by the runtime',
    });
  });

  it('says a settled turn’s cost exactly, and where it comes from', () => {
    const run = aRun();
    const cost = {
      costUsd: 0.38,
      tokens: { inputTokens: 0, cacheWriteTokens: 0, cachedInputTokens: 0, outputTokens: 0 },
      subscriptionTokens: 0,
      turns: [{ runId: run.id, costUsd: 0.38, source: 'GATEWAY' as const }],
    };
    expect(turnLine(run, [], cost)).toMatchObject({
      shaAndCost: '$0.380',
      usageTitle:
        'Input 1,200, cache writes included · output 340 tokens · Settled from the gateway’s records',
    });
    // Another turn's spend leaves this one's estimate as it is.
    const other = { ...cost, turns: [{ ...cost.turns[0], runId: 'another-run' }] };
    expect(turnLine(run, [], other).shaAndCost).toBe('~$0.420');
  });

  it('says a subscription’s turn without an amount, settled or not, since it costs nothing', () => {
    const run = aRun({ modelAccess: 'SUBSCRIPTION' });
    const cost = {
      costUsd: 0,
      tokens: { inputTokens: 0, cacheWriteTokens: 0, cachedInputTokens: 0, outputTokens: 0 },
      subscriptionTokens: 1540,
      turns: [{ runId: run.id, costUsd: null, source: 'SUBSCRIPTION' as const }],
    };
    expect(turnLine(run, [], cost)).toMatchObject({
      shaAndCost: 'subscription',
      usageTitle:
        'Input 1,200, cache writes included · output 340 tokens · On a subscription, at no cost',
    });
    expect(turnLine(run).shaAndCost).toBe('subscription');
  });

  it.each([
    ['FAILED', 'Failed', 'destructive'],
    ['LOST', 'Lost: the runner stopped reporting', 'warning'],
    ['CANCELLED', 'Cancelled', 'muted'],
  ] as const)('says a %s turn', (status, label, tone) => {
    expect(turnLine(aRun({ status, failureClass: 'INFRA' }))).toMatchObject({ label, tone });
  });

  it('says a turn stopped at its time limit as a stop, not a failure', () => {
    expect(turnLine(aRun({ status: 'FAILED', failureClass: 'TIME_LIMIT' }))).toMatchObject({
      label: 'Stopped at its time limit',
      tone: 'warning',
      icon: 'lucideTimerOff',
      failureClass: 'TIME_LIMIT',
    });
  });

  it('says a blocked turn in orange', () => {
    const run = aRun({
      result: { outcome: 'BLOCKED', summary: 'x', nextSteps: [], openQuestions: ['?'] },
    });
    expect(turnLine(run)).toMatchObject({ label: 'Blocked', tone: 'human' });
  });

  it('names the turn, why it started and its host', () => {
    expect(turnMeta(aRun())).toBe('Turn 1 · first message · office');
  });
});

describe('conversationCost', () => {
  const settledRun = aRun({
    id: 'run-1',
    turnNumber: 1,
    usage: { inputTokens: 1, outputTokens: 1, costUsd: 0.5 },
  });
  const pendingRun = aRun({
    id: 'run-2',
    turnNumber: 2,
    usage: {
      inputTokens: 40,
      cacheWriteTokens: 10,
      cachedInputTokens: 300,
      outputTokens: 20,
      costUsd: 0.11,
    },
  });

  it('is null while the server sends no cost', () => {
    expect(conversationCost(aConversation({ turns: [settledRun] }))).toBeNull();
  });

  it('adds the runtime’s estimate of the finished turns not settled yet to the settled spend', () => {
    const cost = conversationCost(
      aConversation({
        turns: [settledRun, pendingRun],
        cost: {
          costUsd: 0.48,
          tokens: {
            inputTokens: 1000,
            cacheWriteTokens: 200,
            cachedInputTokens: 5000,
            outputTokens: 300,
          },
          subscriptionTokens: 0,
          turns: [{ runId: 'run-1', costUsd: 0.48, source: 'GATEWAY' }],
        },
      }),
    )!;
    expect(cost).toMatchObject({
      settled: 0.48,
      estimated: 0.11,
      pendingTurns: 1,
      settledTurns: 1,
    });
    expect(cost.total).toBeCloseTo(0.59);
    expect(costTotal(cost)).toBe('~$0.590');
    expect(costNotes(cost)).toEqual([
      '$0.480 settled, and ~$0.110 the runtime’s estimate for 1 turn whose spend settles a few minutes after it ends, with the tokens it reported.',
    ]);
    // The settled tokens, and those the runtime reported for the turn not settled yet.
    expect(cost.tokens).toEqual({
      inputTokens: 1040,
      cacheWriteTokens: 210,
      cachedInputTokens: 5300,
      outputTokens: 320,
    });
    expect(tokenTotal(cost.tokens)).toBe(6870);
    expect(tokenBreakdown(cost.tokens)).toBe(
      'In 1,040 · Cache write 210 · Cache read 5,300 · Out 320',
    );
  });

  it('counts neither the turn running now nor a subscription’s turn by its estimate', () => {
    const running = aRun({ id: 'run-3', turnNumber: 3, status: 'RUNNING', finishedAt: null });
    const subscription = aRun({ id: 'run-4', turnNumber: 4, modelAccess: 'SUBSCRIPTION' });
    const cost = conversationCost(
      aConversation({
        state: 'OPEN',
        activeRunId: 'run-3',
        turns: [settledRun, running, subscription],
        cost: {
          costUsd: 1.5,
          tokens: {
            inputTokens: 2000,
            cacheWriteTokens: 0,
            cachedInputTokens: 12_000,
            outputTokens: 900,
          },
          subscriptionTokens: 12_400,
          turns: [{ runId: 'run-1', costUsd: 1.5, source: 'RELAY' }],
        },
      }),
    )!;
    expect(cost).toMatchObject({ total: 1.5, estimated: 0, pendingTurns: 0 });
    // Neither adds its tokens: the subscription's settled ones are in the server's.
    expect(tokenTotal(cost.tokens)).toBe(14_900);
    expect(tokenBreakdown(cost.tokens)).toBe('In 2,000 · Cache write 0 · Cache read 12K · Out 900');
    expect(costTotal(cost)).toBe('$1.50');
    expect(costNotes(cost)).toEqual([
      'The settled spend of 1 turn.',
      '12.4K tokens on a subscription, at no cost.',
    ]);
  });

  it('says a total of estimates alone as the runtime’s estimate', () => {
    const cost = conversationCost(
      aConversation({
        turns: [pendingRun],
        cost: {
          costUsd: 0,
          tokens: { inputTokens: 0, cacheWriteTokens: 0, cachedInputTokens: 0, outputTokens: 0 },
          subscriptionTokens: 0,
          turns: [],
        },
      }),
    )!;
    expect(costTotal(cost)).toBe('~$0.110');
    expect(costNotes(cost)).toEqual([
      '~$0.110 the runtime’s estimate for 1 turn whose spend settles a few minutes after it ends, with the tokens it reported.',
    ]);
  });

  it('counts the tokens a runtime reported without a cost, and marks the total as not final', () => {
    const unpriced = aRun({
      id: 'run-5',
      turnNumber: 2,
      runtime: 'CODEX',
      usage: { inputTokens: 900, cacheWriteTokens: 0, cachedInputTokens: 4000, outputTokens: 120 },
    });
    const cost = conversationCost(
      aConversation({
        turns: [settledRun, unpriced],
        cost: {
          costUsd: 0.48,
          tokens: {
            inputTokens: 1000,
            cacheWriteTokens: 200,
            cachedInputTokens: 5000,
            outputTokens: 300,
          },
          subscriptionTokens: 0,
          turns: [{ runId: 'run-1', costUsd: 0.48, source: 'GATEWAY' }],
        },
      }),
    )!;
    expect(cost).toMatchObject({ total: 0.48, estimated: 0, pendingTurns: 1 });
    expect(cost.tokens).toEqual({
      inputTokens: 1900,
      cacheWriteTokens: 200,
      cachedInputTokens: 9000,
      outputTokens: 420,
    });
    expect(costTotal(cost)).toBe('~$0.480');
    expect(costNotes(cost)).toEqual([
      '$0.480 settled, and 1 turn still to settle, a few minutes after it ends: the runtime gave no estimate of its cost.',
    ]);
  });

  it('waits for a turn the relay passed requests for, and not for one that relayed none', () => {
    const unreported = aRun({
      id: 'run-6',
      turnNumber: 2,
      usage: null,
      relay: { requests: 3, requestBytes: 1, responseBytes: 1 },
    });
    const unrelayed = aRun({
      id: 'run-7',
      turnNumber: 3,
      relay: { requests: 0, requestBytes: 0, responseBytes: 0 },
    });
    const cost = conversationCost(
      aConversation({
        turns: [settledRun, unreported, unrelayed],
        cost: {
          costUsd: 0.48,
          tokens: {
            inputTokens: 1000,
            cacheWriteTokens: 200,
            cachedInputTokens: 5000,
            outputTokens: 300,
          },
          subscriptionTokens: 0,
          turns: [{ runId: 'run-1', costUsd: 0.48, source: 'GATEWAY' }],
        },
      }),
    )!;
    // The first settles once LiteLLM's records arrive, though its runtime reported nothing; the
    // second never settles, whatever its runtime reported, and is not waited for.
    expect(cost).toMatchObject({ total: 0.48, estimated: 0, pendingTurns: 1 });
    expect(tokenTotal(cost.tokens)).toBe(6500);
    expect(costTotal(cost)).toBe('~$0.480');
  });

  it('says when nothing is recorded yet', () => {
    const cost = conversationCost(
      aConversation({
        turns: [aRun({ usage: null })],
        cost: {
          costUsd: 0,
          tokens: { inputTokens: 0, cacheWriteTokens: 0, cachedInputTokens: 0, outputTokens: 0 },
          subscriptionTokens: 0,
          turns: [],
        },
      }),
    )!;
    expect(costTotal(cost)).toBe('$0.00');
    expect(costNotes(cost)).toEqual([
      'No spend recorded yet: a turn’s spend settles a few minutes after it ends.',
    ]);
  });
});
