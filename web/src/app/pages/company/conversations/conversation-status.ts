import { NgTemplateOutlet } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleX,
  lucideLock,
  lucidePause,
  lucidePlay,
  lucideRotateCw,
  lucideSquare,
  lucideTimerOff,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import type { Conversation, ModelAccessView } from '../../../api/generated/models';
import { Notify } from '../../../core/notify';
import { failureClassLabel } from '../../../shared/run-status';
import { formatAbsolute } from '../../../shared/time';
import { ConversationStore } from './conversation-store';
import { endedLabel } from './conversation-labels';
import { type ConversationPhase, retryable } from './conversation-view';

/** A link that fixes a failure, under `/c/:companyId`. */
interface FixLink {
  readonly label: string;
  readonly commands: readonly string[];
  readonly queryParams?: Record<string, string>;
}

/** What the line under the thread says about a turn that failed (plan §4.1, the states). */
export interface FailureView {
  /** "Usage limit reached." */
  readonly title: string;
  /** The cause in words: the server's, or the reset of the budget. */
  readonly detail: string | null;
  readonly link: FixLink | null;
  /** Retry may help; not when the agent no longer lists the conversation's runtime. */
  readonly retry: boolean;
  /**
   * Stopped at its time limit rather than failed: its work is kept, the line is neutral, and
   * Retry is "Continue", which goes on from where it stopped.
   */
  readonly stopped: boolean;
}

/** What to change, for a failed turn of a model class whose cause the server did not write. */
const MODEL_CAUSES = {
  MODEL_NOT_ALLOWED: 'The model of your settings is no longer allowed for the budget that pays.',
  MODEL_REQUIRED: 'Your settings name no model, and the budget that pays needs one.',
  MODEL_NOT_SERVED: 'The company’s model gateway does not serve the model of your settings.',
} as const;

/**
 * A failed turn in words (plan §4.1): `USAGE_LIMIT` with the reset of the budget that pays (V7),
 * `RUNTIME_REMOVED` with "Start a new conversation", `MODEL_NOT_ALLOWED`, `MODEL_REQUIRED` and
 * `MODEL_NOT_SERVED` with a link to the reader's settings of the agent, `TIME_LIMIT` as a stop
 * that Continue goes on from, and the server's cause for the rest.
 */
export function failureView(
  c: Pick<Conversation, 'failureClass' | 'failureCause' | 'agentId' | 'agentName' | 'payer'>,
  options: { payerMine: boolean; mine: boolean; access: ModelAccessView | null },
): FailureView {
  const label = failureClassLabel(c.failureClass) ?? 'The turn failed';
  const cause = c.failureCause?.trim() || null;
  switch (c.failureClass) {
    case 'USAGE_LIMIT': {
      if (c.payer.modelAccess !== 'BUDGET') {
        return { title: `${label}.`, detail: cause, link: null, retry: true, stopped: false };
      }
      // The reader's own budget: its reset, and whether the gateway counts it spent.
      const access =
        options.payerMine && options.access?.principalId === c.payer.principalId
          ? options.access
          : null;
      const whose = options.payerMine ? 'Your budget' : `${c.payer.name ?? 'Its payer'}’s budget`;
      const resets = access?.budgetResetsAt ? formatAbsolute(access.budgetResetsAt) : null;
      const detail =
        access?.keyStatus === 'BUDGET_SPENT'
          ? resets
            ? `${whose} is spent; it resets on ${resets}.`
            : `${whose} is spent.`
          : [cause, resets ? `${whose} resets on ${resets}.` : null].filter(Boolean).join(' ') ||
            null;
      return {
        title: `${label}.`,
        detail,
        link: options.payerMine
          ? { label: 'Your budget', commands: ['accounts'], queryParams: { tab: 'budget' } }
          : null,
        retry: true,
        stopped: false,
      };
    }
    case 'RUNTIME_REMOVED':
      return {
        title: `${label}.`,
        detail: `${c.agentName} no longer runs this conversation’s runtime, so its turns cannot run again.`,
        link: options.mine
          ? {
              label: `Start a new conversation with ${c.agentName}`,
              commands: ['conversations', 'new'],
              queryParams: { agentId: c.agentId },
            }
          : null,
        retry: false,
        stopped: false,
      };
    case 'MODEL_NOT_ALLOWED':
    case 'MODEL_REQUIRED':
    case 'MODEL_NOT_SERVED':
      return {
        title: `${label}.`,
        detail: cause ?? MODEL_CAUSES[c.failureClass],
        link: options.mine
          ? { label: 'My settings', commands: ['agents', c.agentId, 'settings'] }
          : null,
        retry: true,
        stopped: false,
      };
    case 'TIME_LIMIT':
      return {
        title: `${label}.`,
        detail: [
          cause ? `${cause.charAt(0).toUpperCase()}${cause.slice(1)}.` : null,
          'Its work so far is kept: Continue goes on from where it stopped.',
        ]
          .filter(Boolean)
          .join(' '),
        link: null,
        retry: true,
        stopped: true,
      };
    default:
      return { title: `${label}.`, detail: cause, link: null, retry: true, stopped: false };
  }
}

