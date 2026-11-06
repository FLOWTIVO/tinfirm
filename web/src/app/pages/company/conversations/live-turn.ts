import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideChevronDown, lucideChevronRight, lucideClock } from '@ng-icons/lucide';
import type { Conversation, Run } from '../../../api/generated/models';
import { initials } from '../../../shared/forms';
import { Markdown } from '../../../shared/markdown';
import {
  QueueReasonText,
  type QueueReasonReader,
  queueReasonText,
} from '../../../shared/queue-reason';
import { isRunActive, LiveDot, RUN_STATUS_LABELS, runDuration } from '../../../shared/run-status';
import { formatAbsolute } from '../../../shared/time';
import { RunStream } from '../runs/run-stream';
import {
  phaseLabel,
  rateLimitText,
  rowTarget,
  rowVerb,
  TranscriptWork,
} from '../runs/transcript-items';
import {
  type MessageItem,
  type TranscriptItem,
  type TranscriptRowItem,
  workEntries,
} from '../runs/transcript-model';
import { turnMeta } from './conversation-view';

/** The first line of a text, cut to a length that fits the working line. */
function firstLine(text: string, max = 90): string {
  const line = text.trim().split('\n', 1)[0].trim();
  return line.length > max ? `${line.slice(0, max - 1)}…` : line;
}

/**
 * The latest step of a live turn, for its working line: "Read src/app.ts", "Thinking", what the
 * agent said last; null before anything happened.
 */
export function latestStep(items: readonly TranscriptItem[]): string | null {
  const item = items.at(-1);
  if (!item) return null;
  switch (item.kind) {
    case 'tool': {
      const target = rowTarget(item);
      return firstLine(`${rowVerb(item)} ${target}`.trim());
    }
    case 'message':
      return firstLine(item.text) || null;
    case 'thought':
      return 'Thinking';
    case 'plan': {
      const step =
        item.entries.find((e) => e.status === 'in_progress') ??
        item.entries.find((e) => e.status !== 'completed');
      return step ? firstLine(step.content) : 'Planned its work';
    }
    case 'git':
      return item.action === 'cloned' ? `Checked out ${item.branch}` : `Git: ${item.action}`;
    case 'logs':
      return firstLine(item.lines.at(-1)?.message ?? '') || null;
    case 'error':
      return firstLine(item.message);
    case 'rate-limit':
      return firstLine(rateLimitText(item.limit), 120);
    default:
      return null;
  }
}

/** Why the queued turn waits, in the conversation's words, where they differ from the server's. */
export interface QueuedLine {
  readonly text: string;
  readonly link: {
    readonly label: string;
    readonly commands: readonly string[];
    readonly queryParams?: Record<string, string>;
  } | null;
  /** The reason of an account the person connects in the card below. */
  readonly toCards: boolean;
}

/**
 * The conversation's own sentence for the queue reasons it says better than the server can (plan
 * §4.1): a spent budget names whose budget and when it resets (V7), a paused agent names the agent,
 * an account to connect points at its card. Null for the others: the server's sentence stands.
 */
export function queuedLine(
  run: Run | null,
  c: Pick<Conversation, 'agentId' | 'agentName' | 'payer'>,
  payerMine: boolean,
): QueuedLine | null {
  const reason = run?.status === 'QUEUED' ? run.queueReason : null;
  switch (reason?.code) {
    case 'BUDGET_SPENT': {
      const whose = payerMine ? 'your budget' : `${c.payer.name ?? 'its payer'}’s budget`;
      const text = reason.retryAt
        ? `Waiting for ${whose} to reset on ${formatAbsolute(reason.retryAt)}.`
        : `Waiting: ${whose} is spent.`;
      return {
        text,
        link: payerMine
          ? { label: 'Your budget', commands: ['accounts'], queryParams: { tab: 'budget' } }
          : null,
        toCards: false,
      };
    }
    case 'AGENT_PAUSED':
      return {
        text: `${c.agentName} is paused; its turns wait.`,
        link: { label: 'Open the agent', commands: ['agents', c.agentId] },
        toCards: false,
      };
    case 'ACCOUNT_REQUIRED':
      return { text: reason.text, link: null, toCards: true };
    default:
      return null;
  }
}

