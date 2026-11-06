import type {
  ConversationBinding,
  ConversationBindingChoice,
  Decision,
  InboxBinding,
  InboxCounts,
  InboxItem,
  InboxItemKind,
  InboxLicence,
  InboxTrigger,
  MyAccount,
  QueueReasonCode,
} from '../../../api/generated/models';
import { ROLE_LABELS } from '../../../core/permissions';
import {
  decidedLabel,
  decisionKindLabel,
  decisionTitle,
  toolCallWhat,
} from '../../../shared/decision-card';
import type { QueuedRun } from '../../../shared/queue-reason';
import { failureClassLabel, stoppedAtTimeLimit } from '../../../shared/run-status';
import { formatDay, formatUsd, plural } from '../../../shared/labels';
import { EDITION_LABELS, licenceDay } from '../../../shared/licence-labels';
import { inboxTriggerNeedsYou, inboxTriggerText } from '../triggers/trigger-labels';

/** The inbox's three groups (plan §5): what needs the reader, the turns that stopped, notices. */
export type InboxGroup = 'needs' | 'turns' | 'notices';

/** Every kind the API has, with its group: a kind the API adds breaks the build here. */
export const INBOX_KIND_GROUPS: Record<InboxItemKind, InboxGroup> = {
  DECISION: 'needs',
  BINDING: 'needs',
  ACCOUNT: 'needs',
  AUDIT_CHECK: 'needs',
  FAILED: 'turns',
  WAITING: 'turns',
  RETENTION_OPEN: 'notices',
  BUDGET: 'notices',
  COMPANY_SPEND: 'notices',
  SPEND_SYNC: 'notices',
  TRIGGER: 'notices',
  DEPARTURE: 'notices',
  CHAT_LINKED: 'notices',
  CHAT_SUBSCRIPTION_SILENT: 'notices',
  SEAT_FALLBACK: 'notices',
  LICENCE: 'notices',
  SCIM_LAST_OWNER: 'notices',
};

export interface InboxGroupView {
  readonly key: InboxGroup;
  readonly title: string;
  /** What the group holds, beside its title. */
  readonly hint: string;
  /** What the group says while its pill shows it alone, empty. */
  readonly empty: string;
  /** Its pill's value in the URL (`?show=`). */
  readonly param: string;
}

export const INBOX_GROUPS: readonly InboxGroupView[] = [
  {
    key: 'needs',
    title: 'Needs you',
    hint: 'Longest waiting first. Answer in the row, or open an item for the whole card.',
    empty: 'Nothing needs you now.',
    param: 'needs',
  },
  {
    key: 'turns',
    title: 'Stopped',
    hint: 'Turns that failed or wait. Nothing here needs a decision.',
    empty: 'No turn stopped.',
    param: 'stopped',
  },
  {
    key: 'notices',
    title: 'Notices',
    hint: 'For your information. Dismiss one you have read: it comes back if it changes.',
    empty: 'No notices.',
    param: 'notices',
  },
];

/** The group a pill shows alone, from `?show=`; null for all of them. */
export function inboxGroupOf(param: string | null | undefined): InboxGroup | null {
  return INBOX_GROUPS.find((g) => g.param === param)?.key ?? null;
}

/**
 * A stable key of an item, across reads of the inbox: a notice's own `noticeKey`, which the server
 * computes from what it is about and the condition that raised it; else its kind and its object.
 */
export function inboxItemKey(item: InboxItem): string {
  if (item.noticeKey) return item.noticeKey;
  return [
    item.kind,
    item.decision?.id ??
      item.binding?.id ??
      item.trigger?.triggerId ??
      item.account?.credential?.id ??
      // A suspended service account keeps conversations waiting on each of its systems.
      (item.account?.holderPrincipalId
        ? `${item.account.holderPrincipalId}:${item.account.systemId}`
        : item.account?.systemId) ??
      item.budget?.principalId ??
      item.departure?.principalId ??
      item.chat?.systemId ??
      item.auditCheck?.id ??
      item.conversation?.id ??
      item.since,
  ].join(':');
}

/** Who reads the inbox, for the words and links that differ: who sets the company up, and OWNER. */
export interface InboxReader {
  readonly admin: boolean;
  readonly owner: boolean;
}

