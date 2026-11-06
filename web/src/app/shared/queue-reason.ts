import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import type { QueueReason, QueueReasonCode, Run } from '../api/generated/models';
import { formatAbsolute, formatRelative } from './time';

/** The fields of a run that say why it waits, and where to fix it. */
export type QueuedRun = Pick<
  Run,
  'status' | 'queueReason' | 'agentId' | 'conversationId' | 'runnerHostId' | 'runnerHostName'
>;

/** Who reads the reason: the fix differs for a member and for who sets the company up. */
export interface QueueReasonReader {
  /** A member of the company: they have Accounts (their budget and runner hosts). */
  readonly member: boolean;
  /** OWNER, ADMIN or an instance admin: they reach the company's runner hosts. */
  readonly admin: boolean;
}

/** A link to the page that fixes what holds the run, under `/c/:companyId`. */
export interface QueueReasonFix {
  readonly label: string;
  readonly commands: readonly string[];
  readonly queryParams?: Record<string, string>;
  readonly fragment?: string;
}

/** A time in the reason: when a budget resets, when the host tries again, when it was last seen. */
export interface QueueReasonTime {
  readonly prefix: string;
  readonly text: string;
  readonly iso: string;
}

export interface QueueReasonView {
  readonly code: QueueReasonCode | null;
  /** The reason in words: the server's `text`, or the generic sentence before it looked. */
  readonly text: string;
  readonly time: QueueReasonTime | null;
  readonly fix: QueueReasonFix | null;
}

const clockToday = new Intl.DateTimeFormat(undefined, { hour: 'numeric', minute: '2-digit' });
const clockOtherDay = new Intl.DateTimeFormat(undefined, {
  month: 'short',
  day: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
});

