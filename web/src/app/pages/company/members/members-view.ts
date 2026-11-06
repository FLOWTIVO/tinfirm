import type { Member, MemberAccount, ModelAccessView, Role } from '../../../api/generated/models';
import { formatDay, formatPeriod, formatUsd } from '../../../shared/labels';
import { isChatKind } from '../systems/system-kinds';

/**
 * The words of Members (plan §5): the last owner, the list's filters, a member's accounts as others
 * may see them (I4: which system, and its status, never more), what a change of role grants or takes
 * away, and a member's budget. Pure functions, tested as tables.
 */

/**
 * The user id of the company's only owner, when every member is loaded and exactly one is an
 * owner: their role cannot change and they cannot be removed until someone else is an owner (the
 * server's `409 LAST_OWNER`, said before it is asked). Null otherwise.
 */
export function onlyOwnerId(members: readonly Member[], complete: boolean): string | null {
  if (!complete) return null;
  const owners = members.filter((member) => member.role === 'OWNER');
  return owners.length === 1 ? owners[0].userId : null;
}

// --- The list's search and filters -----------------------------------------------------------

export type MemberFilter = 'all' | Role | 'suspended';

export const MEMBER_FILTERS: readonly { value: MemberFilter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'OWNER', label: 'Owners' },
  { value: 'ADMIN', label: 'Admins' },
  { value: 'MEMBER', label: 'Members' },
  { value: 'VIEWER', label: 'Viewers' },
  { value: 'suspended', label: 'Suspended' },
];

export function isMemberFilter(value: string): value is MemberFilter {
  return MEMBER_FILTERS.some((f) => f.value === value);
}

/** Whether a member is shown under a filter and a search by name or email, ignoring case. */
export function matchesMember(member: Member, filter: MemberFilter, query: string): boolean {
  const inFilter =
    filter === 'all' || (filter === 'suspended' ? member.suspended : member.role === filter);
  if (!inFilter) return false;
  const q = query.trim().toLowerCase();
  return (
    !q || member.displayName.toLowerCase().includes(q) || member.email.toLowerCase().includes(q)
  );
}

/** How many members each filter shows, before the search. */
export function filterCounts(members: readonly Member[]): Record<MemberFilter, number> {
  return Object.fromEntries(
    MEMBER_FILTERS.map((f) => [
      f.value,
      members.filter((m) => matchesMember(m, f.value, '')).length,
    ]),
  ) as Record<MemberFilter, number>;
}

/** What the list says when the search and the filter leave nobody. */
export function emptyTitle(filter: MemberFilter, query: string): string {
  return filter === 'suspended' && !query.trim() ? 'Nobody is suspended.' : 'No member matches.';
}

// --- Accounts -----------------------------------------------------------------------------------

export type AccountTone = 'ok' | 'warning' | 'destructive';

/** A member's account on a company system, as an admin reads it. */
export const MEMBER_ACCOUNT_STATUS: Record<
  MemberAccount['status'],
  { label: string; tone: AccountTone }
> = {
  OK: { label: 'Connected', tone: 'ok' },
  RECONNECT: { label: 'Must sign in again', tone: 'warning' },
  ERROR: { label: 'Not working', tone: 'destructive' },
};

/** Whether the account is on a chat system: a link an admin may remove (package 5a). */
export function isChatAccount(account: MemberAccount): boolean {
  return !!account.systemKind && isChatKind(account.systemKind);
}

/** A member's account in their dialog: a chat system's account in order is linked. */
export function accountStatus(account: MemberAccount): { label: string; tone: AccountTone } {
  return account.status === 'OK' && isChatAccount(account)
    ? { label: 'Linked', tone: 'ok' }
    : MEMBER_ACCOUNT_STATUS[account.status];
}

const WORST: Record<MemberAccount['status'], number> = { ERROR: 0, RECONNECT: 1, OK: 2 };

/**
 * "Jira must sign in again · GitHub connected": a member's accounts in one line of the list, those
 * that need the member first, in the tone of the worst; status only (I4).
 */
export function accountsLine(accounts: readonly MemberAccount[]): {
  text: string;
  tone: 'muted' | 'warning' | 'destructive';
} {
  if (accounts.length === 0) return { text: 'No account yet', tone: 'muted' };
  const sorted = [...accounts].sort((a, b) => WORST[a.status] - WORST[b.status]);
  const text = sorted
    .map((a) => `${a.systemName} ${accountStatus(a).label.toLowerCase()}`)
    .join(' · ');
  const tone =
    sorted[0].status === 'ERROR'
      ? 'destructive'
      : sorted[0].status === 'RECONNECT'
        ? 'warning'
        : 'muted';
  return { text, tone };
}

// --- Roles --------------------------------------------------------------------------------------

const RANK: Record<Role, number> = { OWNER: 3, ADMIN: 2, MEMBER: 1, VIEWER: 0 };
const WITH_ARTICLE: Record<Role, string> = {
  OWNER: 'an owner',
  ADMIN: 'an admin',
  MEMBER: 'a member',
  VIEWER: 'a viewer',
};
const SHORT: Record<Role, string> = {
  OWNER: 'owner',
  ADMIN: 'admin',
  MEMBER: 'member',
  VIEWER: 'viewer',
};