/** A link to the page that deals with an item, under `/c/:companyId`. */
export interface InboxLink {
  readonly label: string;
  readonly commands: readonly string[];
  readonly queryParams?: Record<string, string>;
  /** The part of the page it opens at, such as a trigger's memory (`memory`). */
  readonly fragment?: string;
}

/**
 * What a row does in place, beside its link. `continue`: Retry of a turn stopped at its time
 * limit, which goes on from where it stopped; `sign-in`: a system's credential signed in again at
 * the system; `enable`: a disabled credential enabled again; `unlink`: a chat account linked to
 * the reader.
 */
export type InboxAction = 'retry' | 'continue' | 'sign-in' | 'enable' | 'unlink';

/** `human`: the reader acts (orange); `destructive`: a failed turn; `muted`: neutral. */
export type InboxTone = 'human' | 'destructive' | 'muted';

/** A row of the inbox, of any kind. */
export interface InboxRowView {
  /** The kind in a word or two: a row's pill, a notice's label. */
  readonly kind: string;
  /** What it is, in one line: a question's first line, what an agent wants, why a turn stopped. */
  readonly title: string;
  readonly body: string | null;
  readonly tone: InboxTone;
  readonly link: InboxLink | null;
  readonly action: InboxAction | null;
}

function conversationLink(item: InboxItem): InboxLink | null {
  const id = item.conversation?.id ?? item.decision?.conversationId;
  return id ? { label: 'Open', commands: ['conversations', id] } : null;
}

function percent(n: number): string {
  return `${n} %`;
}

/**
 * A Markdown text's first line as plain text, for a row of one line: without its block marker,
 * emphasis, code ticks and link targets.
 */
