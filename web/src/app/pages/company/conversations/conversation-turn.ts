import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleCheck,
  lucideCircleX,
  lucideOctagonPause,
  lucideTimerOff,
  lucideTriangleAlert,
} from '@ng-icons/lucide';
import type {
  Conversation,
  ConversationMessage,
  Decision,
  Run,
} from '../../../api/generated/models';
import { DecisionCard } from '../../../shared/decision-card';
import { ResultReviewCard } from '../../../shared/result-review-card';
import { initials } from '../../../shared/forms';
import { bindingTextFor } from '../../../shared/pinned-cards';
import { Markdown } from '../../../shared/markdown';
import { failureClassLabel, stoppedAtTimeLimit } from '../../../shared/run-status';
import { Time } from '../../../shared/time';
import { type LineTone, turnLine, turnMeta } from './conversation-view';
import { TurnWork } from './turn-work';

const TONES: Record<LineTone, string> = {
  success: 'text-success',
  human: 'text-human',
  destructive: 'text-destructive',
  warning: 'text-warning',
  muted: 'text-foreground',
};

/**
 * One finished turn of the conversation (DESIGN.md, The Thread: the agent speaking): its name line
 * ("Turn 2 · a new message · office · 14:12"), "Show work" above what it said (`TurnWork`), the
 * person's messages it took while it worked (projected as `[turnSteered]`), its answer in Markdown (or, without one, its failure in words or its summary), the decisions it
 * raised (W4b's `app-decision-card`, answerable in place), and the status line: the outcome, the duration, the push and its
 * change requests, the commit and the cost, and "View run".
 */
