import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import type { Run, RunFailureClass, RunStatus, RunTurnReason } from '../api/generated/models';

/** Labels and formatting of runs (the package 3 contract, §3.11): a conversation's turns on a runner host. */

export const RUN_STATUS_LABELS: Record<RunStatus, string> = {
  QUEUED: 'Queued',
  RUNNING: 'Running',
  SUCCEEDED: 'Succeeded',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
  LOST: 'Lost',
};

/** A run that may still change: it waits in the queue or runs on a host. */
export function isRunActive(status: RunStatus | null | undefined): boolean {
  return status === 'QUEUED' || status === 'RUNNING';
}

/** Why the turn was started, after "Turn 2 ·" (the package 3 contract, §5.7). */
export const TURN_REASON_PHRASES: Record<RunTurnReason, string> = {
  FIRST: 'first message',
  MESSAGE: 'a new message',
  DECIDED: 'after a decision',
  CONNECTED: 'after an account was connected',
  RETRY: 'run again',
  EVENT: 'from a trigger’s event',
  REVIEW_RETURNED: 'after a review sent it back',
};

export function turnReasonPhrase(reason: RunTurnReason | null | undefined): string {
  return reason ? (TURN_REASON_PHRASES[reason] ?? reason.toLowerCase()) : '';
}

type ResumeOutcome = NonNullable<Run['resumeOutcome']>;

/** Why a run started a new session instead of resuming the conversation's (CONVENTIONS §14.10). */
export const RESUME_OUTCOME_PHRASES: Record<ResumeOutcome, string> = {
  FIRST_RUN: 'first turn of the conversation',
  NO_SESSION: 'the earlier session was not on this runner host',
  LOAD_FAILED: 'loading the earlier session failed (see the log)',
  NOT_SUPPORTED: 'the runtime cannot resume sessions',
};

/** The failure classes of runs (`RunFailureClass`), for a title on the class. */
export const FAILURE_CLASS_LABELS: Record<RunFailureClass, string> = {
  USAGE_LIMIT: 'Usage limit reached',
  SERVER_LIMITED: 'Provider overloaded',
  CONTEXT_OVERFLOW: 'Context window full',
  LOGIN_EXPIRED: 'Subscription login expired',
  VENDOR_BLOCKED: 'Blocked by the provider',
  CREDENTIAL_LEAK: 'Stopped: credential in output',
  PROTOCOL_ERROR: 'Protocol error',
  GUARD_STOPPED: 'Stopped by a guard',
  TIME_LIMIT: 'Time limit reached',
  GIT_ERROR: 'Git error',
  INFRA: 'Infrastructure error',
  RUNNER_UNAVAILABLE: 'Runner unavailable',
  MODEL_REFUSED: 'Model refused',
  LOST: 'Runner host stopped reporting',
  RUNTIME_REMOVED: 'The agent no longer lists this runtime',
  MODEL_NOT_ALLOWED: 'The model is no longer allowed',
  MODEL_REQUIRED: 'No model chosen',
  MODEL_NOT_SERVED: 'The gateway does not serve the model',
};

/**
 * A turn the runner host stopped at the agent's time limit: not a failure in words, since its
 * session and its work so far are kept and Retry goes on from where it stopped.
 */
export function stoppedAtTimeLimit(failureClass: string | null | undefined): boolean {
  return failureClass === 'TIME_LIMIT';
}

/** The class in words; a class newer than this client stays as the server named it. */
export function failureClassLabel(failureClass: string | null | undefined): string | null {
  return failureClass
    ? (FAILURE_CLASS_LABELS[failureClass as RunFailureClass] ?? failureClass)
    : null;
}

/**
 * Whether a finished run resumed the agent's session of the conversation's previous turn; null when
 * that is not known (the run has not finished, ended on the server, or its runner host did not
 * report it).
 */
export function resumeText(run: Pick<Run, 'resumed' | 'resumeOutcome'>): string | null {
  if (run.resumed === true) return 'Resumed its earlier session';
  if (run.resumed !== false) return null;
  return run.resumeOutcome
    ? `Started a new session: ${RESUME_OUTCOME_PHRASES[run.resumeOutcome] ?? run.resumeOutcome}`
    : 'Ended before it opened a session';
}

// --- Formatting -----------------------------------------------------------------------------

const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });
const whole = new Intl.NumberFormat('en');

/** 1234 → "1,234", 12345 → "12.3K". */
export function formatTokens(value: number | null | undefined): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  return value < 10_000 ? whole.format(value) : compact.format(value);
}

/**
 * A run's token usage (`Run.usage`, or a USAGE event's payload). Cache reads and writes are
 * input tokens priced apart from the uncached input, and the parts add up to the run's total.
 * Without `cacheWriteTokens` (a v1 runner host, or a run from before they were reported), the
 * input includes the cache writes.
 */
export interface TokenUsage {
  readonly inputTokens?: number;
  readonly outputTokens?: number;
  readonly cachedInputTokens?: number | null;
  readonly cacheWriteTokens?: number | null;
  readonly estimated?: boolean | null;
}

const reported = (value: number | null | undefined): value is number =>
  value !== null && value !== undefined;