export function plainLine(markdown: string | null | undefined): string {
  const line = (markdown ?? '').split('\n').find((l) => l.trim()) ?? '';
  return line
    .trim()
    .replace(/^(#{1,6}\s+|>\s*|[-*+]\s+|\d+[.)]\s+)/, '')
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/(\*\*|__)(\S(?:.*?\S)?)\1/g, '$2')
    .replace(/(^|[^\w*])([*_])(\S(?:.*?\S)?)\2(?![\w*])/g, '$1$3')
    .replace(/~~(.*?)~~/g, '$1')
    .replace(/`([^`]*)`/g, '$1')
    .trim();
}

/** Whether a Markdown text is one plain line, which a title can carry whole. */
export function isPlainLine(markdown: string | null | undefined): boolean {
  return !!markdown && plainLine(markdown) === markdown.trim();
}

/**
 * Why a waiting turn waits, in a sentence, when the server gave none (`InboxTurn.waitingText`).
 * Every code of the API, once.
 */
export const WAITING_TEXTS: Record<QueueReasonCode, string> = {
  COMPANY_STOPPED: 'An admin stopped the company’s runs; it goes on when they are resumed.',
  SYSTEM_PAUSED: 'A system its workspace uses is paused by an admin.',
  AGENT_PAUSED: 'Its agent is paused; its turns wait until it is resumed.',
  ACCOUNT_REQUIRED: 'An account of its workspace waits to be connected.',
  BUDGET_SPENT: 'Your budget is spent.',
  KEY_ERROR: 'The gateway could not hand out a key for your budget.',
  HOST_OFFLINE: 'The runner host of its subscription is offline.',
  MODEL_ACCESS_UNAVAILABLE: 'Nobody can pay for it as your settings stand.',
  NO_HOST_FOR_CREDENTIALS: 'No runner host may carry its credentials.',
  NO_RUNNER_HOST: 'No runner host can run it.',
  RUNNER_HOST_OFFLINE: 'Its runner host is offline.',
  RUNNER_HOST_DRAINING: 'Its runner host is restarting.',
  CREDENTIAL_POOL_FULL: 'Its subscription is busy with other turns.',
  RUNNER_HOST_FULL: 'Every slot of the runner host is in use.',
  PREVIOUS_RUN_ACTIVE: 'The host still finishes an earlier turn of it.',
  RUNNER_HOST_REJECTED: 'The runner host turned it down for now.',
  ASSIGNED: 'Sent to a runner host, waiting for it to start.',
  TRIGGER_PAUSED: 'Its trigger is paused; its turns wait until it is resumed.',
  NO_HOST_FOR_SKILLS: 'No connected runner host can give its agent its skills yet.',
  RETRY_SCHEDULED: 'Its turn failed for a reason that may pass; it is retried by itself soon.',
  OTHER: 'It waits for a runner host.',
};

/**
 * A waiting turn as the queue reason takes it (its words, its reset and the link to its fix); null
 * for an item without a turn. The words are the server's when it gave them.
 */
export function waitingRun(item: InboxItem): QueuedRun | null {
  const turn = item.turn;
  if (!turn || !item.conversation) return null;
  const code = (turn.waitingReason ?? 'OTHER') as QueueReasonCode;
  return {
    status: 'QUEUED',
    agentId: item.conversation.agentId,
    conversationId: item.conversation.id,
    runnerHostId: null,
    runnerHostName: null,
    queueReason: {
      code,
      since: item.since,
      text: turn.waitingText?.trim() || (WAITING_TEXTS[code] ?? WAITING_TEXTS.OTHER),
      retryAt: turn.retryAt ?? null,
    },
  };
}

/** The agent an item is about, by name. */
export function agentOf(item: InboxItem): string | null {
  return item.conversation?.agentName ?? item.decision?.agentName ?? null;
}

function bindingView(item: InboxItem): InboxRowView {
  const b = item.binding!;
  const system = b.systemName ?? 'the system';
  const agent = agentOf(item) ?? 'The agent';
  const words: Record<InboxBinding['reason'], [string, string]> = {
    NONE: [`Connect ${system}`, `${agent} asked to use ${system}: connect your own account`],
    RECONNECT_YOURS: [
      `Sign in to ${system}`,
      `Your ${system} account no longer works: sign in again`,
    ],
    PICK_GRANT: ['Pick a grant', `${agent} asked to use ${system}: pick the grant it acts through`],
    CHOOSE: [
      `Sign in to ${system}`,
      `Your ${system} account needs signing in again, or a grant of your group instead`,
    ],
  };
  const [kind, title] = words[b.reason] ?? words.NONE;
  return { kind, title, body: null, tone: 'human', link: conversationLink(item), action: null };
}

/** The words, tone, link and action of a row by its kind (plan §5). */
export function inboxRowView(item: InboxItem, reader: InboxReader): InboxRowView {
  const agent = agentOf(item) ?? 'The agent';
  switch (item.kind) {
    case 'DECISION': {
      const d = item.decision;
      if (!d) break;
      const question = d.kind === 'QUESTION' ? plainLine(d.question) : '';
      return {
        kind: decisionKindLabel(d),
        title: question || decisionTitle(d),
        body: null,
        tone: d.mayDecide ? 'human' : 'muted',
        link: conversationLink(item),
        action: null,
      };
    }
    case 'BINDING':
      if (!item.binding) break;
      return bindingView(item);
    case 'FAILED': {
      if (stoppedAtTimeLimit(item.turn?.failureClass)) {
        // Not a failure: its work is kept, and Continue goes on from where it stopped.
        return {
          kind: 'Time limit',
          title: 'Stopped at its time limit',
          body: 'its work is kept, and Continue goes on from where it stopped',
          tone: 'muted',
          link: conversationLink(item),
          action: item.conversation ? 'continue' : null,
        };
      }
      return {
        kind: 'Failed',
        title: failureClassLabel(item.turn?.failureClass) ?? `${agent}’s turn failed`,
        body: item.turn?.failureCause ?? null,
        tone: 'destructive',
        link: conversationLink(item),
        action: item.conversation ? 'retry' : null,
      };
    }
    case 'WAITING':
      return {
        kind: 'Waiting',
        title: waitingRun(item)?.queueReason?.text ?? `${agent}’s turn waits`,
        body: null,
        tone: 'muted',
        link: conversationLink(item),
        action: null,
      };
    case 'ACCOUNT': {
      const a = item.account!;
      const system = a.systemName ?? 'a system';
      const waiting = `It keeps ${plural(a.conversations, 'conversation')} waiting.`;
      if (a.reason === 'SUSPENDED') {
        const holder = a.holderName ?? 'A service account';
        return {
          kind: 'Suspended',
          title: `${holder} is suspended`,
          body: `The credential it borrows on ${system} acts for nothing through it until an admin resumes it. ${waiting}`,
          tone: 'human',
          link: a.holderPrincipalId
            ? { label: `Open ${holder}`, commands: ['service-identities', a.holderPrincipalId] }
            : null,
          action: null,
        };
      }
      if (a.bot) {
        // The chat system's bot is fixed with the bot: its token replaced under Bot.
        return {
          kind: 'Chat bot',
          title: `The bot of ${system} no longer works`,
          body: `${waiting} Replace it under Bot, on ${system}’s page.`,
          tone: 'human',
          link: {
            label: 'Open its bot',
            commands: ['systems', a.systemId],
            queryParams: { tab: 'bot' },
          },
          action: null,
        };
      }
      const c = a.credential;
      if (!c) {
        return {
          kind: 'Sign in again',
          title: `A credential of ${system} needs signing in again`,
          body: `${waiting} Sign it in again, or store a new token, on ${system}’s page.`,
          tone: 'human',
          link: { label: `Open ${system}`, commands: ['systems', a.systemId] },
          action: null,
        };
      }
      const credential = `The credential ${c.name} of ${system}`;
      const atIt = { commands: ['systems', a.systemId], fragment: `credential-${c.id}` };
      if (!c.enabled) {
        return {
          kind: 'Disabled',
          title: `${credential} is disabled`,
          body: `${waiting} Enable it, and ${a.conversations === 1 ? 'the conversation goes' : 'they go'} on.`,
          tone: 'human',
          link: { label: `Open ${system}`, ...atIt },
          action: 'enable',
        };
      }
      if (c.kind === 'CLIENT') {
        return {
          kind: 'New client credentials',
          title: `${credential} needs new client credentials`,
          body: `${waiting} Store new client credentials for it on ${system}’s page.`,
          tone: 'human',
          link: { label: 'Store new client credentials', ...atIt },
          action: null,
        };
      }
      if (c.kind === 'TOKEN') {
        return {
          kind: 'New token',
          title: `${credential} needs a new token`,
          body: `${waiting} Store a new token for it on ${system}’s page.`,
          tone: 'human',
          link: { label: 'Store a new token', ...atIt },
          action: null,
        };
      }
      return {
        kind: 'Sign in again',
        title: `${credential} needs signing in again`,
        body: `${waiting} Sign it in again with ${system}.`,
        tone: 'human',
        link: { label: `Open ${system}`, ...atIt },
        action: 'sign-in',
      };
    }
    case 'AUDIT_CHECK': {
      const check = item.auditCheck;
      const why =
        check?.failure === 'BROKEN'
          ? `an event does not verify${check.firstInvalidSeq != null ? ` (#${check.firstInvalidSeq})` : ''}`
          : check?.failure === 'SHORTER'
            ? 'the log is shorter than at the last check'
            : check?.failure === 'HEAD_CHANGED'
              ? 'its last event changed since the last check'
              : 'it does not verify';
      return {
        kind: 'Audit log',
        title: 'The daily check of the audit log failed',
        body: `The check found that ${why}. ${
          reader.owner
            ? 'Look into it; an owner acknowledges the failure once it is understood.'
            : 'An owner acknowledges the failure once it is understood.'
        }`,
        tone: reader.owner ? 'human' : 'muted',
        link: { label: 'Open the audit log', commands: ['audit'] },
        action: null,
      };
    }
    case 'RETENTION_OPEN': {
      const n = item.openConversations ?? 0;
      return {
        kind: 'Retention',
        title: `${plural(n, 'open conversation')} older than the retention period`,
        body: 'Retention removes a conversation’s texts only once it has ended. End the ones nobody needs.',
        tone: 'muted',
        link: { label: 'Conversations', commands: ['conversations'] },
        action: null,
      };
    }
    case 'BUDGET': {
      const b = item.budget!;
      const whose = b.own ? 'your budget' : `${b.principalName ?? 'a budget'}’s budget`;
      const resets = formatDay(b.resetsAt);
      const waits = [
        b.waitingRuns ? plural(b.waitingRuns, 'turn') + ' wait' : null,
        b.waitingTriggers ? plural(b.waitingTriggers, 'live trigger') + ' it pays' : null,
      ].filter((x): x is string => !!x);
      const link: InboxLink | null = b.own
        ? { label: 'Your budget', commands: ['accounts'], queryParams: { tab: 'budget' } }
        : reader.admin
          ? b.principalKind === 'SERVICE'
            ? {
                label: `Open ${b.principalName ?? 'it'}`,
                commands: ['service-identities', b.principalId],
              }
            : { label: 'Model access', commands: ['model-access'] }
          : null;
      return {
        kind: 'Budget',
        title: `${percent(b.threshold)} of ${whose} used`,
        body: [
          `${formatUsd(b.spentUsd)} of ${formatUsd(b.budgetUsd)}${resets ? `, resets ${resets}` : ''}.`,
          waits.length ? `${waits.join('; ')}.` : null,
        ]
          .filter(Boolean)
          .join(' '),
        tone: 'muted',
        link,
        action: null,
      };
    }
    case 'COMPANY_SPEND': {
      const s = item.companySpend!;
      return {
        kind: 'Spend',
        title: 'The company’s spend crossed its alert amount',
        body: `${formatUsd(s.totalUsd)} this month (${s.month}), over the alert of ${formatUsd(s.alertUsd)}. Nothing is capped.`,
        tone: 'muted',
        link: { label: 'Model access', commands: ['model-access'] },
        action: null,
      };
    }
    case 'SPEND_SYNC': {
      const s = item.spendSync;
      return {
        kind: 'Spend',
        title: 'Spend is counted by Tinfirm, not read from the gateway',
        body: [
          `The gateway’s spend logs could not be read${s?.relayRunsToday ? `; ${plural(s.relayRunsToday, 'run')} today counted by the relay` : ''}.`,
          s?.lastError ? `Last error: ${s.lastError}` : null,
        ]
          .filter(Boolean)
          .join(' '),
        tone: 'muted',
        link: { label: 'Model access', commands: ['model-access'] },
        action: null,
      };
    }
    case 'TRIGGER': {
      const t = item.trigger!;
      return {
        kind: 'Trigger',
        title: t.name,
        body: triggerNoticeText(t),
        tone: inboxTriggerNeedsYou(t) ? 'human' : 'muted',
        link:
          t.reason === 'MEMORY_REVIEW'
            ? { label: 'Review notes', commands: ['triggers', t.triggerId], fragment: 'memory' }
            : { label: 'Open trigger', commands: ['triggers', t.triggerId] },
        action: null,
      };
    }
    case 'DEPARTURE': {
      const d = item.departure!;
      return {
        kind: 'Departure',
        title: `${d.name} left the company`,
        body: 'Their checklist is not closed: hand over their triggers and agents, and check their accounts.',
        tone: 'human',
        link: { label: 'Members', commands: ['members'] },
        action: null,
      };
    }
    case 'CHAT_LINKED': {
      const c = item.chat!;
      return {
        kind: c.systemName ?? 'Chat',
        title: `A ${c.systemName ?? 'chat'} account was linked to you`,
        body: c.text,
        tone: 'muted',
        link: null,
        action: c.unlinkSystemId ? 'unlink' : null,
      };
    }
    case 'SEAT_FALLBACK': {
      // A provider's new user who joined as a Viewer, every seat being taken (ADR-0077), or a member
      // SCIM asked a seat's role for (ADR-0087), whose next request asks again.
      const f = item.seatFallback!;
      return {
        kind: 'Seats',
        title: `${f.name} joined as a Viewer: every seat was taken`,
        body:
          f.provider === 'scim'
            ? `Your identity provider asks for ${ROLE_LABELS[f.role]} through SCIM. Free a seat, or enter a key with more seats: the provider's next update gives them the role.`
            : `Their identity provider gives them ${ROLE_LABELS[f.role]}. Free a seat, or enter a key with more seats, then change their role under Members.`,
        tone: 'human',
        link: { label: 'Members', commands: ['members'] },
        action: null,
      };
    }
    case 'LICENCE': {
      // The instance's licence near or past its end (ADR-0077, decision 7).
      const l = item.licence!;
      return {
        kind: 'Licence',
        ...licenceNoticeWords(l),
        tone: 'human',
        link: { label: 'Licence', commands: ['settings', 'licence'] },
        action: null,
      };
    }
    case 'SCIM_LAST_OWNER': {
      // The company's identity provider tried to deactivate or remove its last active owner (ADR-0087).
      return {
        kind: 'Provisioning',
        title: 'Your identity provider tried to remove you, the last active owner',
        body: 'Tinfirm refused it through SCIM, so that the company keeps an owner. Appoint another owner under Members before you leave, or ask your identity provider’s admin to assign you again.',
        tone: 'human',
        link: { label: 'Members', commands: ['members'] },
        action: null,
      };
    }
    case 'CHAT_SUBSCRIPTION_SILENT': {
      const c = item.chat!;
      return {
        kind: c.systemName ?? 'Chat',
        title: `${c.systemName ?? 'A chat system'} stopped sending events`,
        body: c.text,
        tone: 'human',
        link: { label: `Open ${c.systemName ?? 'the system'}`, commands: ['systems', c.systemId] },
        action: null,
      };
    }
  }
  return {
    kind: 'Item',
    title: `${agent} waits`,
    body: null,
    tone: 'muted',
    link: conversationLink(item),
    action: null,
  };
}

