import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  Injectable,
  input,
  signal,
  untracked,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideChevronRight } from '@ng-icons/lucide';
import { Api } from '../../../api/generated/api';
import { listRunEvents } from '../../../api/generated/functions';
import type { Run, RunEvent } from '../../../api/generated/models';
import { Markdown } from '../../../shared/markdown';
import { runDuration } from '../../../shared/run-status';
import { RunStream } from '../runs/run-stream';
import { TranscriptWork } from '../runs/transcript-items';
import {
  agentMessages,
  countsText,
  type MessageItem,
  runCounts,
  SAID_TYPES,
  type TranscriptRowItem,
  workCounts,
  workEntries,
} from '../runs/transcript-model';

const PAGE_LIMIT = 1000;

/** The reader's choice to show or fold a turn's work, per run, for this visit. */
@Injectable({ providedIn: 'root' })
export class RunWorkChoices {
  private readonly choices = new Map<string, boolean>();

  get(runId: string): boolean | undefined {
    return this.choices.get(runId);
  }

  set(runId: string, open: boolean): void {
    this.choices.set(runId, open);
  }
}

/**
 * "Show work" of a finished turn (plan §4.1 and §11: the owner chose messages with a live working
 * line and "Show work" in place). One row above what the agent said, a chevron and "Worked for
 * 6m 12s · 14 tool calls · 2 messages" (DESIGN.md, The Thread: the work folded into one row), that
 * unfolds the turn's work in place as `transcript-items` draws it. A failed or lost turn starts open, so that its failed call and error
 * show; the reader's choice holds for the visit. The counts come with the run (`Run.counts`); the
 * events are read only when the work is first opened (`listRunEvents`, page by page), never on the
 * page's polls. The full transcript stays on the run page, one click away.
 *
 * What the agent said along the way shows while the work is folded too: a turn whose counts have
 * messages besides its answer reads only those (`listRunEvents` with `types`), once.
 */