@Component({
  selector: 'app-conversation-turn',
  imports: [RouterLink, NgIcon, DecisionCard, ResultReviewCard, Markdown, Time, TurnWork],
  providers: [
    provideIcons({
      lucideCircleCheck,
      lucideCircleX,
      lucideOctagonPause,
      lucideTimerOff,
      lucideTriangleAlert,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'flex min-w-0 flex-col gap-2.5',
    'data-author': 'AGENT',
    'data-testid': 'turn',
    '[attr.data-run-id]': 'run().id',
    '[attr.data-status]': 'run().status',
  },
  template: `
    @let r = run();
    <p class="text-muted-foreground flex flex-wrap items-center gap-x-2 gap-y-1 text-[0.8125rem]">
      <span
        class="bg-primary text-primary-foreground inline-flex size-5 shrink-0 items-center justify-center rounded-md text-[0.5625rem] font-bold"
        aria-hidden="true"
        >{{ agentInitials() }}</span
      >
      <span class="text-foreground font-semibold">{{ agentName() }}</span>
      <span data-testid="turn-meta">{{ meta() }} ·</span>
      <app-time mode="clock" [value]="r.finishedAt ?? r.startedAt ?? r.createdAt" />
    </p>
    @if (r.startedAt) {
      <app-turn-work [companyId]="companyId()" [run]="r" [answered]="answered()" />
    }
    <!-- The person's messages the turn took while it worked (steering), before what it answered. -->
    <ng-content select="[turnSteered]" />
    @for (m of messages(); track m.id) {
      <app-markdown
        class="max-w-[66ch] text-[0.9375rem] leading-normal"
        data-testid="turn-answer"
        [text]="m.body"
      />
    } @empty {
      @if (r.status === 'FAILED' || r.status === 'LOST') {
        @let stopped = r.status === 'FAILED' && stoppedAtTimeLimit(r.failureClass);
        <div class="flex max-w-[66ch] flex-col gap-1" role="note" data-testid="turn-failure">
          <p
            class="flex flex-wrap items-center gap-x-2 font-semibold"
            [class]="stopped ? 'text-warning' : 'text-destructive'"
          >
            <ng-icon
              [name]="stopped ? 'lucideTimerOff' : 'lucideCircleX'"
              class="shrink-0 text-[15px]"
              aria-hidden="true"
            />
            {{ stopped ? 'Turn stopped' : r.status === 'FAILED' ? 'Turn failed' : 'Turn lost' }}
            @if (r.failureClass) {
              <code class="font-mono text-xs font-normal" [title]="failureLabel(r.failureClass)">{{
                r.failureClass
              }}</code>
            }
          </p>
          @if (failureLabel(r.failureClass); as label) {
            <p class="text-[0.9375rem]">{{ label }}.</p>
          }
          @if (r.failureMessage) {
            <p class="text-[0.9375rem] break-words whitespace-pre-line text-pretty">
              {{ r.failureMessage }}
            </p>
          }
        </div>
      } @else if (r.status === 'SUCCEEDED' && r.summary) {
        <app-markdown
          class="max-w-[66ch] text-[0.9375rem] leading-normal"
          data-testid="turn-answer"
          [text]="r.summary"
        />
      }
    }
    @for (d of decisions(); track d.id) {
      @if (d.subject?.type === 'RESULT') {
        <app-result-review-card
          [attr.data-decision-id]="d.id"
          [attr.data-open-card]="d.status === 'OPEN' && d.mayDecide ? '' : null"
          [decision]="d"
          [companyId]="companyId()"
          (decided)="decided.emit($event)"
          (outdated)="outdated.emit()"
        />
      } @else {
        <app-decision-card
          [attr.data-decision-id]="d.id"
          [attr.data-open-card]="d.status === 'OPEN' && d.mayDecide ? '' : null"
          [decision]="d"
          [companyId]="companyId()"
          [agentEditable]="agentEditable()"
          [bindingText]="bindingText(d)"
          (decided)="decided.emit($event)"
          (outdated)="outdated.emit()"
        />
      }
    }
    @let s = line();
    <p
      class="text-muted-foreground flex flex-wrap items-center gap-x-2.5 gap-y-1 text-xs"
      data-testid="turn-status"
    >
      <span class="inline-flex items-center gap-1.5 font-semibold" [class]="tones[s.tone]">
        @if (s.icon) {
          <ng-icon [name]="s.icon" class="text-[13px]" aria-hidden="true" />
        }
        {{ s.label }}
      </span>
      @if (s.failureClass) {
        <code class="font-mono" [title]="failureLabel(s.failureClass)">{{ s.failureClass }}</code>
      }
      @if (s.duration) {
        <span class="font-mono tabular-nums">{{ s.duration }}</span>
      }
      @if (s.push; as p) {
        <span data-testid="turn-push"
          >{{ p.text }}
          @if (p.branch) {
            <code class="font-mono">{{ p.branch }}</code>
          }
        </span>
      }
      @for (cr of s.changeRequests; track cr.url) {
        <a
          class="hover:text-foreground underline underline-offset-2"
          target="_blank"
          rel="noopener noreferrer"
          [href]="cr.url"
          >{{ cr.label }}<span class="sr-only"> (opens in a new tab)</span></a
        >
      }
      @if (s.shaAndCost) {
        <span
          class="text-muted-foreground font-mono"
          data-testid="turn-cost"
          [title]="s.usageTitle ?? ''"
          >{{ s.shaAndCost }}</span
        >
      }
      <a
        class="hover:text-foreground focus-visible:ring-ring/50 rounded-sm underline underline-offset-2 outline-none focus-visible:ring-3"
        data-testid="turn-run"
        [routerLink]="['/c', companyId(), 'runs', r.id]"
        >View run</a
      >
    </p>
  `,
})
export class ConversationTurn {
  readonly run = input.required<Run>();
  readonly messages = input<readonly ConversationMessage[]>([]);
  readonly decisions = input<readonly Decision[]>([]);
  readonly conversation = input.required<Conversation>();
  readonly companyId = input.required<string>();
  readonly agentEditable = input(false);
  /** A decision as the server answered it once decided here. */
  readonly decided = output<Decision>();
  /** Someone decided it first, or it was cancelled: the page reads the conversation again. */
  readonly outdated = output<void>();

  protected readonly tones = TONES;
  protected readonly stoppedAtTimeLimit = stoppedAtTimeLimit;
  protected readonly agentName = computed(() => this.conversation().agentName);
  protected readonly agentInitials = computed(() => initials(this.agentName()));
  protected readonly meta = computed(() => turnMeta(this.run()));
  protected readonly line = computed(() => {
    const c = this.conversation();
    return turnLine(this.run(), c.outputs, c.cost);
  });
  /** What the agent said shows under its work, so the work leaves its last message out. */
  protected readonly answered = computed(
    () => this.messages().length > 0 || (this.run().status === 'SUCCEEDED' && !!this.run().summary),
  );

  /** The account a tool call runs as: its system's binding, in the server's words. */
  protected bindingText(decision: Decision): string | null {
    return bindingTextFor(decision, this.conversation().bindings);
  }

  protected failureLabel(failureClass: string | null | undefined): string {
    return failureClassLabel(failureClass) ?? '';
  }
}