/** What the licence's notice says, by its state (ADR-0077, decision 7). */
export function licenceNoticeWords(l: InboxLicence): { title: string; body: string } {
  const edition = l.edition ? `${EDITION_LABELS[l.edition]} ` : '';
  // ADR-0087 decision 10: SCIM stops with Enterprise; a person who leaves is then removed by hand.
  const scim =
    l.edition === 'ENTERPRISE'
      ? ' SCIM provisioning stops then: remove a person who leaves under Members.'
      : '';
  switch (l.state) {
    case 'GRACE':
      return {
        title: `The ${edition}licence has ended: its paid features stop on ${licenceDay(l.graceEndsAt)}`,
        body: `It ended on ${licenceDay(l.endsAt)}; everything keeps working until ${licenceDay(l.graceEndsAt)}. A new key, which an instance admin enters, restores everything at once.${scim}`,
      };
    case 'EXPIRED':
      return {
        title: 'The licence has run out: the instance runs as Community',
        body: `Work, data and roles stay${l.auditExportUntil ? `, and the audit’s export until ${licenceDay(l.auditExportUntil)}` : ''}. A new key, which an instance admin enters, restores the paid features at once.`,
      };
    default:
      return {
        title: `The ${edition}licence ends on ${licenceDay(l.endsAt)}`,
        body: `Ask your vendor for the renewal’s key; an instance admin enters it under Licence. Nothing changes until 30 days after the end.${scim}`,
      };
  }
}