@Component({
  selector: 'app-turn-work',
  imports: [NgIcon, Markdown, TranscriptWork],
  providers: [RunStream, provideIcons({ lucideChevronRight })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex min-w-0 flex-col gap-3' },
  template: `
    <button
      type="button"
      class="text-muted-foreground hover:text-foreground focus-visible:ring-ring/50 flex w-fit max-w-full flex-wrap items-center gap-x-2 gap-y-0.5 rounded-sm py-0.5 text-start text-[0.8125rem] outline-none focus-visible:ring-3"
      data-testid="turn-work-toggle"
      [attr.aria-expanded]="open()"
      [attr.aria-controls]="workId()"
      (click)="toggle()"
    >
      <ng-icon
        name="lucideChevronRight"
        class="shrink-0 text-[14px] transition-transform duration-150 motion-reduce:transition-none"
        aria-hidden="true"
        [class.rotate-90]="open()"
      />
      @if (duration(); as d) {
        <span
          >Worked for <span class="text-foreground font-mono tabular-nums">{{ d }}</span></span
        >
      } @else {
        <span class="text-foreground font-semibold">Its work</span>
      }
      @if (counts(); as c) {
        <!-- The space keeps the button's name readable; the gap shows it. -->
        <span class="text-muted-foreground" data-testid="turn-work-counts"> {{ c }}</span>
      }
    </button>
    @if (open()) {
      <div class="min-w-0" data-testid="turn-work" [id]="workId()">
        @if (entries().length > 0) {
          <app-transcript-work [entries]="entries()" />
        } @else if (stream.state() === 'failed') {
          <p class="text-destructive ms-1.5 border-s ps-[18px] text-[0.8125rem]" role="alert">
            Couldn't read the turn's work.
            <button
              type="button"
              class="hover:text-foreground focus-visible:ring-ring/50 rounded-sm underline underline-offset-2 outline-none focus-visible:ring-3"
              (click)="reload()"
            >
              Retry
            </button>
          </p>
        } @else if (stream.state() === 'ended') {
          <p class="text-muted-foreground ms-1.5 border-s ps-[18px] text-[0.8125rem]">
            The turn recorded no work.
          </p>
        } @else {
          <p class="text-muted-foreground ms-1.5 border-s ps-[18px] text-[0.8125rem]" role="status">
            Loading the work…
          </p>
        }
      </div>
    } @else if (said().length > 0) {
      <!-- What the agent said along the way, without the work between. -->
      <div class="flex min-w-0 flex-col gap-3" data-testid="turn-said">
        @for (m of said(); track m.id) {
          <app-markdown class="max-w-[66ch] text-[0.9375rem] leading-normal" [text]="m.text" />
        }
      </div>
    }
  `,
})
export class TurnWork {
  protected readonly stream = inject(RunStream);
  private readonly api = inject(Api);
  private readonly choices = inject(RunWorkChoices);

  readonly companyId = input.required<string>();
  /** The turn's run, finished: the live turn shows its own work. */
  readonly run = input.required<Run>();
  /** What the agent said is shown under the work, so its last message is left out of it. */
  readonly answered = input(false);

  private readonly choice = signal<boolean | null>(null);
  /** The run whose events were read, so a refreshed copy of the run reads nothing again. */
  private loadedId: string | null = null;
  /** The run whose messages were read, and what they said. */
  private saidId: string | null = null;
  private readonly saidItems = signal<readonly MessageItem[]>([]);
  private lastEntries: readonly TranscriptRowItem[] = [];

  protected readonly workId = computed(() => `turn-work-${this.run().id}`);
  protected readonly open = computed(() => {
    const run = this.run();
    return (
      this.choice() ??
      this.choices.get(run.id) ??
      (run.status === 'FAILED' || run.status === 'LOST')
    );
  });
  protected readonly duration = computed(() => {
    const run = this.run();
    return runDuration(run.startedAt, run.finishedAt);
  });
  protected readonly entries = computed(() => {
    this.lastEntries = workEntries(
      this.stream.items(),
      {
        live: false,
        dropLastMessage: this.answered(),
        failureMessage: this.run().failureMessage,
      },
      this.lastEntries,
    );
    return this.lastEntries;
  });
  /** Whether the turn said something besides its answer: its counts say so, or it has none. */
  private readonly saysMore = computed(() => {
    const run = this.run();
    if (run.eventsPurgedAt || run.lastEventSeq === 0) return false;
    return !run.counts || runCounts(run.counts, this.answered()).messages > 0;
  });
  /**
   * What the agent said while it worked, shown while the work is folded: from the work once it was
   * read, else from its messages alone. Its answer shows under it and is left out.
   */
  protected readonly said = computed(() => {
    const all =
      this.stream.state() === 'ended'
        ? this.stream.items().filter((item): item is MessageItem => item.kind === 'message')
        : this.saidItems();
    return this.answered() ? all.slice(0, -1) : all;
  });
  /** "· 14 tool calls · 2 messages": the run's own counts, else from its events once read. */
  protected readonly counts = computed(() => {
    const counted = this.run().counts;
    if (counted) return countsText(runCounts(counted, this.answered())) || null;
    const items = this.stream.items();
    if (items.length === 0) return null;
    return countsText(workCounts(items, this.answered())) || null;
  });

  constructor() {
    effect(() => {
      if (this.open() || !this.saysMore()) return;
      const companyId = this.companyId();
      const run = this.run();
      untracked(() => {
        if (this.saidId === run.id || this.loadedId === run.id) return;
        this.saidId = run.id;
        void this.readSaid(companyId, run.id);
      });
    });
    effect(() => {
      if (!this.open()) return;
      const companyId = this.companyId();
      const run = this.run();
      untracked(() => {
        if (this.loadedId === run.id) return;
        this.loadedId = run.id;
        this.stream.start(companyId, run.id, run);
      });
    });
  }

  protected toggle(): void {
    const next = !this.open();
    this.choices.set(this.run().id, next);
    this.choice.set(next);
  }

  protected reload(): void {
    this.stream.start(this.companyId(), this.run().id, this.run());
  }

  /**
   * The run's messages, page by page. Without them the folded work shows nothing more than it did;
   * opening it reads them with the rest, or says why it could not.
   */
  private async readSaid(companyId: string, runId: string): Promise<void> {
    const events: RunEvent[] = [];
    let afterSeq = 0;
    try {
      for (;;) {
        const page = await this.api.invoke(listRunEvents, {
          companyId,
          runId,
          afterSeq,
          types: [...SAID_TYPES],
          limit: PAGE_LIMIT,
        });
        events.push(...page.items);
        const last = page.items.at(-1);
        if (page.complete || !last || this.saidId !== runId) break;
        afterSeq = last.seq;
      }
    } catch {
      return;
    }
    if (this.saidId === runId) this.saidItems.set(agentMessages(events));
  }
}