/**
 * The line under the thread that says what the conversation's state means now (plan §4.1, the
 * states): a failed turn in words with Retry (destructive: it went wrong, not orange: nobody is
 * asked); a cancelled turn with Retry and its unanswered messages; a paused agent ("its turns
 * wait"); and an ended conversation, read only. Retry is an idempotent submission (the store's).
 * Running, queued and waiting on the person say themselves: the live turn and the cards.
 */
@Component({
  selector: 'app-conversation-status',
  imports: [NgTemplateOutlet, RouterLink, NgIcon, HlmButton, HlmSpinner],
  providers: [
    provideIcons({
      lucideCircleX,
      lucideLock,
      lucidePause,
      lucidePlay,
      lucideRotateCw,
      lucideSquare,
      lucideTimerOff,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block empty:hidden' },
  template: `
    @let c = conversation();
    @switch (phase()) {
      @case ('failed') {
        @let f = failure();
        <div
          class="flex flex-col gap-2 rounded-xl border px-3.5 py-3 text-sm sm:flex-row sm:items-start sm:justify-between"
          [class]="f.stopped ? 'bg-card' : 'border-destructive/30 bg-destructive/5'"
          role="note"
          data-testid="conversation-status"
          data-phase="failed"
          [attr.data-failure]="c.failureClass ?? null"
        >
          <div class="flex min-w-0 items-start gap-2">
            <ng-icon
              [name]="f.stopped ? 'lucideTimerOff' : 'lucideCircleX'"
              class="mt-0.5 shrink-0"
              [class]="f.stopped ? 'text-muted-foreground' : 'text-destructive'"
              aria-hidden="true"
            />
            <p class="flex min-w-0 flex-col gap-0.5">
              <span class="font-semibold" [class]="f.stopped ? '' : 'text-destructive'">
                {{ f.stopped ? 'The last turn stopped' : 'The last turn failed' }}: {{ f.title }}
                @if (c.failureClass) {
                  <code class="font-mono text-xs font-normal">{{ c.failureClass }}</code>
                }
              </span>
              @if (f.detail) {
                <span class="break-words" data-testid="failure-detail">{{ f.detail }}</span>
              }
              @if (f.link; as link) {
                <a
                  class="text-foreground decoration-primary/50 hover:decoration-primary focus-visible:ring-ring/50 w-fit rounded-sm font-semibold underline underline-offset-2 outline-none focus-visible:ring-3"
                  data-testid="failure-fix"
                  [routerLink]="['/c', companyId()].concat(link.commands)"
                  [queryParams]="link.queryParams"
                  >{{ link.label }}</a
                >
              }
            </p>
          </div>
          @if (canAct() && f.retry && canRetry()) {
            <ng-container *ngTemplateOutlet="retryTpl; context: { $implicit: f.stopped }" />
          }
        </div>
      }
      @case ('cancelled') {
        <div
          class="bg-card flex flex-col gap-2 rounded-xl border px-3.5 py-3 text-sm sm:flex-row sm:items-center sm:justify-between"
          role="note"
          data-testid="conversation-status"
          data-phase="cancelled"
        >
          <p class="text-muted-foreground flex items-start gap-2">
            <ng-icon name="lucideSquare" class="mt-0.5 shrink-0" aria-hidden="true" />
            <span
              >The turn was cancelled.
              @if (unanswered() > 0) {
                {{ unanswered() === 1 ? 'A message was' : unanswered() + ' messages were' }}
                not answered.
              }
            </span>
          </p>
          @if (canAct() && canRetry()) {
            <ng-container *ngTemplateOutlet="retryTpl" />
          }
        </div>
      }
      @case ('paused') {
        @if (!c.activeRunId) {
          <p
            class="bg-card text-muted-foreground flex items-start gap-2 rounded-xl border px-3.5 py-3 text-sm"
            role="note"
            data-testid="conversation-status"
            data-phase="paused"
          >
            <ng-icon name="lucidePause" class="mt-0.5 shrink-0" aria-hidden="true" />
            <span
              >{{ c.agentName }} is paused; its turns wait.
              <a
                class="text-foreground hover:decoration-primary rounded-sm font-semibold underline underline-offset-2"
                [routerLink]="['/c', companyId(), 'agents', c.agentId]"
                >Open the agent</a
              ></span
            >
          </p>
        }
      }
      @case ('ended') {
        <p
          class="bg-card text-muted-foreground flex items-start gap-2 rounded-xl border px-3.5 py-3 text-sm"
          role="note"
          data-testid="conversation-status"
          data-phase="ended"
        >
          <ng-icon name="lucideLock" class="mt-0.5 shrink-0" aria-hidden="true" />
          <span
            >{{ reason() }}{{ c.endedAt ? ' on ' + ended() : '' }}. It is read only; its outputs and
            files stay.</span
          >
        </p>
      }
    }

    <ng-template #retryTpl let-goesOn>
      <button
        hlmBtn
        variant="outline"
        size="sm"
        type="button"
        class="shrink-0 self-start"
        data-testid="retry"
        [disabled]="busy()"
        (click)="retry(!!goesOn)"
      >
        @if (busy()) {
          <hlm-spinner aria-hidden="true" />
        } @else {
          <ng-icon [name]="goesOn ? 'lucidePlay' : 'lucideRotateCw'" aria-hidden="true" />
        }
        {{ goesOn ? 'Continue' : 'Retry' }}
      </button>
    </ng-template>
  `,
})
export class ConversationStatus {
  private readonly store = inject(ConversationStore);
  private readonly notify = inject(Notify);

  readonly conversation = input.required<Conversation>();
  readonly companyId = input.required<string>();
  readonly phase = input.required<ConversationPhase>();
  /** The reader is the conversation's person. */
  readonly mine = input(false);
  /** The reader pays for it: "your budget", with its reset. */
  readonly payerMine = input(false);
  /** The reader may retry: its person and the Owner. */
  readonly canAct = input(false);
  /** The reader's model access: the reset of their budget. */
  readonly access = input<ModelAccessView | null>(null);

  protected readonly busy = signal(false);
  protected readonly failure = computed(() =>
    failureView(this.conversation(), {
      payerMine: this.payerMine(),
      mine: this.mine(),
      access: this.access(),
    }),
  );
  protected readonly canRetry = computed(() => retryable(this.conversation()));
  protected readonly unanswered = computed(
    () => this.conversation().messages.filter((m) => m.unanswered).length,
  );
  protected readonly ended = computed(() => {
    const at = this.conversation().endedAt;
    return at ? formatAbsolute(at) : '';
  });
  protected readonly reason = computed(() => endedLabel(this.conversation().endedReason));

  /** Retries the last turn; `goesOn` for a turn stopped at its time limit, which it continues. */
  protected async retry(goesOn = false): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    try {
      await this.store.retry();
      const agent = this.conversation().agentName;
      this.notify.success(
        goesOn ? `${agent} goes on with the turn` : `${agent} runs the turn again`,
      );
    } catch (error) {
      this.notify.error("Couldn't retry the turn", error);
    } finally {
      this.busy.set(false);
    }
  }
}
