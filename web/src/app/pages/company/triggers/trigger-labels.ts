import type {
  InboxTrigger,
  SystemKind,
  TriggerAccount,
  TriggerAction,
  TriggerChoiceSystem,
  TriggerEvent,
  TriggerFiringOutcome,
  TriggerPausedReason,
  TriggerPreviewAccount,
  TriggerRepeat,
  TriggerSource,
  TriggerSubject,
  TriggerWaitingReason,
  WebhookCondition,
} from '../../../api/generated/models';

/**
 * The words of triggers (the package 4 contract; CONVENTIONS §15.15): events, subjects, why a
 * trigger is paused or waits, what a firing did, the write-back's actions, and each row's status.
 */

/** Trackers a trigger may belong to (`TriggerSource` `TRACKER`). */
export const TRACKER_KINDS: readonly SystemKind[] = ['JIRA_DC', 'JIRA_CLOUD', 'GITHUB', 'GITLAB'];

export function isTracker(kind: SystemKind | null | undefined): boolean {
  return !!kind && TRACKER_KINDS.includes(kind);
}

/** Chats whose channels a trigger may listen in (`TriggerSource` `CHANNEL`). */
export function isChat(kind: SystemKind | null | undefined): boolean {
  return kind === 'SLACK' || kind === 'TEAMS';
}

/** The system kinds a source belongs to. */
export function sourceKinds(source: TriggerSource): readonly SystemKind[] {
  switch (source) {
    case 'TRACKER':
      return TRACKER_KINDS;
    case 'CHANNEL':
      return ['SLACK', 'TEAMS'];
    case 'WEBHOOK':
      return ['WEBHOOK'];
    default:
      return [];
  }
}

export const SOURCE_LABELS: Record<TriggerSource, string> = {
  TRACKER: 'Something happens on an issue',
  CHANNEL: 'Someone mentions it in a channel',
  WEBHOOK: 'A webhook is delivered',
  SCHEDULE: 'On a schedule',
};

/** What each source covers: under "It starts when", and as each option's second line. */
export const SOURCE_DETAILS: Record<TriggerSource, string> = {
  TRACKER: 'In Jira, GitHub or GitLab',
  CHANNEL: 'In Slack or Teams',
  WEBHOOK: 'Any other software calls Tinfirm',
  SCHEDULE: 'Every day, week or month',
};

/** What a source is called in a trigger's line: "Jira", "Slack channels", "Webhook", "Schedule". */
export function sourceText(source: TriggerSource, systemName: string | null | undefined): string {
  switch (source) {
    case 'SCHEDULE':
      return 'Schedule';
    case 'CHANNEL':
      return `${systemName ?? 'A chat'} channels`;
    case 'WEBHOOK':
      return `${systemName ?? 'A'} webhook`;
    default:
      return systemName ?? 'A system';
  }
}

export const WEBHOOK_OP_LABELS: Record<WebhookCondition['op'], string> = {
  EQUALS: 'is',
  IN: 'is one of',
  PRESENT: 'is present',
  ABSENT: 'is absent',
};

export function isJira(kind: SystemKind | null | undefined): boolean {
  return kind === 'JIRA_DC' || kind === 'JIRA_CLOUD';
}

export const EVENT_LABELS: Record<TriggerEvent, string> = {
  CREATED: 'Created',
  UPDATED: 'Title, description or type changed',
  STATUS_ENTERED: 'Moved to a status',
  LABEL_ADDED: 'Label added',
  LABEL_REMOVED: 'Label removed',
  ASSIGNED: 'Assigned to the trigger’s account',
  MENTIONED: 'The trigger’s account mentioned in a comment',
  COMMENTED: 'Commented on',
  CHANGE_REQUEST_OPENED: 'Change request opened',
  CHANGE_REQUEST_UPDATED: 'Change request got new commits',
  CLOSED: 'Closed',
  SCHEDULE: 'On schedule',
  DELIVERED: 'A webhook delivery',
};