/** "1,200 in · 800 cache read · 300 cache write · 340 out"; a count not reported is left out. */
export function formatUsage(u: TokenUsage): string {
  return [
    `${formatTokens(u.inputTokens ?? 0)} in`,
    reported(u.cachedInputTokens) ? `${formatTokens(u.cachedInputTokens)} cache read` : null,
    reported(u.cacheWriteTokens) ? `${formatTokens(u.cacheWriteTokens)} cache write` : null,
    `${formatTokens(u.outputTokens ?? 0)} out`,
  ]
    .filter((part) => part !== null)
    .join(' · ');
}

/** The counts spelled out, for a tooltip; says when the input includes the cache writes. */
export function usageTitle(u: TokenUsage): string {
  const writes = reported(u.cacheWriteTokens);
  return [
    writes
      ? `Uncached input ${formatTokens(u.inputTokens ?? 0)}`
      : `Input ${formatTokens(u.inputTokens ?? 0)}, cache writes included`,
    reported(u.cachedInputTokens) ? `cache read ${formatTokens(u.cachedInputTokens)}` : null,
    writes ? `cache write ${formatTokens(u.cacheWriteTokens)}` : null,
    `output ${formatTokens(u.outputTokens ?? 0)} tokens${u.estimated ? ' (estimated split)' : ''}`,
  ]
    .filter((part) => part !== null)
    .join(' · ');
}

export function formatCost(value: number | null | undefined): string | null {
  if (value === null || value === undefined || !Number.isFinite(value)) return null;
  return `$${value < 1 ? value.toFixed(3) : value.toFixed(2)}`;
}

/** "42s", "3m 05s", "1h 02m". */
export function formatDuration(ms: number): string {
  if (!Number.isFinite(ms) || ms < 0) return '—';
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}m ${String(s % 60).padStart(2, '0')}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${String(m % 60).padStart(2, '0')}m`;
}

/** Duration between two timestamps; a run that has not finished counts up to `now`. */
export function runDuration(
  startedAt: string | null | undefined,
  finishedAt: string | null | undefined,
  now: number = Date.now(),
): string | null {
  if (!startedAt) return null;
  const start = Date.parse(startedAt);
  const end = finishedAt ? Date.parse(finishedAt) : now;
  if (Number.isNaN(start) || Number.isNaN(end)) return null;
  return formatDuration(end - start);
}

/** The first 7 characters of a commit id. */
export function shortSha(sha: string | null | undefined): string {
  return (sha ?? '').slice(0, 7);
}

/** "1 commit", "3 commits". */
export function commitCount(n: number): string {
  return `${n} commit${n === 1 ? '' : 's'}`;
}

/**
 * "owner/repo#12" for a GitHub pull request URL, "group/subgroup/project!12" for a GitLab merge
 * request URL, else the URL.
 */
export function changeRequestLabel(url: string): string {
  const github = /github\.com\/([^/]+\/[^/]+)\/pull\/(\d+)/.exec(url);
  if (github) return `${github[1]}#${github[2]}`;
  const gitlab = /^https:\/\/[^/]+\/(.+?)\/(?:-\/)?merge_requests\/(\d+)\/?(?:[?#].*)?$/.exec(url);
  if (gitlab) return `${gitlab[1]}!${gitlab[2]}`;
  return url;
}

// --- Badges ---------------------------------------------------------------------------------

const RUN_TONE: Record<RunStatus, string> = {
  QUEUED: 'text-muted-foreground border-border bg-card',
  RUNNING: 'text-primary border-primary/30 bg-primary/10',
  SUCCEEDED: 'text-success border-success/40 bg-card',
  FAILED: 'text-destructive border-destructive/40 bg-card',
  CANCELLED: 'text-muted-foreground border-border bg-card',
  LOST: 'text-warning border-warning/40 bg-card',
};

/** A pulsing green dot for "an agent works on this right now"; a hollow ring while it waits. */
@Component({
  selector: 'app-live-dot',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'relative inline-flex size-2 shrink-0', 'aria-hidden': 'true' },
  template: `
    @if (queued()) {
      <span class="border-muted-foreground inline-flex size-2 rounded-full border"></span>
    } @else {
      <span
        class="bg-primary absolute inline-flex size-full animate-ping rounded-full opacity-60 motion-reduce:hidden"
      ></span>
      <span class="bg-primary relative inline-flex size-2 rounded-full"></span>
    }
  `,
})
export class LiveDot {
  readonly queued = input(false);
}

/** A run's status as a pill: green and live while running, tinted once it ended. */
@Component({
  selector: 'app-run-status-badge',
  imports: [LiveDot],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class:
      'inline-flex h-6 shrink-0 items-center gap-1.5 rounded-full border px-2.5 text-xs font-semibold whitespace-nowrap',
    '[class]': 'tone()',
    '[attr.data-status]': 'status()',
  },
  template: `
    @if (status() === 'RUNNING' || status() === 'QUEUED') {
      <app-live-dot [queued]="status() === 'QUEUED'" />
    }
    {{ label() }}
  `,
})
export class RunStatusBadge {
  readonly status = input.required<RunStatus>();
  protected readonly label = computed(() => RUN_STATUS_LABELS[this.status()]);
  protected readonly tone = computed(() => RUN_TONE[this.status()]);
}
