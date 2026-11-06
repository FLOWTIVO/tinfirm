import type { Conversation } from '../../api/generated/models';
import { describeRequest } from './email-request';

/** One message of the conversation as the pane shows it. */
export interface PaneMessage {
  id: string;
  /** `you`: the person; `agent`: the agent's answer or question; `tinfirm`: Tinfirm's note; `other`: anyone else. */
  author: 'you' | 'agent' | 'tinfirm' | 'other';
  /** Who wrote an `other` message. */
  name: string | null;
  /** Markdown. */
  text: string;
  /** The first message, sent with the email's text. */
  aboutEmail: boolean;
  /** A message of the person's that waits for the agent's next turn. */
  waiting: boolean;
  /** What **Use as reply** puts in Outlook's reply form: the turn's summary, without its next steps. */
  reply: string | null;
}

/**
 * The whole conversation, oldest first, as the pane shows it: the person's messages (the first as
 * its instruction alone, not the email), the agent's answers and questions, and Tinfirm's notes.
 * An answer of a turn that succeeded may be used as the reply.
 */
export function paneThread(conversation: Conversation): PaneMessage[] {
  const turns = new Map(conversation.turns.map((turn) => [turn.id, turn]));
  return conversation.messages.map((message, index) => {
    const entry: PaneMessage = {
      id: message.id,
      author: 'other',
      name: message.authorName ?? null,
      text: message.body,
      aboutEmail: false,
      waiting: false,
      reply: null,
    };
    switch (message.kind) {
      case 'PERSON': {
        const request = index === 0 ? describeRequest(message.body) : null;
        return {
          ...entry,
          author: 'you',
          text: request?.text ?? message.body,
          aboutEmail: request?.aboutEmail ?? false,
          waiting: message.waiting && conversation.state === 'OPEN',
        };
      }
      case 'AGENT': {
        // A question's card has a decision; a turn's answer has only its turn.
        const turn = message.decisionId ? undefined : turns.get(message.runId ?? '');
        const summary =
          turn?.status === 'SUCCEEDED' ? (turn.result?.summary ?? turn.summary ?? '') : '';
        return { ...entry, author: 'agent', reply: summary.trim() ? summary : null };
      }
      case 'SYSTEM':
        return { ...entry, author: 'tinfirm' };
      default:
        return entry;
    }
  });
}
