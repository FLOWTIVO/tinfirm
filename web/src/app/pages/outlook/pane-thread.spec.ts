import { aConversation, aMessage, aRun } from '../../testing/fixtures';
import { emailRequest } from './email-request';
import { paneThread } from './pane-thread';

const EMAIL = {
  subject: 'Checkout fails',
  from: { displayName: 'Ann Customer', emailAddress: 'ann@example.com' },
  to: [],
  cc: [],
  date: null,
  body: 'It answers 500.',
};

describe('the conversation in the pane', () => {
  it('shows every message in order, the first as its action without the email', () => {
    const conversation = aConversation({
      state: 'OPEN',
      turns: [
        aRun({
          id: 'run-1',
          status: 'SUCCEEDED',
          result: {
            outcome: 'COMPLETED',
            summary: 'Dear Ann, we are on it.',
            nextSteps: ['Check the logs'],
            openQuestions: [],
          },
        }),
        aRun({ id: 'run-2', status: 'RUNNING', result: null, summary: null }),
      ],
      messages: [
        aMessage({ id: 'm1', body: emailRequest('DRAFT_REPLY', EMAIL) }),
        aMessage({
          id: 'm2',
          kind: 'AGENT',
          runId: 'run-1',
          body: 'Dear Ann, we are on it.\n\n**Next steps**\n\n- Check the logs',
        }),
        aMessage({ id: 'm3', kind: 'SYSTEM', body: 'Your company’s owners can read it.' }),
        aMessage({ id: 'm4', body: 'Shorter, please.', waiting: true }),
      ],
    });

    const thread = paneThread(conversation);

    expect(thread.map((m) => [m.author, m.text])).toEqual([
      ['you', 'Draft a reply'],
      ['agent', 'Dear Ann, we are on it.\n\n**Next steps**\n\n- Check the logs'],
      ['tinfirm', 'Your company’s owners can read it.'],
      ['you', 'Shorter, please.'],
    ]);
    expect(thread[0]?.aboutEmail).toBe(true);
    // The reply is the turn's summary, without its next steps.
    expect(thread[1]?.reply).toBe('Dear Ann, we are on it.');
    expect(thread[3]?.waiting).toBe(true);
  });

  it('offers no reply for a question or for a turn that did not succeed', () => {
    const conversation = aConversation({
      turns: [aRun({ id: 'run-1', status: 'FAILED' })],
      messages: [
        aMessage({ id: 'm1', kind: 'AGENT', runId: 'run-1', body: 'Partial work.' }),
        aMessage({
          id: 'm2',
          kind: 'AGENT',
          runId: 'run-1',
          decisionId: 'd1',
          body: 'Which order number?',
        }),
      ],
    });

    expect(paneThread(conversation).map((m) => m.reply)).toEqual([null, null]);
  });

  it('no longer says a message waits once the conversation ended', () => {
    const conversation = aConversation({
      state: 'ENDED',
      messages: [aMessage({ id: 'm1', waiting: true })],
    });

    expect(paneThread(conversation)[0]?.waiting).toBe(false);
  });
});
