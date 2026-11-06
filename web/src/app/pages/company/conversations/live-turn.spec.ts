import type { Run } from '../../../api/generated/models';
import { aConversation, aQueuedRun, aRun } from '../../../testing/fixtures';
import type { TranscriptItem } from '../runs/transcript-model';
import { latestStep, queuedLine } from './live-turn';

const base = { seq: 1, at: '2026-10-12T08:14:00Z' };

describe('latestStep', () => {
  it.each<[string, TranscriptItem, string | null]>([
    [
      'a tool call',
      {
        ...base,
        id: 't1',
        kind: 'tool',
        toolCallId: 't1',
        title: 'Read src/checkout.ts',
        toolKind: 'read',
        status: 'in_progress',
        input: null,
        locations: [],
        output: null,
        diffs: [],
      },
      'Read src/checkout.ts',
    ],
    [
      'a command',
      {
        ...base,
        id: 't2',
        kind: 'tool',
        toolCallId: 't2',
        title: 'npm test',
        toolKind: 'execute',
        status: 'in_progress',
        input: null,
        locations: [],
        output: null,
        diffs: [],
      },
      'Ran npm test',
    ],
    [
      'what it said',
      { ...base, id: 'm', kind: 'message', text: 'The guest flow skips the check.\nMore.' },
      'The guest flow skips the check.',
    ],
    ['a thought', { ...base, id: 'th', kind: 'thought', text: 'Hmm' }, 'Thinking'],
    [
      'its plan',
      {
        ...base,
        id: 'p',
        kind: 'plan',
        entries: [
          { content: 'Read the issue', status: 'completed', priority: 'medium' },
          { content: 'Write the brief', status: 'in_progress', priority: 'medium' },
        ],
      },
      'Write the brief',
    ],
  ])('says %s', (_what, item, step) => {
    expect(latestStep([item])).toBe(step);
  });

  it('says nothing before anything happened', () => {
    expect(latestStep([])).toBeNull();
  });

  it('cuts a long line', () => {
    const step = latestStep([{ ...base, id: 'm', kind: 'message', text: 'x'.repeat(200) }]);
    expect(step?.length).toBe(90);
    expect(step?.endsWith('…')).toBe(true);
  });
});

describe('queuedLine', () => {
  const c = aConversation();
  const budget = (): Run =>
    aQueuedRun({
      code: 'BUDGET_SPENT',
      text: 'The budget that pays for this conversation is spent.',
      retryAt: '2026-11-01T00:00:00Z',
    });

  it('names whose budget and when it resets, with a link for its payer (V7)', () => {
    const mine = queuedLine(budget(), c, true)!;
    expect(mine.text).toMatch(/^Waiting for your budget to reset on .*2026/);
    expect(mine.link).toEqual({
      label: 'Your budget',
      commands: ['accounts'],
      queryParams: { tab: 'budget' },
    });
    const theirs = queuedLine(budget(), c, false)!;
    expect(theirs.text).toMatch(/^Waiting for Alice Novak’s budget to reset on /);
    expect(theirs.link).toBeNull();
  });

  it('names the paused agent', () => {
    const line = queuedLine(
      aQueuedRun({ code: 'AGENT_PAUSED', text: 'The agent is paused.' }),
      c,
      true,
    );
    expect(line).toMatchObject({
      text: 'Business analyst is paused; its turns wait.',
      link: { label: 'Open the agent' },
    });
  });

  it('points an account to connect at its card', () => {
    const line = queuedLine(
      aQueuedRun({ code: 'ACCOUNT_REQUIRED', text: 'An account waits for you.' }),
      c,
      true,
    );
    expect(line).toMatchObject({ text: 'An account waits for you.', toCards: true });
  });

  it('leaves the other reasons, and a run that does not wait, to the server’s words', () => {
    expect(queuedLine(aQueuedRun(), c, true)).toBeNull();
    expect(queuedLine(aRun(), c, true)).toBeNull();
  });
});