function validDate(iso: string): Date | null {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** "3:00 PM (in 2 hours)"; the day is added when it is not today. */
function untilText(iso: string, now: number): string {
  const date = validDate(iso);
  if (!date) return iso;
  const sameDay = new Date(now).toDateString() === date.toDateString();
  const clock = (sameDay ? clockToday : clockOtherDay).format(date);
  return date.getTime() > now ? `${clock} (${formatRelative(date, new Date(now))})` : clock;
}

/** "in 8 seconds", or "shortly" once the time has passed. */
function soonText(iso: string, now: number): string {
  const date = validDate(iso);
  if (!date) return iso;
  return date.getTime() - now < 1000 ? 'shortly' : formatRelative(date, new Date(now));
}

function sentence(text: string): string {
  const trimmed = text.trim();
  return /[.!?]$/.test(trimmed) ? trimmed : `${trimmed}.`;
}

const RUNNER_HOST_CODES: ReadonlySet<QueueReasonCode> = new Set<QueueReasonCode>([
  'RUNNER_HOST_OFFLINE',
  'RUNNER_HOST_DRAINING',
  'RUNNER_HOST_FULL',
  'RUNNER_HOST_REJECTED',
  'PREVIOUS_RUN_ACTIVE',
  'ASSIGNED',
]);

/**
 * Where a person fixes what holds the run (plan §4.1): the agent when it is paused, the
 * conversation's Connect card for an account, Accounts › Budget for a spent or refused budget,
 * one's runner hosts for a subscription's host, and the company's runner hosts for the admins.
 * Waiting for a busy company host has nothing for a member to fix.
 */
function fixFor(
  run: QueuedRun,
  reason: QueueReason,
  reader: QueueReasonReader,
): QueueReasonFix | null {
  const ownHosts: QueueReasonFix | null = reader.member
    ? { label: 'Your runner hosts', commands: ['accounts'], queryParams: { tab: 'hosts' } }
    : null;
  const hostId = reason.runnerHostId ?? run.runnerHostId;
  const companyHosts: QueueReasonFix | null = reader.admin
    ? {
        label: 'Runner hosts',
        commands: ['runner-hosts'],
        ...(hostId ? { fragment: `host-${hostId}` } : {}),
      }
    : null;
  switch (reason.code) {
    case 'AGENT_PAUSED':
      return { label: 'Open the agent', commands: ['agents', run.agentId] };
    case 'ACCOUNT_REQUIRED':
      return {
        label: 'Connect the account',
        commands: ['conversations', run.conversationId],
        fragment: 'bindings',
      };
    case 'BUDGET_SPENT':
    case 'KEY_ERROR':
    case 'NO_MODEL_KEY':
    case 'MODEL_ACCESS_UNAVAILABLE':
      return reader.member
        ? { label: 'Your budget', commands: ['accounts'], queryParams: { tab: 'budget' } }
        : null;
    case 'HOST_OFFLINE':
    case 'CREDENTIAL_POOL_FULL':
      return ownHosts;
    // NO_HOST_FOR_SKILLS waits for a host whose runner delivers skills (ADR-0064).
    case 'NO_HOST_FOR_CREDENTIALS':
    case 'NO_RUNNER_HOST':
    case 'NO_HOST_FOR_SKILLS':
      return companyHosts ?? ownHosts;
    default:
      return RUNNER_HOST_CODES.has(reason.code) ? companyHosts : null;
  }
}

function timeFor(reason: QueueReason, now: number): QueueReasonTime | null {
  switch (reason.code) {
    case 'BUDGET_SPENT':
      return reason.retryAt
        ? { prefix: 'Resets', text: untilText(reason.retryAt, now), iso: reason.retryAt }
        : null;
    // A rejected run, and an automatic retry of a failed turn (ADR-0073), wait until their time.
    case 'RUNNER_HOST_REJECTED':
    case 'PREVIOUS_RUN_ACTIVE':
    case 'RETRY_SCHEDULED':
      return reason.retryAt
        ? { prefix: 'Tries again', text: soonText(reason.retryAt, now), iso: reason.retryAt }
        : null;
    case 'RUNNER_HOST_OFFLINE': {
      const seen = reason.lastSeenAt ? validDate(reason.lastSeenAt) : null;
      return seen && reason.lastSeenAt
        ? {
            prefix: 'Last seen',
            text: formatRelative(seen, new Date(now)),
            iso: reason.lastSeenAt,
          }
        : null;
    }
    default:
      return null;
  }
}

/**
 * Why a queued run waits (the package 3 contract, §5.7): the server's sentence, the time that
 * matters, and a link to the fix. Null for a run that is not QUEUED.
 */
export function queueReasonView(
  run: QueuedRun | null | undefined,
  reader: QueueReasonReader,
  now = Date.now(),
): QueueReasonView | null {
  if (!run || run.status !== 'QUEUED') return null;
  const reason = run.queueReason;
  if (!reason) {
    return {
      code: null,
      text: run.runnerHostName
        ? `Queued. Waiting for ${run.runnerHostName} to pick it up.`
        : 'Queued. Waiting for a runner host to pick it up.',
      time: null,
      fix: null,
    };
  }
  const text = reason.text?.trim() || reason.message?.trim() || 'Queued.';
  return {
    code: reason.code,
    text: sentence(text),
    time: timeFor(reason, now),
    fix: fixFor(run, reason, reader),
  };
}

/** The reason as plain text, e.g. for a live region; empty unless the run is QUEUED. */
export function queueReasonText(
  run: QueuedRun | null | undefined,
  now = Date.now(),
  reader: QueueReasonReader = { member: true, admin: false },
): string {
  const view = queueReasonView(run, reader, now);
  if (!view) return '';
  return view.time ? `${view.text} ${view.time.prefix} ${view.time.text}.` : view.text;
}

/** Whether the text of this reason changes with time, so it needs a clock. */
function hasTimes(reason: QueueReason | null | undefined): boolean {
  return !!(reason?.retryAt || reason?.lastSeenAt);
}

/**
 * Why a queued run waits, with the time that matters (counting) and a link to what fixes it.
 * Renders nothing for a run that is not QUEUED.
 */
@Component({
  selector: 'app-queue-reason',
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (view(); as v) {
      <span data-testid="queue-reason-text">{{ v.text }}</span>
      @if (v.time; as t) {
        {{ ' ' + t.prefix + ' '
        }}<time class="tabular-nums" [attr.datetime]="t.iso" [title]="absolute(t.iso)">{{
          t.text
        }}</time
        >.
      }
      @if (v.fix; as fix) {
        {{ ' '
        }}<a
          class="text-foreground decoration-primary/50 hover:decoration-primary focus-visible:ring-ring/50 rounded-sm font-semibold underline underline-offset-2 outline-none focus-visible:ring-3"
          data-testid="queue-reason-fix"
          [routerLink]="['/c', companyId()].concat(fix.commands)"
          [queryParams]="fix.queryParams"
          [fragment]="fix.fragment"
          >{{ fix.label }}</a
        >
      }
    }
  `,
})
export class QueueReasonText {
  readonly run = input.required<QueuedRun | null>();
  readonly companyId = input.required<string>();
  /** Who reads it: a member gets the links to their own budget and hosts, an admin the company's hosts. */
  readonly reader = input<QueueReasonReader>({ member: true, admin: false });

  private readonly now = signal(Date.now());
  protected readonly view = computed(() => queueReasonView(this.run(), this.reader(), this.now()));

  constructor() {
    // Relative times ("tries again in 8 seconds") count down while the reason is shown.
    const destroyRef = inject(DestroyRef);
    let timer: ReturnType<typeof setInterval> | null = null;
    const stop = () => {
      if (timer !== null) clearInterval(timer);
      timer = null;
    };
    effect(() => {
      const run = this.run();
      if (run?.status === 'QUEUED' && hasTimes(run.queueReason)) {
        this.now.set(Date.now());
        timer ??= setInterval(() => this.now.set(Date.now()), 1000);
      } else {
        stop();
      }
    });
    destroyRef.onDestroy(stop);
  }

  protected absolute(iso: string): string {
    return formatAbsolute(iso);
  }
}
