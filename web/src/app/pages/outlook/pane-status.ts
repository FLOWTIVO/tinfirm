import type { Conversation, Decision } from '../../api/generated/models';

/** How the pane shows where a conversation stands. */
export interface PaneStatus {
  /** `agent`: the agent or a runner host is on it; `person`: it waits for the person. */
  tone: 'agent' | 'person' | 'done' | 'failed';
  text: string;
  /** Whether the pane keeps reading the conversation. */
  active: boolean;
}

/** Whether the conversation's latest turn is still going. */
export function isActive(conversation: Conversation): boolean {
  return (
    conversation.state === 'OPEN' &&
    (conversation.turnStatus === 'QUEUED' ||
      conversation.turnStatus === 'RUNNING' ||
      conversation.waitingOn === 'AGENT' ||
      conversation.waitingOn === 'RUNNER')
  );
}

/** The open questions the person may answer with a message from the pane. */
export function openQuestions(conversation: Conversation): Decision[] {
  return conversation.decisions.filter(
    (decision) => decision.kind === 'QUESTION' && decision.status === 'OPEN',
  );
}

export function paneStatus(conversation: Conversation): PaneStatus {
  if (conversation.state === 'ENDED') {
    return { tone: 'done', text: 'The conversation has ended.', active: false };
  }
  if (conversation.turnStatus === 'RUNNING' || conversation.waitingOn === 'AGENT') {
    return { tone: 'agent', text: 'Working on it. A turn takes a few minutes.', active: true };
  }
  if (conversation.turnStatus === 'QUEUED' || conversation.waitingOn === 'RUNNER') {
    return { tone: 'agent', text: 'Waiting for a runner host.', active: true };
  }
  if (conversation.waitingOn === 'PERSON') {
    switch (conversation.waitingReason) {
      case 'QUESTION':
        return { tone: 'person', text: 'The agent asks you something.', active: false };
      case 'APPROVAL':
        return {
          tone: 'person',
          text: 'The agent needs your approval. Decide it in Tinfirm.',
          active: false,
        };
      case 'ACCOUNT':
        return {
          tone: 'person',
          text: 'The agent needs an account of yours. Connect it in Tinfirm.',
          active: false,
        };
      case 'FAILED':
        return {
          tone: 'failed',
          text: conversation.failureCause ?? 'The turn failed.',
          active: false,
        };
      default:
        return { tone: 'person', text: 'The agent waits for you in Tinfirm.', active: false };
    }
  }
  switch (conversation.turnStatus) {
    case 'FAILED':
      return {
        tone: 'failed',
        text: conversation.failureCause ?? 'The turn failed.',
        active: false,
      };
    case 'CANCELLED':
      return { tone: 'failed', text: 'The turn was cancelled.', active: false };
    default:
      return { tone: 'done', text: 'Done.', active: false };
  }
}
