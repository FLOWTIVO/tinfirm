import {
  lucideBlocks,
  lucideFolderGit2,
  lucideMessagesSquare,
  lucidePlug,
  lucideTicket,
} from '@ng-icons/lucide';
import type { ExternalSystem, SystemKind } from '../../../api/generated/models';
import { SYSTEM_KIND_LABELS } from '../../../shared/labels';

/**
 * What differs between the kinds of system (PLATFORM.md §5.1; the web plan §5, Systems): the base
 * URL, whether it holds repositories, its tools (a tracker's fixed ones, an MCP server's
 * catalogue), its app and the token an account takes. The server decides; this lets the pages ask
 * only for what a kind takes and say early what is wrong.
 */
export interface SystemKindInfo {
  readonly value: SystemKind;
  /** "Jira Data Center". */
  readonly label: string;
  /** What a system of this kind is, under its choice. */
  readonly description: string;
  readonly group: SystemGroup;
  /** The base URL's field, or null for a kind that takes none (GIT). */
  readonly baseUrl: {
    readonly label: string;
    readonly placeholder: string;
    readonly hint: string;
  } | null;
  /** Its repositories, added by URL; null for a kind that holds none. */
  readonly repositories: { readonly placeholder: string; readonly hint: string } | null;
  /** A tracker's fixed tools, an MCP server's catalogue, or none. */
  readonly tools: 'fixed' | 'catalogue' | null;
  /**
   * Accounts on it may sign in with an app (OAuth); GIT takes tokens only. Slack's app is for the
   * sign-ins of its `history` tool only, with fixed scopes.
   */
  readonly app: boolean;
  /** Where the app is registered, after "Register an OAuth app at …". */
  readonly appWhere: string;
  /** MCP only: the token may go in a header of the server's own name. */
  readonly authHeader: boolean;
  /** What an account's token is, where it is pasted. */
  readonly tokenHint: string;
  /** Registered on the Systems page; Buzz is parked. */
  readonly registrable: boolean;
  /**
   * Accounts connect on it by a token or a sign-in. A chat's accounts are its bot, set on its
   * page, and its members' links, made from the chat (package 5a).
   */
  readonly accounts: boolean;
}

/** The sections of the Systems page, in order. */
export type SystemGroup = 'tracker' | 'code' | 'mcp' | 'chat' | 'webhook' | 'other';

export const SYSTEM_GROUP_LABELS: Record<SystemGroup, string> = {
  tracker: 'Trackers',
  code: 'Code hosts',
  mcp: 'MCP servers',
  chat: 'Chats',
  webhook: 'Webhooks',
  other: 'Other',
};

const NOT_YET = {
  baseUrl: null,
  repositories: null,
  tools: null,
  app: false,
  appWhere: '',
  authHeader: false,
  tokenHint: '',
  registrable: false,
  accounts: false,
} as const;

