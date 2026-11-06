import type {
  DepartureCredential,
  ErasureCounts,
  ErasureRemains,
  KillSwitch,
  KillSwitchCount,
  KillSwitchKind,
  RetentionOpenConversation,
  SpendAmounts,
  SpendDimension,
  SpendKey,
  SpendSource,
} from '../api/generated/models';
import { formatUsd, plural } from './labels';

/**
 * The words of governance (the package 6 contract; CONVENTIONS §15.11 to §15.13): kill switches,
 * suspension, a member who leaves, spend, retention, holds and erasure. Pure functions, so that every
 * state of the API is tested as a table.
 */

// --- Kill switches -------------------------------------------------------------------------------

export const KILL_SWITCH_LABELS: Record<KillSwitchKind, { one: string; many: string }> = {
  RUNS_STOPPED: { one: 'The company’s runs are stopped', many: 'The company’s runs are stopped' },
  SYSTEM_PAUSED: { one: 'paused system', many: 'paused systems' },
  MEMBER_SUSPENDED: { one: 'suspended member', many: 'suspended members' },
  SERVICE_IDENTITY_SUSPENDED: {
    one: 'suspended service account',
    many: 'suspended service accounts',
  },
  AGENT_PAUSED: { one: 'paused agent', many: 'paused agents' },
  TRIGGER_PAUSED: { one: 'paused trigger', many: 'paused triggers' },
  PERSONAL_MCP_SERVERS_OFF: {
    one: 'Members’ own MCP servers are off',
    many: 'Members’ own MCP servers are off',
  },
};

/** "2 paused systems", "the company’s runs are stopped": one count of `GET /kill-switches`. */
export function switchCountPhrase(count: KillSwitchCount): string {
  const label = KILL_SWITCH_LABELS[count.kind];
  if (count.kind === 'RUNS_STOPPED' || count.kind === 'PERSONAL_MCP_SERVERS_OFF') return label.one;
  return `${count.count} ${count.count === 1 ? label.one : label.many}`;
}

/** What stays on after the lift of the company's stop, in one sentence; null when nothing does. */
export function stillOnPhrase(stillOn: readonly KillSwitchCount[]): string | null {
  const parts = stillOn.filter((c) => c.kind !== 'RUNS_STOPPED' && c.count > 0);
  if (parts.length === 0) return null;
  return `Still on: ${joinWords(parts.map(switchCountPhrase))}.`;
}

/** Who pulled a switch, and when, in words: "by Adam Admin" or "" when nobody is named. */
export function pulledBy(kill: Pick<KillSwitch, 'byName'>): string {
  return kill.byName ? `by ${kill.byName}` : '';
}

/** A suspension's cause: an admin's, or the instance admin's deactivation of the user. */
export function suspensionCause(cause: string | null | undefined): string | null {
  if (cause === 'USER_DEACTIVATED') {
    return 'Their Tinfirm account was deactivated; reactivating the user resumes them.';
  }
  return null;
}

/** Where a switch's page is, under the company: the system, the member, the agent, the trigger. */
export function switchLink(kill: Pick<KillSwitch, 'kind' | 'targetId'>): string[] | null {
  switch (kill.kind) {
    case 'SYSTEM_PAUSED':
      return ['systems', kill.targetId];
    case 'SERVICE_IDENTITY_SUSPENDED':
      return ['service-identities', kill.targetId];
    case 'MEMBER_SUSPENDED':
      return ['members'];
    case 'AGENT_PAUSED':
      return ['agents', kill.targetId];
    case 'TRIGGER_PAUSED':
      return ['triggers', kill.targetId];
    case 'PERSONAL_MCP_SERVERS_OFF':
      return ['settings'];
    default:
      return null;
  }
}

// --- Spend ---------------------------------------------------------------------------------------

export const SPEND_DIMENSION_LABELS: Record<SpendDimension, string> = {
  payer: 'Who paid',
  actor: 'Who acted',
  agent: 'Agent',
  trigger: 'Trigger',
  conversation: 'Conversation',
  model: 'Model',
  period: 'Period',
};

export const SPEND_SOURCE_LABELS: Record<SpendSource, string> = {
  GATEWAY: 'The gateway’s records',
  RELAY: 'Tinfirm’s own count',
  SUBSCRIPTION: 'Subscriptions (reported, not metered)',
};

/** A row's value for one grouping, in words. */
export function spendKeyLabel(key: SpendKey): string {
  if (key.folded) return 'Their personal conversations';
  switch (key.dimension) {
    case 'conversation':
      return key.title
        ? `${key.key ?? key.label ?? ''} · ${key.title}`
        : (key.key ?? key.label ?? '—');
    case 'period':
      return key.id ?? key.label ?? '—';
    case 'trigger':
    case 'agent':
    case 'payer':
    case 'actor':
      return key.label ?? '(unnamed)';
    case 'model':
      return key.label ?? key.id ?? 'default';
  }
}

