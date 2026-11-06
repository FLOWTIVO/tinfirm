import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCircleX, lucideClock } from '@ng-icons/lucide';
import type { ConversationSummary, ConversationWaitingOn } from '../api/generated/models';
import { stoppedAtTimeLimit } from './run-status';
import { formatAbsolute } from './time';

/**
 * What a conversation waits on, as `Conversation.waitingOn` and `waitingReason` say it, with the
 * latest turn's failure class, which tells a turn stopped at its time limit from a failed one.
 */
export type Waiting = Pick<ConversationSummary, 'waitingOn' | 'waitingReason' | 'waitingSince'> &
  Partial<Pick<ConversationSummary, 'failureClass'>>;

export type WaitingTone = 'human' | 'agent' | 'destructive' | 'muted';

/** Who a conversation waits on, as a filter offers it. */
export const WAITING_ON_FILTER_LABELS: Record<ConversationWaitingOn, string> = {
  PERSON: 'Needs a person',
  AGENT: 'Working',
  RUNNER: 'Waiting for a runner',
  NONE: 'Waiting on nobody',
};

/** `waitingReason` of a conversation waiting on its person, after "Needs you ·". */
const PERSON_REASONS: Record<string, string> = {
  QUESTION: 'Question',
  APPROVAL: 'Approval',
  REVIEW: 'Review',
  ACCOUNT: 'Account',
};

/** `waitingReason` of a conversation waiting on a runner: the queued run's reason, in short. */
const RUNNER_REASONS: Record<string, string> = {
  QUEUED: 'Queued',
  AGENT_PAUSED: 'Agent paused',
  ACCOUNT_REQUIRED: 'Account needed',
  BUDGET_SPENT: 'Budget spent',
  KEY_ERROR: 'Budget unavailable',
  HOST_OFFLINE: 'Host offline',
  MODEL_ACCESS_UNAVAILABLE: 'Nobody can pay',
  NO_HOST_FOR_CREDENTIALS: 'No host may run it',
  NO_RUNNER_HOST: 'No runner host',
  RUNNER_HOST_OFFLINE: 'Host offline',
  RUNNER_HOST_DRAINING: 'Host restarting',
  CREDENTIAL_POOL_FULL: 'Subscription busy',
  RUNNER_HOST_FULL: 'Host busy',
  NO_HOST_FOR_SKILLS: 'No host for its skills',
  RETRY_SCHEDULED: 'Retrying soon',
};

/**
 * The badge's words and tone. Orange only for the person who must act (DESIGN.md, the scarce
 * orange rule): their question, approval or account. A failed turn is destructive, one stopped at
 * its time limit neutral (it goes on with Retry); the agent at work is green; a runner, a budget
 * or an admin to wait for stays neutral. `mine` is false when
 * the reader is not the conversation's person (the Owner reading it): it waits on them, not on
 * the reader, so it is neutral.
 */
export function waitingBadge(
  item: Waiting,
  mine = true,
  personName?: string | null,
): { label: string; tone: WaitingTone } | null {
  const reason = item.waitingReason ?? '';
  switch (item.waitingOn) {
    case 'PERSON': {
      if (reason === 'FAILED') {
        return stoppedAtTimeLimit(item.failureClass)
          ? { label: 'Stopped · Time limit', tone: 'muted' }
          : { label: 'Turn failed', tone: 'destructive' };
      }
      const what = PERSON_REASONS[reason];
      const who = mine ? 'Needs you' : `Waits for ${personName ?? 'its person'}`;
      return { label: what ? `${who} · ${what}` : who, tone: mine ? 'human' : 'muted' };
    }
    case 'AGENT':
      return { label: 'Working', tone: 'agent' };
    case 'RUNNER': {
      const what = RUNNER_REASONS[reason];
      return { label: what ? `Waiting · ${what}` : 'Waiting for a runner', tone: 'muted' };
    }
    default:
      return null;
  }
}

const TONES: Record<WaitingTone, string> = {
  human: 'bg-human-soft text-human',
  agent: 'bg-primary/10 text-primary',
  destructive: 'bg-destructive/10 text-destructive',
  muted: 'bg-muted text-muted-foreground',
};

/**
 * Who a conversation waits on, as the server says (`waitingOn`); nothing when nothing waits. A
 * failed turn leads with a crossed circle, the agent at work with a pulsing dot, a person to act
 * with a dot, a runner to wait for with a clock.
 */
@Component({
  selector: 'app-waiting-on-badge',
  imports: [NgIcon],
  providers: [provideIcons({ lucideCircleX, lucideClock })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (badge(); as b) {
      <span
        class="inline-flex h-6 max-w-full items-center gap-1.5 rounded-full px-2.5 font-sans text-xs font-semibold whitespace-nowrap"
        data-testid="waiting-on"
        [class]="tones[b.tone]"
        [attr.data-waiting-on]="item().waitingOn"
        [attr.data-reason]="item().waitingReason ?? null"
        [title]="title()"
      >
        @switch (b.tone) {
          @case ('destructive') {
            <ng-icon name="lucideCircleX" size="12" class="shrink-0" aria-hidden="true" />
          }
          @case ('agent') {
            <span
              class="size-1.5 shrink-0 rounded-full bg-current motion-safe:animate-pulse"
              aria-hidden="true"
            ></span>
          }
          @case ('human') {
            <span class="size-1.5 shrink-0 rounded-full bg-current" aria-hidden="true"></span>
          }
          @default {
            @if (item().waitingOn === 'RUNNER') {
              <ng-icon name="lucideClock" size="12" class="shrink-0" aria-hidden="true" />
            }
          }
        }
        {{ b.label }}
      </span>
    }
  `,
})
export class WaitingOnBadge {
  readonly item = input.required<Waiting>();
  /** Whether the reader is the conversation's person. */
  readonly mine = input(true);
  readonly personName = input<string | null>();

  protected readonly tones = TONES;
  protected readonly badge = computed(() =>
    waitingBadge(this.item(), this.mine(), this.personName()),
  );
  protected readonly title = computed(() => {
    const since = this.item().waitingSince;
    return since ? `Since ${formatAbsolute(since)}` : '';
  });
}
