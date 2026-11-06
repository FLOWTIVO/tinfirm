import type {
  Account,
  AgentEffort,
  AgentRepositoryAccess,
  AgentRuntime,
  AgentToolMode,
  ModelSubscription,
  RunnerHostScope,
  SystemKind,
  ToolEffect,
} from '../api/generated/models';

/**
 * The words of the design's nouns (plan §2): runtimes, systems, access, tool modes and effects,
 * runner hosts and subscriptions, as every page says them.
 */

/** "1 question", "3 questions". */
export function plural(n: number, one: string, many = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`;
}

/** The agent runtimes (ADR-0041). */
export const RUNTIME_LABELS: Record<AgentRuntime, string> = {
  CLAUDE_CODE: 'Claude Code',
  CODEX: 'Codex',
  PI: 'pi',
  OPENCODE: 'OpenCode',
};

/** How much the runtime thinks; `DEFAULT` leaves it to the runtime and its model. */
export const EFFORT_LABELS: Record<AgentEffort, string> = {
  DEFAULT: 'The runtime’s default',
  LOW: 'Low',
  MEDIUM: 'Medium',
  HIGH: 'High',
  XHIGH: 'Extra high',
  MAX: 'Maximum',
};

/** The kinds of systems (PLATFORM.md §5.1). */
export const SYSTEM_KIND_LABELS: Record<SystemKind, string> = {
  JIRA_DC: 'Jira Data Center',
  JIRA_CLOUD: 'Jira Cloud',
  GITHUB: 'GitHub',
  GITLAB: 'GitLab',
  GIT: 'Git',
  MCP: 'MCP server',
  SLACK: 'Slack',
  TEAMS: 'Microsoft Teams',
  BUZZ: 'Buzz',
  WEBHOOK: 'Webhook',
};

/** A repository's or a grant's access, as an adjective: "GitHub: via Analysts, read-only". */
export const ACCESS_LABELS: Record<AgentRepositoryAccess, string> = {
  READ: 'read-only',
  WRITE: 'read and write',
};

/** A tool's mode on an agent (PLATFORM.md §10): allowed, or each call waits for an approval. */
export const TOOL_MODE_LABELS: Record<AgentToolMode, string> = {
  ALLOW: 'Allowed',
  ASK: 'Needs your approval',
};

/** A tool's effect on its system; an unconfirmed effect counts as a write. */
export const TOOL_EFFECT_LABELS: Record<ToolEffect, string> = {
  READ: 'Reads',
  WRITE: 'Writes',
  UNKNOWN: 'Unconfirmed: counts as a write',
};

/** Whose runner host it is (package 2). */
export const RUNNER_HOST_SCOPE_LABELS: Record<RunnerHostScope, string> = {
  COMPANY: 'a company host',
  PERSONAL: 'a personal host',
};

/** The kinds of model access a personal runner host reports, by what the person calls them. */
export const SUBSCRIPTION_KIND_LABELS: Record<ModelSubscription['kind'], string> = {
  CLAUDE_LOGIN: 'Claude subscription',
  CODEX_CHATGPT_AUTH: 'ChatGPT subscription',
  ANTHROPIC_API_KEY: 'Anthropic API key',
  OPENAI_API_KEY: 'OpenAI API key',
};

const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

/** "$38.20". */
export function formatUsd(value: number | null | undefined): string {
  return value === null || value === undefined || !Number.isFinite(value) ? '—' : usd.format(value);
}

const day = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short' });

/** "1 Nov": the day a budget resets or a wait ends. */
export function formatDay(iso: string | null | undefined): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : day.format(date);
}

/** How an account was connected, after "connected …" (package 1). */
export const ACCOUNT_KIND_LABELS: Record<Account['kind'], string> = {
  TOKEN: 'with a token',
  OAUTH: 'by signing in',
  CLIENT: 'with client credentials',
  LINK: 'from a chat',
};

/** What a runner host last reported of a subscription on it (package 2). */
export const SUBSCRIPTION_STATUS_LABELS: Record<ModelSubscription['status'], string> = {
  AVAILABLE: 'Available',
  COOLDOWN: 'Cooling down',
  INVALID: 'Invalid',
  OFFLINE: 'Host offline',
};

/** The chip of a subscription's status: success, warning, destructive, or neutral while offline. */
export const SUBSCRIPTION_STATUS_TONES: Record<ModelSubscription['status'], string> = {
  AVAILABLE: 'border-success/40 bg-success/10 text-success',
  COOLDOWN: 'border-warning/40 bg-warning/10 text-warning',
  INVALID: 'border-destructive/40 bg-destructive/10 text-destructive',
  OFFLINE: 'bg-card text-muted-foreground',
};

/** "30d" → "30 days", "1mo" → "month", "12h" → "12 hours": a budget's period after "per". */
export function formatPeriod(period: string | null | undefined): string {
  const match = /^([1-9][0-9]{0,3})(h|d|w|mo)$/.exec(period ?? '');
  if (!match) return period ?? '';
  const n = Number(match[1]);
  const unit = { h: 'hour', d: 'day', w: 'week', mo: 'month' }[match[2] as 'h' | 'd' | 'w' | 'mo'];
  return n === 1 ? unit : `${n} ${unit}s`;
}
