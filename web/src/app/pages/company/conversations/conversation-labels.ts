import type {
  ConversationEndedReason,
  ConversationState,
  ConversationSummary,
  ConversationWaitingOn,
} from '../../../api/generated/models';

/** Why a conversation ended (`endedReason`), in a few words for its row. */
export const ENDED_REASON_LABELS: Record<ConversationEndedReason, string> = {
  USER: 'Ended',
  AGENT_DELETED: 'Ended: its agent was deleted',
  PERSON_LEFT: 'Ended: its person left',
  AUTHOR_LEFT: 'Ended: its trigger’s author left',
  TRIGGER_DELETED: 'Ended: its trigger was deleted',
  SUBJECT_CLOSED: 'Ended: its issue was closed',
  SUPERSEDED: 'Ended: a newer one took over',
  SYSTEM_REMOVED: 'Ended: its chat was removed',
};

export function endedLabel(reason: ConversationEndedReason | null | undefined): string {
  return reason ? (ENDED_REASON_LABELS[reason] ?? 'Ended') : 'Ended';
}

/** What the list shows, as one row of pills: its state and whom it waits on, together. */
export type ConversationShow = 'all' | 'open' | 'person' | 'agent' | 'runner' | 'ended';

/**
 * The pills, each with the `state` and `waitingOn` the URL keeps for it. The open ones narrow by
 * whom they wait on; "waiting on nobody" has no pill.
 */
export const SHOW_FILTERS: readonly {
  readonly value: ConversationShow;
  readonly label: string;
  readonly state?: ConversationState;
  readonly waitingOn?: ConversationWaitingOn;
}[] = [
  { value: 'all', label: 'All' },
  { value: 'open', label: 'Open', state: 'OPEN' },
  { value: 'person', label: 'Needs a person', waitingOn: 'PERSON' },
  { value: 'agent', label: 'Working', waitingOn: 'AGENT' },
  { value: 'runner', label: 'Waiting for a runner', waitingOn: 'RUNNER' },
  { value: 'ended', label: 'Ended', state: 'ENDED' },
];

/**
 * The pill the URL's state and waiting pick; none when no pill shows that (a link that sets
 * another combination).
 */
export function showOf(
  filters: Pick<ConversationFilters, 'state' | 'waitingOn'>,
): ConversationShow | undefined {
  return SHOW_FILTERS.find((s) => s.state === filters.state && s.waitingOn === filters.waitingOn)
    ?.value;
}

/**
 * A conversation's title in a list: its key once retention removed its content (the title is
 * then the key too, `contentPurgedAt`).
 */
export function conversationTitle(c: Pick<ConversationSummary, 'title' | 'key'>): string {
  return c.title.trim() || c.key;
}

/** The list's filters, as the URL keeps them (`?agentId=&state=&waitingOn=…`). */
export interface ConversationFilters {
  readonly agentId?: string;
  readonly state?: ConversationState;
  readonly waitingOn?: ConversationWaitingOn;
  readonly subjectSystemId?: string;
  readonly subjectKey?: string;
  readonly triggerId?: string;
  /** OWNER only, in "Everyone's": one person's conversations. */
  readonly personId?: string;
}

const STATES = new Set<string>(['OPEN', 'ENDED']);
const WAITING = new Set<string>(['PERSON', 'AGENT', 'RUNNER', 'NONE']);

/** The filters of the URL's query, each kept only when it is one the API takes. */
export function filtersOf(query: Record<string, string | undefined>): ConversationFilters {
  const value = (name: string) => query[name]?.trim() || undefined;
  const state = value('state');
  const waitingOn = value('waitingOn');
  const subjectKey = value('subjectKey');
  return {
    agentId: value('agentId'),
    state: state && STATES.has(state) ? (state as ConversationState) : undefined,
    waitingOn:
      waitingOn && WAITING.has(waitingOn) ? (waitingOn as ConversationWaitingOn) : undefined,
    // A subject is a key on a system: the API takes the key only with its system.
    subjectSystemId: subjectKey ? value('subjectSystemId') : undefined,
    subjectKey: value('subjectSystemId') ? subjectKey : undefined,
    triggerId: value('triggerId'),
    personId: value('personId'),
  };
}

/** How many filters narrow the list (the person apart: "Everyone's" says it). */
export function filterCount(filters: ConversationFilters): number {
  return [
    filters.agentId,
    filters.state,
    filters.waitingOn,
    filters.subjectKey,
    filters.triggerId,
    filters.personId,
  ].filter(Boolean).length;
}