/**
 * The turn the conversation runs now (plan §4.1, `LiveTurn`; DESIGN.md, the turn running now), at
 * the end of the thread: the agent's name line, then a live working line, "Working · 2m 10s ·
 * Running · Read src/app.ts" (the elapsed time, the phase and the latest step), with "Show work"
 * unfolding the work so far in place, and "View run"; while the work is folded, what the agent has
 * said so far shows in its place. It follows the run with `RunStream` (`streamRunEvents`, falling
 * back to polling `listRunEvents`): the only stream on the page.
 *
 * While the run is queued it says why in a neutral line, with the link that fixes it: a spent
 * budget with its reset (V7), an account to connect in its card, a paused agent, the runner hosts.
 * Each change of the run's status is told to the page (`statusChanged`), which reads the
 * conversation again; so is its end (`runEnded`). A live region says what changes, not every tick.
 */
@Component({
  selector: 'app-live-turn',
  imports: [RouterLink, NgIcon, LiveDot, Markdown, QueueReasonText, TranscriptWork],
  providers: [RunStream, provideIcons({ lucideChevronDown, lucideChevronRight, lucideClock })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'flex min-w-0 flex-col gap-2.5',
    'data-testid': 'live-turn',
    '[attr.data-run-id]': 'runId()',
    '[attr.data-status]': 'current()?.status ?? null',
  },
  template: `
    @let r = current();
    <p class="text-muted-foreground flex flex-wrap items-center gap-x-2 gap-y-1 text-[0.8125rem]">
      <span
        class="bg-primary text-primary-foreground inline-flex size-5 shrink-0 items-center justify-center rounded-md text-[0.5625rem] font-bold"
        aria-hidden="true"
        >{{ agentInitials() }}</span
      >
      <span class="text-foreground font-semibold">{{ conversation().agentName }}</span>
      @if (r) {
        <span>{{ meta() }}</span>
      }
    </p>

    @if (queued()) {
      <p
        class="text-muted-foreground flex items-start gap-2 text-[0.8125rem]"
        data-testid="live-turn-queued"
        [attr.data-reason]="r?.queueReason?.code ?? null"
      >
        <ng-icon name="lucideClock" class="mt-[3px] shrink-0 text-[13px]" aria-hidden="true" />
        <span>
          @if (own(); as line) {
            <span data-testid="live-turn-reason">{{ line.text }}</span>
            @if (line.link; as link) {
              {{ ' '
              }}<a
                class="text-foreground decoration-primary/50 hover:decoration-primary focus-visible:ring-ring/50 rounded-sm font-semibold underline underline-offset-2 outline-none focus-visible:ring-3"
                data-testid="live-turn-fix"
                [routerLink]="['/c', companyId()].concat(link.commands)"
                [queryParams]="link.queryParams"
                >{{ link.label }}</a
              >
            }
            @if (line.toCards) {
              {{ ' '
              }}<button
                type="button"
                class="text-foreground decoration-primary/50 hover:decoration-primary focus-visible:ring-ring/50 rounded-sm font-semibold underline underline-offset-2 outline-none focus-visible:ring-3"
                data-testid="live-turn-fix"
                (click)="toCards.emit()"
              >
                Connect the account
              </button>
            }
          } @else if (r?.status === 'QUEUED') {
            <app-queue-reason [run]="r" [companyId]="companyId()" [reader]="reader()" />
          } @else {
            Waiting for a runner host to pick it up.
          }
        </span>
      </p>
    } @else {
      <p
        class="text-muted-foreground flex flex-wrap items-center gap-x-2 gap-y-1 text-xs"
        data-testid="live-turn-working"
      >
        @if (finished()) {
          <span class="font-semibold">{{ endedLabel() }}</span>
        } @else {
          <span class="text-primary inline-flex items-center gap-1.5 font-semibold">
            <app-live-dot />
            Working
          </span>
          @if (elapsed(); as e) {
            <span class="font-mono tabular-nums" data-testid="live-turn-elapsed">{{ e }}</span>
          }
          @if (phase(); as p) {
            <span class="text-muted-foreground" data-testid="live-turn-phase">· {{ p }}</span>
          }
          @if (step(); as s) {
            <span class="text-foreground min-w-0 break-words" data-testid="live-turn-step"
              >· {{ s }}</span
            >
          }
        }
        @if (stream.state() === 'failed') {
          <span class="text-destructive" data-testid="live-turn-stopped">· Updates stopped</span>
          <button
            type="button"
            class="hover:text-foreground focus-visible:ring-ring/50 rounded-sm underline underline-offset-2 outline-none focus-visible:ring-3"
            (click)="restart()"
          >
            Retry
          </button>
        }
      </p>
      @if (!started()) {
        <span
          class="bg-muted inline-flex w-fit items-center gap-1 rounded-[1rem] rounded-bl-md px-4 py-3"
          aria-hidden="true"
          data-testid="live-turn-typing"
        >
          <span class="thread-dot"></span><span class="thread-dot"></span
          ><span class="thread-dot"></span>
        </span>
      }
    }

    <p class="text-muted-foreground flex flex-wrap items-center gap-x-2.5 gap-y-1 text-xs">
      @if (entries().length > 0) {
        <button
          type="button"
          class="hover:text-foreground focus-visible:ring-ring/50 inline-flex items-center gap-1 rounded-sm font-semibold outline-none focus-visible:ring-3"
          data-testid="live-turn-show-work"
          [attr.aria-expanded]="showWork()"
          [attr.aria-controls]="workId()"
          (click)="showWork.set(!showWork())"
        >
          <ng-icon
            [name]="showWork() ? 'lucideChevronDown' : 'lucideChevronRight'"
            class="text-[13px]"
            aria-hidden="true"
          />
          {{ showWork() ? 'Hide work' : 'Show work' }}
        </button>
      }
      <a
        class="hover:text-foreground focus-visible:ring-ring/50 rounded-sm underline underline-offset-2 outline-none focus-visible:ring-3"
        data-testid="live-turn-run"
        [routerLink]="['/c', companyId(), 'runs', runId()]"
        >View run</a
      >
    </p>
    @if (showWork() && entries().length > 0) {
      <div class="min-w-0" data-testid="live-turn-work" [id]="workId()">
        <app-transcript-work [entries]="entries()" [live]="!finished()" />
      </div>
    } @else if (said().length > 0) {
      <!-- What the agent has said so far, without the work between. -->
      <div class="flex min-w-0 flex-col gap-3" data-testid="live-turn-said">
        @for (m of said(); track m.id) {
          <app-markdown
            class="max-w-[66ch] text-[0.9375rem] leading-normal"
            [class.thread-enter]="!finished()"
            [text]="m.text"
          />
        }
      </div>
    }
    <p class="sr-only" role="status">{{ announcement() }}</p>
  `,
})
export class LiveTurn {
  protected readonly stream = inject(RunStream);