/** Short words for lists: "assigned", "commented". */
export const EVENT_SHORT: Record<TriggerEvent, string> = {
  CREATED: 'created',
  UPDATED: 'updated',
  STATUS_ENTERED: 'moved to',
  LABEL_ADDED: 'labelled',
  LABEL_REMOVED: 'unlabelled',
  ASSIGNED: 'assigned',
  MENTIONED: 'mentioned',
  COMMENTED: 'commented',
  CHANGE_REQUEST_OPENED: 'change request opened',
  CHANGE_REQUEST_UPDATED: 'change request updated',
  CLOSED: 'closed',
  SCHEDULE: 'scheduled',
  DELIVERED: 'delivered',
};

/** Events that take a value: the status entered, the label added or removed. */
export const EVENTS_WITH_VALUE: ReadonlySet<TriggerEvent> = new Set<TriggerEvent>([
  'STATUS_ENTERED',
  'LABEL_ADDED',
  'LABEL_REMOVED',
]);

export const EVENT_VALUE_LABELS: Partial<Record<TriggerEvent, string>> = {
  STATUS_ENTERED: 'Status',
  LABEL_ADDED: 'Label',
  LABEL_REMOVED: 'Label',
};

/**
 * Events anyone who may write in the scope can cause (§5.8): a comment, a mention, a new issue.
 * The server decides `openToAnyone`; the editor uses this only to order its hints.
 */
export const OPEN_EVENTS: ReadonlySet<TriggerEvent> = new Set<TriggerEvent>([
  'CREATED',
  'COMMENTED',
  'MENTIONED',
]);

/** The events a tracker of a kind raises: Jira has statuses and no change requests. */
export function eventsFor(kind: SystemKind | null | undefined): TriggerEvent[] {
  if (isJira(kind)) {
    return [
      'ASSIGNED',
      'MENTIONED',
      'COMMENTED',
      'CREATED',
      'UPDATED',
      'STATUS_ENTERED',
      'LABEL_ADDED',
      'LABEL_REMOVED',
      'CLOSED',
    ];
  }
  return [
    'ASSIGNED',
    'MENTIONED',
    'COMMENTED',
    'CREATED',
    'UPDATED',
    'LABEL_ADDED',
    'LABEL_REMOVED',
    'CHANGE_REQUEST_OPENED',
    'CHANGE_REQUEST_UPDATED',
    'CLOSED',
  ];
}

export const SUBJECT_LABELS: Record<TriggerSubject, string> = {
  ANY: 'Issues and change requests',
  ISSUE: 'Issues',
  CHANGE_REQUEST: 'Change requests',
};

/** The subjects a tracker of a kind has: Jira has issues only. */
export function subjectsFor(kind: SystemKind | null | undefined): TriggerSubject[] {
  return isJira(kind) ? ['ISSUE'] : ['ANY', 'ISSUE', 'CHANGE_REQUEST'];
}

/** What the scope is called, and an example, per kind. */
export function scopeHint(kind: SystemKind | null | undefined): {
  label: string;
  placeholder: string;
  hint: string;
} {
  switch (kind) {
    case 'JIRA_DC':
    case 'JIRA_CLOUD':
      return {
        label: 'Project',
        placeholder: 'SHOP',
        hint: 'A project key. Empty: every project the trigger’s account sees.',
      };
    case 'GITHUB':
      return {
        label: 'Repository',
        placeholder: 'acme/shop',
        hint: '`owner/repo`. Empty: every repository the trigger’s account sees.',
      };
    case 'GITLAB':
      return {
        label: 'Project or group',
        placeholder: 'acme/shop',
        hint: 'A project’s or a group’s path. Empty: everything the trigger’s account sees.',
      };
    default:
      return { label: 'Scope', placeholder: '', hint: '' };
  }
}

export const REPEAT_CHOICES: readonly { value: TriggerRepeat; label: string; detail: string }[] = [
  {
    value: 'EVERY_TIME',
    label: 'Every time',
    detail:
      'Each event starts a conversation on the issue, or continues the one this trigger has open there.',
  },
  {
    value: 'ONCE',
    label: 'Once per issue',
    detail: 'Fires on an issue once, and never there again.',
  },
];

