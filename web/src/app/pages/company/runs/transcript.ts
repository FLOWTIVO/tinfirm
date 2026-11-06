import {
  afterNextRender,
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  type ElementRef,
  inject,
  Injector,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArrowDown,
  lucideArrowUp,
  lucideCheck,
  lucideRotateCw,
  lucideTriangleAlert,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import type { Run } from '../../../api/generated/models';
import type { RunPhase } from '../../../api/run-events';
import { problemMessage } from '../../../api/problem';
import { queueReasonText } from '../../../shared/queue-reason';
import { formatCost, formatTokens, isRunActive, usageTitle } from '../../../shared/run-status';
import { RunStream } from './run-stream';
import { phaseLabel, rateLimitText, TranscriptRow } from './transcript-items';
import { groupActivity, type TranscriptRowItem } from './transcript-model';

/** Rows rendered at first; "Show earlier" adds this many more. */
const WINDOW = 300;
/** Distance from the bottom (px) that still counts as "following the output". */
const STICK_THRESHOLD = 48;
const MAIN_PHASES: readonly RunPhase[] = [
  'preparing',
  'cloning',
  'starting',
  'running',
  'finalizing',
  'pushing',
];

/**
 * The live transcript of one run (the run page): phase chips, a token meter, a rate-limit banner
 * and the event rows, drawn by `transcript-items` as a thread draws a turn's work. Follows the
 * output like a terminal until the user scrolls up. Only the newest rows are rendered (older ones
 * on demand), and off-screen rows skip layout via `content-visibility`, so long runs stay smooth.
 */
@Component({
  selector: 'app-transcript',
  imports: [NgIcon, HlmButton, TranscriptRow],
  providers: [
    RunStream,
    provideIcons({
      lucideArrowDown,
      lucideArrowUp,
      lucideCheck,
      lucideRotateCw,
      lucideTriangleAlert,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex min-h-0 flex-col' },
  template: `
    <section class="relative flex min-h-0 flex-1 flex-col" aria-label="Run transcript">
      <header class="flex flex-wrap items-center gap-x-3 gap-y-2 px-1 pb-1 text-xs">
        <span class="flex items-center gap-1.5 font-semibold" data-testid="stream-state">
          @switch (stream.state()) {
            @case ('live') {
              <span
                class="bg-primary/10 text-primary inline-flex h-6 items-center gap-1.5 rounded-full px-2.5"
              >
                <span class="relative inline-flex size-2" aria-hidden="true">
                  <span
                    class="bg-primary absolute inline-flex size-full animate-ping rounded-full opacity-60 motion-reduce:hidden"
                  ></span>
                  <span class="bg-primary relative inline-flex size-2 rounded-full"></span>
                </span>
                Live
              </span>
            }
            @case ('connecting') {
              <span class="text-muted-foreground">Connecting…</span>
            }
            @case ('reconnecting') {
              <span class="text-warning">Reconnecting…</span>
            }
            @case ('polling') {
              <span class="text-muted-foreground" title="Live streaming isn't available">{{
                active() ? 'Refreshing every few seconds' : 'Loading…'
              }}</span>
            }
            @case ('failed') {
              <span class="text-destructive">Updates stopped</span>
              <button hlmBtn variant="ghost" size="xs" type="button" (click)="restart()">
                <ng-icon name="lucideRotateCw" aria-hidden="true" />
                Retry
              </button>
            }
            @default {
              <span class="text-muted-foreground">{{ eventCountText() }}</span>
            }
          }
        </span>

        @if (showPhases() && phaseChips().length > 0) {
          <ol class="flex flex-wrap items-center gap-1" aria-label="Run phases">
            @for (chip of phaseChips(); track chip.phase) {
              <li
                class="inline-flex h-6 items-center gap-1 rounded-full border px-2.5 font-semibold"
                [class]="chipClass[chip.state]"
                [attr.aria-current]="chip.state === 'current' ? 'step' : null"
              >
                @if (chip.state === 'done') {
                  <ng-icon name="lucideCheck" class="text-[0.7rem]" aria-hidden="true" />
                }
                {{ chip.label }}
              </li>
            }
          </ol>
        }

        @if (usage(); as u) {
          <div
            class="text-muted-foreground ms-auto flex items-center gap-2.5 font-mono tabular-nums"
            data-testid="token-meter"
            [title]="u.title"
          >
            <span class="inline-flex items-center gap-0.5"
              ><ng-icon name="lucideArrowUp" aria-hidden="true" /><span class="sr-only"
                >Input tokens </span
              >{{ u.input }}</span
            >
            <span class="inline-flex items-center gap-0.5"
              ><ng-icon name="lucideArrowDown" aria-hidden="true" /><span class="sr-only"
                >Output tokens </span
              >{{ u.output }}</span
            >
            @if (u.contextPct !== null) {
              <span class="flex items-center gap-1">
                <span
                  class="bg-muted relative h-1.5 w-16 overflow-hidden rounded-full"
                  role="meter"
                  aria-label="Context window used"
                  [attr.aria-valuenow]="u.contextPct"
                  aria-valuemin="0"
                  aria-valuemax="100"
                >
                  <span
                    class="absolute inset-y-0 start-0 rounded-full"
                    [class]="u.contextPct >= 85 ? 'bg-warning' : 'bg-primary'"
                    [style.width.%]="u.contextPct"
                  ></span>
                </span>
                {{ u.contextPct }}%
              </span>
            }
            @if (u.cost) {
              <span>{{ u.cost }}</span>
            }
          </div>
        }
      </header>

      @if (rateLimit(); as text) {
        <div
          role="alert"
          class="bg-card text-foreground mx-1 mb-1 flex items-start gap-2 rounded-xl border px-3.5 py-2 text-xs"
        >
          <ng-icon
            name="lucideTriangleAlert"
            class="text-warning mt-0.5 shrink-0"
            aria-hidden="true"
          />
          <span>{{ text }}</span>
        </div>
      }

      <div
        #scroller
        class="min-h-0 flex-1 overflow-y-auto overscroll-contain [mask-image:linear-gradient(to_bottom,transparent,black_1.5rem)]"
        role="log"
        aria-live="off"
        aria-label="Run events"
        tabindex="0"
        (scroll)="onScroll()"
        (wheel)="onUserScroll()"
        (touchmove)="onUserScroll()"
        (pointerdown)="onUserScroll()"
        (keydown)="onUserScroll()"
      >
        @if (hiddenCount() > 0) {
          <div class="flex justify-center pt-4">
            <button hlmBtn variant="outline" size="xs" type="button" (click)="showEarlier()">
              Show {{ hiddenCount() }} earlier {{ hiddenCount() === 1 ? 'entry' : 'entries' }}
            </button>
          </div>
        }
        @if (visible().length === 0) {
          <p
            class="text-muted-foreground px-3 py-8 text-center text-sm text-pretty"
            data-testid="transcript-empty"
          >
            {{ emptyText() }}
          </p>
        } @else {
          <ol #list class="flex flex-col items-stretch gap-3 px-2 pt-5 pb-3">
            @for (item of rows(); track item.id) {
              <li class="min-w-0 [contain-intrinsic-size:auto_1.5rem] [content-visibility:auto]">
                <app-transcript-row [item]="item" [live]="!finished()" />
              </li>
            }
            @if (!finished() && stream.state() === 'live') {
              <li aria-hidden="true" data-testid="agent-working">
                <span
                  class="bg-muted inline-flex items-center gap-1 rounded-[1rem] rounded-bl-md px-4 py-3"
                >
                  <span class="thread-dot"></span><span class="thread-dot"></span
                  ><span class="thread-dot"></span>
                </span>
              </li>
            }
          </ol>
        }
      </div>

      @if (!stick() && visible().length > 0) {
        <button
          hlmBtn
          variant="outline"
          size="sm"
          type="button"
          class="absolute end-2 bottom-3 shadow-[0_6px_16px_-4px_rgb(0_0_0/0.2)]"
          (click)="jumpToLatest()"
        >
          <ng-icon name="lucideArrowDown" aria-hidden="true" />
          Latest
        </button>
      }
      <p class="sr-only" aria-live="polite">{{ announcement() }}</p>
    </section>
  `,
})
export class Transcript {
  protected readonly stream = inject(RunStream);
  private readonly injector = inject(Injector);

  readonly companyId = input.required<string>();
  readonly runId = input.required<string>();
  /** The run as the caller knows it; shown until the stream reports its own. */
  readonly run = input<Run | null>(null);
  /** The phase pills in the header. */
  readonly showPhases = input(true);
  /** Every run update from the stream (status changes, the final status). */
  readonly runChange = output<Run>();

  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');
  private readonly list = viewChild<ElementRef<HTMLElement>>('list');
  /** Keeps following when rows change height (collapsibles, rows laid out late). */
  private readonly resizes =
    typeof ResizeObserver === 'function' ? new ResizeObserver(() => this.follow()) : null;

  protected readonly chipClass = {
    current: 'bg-primary text-primary-foreground border-transparent',
    done: 'bg-muted text-primary border-transparent',
    todo: 'text-muted-foreground border-dashed',
  } as const;

  protected readonly stick = signal(true);
  private readonly windowSize = signal(WINDOW);

  private readonly items = this.stream.items;

  protected readonly visible = computed(() => {
    const items = this.items();
    return items.length > this.windowSize() ? items.slice(items.length - this.windowSize()) : items;
  });
  protected readonly hiddenCount = computed(() => this.items().length - this.visible().length);
  /**
   * One row per line: the tool calls and logs between two other rows fold into one group, so a
   * long run reads as what the agent said. Off-screen rows skip layout.
   */
  protected readonly rows = computed(() => {
    this.lastRows = groupActivity(this.visible(), this.lastRows);
    return this.lastRows;
  });
  /** The last grouping, so unchanged groups keep their object. */
  private lastRows: readonly TranscriptRowItem[] = [];

  private readonly currentRun = computed(() => this.stream.run() ?? this.run());
  protected readonly active = computed(() => isRunActive(this.currentRun()?.status));
  /** The run is known to be over (an unknown run counts as still going). */
  protected readonly finished = computed(() => {
    const status = this.currentRun()?.status;
    return (status !== undefined && !isRunActive(status)) || this.stream.state() === 'ended';
  });

  protected readonly eventCountText = computed(() => {
    const n = this.stream.summary().eventCount;
    return `${n.toLocaleString('en')} event${n === 1 ? '' : 's'}`;
  });

  /**
   * Said in place of the rows. Why a queued run waits is said above the transcript (the page's
   * header) and announced below.
   */
  protected readonly emptyText = computed(() => {
    const state = this.stream.state();
    if (state === 'failed') return problemMessage(this.stream.error());
    if (state === 'ended') return 'This run recorded no events.';
    if (this.currentRun()?.status === 'QUEUED') {
      return 'Waiting in the queue: the events start when a runner host takes the run.';
    }
    return 'Waiting for events…';
  });

  protected readonly phaseChips = computed(() => {
    const { phases, phase } = this.stream.summary();
    if (phases.length === 0) return [];
    const ended = !this.active() && this.stream.state() === 'ended';
    const list = [...MAIN_PHASES, ...phases.filter((p) => !MAIN_PHASES.includes(p))];
    const currentIndex = phase ? list.indexOf(phase) : -1;
    return list
      .filter((p) => phases.includes(p) || (!ended && list.indexOf(p) > currentIndex))
      .map((p) => ({
        phase: p,
        label: phaseLabel(p),
        state:
          p === phase && !ended
            ? ('current' as const)
            : phases.includes(p)
              ? ('done' as const)
              : ('todo' as const),
      }));
  });

  protected readonly usage = computed(() => {
    const u = this.stream.summary().usage;
    if (!u) return null;
    const contextPct =
      u.contextUsed && u.contextSize
        ? Math.min(100, Math.round((u.contextUsed / u.contextSize) * 100))
        : null;
    return {
      input: formatTokens(u.inputTokens),
      output: formatTokens(u.outputTokens),
      contextPct,
      cost: formatCost(u.costUsd),
      title: `${usageTitle(u)}${contextPct !== null ? ` · context ${contextPct}% used` : ''}`,
    };
  });

  /** The current rate-limit state; a warning stops mattering once the run is over. */
  protected readonly rateLimit = computed(() => {
    const limit = this.stream.summary().rateLimit;
    if (!limit || limit.status === 'allowed') return null;
    if (limit.status === 'allowed_warning' && !this.active()) return null;
    return rateLimitText(limit);
  });

  /** Said once per change of the run: its status, or why a queued run still waits. */
  protected readonly announcement = computed(() => {
    const run = this.stream.run();
    if (!run) return '';
    if (run.status === 'QUEUED' && run.queueReason) {
      // A snapshot taken when the run changes, so a counting relative time is not re-announced.
      return queueReasonText(run, Date.now());
    }
    return `Run ${run.status.toLowerCase()}`;
  });

  constructor() {
    effect(() => {
      const companyId = this.companyId();
      const runId = this.runId();
      untracked(() => {
        this.stick.set(true);
        this.windowSize.set(WINDOW);
        this.stream.start(companyId, runId, this.run());
      });
    });

    effect(() => {
      const run = this.stream.run();
      if (run && run !== untracked(this.run)) untracked(() => this.runChange.emit(run));
    });

    // The server can resume a run it marked lost when its runner host reports it again (as v1
    // did). The stream ended on LOST; follow the run again once the caller sees it active.
    effect(() => {
      const run = this.run();
      if (!run || !isRunActive(run.status)) return;
      untracked(() => {
        const streamed = this.stream.run();
        if (
          this.stream.state() === 'ended' &&
          streamed?.id === run.id &&
          !isRunActive(streamed.status)
        ) {
          this.stream.start(this.companyId(), this.runId(), run);
        }
      });
    });

    // Follow the output while the user is at the bottom.
    afterRenderEffect({
      write: () => {
        this.rows();
        const el = this.scroller()?.nativeElement;
        if (el && this.stick()) el.scrollTop = el.scrollHeight;
      },
    });

    effect((onCleanup) => {
      const scroller = this.scroller()?.nativeElement;
      const list = this.list()?.nativeElement;
      if (!this.resizes) return;
      if (scroller) this.resizes.observe(scroller);
      if (list) this.resizes.observe(list);
      onCleanup(() => this.resizes?.disconnect());
    });
    inject(DestroyRef).onDestroy(() => this.resizes?.disconnect());
  }

  private follow(): void {
    // A row the user just expanded stays where it is.
    if (Date.now() - this.lastUserInput < 1000) return;
    const el = this.scroller()?.nativeElement;
    if (el && untracked(this.stick)) el.scrollTop = el.scrollHeight;
  }

  protected restart(): void {
    this.stream.start(this.companyId(), this.runId(), this.currentRun());
  }

  /** Last wheel, touch, pointer or key input on the log: only the user unpins it. */
  private lastUserInput = 0;

  protected onUserScroll(): void {
    this.lastUserInput = Date.now();
  }

  protected onScroll(): void {
    const el = this.scroller()?.nativeElement;
    if (!el) return;
    if (el.scrollHeight - el.scrollTop - el.clientHeight < STICK_THRESHOLD) {
      this.stick.set(true);
    } else if (Date.now() - this.lastUserInput < 1000) {
      this.stick.set(false);
    } else if (this.stick()) {
      // Rows were laid out after the last jump (or scroll anchoring moved us): stay pinned.
      el.scrollTop = el.scrollHeight;
    }
  }

  protected jumpToLatest(): void {
    this.stick.set(true);
  }

  /** Renders older rows above, keeping what the user is looking at in place. */
  protected showEarlier(): void {
    const el = this.scroller()?.nativeElement;
    const before = el ? el.scrollHeight - el.scrollTop : 0;
    this.stick.set(false);
    this.windowSize.update((n) => n + WINDOW);
    afterNextRender(
      () => {
        if (el) el.scrollTop = el.scrollHeight - before;
      },
      { injector: this.injector },
    );
  }
}