  readonly companyId = input.required<string>();
  readonly runId = input.required<string>();
  /** The run as the conversation knows it (`turns`), until the stream reports its own. */
  readonly run = input<Run | null>(null);
  readonly conversation = input.required<Conversation>();
  /** The reader pays for the conversation: "your budget", with the link to it. */
  readonly payerMine = input(false);
  /** Who reads the server's reasons: a member gets their own budget and hosts, an admin the company's. */
  readonly reader = input<QueueReasonReader>({ member: true, admin: false });
  /** The run's status changed (it started, its reason changed): the page reads the conversation. */
  readonly statusChanged = output<Run>();
  /** The run ended (its final `run-status`): the page reads the conversation. */
  readonly runEnded = output<Run>();
  /** "Connect the account": the page brings the binding cards into view. */
  readonly toCards = output<void>();

  private readonly now = signal(Date.now());
  private lastEntries: readonly TranscriptRowItem[] = [];
  protected readonly showWork = signal(false);

  protected readonly current = computed(() => {
    const streamed = this.stream.run();
    if (streamed?.id === this.runId()) return streamed;
    const known = this.run();
    return known?.id === this.runId() ? known : null;
  });
  protected readonly queued = computed(() => {
    const run = this.current();
    return run ? run.status === 'QUEUED' : this.conversation().waitingOn === 'RUNNER';
  });
  protected readonly finished = computed(() => {
    const status = this.current()?.status;
    return status !== undefined && !isRunActive(status);
  });
  protected readonly started = computed(
    () => this.entries().length > 0 || !!this.stream.summary().phase,
  );
  protected readonly endedLabel = computed(() => {
    const status = this.current()?.status;
    return status ? RUN_STATUS_LABELS[status] : '';
  });
  protected readonly agentInitials = computed(() => initials(this.conversation().agentName));
  protected readonly meta = computed(() => {
    const run = this.current();
    return run ? turnMeta(run) : '';
  });
  protected readonly workId = computed(() => `live-work-${this.runId()}`);
  protected readonly entries = computed(() => {
    this.lastEntries = workEntries(
      this.stream.items(),
      { live: !this.finished(), dropLastMessage: false },
      this.lastEntries,
    );
    return this.lastEntries;
  });
  /** What the agent has said so far, shown while the work is folded. */
  protected readonly said = computed(() =>
    this.stream.items().filter((item): item is MessageItem => item.kind === 'message'),
  );
  protected readonly elapsed = computed(() =>
    runDuration(this.current()?.startedAt, null, this.now()),
  );
  protected readonly phase = computed(() => {
    const phase = this.stream.summary().phase;
    return phase ? phaseLabel(phase) : null;
  });
  protected readonly step = computed(() => latestStep(this.stream.items()));
  protected readonly own = computed(() =>
    queuedLine(this.current(), this.conversation(), this.payerMine()),
  );

