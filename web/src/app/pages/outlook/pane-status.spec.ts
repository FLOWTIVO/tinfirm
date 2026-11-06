import { aConversation, aDecision } from '../../testing/fixtures';
import { isActive, openQuestions, paneStatus } from './pane-status';

describe('where a conversation stands, in the pane', () => {
  it('follows a turn while it is queued or running', () => {
    const running = aConversation({ turnStatus: 'RUNNING', waitingOn: 'AGENT' });
    expect(isActive(running)).toBe(true);
    expect(paneStatus(running)).toMatchObject({ tone: 'agent', active: true });
    const queued = aConversation({ turnStatus: 'QUEUED', waitingOn: 'RUNNER' });
    expect(paneStatus(queued).text).toBe('Waiting for a runner host.');
  });

  it('says what the person must do when it waits for them', () => {
    const asked = aConversation({
      turnStatus: 'WAITING',
      waitingOn: 'PERSON',
      waitingReason: 'QUESTION',
      decisions: [aDecision({ status: 'OPEN' }), aDecision({ id: 'd2', status: 'DECIDED' })],
    });
    expect(isActive(asked)).toBe(false);
    expect(paneStatus(asked)).toMatchObject({
      tone: 'person',
      text: 'The agent asks you something.',
    });
    expect(openQuestions(asked).map((d) => d.status)).toEqual(['OPEN']);
    expect(
      paneStatus(
        aConversation({ turnStatus: 'WAITING', waitingOn: 'PERSON', waitingReason: 'APPROVAL' }),
      ).text,
    ).toContain('Decide it in Tinfirm');
  });

  it('shows a failure with its cause and a result as done', () => {
    const failed = aConversation({
      turnStatus: 'FAILED',
      waitingOn: 'NONE',
      failureCause: 'Budget spent',
    });
    expect(paneStatus(failed)).toEqual({ tone: 'failed', text: 'Budget spent', active: false });
    const done = aConversation({ turnStatus: 'SUCCEEDED', waitingOn: 'NONE' });
    expect(paneStatus(done)).toEqual({ tone: 'done', text: 'Done.', active: false });
    const ended = aConversation({ state: 'ENDED', turnStatus: 'RUNNING' });
    expect(isActive(ended)).toBe(false);
    expect(paneStatus(ended).text).toBe('The conversation has ended.');
  });
});