/** What a trigger's notice says: the reason's own words, saying "Paused" where they do not. */
export function triggerNoticeText(t: InboxTrigger): string {
  const text = inboxTriggerText(t);
  return t.reason === 'PAUSED' && !text.startsWith('Paused')
    ? `Paused: ${text.charAt(0).toLowerCase()}${text.slice(1)}`
    : text;
}

/** How long an item has waited, short, for its meta line: "waiting 12 min". */
export function waitedText(since: string, now = Date.now()): string {
  const ms = now - new Date(since).getTime();
  if (Number.isNaN(ms)) return 'waiting';
  const minutes = Math.floor(ms / 60_000);
  if (minutes < 1) return 'waiting under a minute';
  if (minutes < 60) return `waiting ${minutes} min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `waiting ${hours} h`;
  return `waiting ${plural(Math.floor(hours / 24), 'day')}`;
}

/** What a row collapses to once the reader acted on it: "Answered · 2.4 · Analyst goes on". */
export interface InboxDoneView {
  readonly label: string;
  /** What was decided or done, in bold. */
  readonly detail: string;
  /** What happens next. */
  readonly after: string;
}

/** A decision as the reader decided it, from the server's answer. */
export function decidedView(d: Decision): InboxDoneView {
  const agent = d.agentName ?? 'The agent';
  const label = decidedLabel(d);
  if (d.kind === 'QUESTION') {
    return {
      label,
      detail: [d.option, d.answer ? `“${d.answer}”` : null].filter(Boolean).join(', '),
      after: `${agent} goes on once no question is open`,
    };
  }
  const s = d.subject;
  const detail =
    s?.type === 'TOOL_CALL'
      ? toolCallWhat(s)
      : s?.type === 'REPOSITORY'
        ? `Repository ${s.repositoryName ?? ''}`.trim()
        : s?.type === 'HOST'
          ? `Network host ${s.host ?? ''}`.trim()
          : s?.type === 'CHANGE_REQUEST'
            ? `A change request${s.repositoryName ? ` on ${s.repositoryName}` : ''}`
            : s?.type === 'RESULT'
              ? 'Its result'
              : decisionTitle(d);
  if (s?.type === 'RESULT') {
    return {
      label,
      detail,
      after:
        d.outcome === 'DENIED'
          ? `${agent} gets your note and works on it again`
          : 'It is written to the issue',
    };
  }
  return {
    label,
    detail,
    after:
      d.outcome === 'DENIED'
        ? `${agent} is told and goes on without it`
        : d.note
          ? `${agent} goes on with it and your note`
          : `${agent} goes on with it`,
  };
}

/** A Connect card the reader settled: the account it acts with now. */
export function boundView(item: InboxItem, binding: ConversationBinding): InboxDoneView {
  return {
    label: 'Connected',
    detail: binding.text || binding.systemName || 'The account',
    after: `${agent(item)} goes on`,
  };
}

/** A disabled credential the reader enabled again: what waited on it goes on. */
export function enabledView(item: InboxItem): InboxDoneView {
  const a = item.account;
  return {
    label: 'Enabled',
    detail: a?.credential?.name ?? 'The credential',
    after: `${plural(a?.conversations ?? 0, 'conversation')} ${a?.conversations === 1 ? 'goes' : 'go'} on`,
  };
}

/** A failed or stopped turn the reader queued again. */
export function retriedView(item: InboxItem, goesOn: boolean): InboxDoneView {
  return {
    label: 'Queued again',
    detail: item.conversation?.title ?? 'Its turn',
    after: goesOn ? `${agent(item)} goes on from where it stopped` : `${agent(item)} tries again`,
  };
}

function agent(item: InboxItem): string {
  return agentOf(item) ?? 'The agent';
}

/** The kinds the counts name, by kind: what the inbox holds beyond the 100 of each it lists. */
const COUNTED: Partial<Record<InboxItemKind, keyof InboxCounts>> = {
  DECISION: 'decisions',
  BINDING: 'bindings',
  ACCOUNT: 'accounts',
  FAILED: 'failed',
  WAITING: 'waiting',
};

/** How many items of a group the inbox holds but did not list (at most 100 of each kind). */
export function unlisted(
  group: InboxGroup,
  items: readonly InboxItem[],
  counts: InboxCounts,
): number {
  let more = 0;
  for (const [kind, key] of Object.entries(COUNTED) as [InboxItemKind, keyof InboxCounts][]) {
    if (INBOX_KIND_GROUPS[kind] !== group) continue;
    const listed = items.filter((i) => i.kind === kind).length;
    more += Math.max(0, (counts[key] ?? 0) - listed);
  }
  return more;
}

/**
 * An inbox's binding as the Connect card takes it (`ConversationBinding`), with what the reader's
 * accounts say of its system: its kind (the token it takes) and its grants by id (who lends what,
 * for CHOOSE and PICK_GRANT). The inbox's binding names the grants only by id (an API gap).
 */
export function inboxBindingCard(
  binding: InboxBinding,
  account: MyAccount | undefined,
): ConversationBinding {
  const choices: ConversationBindingChoice[] = binding.choices.map((grantId) => {
    const grant = account?.grants.find((g) => g.grantId === grantId);
    return grant
      ? {
          grantId,
          groupId: grant.groupId,
          groupName: grant.groupName,
          credentialName: grant.credentialName,
          access: grant.access,
        }
      : { grantId, access: 'READ' };
  });
  const system = binding.systemName ?? account?.system.name ?? 'The system';
  return {
    id: binding.id,
    systemId: binding.systemId,
    systemName: binding.systemName ?? account?.system.name ?? null,
    systemKind: account?.system.kind ?? null,
    state: 'WAITING',
    reason: binding.reason,
    text: `${system}: waiting for you`,
    choices,
  };
}

/** "Unlink Slack user Jane Doe (U04ABC123)": the button's name, from what the item names. */
export function unlinkLabel(item: InboxItem): string {
  const chat = item.chat;
  const name = chat?.systemName ?? null;
  const who =
    chat?.chatUserName && chat.chatUserId
      ? `${chat.chatUserName} (${chat.chatUserId})`
      : (chat?.chatUserName ?? chat?.chatUserId ?? null);
  if (who) return `Unlink ${name ?? 'the chat'} user ${who}`;
  return `Unlink the ${name ?? 'chat'} account`;
}