export const PAUSED_REASON_LABELS: Record<TriggerPausedReason, string> = {
  USER: 'Paused by hand',
  AUTHOR_LEFT: 'Its author left the company',
  NOT_ENTITLED: 'Its author may no longer use an account, the agent or the payer',
  ACCOUNT_REMOVED: 'An account it acts with was disconnected',
  SYSTEM_REMOVED: 'A system it used was removed',
  ACCOUNT_BROKEN: 'An account it acts with needs signing in again',
  SYSTEM_PAUSED: 'An admin paused its system',
  TO_ACCEPT: 'Reassigned: waits for its new author to accept it',
};

/** What fixes a pause, for the trigger page. */
export const PAUSED_REASON_FIX: Record<TriggerPausedReason, string> = {
  USER: 'It fires nothing until its author or an admin resumes it.',
  AUTHOR_LEFT:
    'Its open conversations wait. An admin reassigns it to another member, who resumes it.',
  NOT_ENTITLED:
    'Its author changes the accounts or the payer to ones they may use, then resumes it.',
  ACCOUNT_REMOVED: 'Its author names another account for the system, then resumes it.',
  SYSTEM_REMOVED: 'The system’s row was removed. Its author checks the accounts, then resumes it.',
  ACCOUNT_BROKEN:
    'Once the account is signed in again, its author or an admin resumes it. What happened meanwhile fires nothing.',
  SYSTEM_PAUSED: 'It resumes when an admin resumes the system.',
  TO_ACCEPT:
    'Its new author accepts its accounts, its payer and its open events, which resumes it. Nobody else resumes it.',
};

export const WAITING_REASON_LABELS: Record<TriggerWaitingReason, string> = {
  RECONNECT_YOURS: 'Waits for its author to sign in again',
  RECONNECT_COMPANY: 'Waits for an admin to sign a credential in again',
  SUSPENDED: 'Waits: an account’s holder or the payer is suspended',
  SYSTEM_UNREACHABLE: 'Waits: its system does not answer',
  SCOPE_UNREADABLE: 'Waits: its account cannot read the scope',
  GATEWAY_NOT_CONFIGURED: 'Waits for an admin to set up model access',
  COMPANY_STOPPED: 'Waits: an admin stopped the company’s runs',
  BOT_MISSING: 'Waits: its chat system has no bot',
  BOT_RECONNECT: 'Waits for an admin to sign the chat’s bot in again',
  BOT_SUSPENDED: 'Waits: the chat bot’s service account is suspended',
  CHANNEL_UNAVAILABLE: 'Waits: the bot is in none of its channels',
};

export const OUTCOME_LABELS: Record<TriggerFiringOutcome, string> = {
  STARTED: 'Started a conversation',
  CONTINUED: 'Continued its conversation',
  SKIPPED_OPEN: 'Skipped: the last one is still busy',
  SKIPPED_MISSED: 'Skipped: missed while Tinfirm was down',
  SKIPPED_CHAIN: 'Skipped: too many triggered conversations in a chain',
  SKIPPED_RATE: 'Skipped: over its firings per hour',
  SKIPPED_AGENT_PAUSED: 'Skipped: the agent is paused',
  FAILED: 'Failed',
};

/** Whether a firing's outcome started or continued work. */
export function fired(outcome: TriggerFiringOutcome): boolean {
  return outcome === 'STARTED' || outcome === 'CONTINUED';
}

export const ACTION_LABELS: Record<TriggerAction['kind'], string> = {
  TRANSITION: 'Move to status',
  ATTACH_DOCUMENTS: 'Attach the documents',
  LABEL_ADD: 'Add a label',
  LABEL_REMOVE: 'Remove a label',
  CLOSE: 'Close it',
};

export const ACTIONS_WITH_VALUE: ReadonlySet<TriggerAction['kind']> = new Set<
  TriggerAction['kind']
>(['TRANSITION', 'LABEL_ADD', 'LABEL_REMOVE']);

/** The write-back's actions a tracker of a kind has. */
export function actionsFor(kind: SystemKind | null | undefined): TriggerAction['kind'][] {
  if (isJira(kind)) return ['TRANSITION', 'ATTACH_DOCUMENTS'];
  if (kind === 'GITHUB' || kind === 'GITLAB') return ['LABEL_ADD', 'LABEL_REMOVE', 'CLOSE'];
  return [];
}

/** An action in words: "Move to status In review", "Close it". */
export function actionText(action: TriggerAction): string {
  const label = ACTION_LABELS[action.kind];
  return action.value ? `${label} ${action.value}` : label;
}