/** What a change of role asks before it applies: the title, what it grants or takes away, the button. */
export interface RoleChange {
  readonly title: string;
  readonly lead: string;
  /** `keep`: what stays or is granted (a check); otherwise what is taken away. */
  readonly lines: readonly { text: string; keep: boolean }[];
  readonly confirm: string;
  /** A role with fewer rights: its button is the destructive tint. */
  readonly lowering: boolean;
}

export function roleChange(from: Role, to: Role, name: string, self: boolean): RoleChange {
  const lowering = RANK[to] < RANK[from];
  const title = self ? `Make yourself ${WITH_ARTICLE[to]}?` : `Make ${name} ${WITH_ARTICLE[to]}?`;
  const confirm = self ? `Make me ${WITH_ARTICLE[to]}` : `Make ${SHORT[to]}`;
  const who = self ? 'You' : name;
  const keeps = self ? 'keep' : 'keeps';
  const lead = lowering
    ? self
      ? 'What changes for you'
      : 'What changes'
    : `What it grants ${self ? 'you' : name}`;
  const their = self ? 'your' : 'their';
  const grant = (...texts: string[]) => texts.map((text) => ({ text, keep: true }));
  // From VIEWER, every role takes one of the licence's seats (ADR-0077); to VIEWER frees it.
  const work =
    'Talks to agents, defines agents and triggers, answers and decides what is asked of them, and connects their own accounts and runner host.';
  const seat = from === 'VIEWER' ? ['Takes one of the licence’s seats.'] : [];
  let lines: { text: string; keep: boolean }[];
  if (to === 'VIEWER') {
    lines = [
      {
        text: `${who} ${keeps} reading the conversations and results shared with ${their} groups, and ${their} inbox. A viewer holds no seat.`,
        keep: true,
      },
      {
        text: self
          ? 'You no longer start or continue conversations, own or edit agents, author triggers, or answer and decide requests.'
          : `${name} no longer starts or continues conversations, owns or edits agents, authors triggers, or answers and decides requests.`,
        keep: false,
      },
      ...(from === 'MEMBER'
        ? []
        : [
            {
              text: self
                ? 'You no longer set the company up: the Company pages close for you at once, this one too.'
                : `${name} no longer sets the company up: the Company pages close.`,
              keep: false,
            },
          ]),
    ];
  } else if (to === 'OWNER') {
    lines = grant(
      ...seat,
      ...(from === 'VIEWER' ? [work] : []),
      ...(from === 'MEMBER' || from === 'VIEWER'
        ? ['Everything an admin does: sets the company up, and adds, suspends and removes members.']
        : []),
      'How long the company keeps its content, and who else is an owner.',
      'Reads every member’s conversations in full; each read is recorded and shown to them.',
    );
  } else if (to === 'ADMIN' && !lowering) {
    lines = grant(
      ...seat,
      ...(from === 'VIEWER' ? [work] : []),
      'Sets the company up: systems and their credentials, service accounts, groups, budgets, runner hosts and the model gateway.',
      'Adds, suspends and removes members and changes their roles, except an owner’s.',
      'Sees every agent in the company and reads the audit log.',
    );
  } else if (to === 'MEMBER' && !lowering) {
    lines = grant(...seat, work);
  } else if (to === 'ADMIN') {
    lines = [
      {
        text: `${who} ${keeps} setting the company up and managing its members, except owners.`,
        keep: true,
      },
      {
        text: self
          ? 'You no longer manage the company’s retention or who else is an owner, and no longer read everyone’s conversations.'
          : `${name} no longer manages the company’s retention or who else is an owner, and no longer reads everyone’s conversations.`,
        keep: false,
      },
    ];
  } else {
    const what = from === 'OWNER' ? ' or manage its retention' : '';
    const whatThird = from === 'OWNER' ? ' or manages its retention' : '';
    lines = [
      {
        text: `${who} ${keeps} talking to agents, defining agents and triggers, and connecting ${their} own accounts and runner host.`,
        keep: true,
      },
      {
        text: self
          ? `You no longer set the company up${what}: the Company pages close for you at once, this one too.`
          : `${name} no longer sets the company up${whatThird}: the Company pages close.`,
        keep: false,
      },
    ];
  }
  return { title, lead, lines, confirm, lowering };
}

// --- Budget and dates ---------------------------------------------------------------------------

/** "The default, $50.00 per 30 days · resets 12 Nov": a member's budget, from its model access. */
export interface BudgetLine {
  readonly whose: string;
  readonly amount: string;
  readonly period: string;
  readonly resets: string | null;
}

export function budgetLine(access: ModelAccessView): BudgetLine {
  return {
    whose: access.budgetOverridden ? 'Their own' : 'The default',
    amount: formatUsd(access.budgetUsd),
    period: formatPeriod(access.budgetPeriod),
    resets: formatDay(access.budgetResetsAt),
  };
}

/** Three letters each, as the design writes them ("Sep", where en-GB now says "Sept"). */
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** "21 Sep" this year, "21 Sep 2025" before: the day a member was added. */
export function addedOn(iso: string, now: Date = new Date()): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  const day = `${date.getDate()} ${MONTHS[date.getMonth()]}`;
  return date.getFullYear() === now.getFullYear() ? day : `${day} ${date.getFullYear()}`;
}