export const SYSTEM_KINDS: readonly SystemKindInfo[] = [
  {
    value: 'JIRA_DC',
    label: SYSTEM_KIND_LABELS.JIRA_DC,
    description:
      'Issues of a Jira Data Center or Server, read and written with each person’s token.',
    group: 'tracker',
    baseUrl: {
      label: 'Base URL',
      placeholder: 'https://jira.example.com',
      hint: 'The address you open Jira at, without /rest.',
    },
    repositories: null,
    tools: 'fixed',
    app: true,
    appWhere: 'Jira (Administration › Applications › Application links)',
    authHeader: false,
    tokenHint: 'A personal access token of the Jira user.',
    registrable: true,
    accounts: true,
  },
  {
    value: 'JIRA_CLOUD',
    label: SYSTEM_KIND_LABELS.JIRA_CLOUD,
    description: 'Issues of a Jira Cloud site (yoursite.atlassian.net).',
    group: 'tracker',
    baseUrl: {
      label: 'Site URL',
      placeholder: 'https://acme.atlassian.net',
      hint: 'The address of your Jira Cloud site.',
    },
    repositories: null,
    tools: 'fixed',
    app: true,
    appWhere: 'the Atlassian developer console',
    authHeader: false,
    tokenHint:
      'The Atlassian account’s email and an API token, joined by a colon: email:api-token.',
    registrable: true,
    accounts: true,
  },
  {
    value: 'GITHUB',
    label: SYSTEM_KIND_LABELS.GITHUB,
    description: 'Issues, pull requests and repositories on github.com or GitHub Enterprise.',
    group: 'code',
    baseUrl: {
      label: 'API URL',
      placeholder: 'https://api.github.com',
      hint: 'https://api.github.com, or https://host/api/v3 on GitHub Enterprise.',
    },
    repositories: {
      placeholder: 'https://github.com/acme/shop',
      hint: 'On the system’s host, as https://host/owner/repo.',
    },
    tools: 'fixed',
    app: true,
    appWhere: 'GitHub (Settings › Developer settings › OAuth Apps)',
    authHeader: false,
    tokenHint: 'A personal access token of the GitHub user.',
    registrable: true,
    accounts: true,
  },
  {
    value: 'GITLAB',
    label: SYSTEM_KIND_LABELS.GITLAB,
    description: 'Issues, merge requests and projects on gitlab.com or a self-managed GitLab.',
    group: 'code',
    baseUrl: {
      label: 'Instance URL',
      placeholder: 'https://gitlab.com',
      hint: 'The instance’s address; Tinfirm adds /api/v4.',
    },
    repositories: {
      placeholder: 'https://gitlab.com/acme/shop',
      hint: 'On the system’s host, as https://host/group/project.',
    },
    tools: 'fixed',
    app: true,
    appWhere: 'GitLab (User settings › Applications)',
    authHeader: false,
    tokenHint: 'A personal access token of the GitLab user.',
    registrable: true,
    accounts: true,
  },
  {
    value: 'GIT',
    label: SYSTEM_KIND_LABELS.GIT,
    description: 'Repositories on any https git host, cloned and pushed with a token.',
    group: 'code',
    baseUrl: null,
    repositories: {
      placeholder: 'https://git.example.com/acme/shop.git',
      hint: 'Any https address the token can clone and push.',
    },
    tools: null,
    app: false,
    appWhere: '',
    authHeader: false,
    tokenHint: 'A token that clones and pushes the repositories.',
    registrable: true,
    accounts: true,
  },
  {
    value: 'MCP',
    label: SYSTEM_KIND_LABELS.MCP,
    description:
      'A remote MCP server: Tinfirm reads its tools into a catalogue, and you confirm what each one does.',
    group: 'mcp',
    baseUrl: {
      label: 'MCP endpoint',
      placeholder: 'https://tools.example.com/mcp',
      hint: 'The URL the server answers MCP Streamable HTTP at.',
    },
    repositories: null,
    tools: 'catalogue',
    app: true,
    appWhere: 'the server’s authorization server',
    authHeader: true,
    tokenHint: 'The token the server takes.',
    registrable: true,
    accounts: true,
  },
  {
    // Its one tool, history, reads with a person's own Slack sign-in through the app (open point
    // 13); its bot and its members' links are set on its page, never as accounts here.
    ...NOT_YET,
    value: 'SLACK',
    label: SYSTEM_KIND_LABELS.SLACK,
    description:
      'A Slack workspace: members message Tinfirm’s bot directly, and agents answer as them.',
    group: 'chat',
    tools: 'fixed',
    app: true,
    appWhere: 'Slack (api.slack.com › Your Apps › the app › Basic Information)',
    registrable: true,
  },
  {
    ...NOT_YET,
    value: 'TEAMS',
    label: SYSTEM_KIND_LABELS.TEAMS,
    description:
      'A Microsoft Teams tenant: members chat with Tinfirm’s bot, and agents answer as them.',
    group: 'chat',
    registrable: true,
  },
  {
    ...NOT_YET,
    value: 'WEBHOOK',
    label: SYSTEM_KIND_LABELS.WEBHOOK,
    description:
      'Any other software posts its events to Tinfirm, signed; a webhook trigger’s agent acts on them.',
    group: 'webhook',
    registrable: true,
  },
  {
    ...NOT_YET,
    value: 'BUZZ',
    label: SYSTEM_KIND_LABELS.BUZZ,
    description: 'Parked.',
    group: 'other',
  },
];

const BY_VALUE = new Map(SYSTEM_KINDS.map((kind) => [kind.value, kind]));

export function systemKind(kind: SystemKind): SystemKindInfo {
  return (
    BY_VALUE.get(kind) ?? {
      ...NOT_YET,
      value: kind,
      label: kind,
      description: '',
      group: 'other',
    }
  );
}

/** The kinds the Register dialog offers, by group, in order. */
export function registrableKinds(): {
  group: SystemGroup;
  label: string;
  kinds: SystemKindInfo[];
}[] {
  return (['tracker', 'code', 'mcp', 'chat', 'webhook'] as const).map((group) => ({
    group,
    label: SYSTEM_GROUP_LABELS[group],
    kinds: SYSTEM_KINDS.filter((kind) => kind.registrable && kind.group === group),
  }));
}

/** A chat system (package 5): its bot and secrets are set on its page, its accounts are links. */
export function isChatKind(kind: SystemKind): boolean {
  return kind === 'SLACK' || kind === 'TEAMS';
}

/**
 * Slack's user scopes for `history`, fixed by the app's manifest (the package 5 contract, open
 * point 13): the server refuses others.
 */
export const SLACK_HISTORY_SCOPES = 'channels:history groups:history';