export const ACCOUNT_STATUS_LABELS: Record<
  TriggerAccount['status'] | TriggerPreviewAccount['status'],
  string
> = {
  OK: 'Ready',
  RECONNECT_YOURS: 'Sign in again',
  RECONNECT_COMPANY: 'An admin must sign it in again',
  SUSPENDED: 'Its holder is suspended',
  NOT_ENTITLED: 'Not yours to use',
  ACCOUNT_REMOVED: 'Disconnected',
};

export const CHOICE_STATUS_LABELS: Record<TriggerChoiceSystem['status'], string> = {
  OK: 'Ready',
  RECONNECT: 'Needs signing in again',
  NONE: 'No account: not available',
  NOT_NEEDED: 'No account needed',
};

export const INBOX_TRIGGER_LABELS: Record<InboxTrigger['reason'], string> = {
  PAUSED: 'Paused',
  WAITING: 'Waiting',
  WIDENING: 'A new version of its agent to accept',
  WRITE_BACK_DROPPED: 'Results could not be written back',
  FIRINGS_SKIPPED: 'Firings were skipped',
  CHANGED_BY_ADMIN: 'An admin changed it',
  TO_REASSIGN: 'Its author left: reassign it',
  POLL_WAITING: 'Its system is not read',
  MEMORY_REVIEW: 'Memory notes to review',
  TRIGGER_REASSIGNED_TO_YOU: 'Reassigned to you: accept it',
  CREDENTIAL_EXPOSED_TO_OPEN_SOURCE: 'A credential you lent acts for anyone',
};

/** The sentence an inbox item of a trigger says (§5.13), with the reason's own words. */
export function inboxTriggerText(item: InboxTrigger): string {
  const detail = item.detail ?? '';
  switch (item.reason) {
    case 'PAUSED':
      return (
        PAUSED_REASON_LABELS[detail as TriggerPausedReason] ??
        (detail ? `Paused: ${detail}` : 'Paused')
      );
    case 'WAITING':
      return (
        WAITING_REASON_LABELS[detail as TriggerWaitingReason] ??
        (detail ? `Waiting: ${detail}` : 'Waiting')
      );
    case 'WRITE_BACK_DROPPED':
      return item.count && item.count > 1
        ? `${item.count} results could not be written back to their issues`
        : 'A result could not be written back to its issue';
    case 'FIRINGS_SKIPPED':
      return item.count
        ? `${item.count} firings were skipped in the last hour${detail ? ` (${detail})` : ''}`
        : 'Firings were skipped in the last hour';
    case 'CHANGED_BY_ADMIN':
      return detail ? `An admin changed it: ${detail}` : 'An admin changed it on your behalf';
    case 'POLL_WAITING':
      return detail ? `Its system is not read: ${detail}` : 'Its system is not read';
    case 'WIDENING':
      return 'Its agent may do more in its new version: its runs keep the old one until it is accepted.';
    case 'TO_REASSIGN':
      return 'Its author left the company: it is paused until an admin reassigns it.';
    case 'MEMORY_REVIEW':
      return item.count && item.count > 1
        ? `${item.count} notes for its memory wait for your review`
        : 'A note for its memory waits for your review';
    case 'TRIGGER_REASSIGNED_TO_YOU':
      return (
        detail ||
        'It was reassigned to you: accept its accounts, its payer and its open events to resume it.'
      );
    case 'CREDENTIAL_EXPOSED_TO_OPEN_SOURCE':
      return (
        detail || 'A trigger anyone may drive acts with a credential lent to a service account.'
      );
  }
}

/** Orange only where the reader acts (DESIGN.md): accepting a version, reassigning, reviewing memory notes. */
export function inboxTriggerNeedsYou(item: InboxTrigger): boolean {
  return (
    item.reason === 'WIDENING' ||
    item.reason === 'TO_REASSIGN' ||
    item.reason === 'TRIGGER_REASSIGNED_TO_YOU' ||
    item.reason === 'MEMORY_REVIEW' ||
    (item.reason === 'PAUSED' && item.detail === 'ACCOUNT_BROKEN') ||
    (item.reason === 'WAITING' && item.detail === 'RECONNECT_YOURS')
  );
}