  /** Said when the run's state changes, not with every tick of the clock or every step. */
  protected readonly announcement = computed(() => {
    const run = this.current();
    const name = this.conversation().agentName;
    if (!run || run.status === 'RUNNING') return `${name} is working.`;
    if (run.status === 'QUEUED') {
      const why = this.own()?.text ?? queueReasonText(run, Date.now(), this.reader());
      return why ? `${name} waits. ${why}` : `${name} waits in the queue.`;
    }
    return `${name}'s turn ${RUN_STATUS_LABELS[run.status].toLowerCase()}.`;
  });

  constructor() {
    effect(() => {
      const companyId = this.companyId();
      const runId = this.runId();
      untracked(() => {
        const known = this.run();
        this.lastEntries = [];
        this.showWork.set(false);
        this.stream.start(companyId, runId, known?.id === runId ? known : null);
      });
    });

    // Each status the stream reports, once: a queued run's reason, its start, its end.
    let told: string | null = null;
    effect(() => {
      const run = this.stream.run();
      if (!run || run.id !== this.runId()) return;
      const key = `${run.id}|${run.status}|${run.queueReason?.code ?? ''}`;
      if (key === told) return;
      const first = told === null;
      told = key;
      untracked(() => {
        if (!isRunActive(run.status)) this.runEnded.emit(run);
        else if (!first || run.status !== this.run()?.status) this.statusChanged.emit(run);
      });
    });

    const clock = setInterval(() => {
      if (untracked(this.current)?.status === 'RUNNING') this.now.set(Date.now());
    }, 1000);
    inject(DestroyRef).onDestroy(() => clearInterval(clock));
  }

  /** Follows the run again after its updates stopped (the run is gone, or access was lost). */
  protected restart(): void {
    this.stream.start(this.companyId(), this.runId(), this.current());
  }
}