/** The Lucide icon of a system's kind, on the pages that list accounts per system. */
export function systemKindIcon(kind: SystemKind): string {
  switch (kind) {
    case 'JIRA_DC':
    case 'JIRA_CLOUD':
      return 'lucideTicket';
    case 'GITHUB':
    case 'GITLAB':
    case 'GIT':
      return 'lucideFolderGit2';
    case 'MCP':
      return 'lucideBlocks';
    case 'SLACK':
    case 'TEAMS':
      return 'lucideMessagesSquare';
    default:
      return 'lucidePlug';
  }
}

/** Every icon `systemKindIcon` names, for a component's `provideIcons`. */
export const SYSTEM_KIND_ICONS = {
  lucideBlocks,
  lucideFolderGit2,
  lucideMessagesSquare,
  lucidePlug,
  lucideTicket,
};

/** A base URL the form accepts before the server's outbound guard decides. */
export const BASE_URL_PATTERN = /^https?:\/\/[^\s/@?#]+(\/[^\s?#]*)?$/;

/** A header name: printable ASCII without whitespace or a colon. */
export const HEADER_NAME_PATTERN = /^[!-9;-~]{1,64}$/;

/** The system's status as its chip reads, and its tone (DESIGN.md: neutral, success, destructive). */
export function systemStatus(system: Pick<ExternalSystem, 'status'>): {
  label: string;
  tone: 'neutral' | 'success' | 'destructive';
} {
  switch (system.status) {
    case 'OK':
      return { label: 'Reachable', tone: 'success' };
    case 'ERROR':
      return { label: 'Unreachable', tone: 'destructive' };
    default:
      return { label: 'Not tested yet', tone: 'neutral' };
  }
}

/**
 * The callback URL to register at the system for its app: the server says it to whoever manages a
 * system whose accounts may sign in with OAuth, before the app is set as after
 * (`oauthCallbackUrl`; `app.callbackUrl` once there is an app). Null for anyone else.
 */
export function callbackUrlFor(
  system: Pick<ExternalSystem, 'app' | 'oauthCallbackUrl'>,
): string | null {
  return system.oauthCallbackUrl ?? system.app?.callbackUrl ?? null;
}

/** Who manages a system: OWNER and ADMIN a company system; its owner a personal MCP server. */
export function managesSystem(
  system: Pick<ExternalSystem, 'personal'>,
  managesCompany: boolean,
): boolean {
  return system.personal || managesCompany;
}

/**
 * The kinds whose issues Tinfirm polls for triggers (package 4; `ExternalSystem.pollIntervalSeconds`
 * is set for these and null for the others).
 */
const POLLED_KINDS: ReadonlySet<SystemKind> = new Set([
  'JIRA_DC',
  'JIRA_CLOUD',
  'GITHUB',
  'GITLAB',
]);

export function pollsIssues(kind: SystemKind | ''): boolean {
  return kind !== '' && POLLED_KINDS.has(kind);
}

/** How often a tracker is polled, in seconds: the server's range and default. */
export const POLL_INTERVAL = { min: 30, max: 3600, default: 60 } as const;

/** "every minute", "every 30 seconds", "every 5 minutes", "every hour", "every 90 seconds". */
export function pollIntervalPhrase(seconds: number): string {
  if (seconds === 3600) return 'every hour';
  if (seconds === 60) return 'every minute';
  if (seconds % 60 === 0) return `every ${seconds / 60} minutes`;
  return `every ${seconds} seconds`;
}

/** The interval as typed: null when it may be sent (empty keeps the default), else what is wrong. */
export function pollIntervalError(text: string): string | null {
  const value = text.trim();
  if (!value) return null;
  const n = /^\d+$/.test(value) ? Number(value) : NaN;
  return n >= POLL_INTERVAL.min && n <= POLL_INTERVAL.max
    ? null
    : `A whole number of seconds from ${POLL_INTERVAL.min} to ${POLL_INTERVAL.max} (an hour).`;
}

/**
 * What a system receives from agents, one sentence under its name (the threat model's accepted
 * risk for tools as data sinks): every system an agent may call tools on gets what the agent passes
 * as their arguments, a read tool's too, so what a conversation read elsewhere may reach it. An MCP
 * server not on one of the operator's internal hosts, a personal one included, is a third-party
 * server. Null for a system without tools (git, Teams, a webhook). Information only: nothing asks
 * before a call because of it.
 */
export function receivesDataSentence(
  system: Pick<ExternalSystem, 'kind' | 'receivesData' | 'internalHost'>,
): string | null {
  if (!system.receivesData) return null;
  const which =
    system.kind === 'MCP' && !system.internalHost
      ? 'this system (a third-party server)'
      : 'this system';
  return `Agents send ${which} what they pass to its tools, which can include what they read elsewhere in the conversation.`;
}