const tokens = new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 });

/** "12.4K", the tokens of a row: in, out and cache together. */
export function formatTokens(value: number): string {
  return tokens.format(value);
}

/** All tokens of a row, metered and reported. */
export function totalTokens(a: SpendAmounts): number {
  return a.inputTokens + a.outputTokens + a.cachedInputTokens + a.cacheWriteTokens;
}

/**
 * A row's cost in words: "$4.20", with the relay's share named when there is one, and the tokens of
 * subscriptions, which have no cost.
 */
export function costPhrase(a: SpendAmounts): string {
  const parts = [formatUsd(a.costUsd)];
  if (a.relayCostUsd > 0) parts.push(`${formatUsd(a.relayCostUsd)} by Tinfirm’s count`);
  if (a.subscriptionTokens > 0) {
    parts.push(`${formatTokens(a.subscriptionTokens)} subscription tokens`);
  }
  return parts.join(' · ');
}

/** The first and the next month's first day of a date, as `yyyy-mm-dd`: the default range. */
export function monthRange(today: Date): { from: string; to: string } {
  const y = today.getFullYear();
  const m = today.getMonth();
  return { from: isoDay(new Date(y, m, 1)), to: isoDay(new Date(y, m + 1, 1)) };
}

export function isoDay(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** The check the server makes, made first so the form can say it: `to` after `from`, ≤ 366 days. */
export function spendRangeError(from: string, to: string): string | null {
  const a = Date.parse(`${from}T00:00:00Z`);
  const b = Date.parse(`${to}T00:00:00Z`);
  if (Number.isNaN(a) || Number.isNaN(b)) return 'Enter both days.';
  if (b <= a) return 'The range must end after it starts.';
  if ((b - a) / 86_400_000 > 366) return 'A range covers at most 366 days.';
  return null;
}

// --- Budget alerts -------------------------------------------------------------------------------

/** "80, 100" → [80, 100]; null when it is not 1 to 3 distinct whole percentages from 1 to 100. */
export function parseThresholds(text: string): number[] | null {
  const parts = text
    .split(/[,\s]+/)
    .map((p) => p.replace(/%$/, ''))
    .filter(Boolean);
  if (parts.length < 1 || parts.length > 3) return null;
  if (!parts.every((p) => /^\d{1,3}$/.test(p))) return null;
  const values = parts.map(Number);
  if (values.some((v) => v < 1 || v > 100)) return null;
  if (new Set(values).size !== values.length) return null;
  return [...values].sort((a, b) => a - b);
}

/** [80, 100] → "80 %, 100 %". */
export function thresholdsPhrase(values: readonly number[]): string {
  return values.map((v) => `${v} %`).join(', ');
}

// --- A member who leaves -------------------------------------------------------------------------

/** The preview of a removal, one line per thing it changes; empty lines are left out. */
export function departurePreviewLines(p: {
  triggersPausing: number;
  conversationsEnding: number;
  agentsPassing: number;
  runnerHostsRemoved: number;
  accountsRemoved: number;
  groupsLeft: number;
}): string[] {
  const lines: string[] = [];
  const verb = (n: number, one: string, many: string) => (n === 1 ? one : many);
  const n = p.conversationsEnding;
  if (n) lines.push(`${plural(n, 'open conversation')} of theirs ${verb(n, 'ends', 'end')}.`);
  const t = p.triggersPausing;
  if (t) {
    lines.push(
      `${plural(t, 'trigger')} they wrote ${verb(t, 'pauses', 'pause')} until an admin reassigns ${verb(t, 'it', 'them')}.`,
    );
  }
  const a = p.agentsPassing;
  if (a) lines.push(`${plural(a, 'agent')} they own ${verb(a, 'passes', 'pass')} to the admins.`);
  const c = p.accountsRemoved;
  if (c)
    lines.push(`${plural(c, 'account')} of theirs on systems ${verb(c, 'is', 'are')} removed.`);
  const h = p.runnerHostsRemoved;
  if (h) lines.push(`${plural(h, 'personal runner host')} ${verb(h, 'is', 'are')} removed.`);
  if (p.groupsLeft) lines.push(`They leave ${plural(p.groupsLeft, 'group')}.`);
  return lines;
}

/**
 * What an admin does to a system's credential a member who left connected: a token they may have
 * made is stored again, a sign-in that may be theirs signed in again, client credentials they may
 * have created stored again (ADR-0072).
 */
export const DEPARTURE_CREDENTIAL_FIXES: Record<DepartureCredential['kind'], string> = {
  TOKEN: 'store a new token',
  OAUTH: 'sign it in again',
  CLIENT: 'store new client credentials',
};

// --- Retention, holds and erasure ----------------------------------------------------------------

export const PLACE_LABELS: Record<RetentionOpenConversation['place'], string> = {
  TINFIRM_THREAD: 'In Tinfirm',
  CHAT_THREAD: 'In a chat',
  TRACKER_ISSUE: 'On an issue',
  SCHEDULE: 'On a schedule',
  WEBHOOK: 'From a webhook',
};

/** A period in days, or "Keep" for `null`. */
export function retentionPhrase(days: number | null | undefined): string {
  if (days === null || days === undefined) return 'Kept for good';
  return plural(days, 'day');
}

const counts = new Intl.NumberFormat('en-US');

/** "1,204": a count with its thousands marked. */
export function formatCount(n: number): string {
  return counts.format(n);
}

/** "1,204 messages", "1 message": a count with its noun. */
export function countOf(n: number, one: string, many = `${one}s`): string {
  return `${counts.format(n)} ${n === 1 ? one : many}`;
}

/** The kinds the other modules count in an erasure's `other`, by their key. */
const OTHER_KINDS: Record<string, readonly [string, string]> = {
  memoryNotes: ['memory note', 'memory notes'],
  inboxDismissals: ['dismissed inbox notice', 'dismissed inbox notices'],
  systemTests: ['system test that named them', 'system tests that named them'],
  messages: ['message', 'messages'],
  decisions: ['decision', 'decisions'],
  messageAuthors: ['name on a member’s message', 'names on members’ messages'],
  titles: ['title made from their words', 'titles made from their words'],
  chatUsers: ['chat profile', 'chat profiles'],
  chatEvents: ['chat event', 'chat events'],
  chatLinks: ['sign-in link', 'sign-in links'],
};

/** A kind the app does not know, as the server named it: "chatUsers", "CHAT_USER" → "chat users". */
export function otherKindLabel(kind: string): string {
  return kind
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .toLowerCase()
    .replace(/_/g, ' ');
}

/** An `other` kind's count in words; a kind the app does not know as the server named it. */
export function otherKindWords(kind: string, n: number): string {
  const known = OTHER_KINDS[kind];
  return known ? countOf(n, known[0], known[1]) : `${counts.format(n)} ${otherKindLabel(kind)}`;
}

/** An erasure's counts in words, "2 conversations, 14 messages and …"; null when they are all zero. */
export function erasureWords(e: ErasureCounts): string | null {
  const parts = [
    e.conversations && countOf(e.conversations, 'conversation'),
    e.messages && countOf(e.messages, 'message'),
    e.decisions && countOf(e.decisions, 'decision'),
    e.outputs && countOf(e.outputs, 'output'),
    e.files && countOf(e.files, 'file'),
    e.runs && `the texts of ${countOf(e.runs, 'turn')}`,
    e.runEvents && `the transcripts of ${countOf(e.runEvents, 'turn')}`,
    ...Object.entries(e.other ?? {})
      .filter(([, n]) => n > 0)
      .map(([kind, n]) => otherKindWords(kind, n)),
  ].filter((p): p is string => !!p);
  return parts.length ? joinWords(parts) : null;
}

/** The counts an erasure answered, as a sentence of what went. */
export function erasedPhrase(e: ErasureCounts): string {
  const words = erasureWords(e);
  return words ? `Erased ${words}.` : 'Nothing was left to erase.';
}

const REMAINS_LABELS: Record<string, string> = {
  AUDIT: 'The audit log',
  SPEND: 'Spend records',
  TOMBSTONES: 'What each conversation was',
  AGENT_TEXTS: 'The agent’s own words',
  OPEN_CONVERSATIONS: 'Open conversations',
  RUNNER_SESSIONS: 'Runner hosts’ sessions',
  SOURCE_SYSTEM: 'The source system',
  USER_ACCOUNT: 'Their Tinfirm account',
};

/** A kind of what remains, in words; an unknown kind as the server named it. */
export function remainsLabel(r: Pick<ErasureRemains, 'what'>): string {
  return REMAINS_LABELS[r.what] ?? r.what.toLowerCase().replace(/_/g, ' ');
}

/** "a, b and c". */
export function joinWords(parts: readonly string[]): string {
  if (parts.length <= 1) return parts.join('');
  return `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`;
}

/**
 * A conversation's id out of what an owner pastes: its link in the app (`…/conversations/{id}`), or
 * the id itself. Null when neither.
 */
export function conversationIdOf(text: string): string | null {
  const uuid = /([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/i;
  const trimmed = text.trim();
  const fromLink = /\/conversations\/([0-9a-f-]{36})/i.exec(trimmed);
  if (fromLink) return fromLink[1].toLowerCase();
  const bare = uuid.exec(trimmed);
  return bare && bare[1].length === trimmed.length ? bare[1].toLowerCase() : null;
}
