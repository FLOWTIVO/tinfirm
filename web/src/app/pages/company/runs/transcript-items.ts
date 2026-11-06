import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCheck,
  lucideChevronDown,
  lucideChevronRight,
  lucideCircle,
  lucideCircleDot,
  lucideCircleX,
  lucideClock,
  lucideGitBranch,
  lucideGitCommitHorizontal,
  lucideListChecks,
  lucideLoaderCircle,
  lucideUpload,
} from '@ng-icons/lucide';
import type { RateLimitPayload, ToolCallKind } from '../../../api/run-events';
import { Markdown } from '../../../shared/markdown';
import { plural } from '../../../shared/labels';
import { failureClassLabel, shortSha } from '../../../shared/run-status';
import { formatAbsolute } from '../../../shared/time';
import { unifiedDiff } from './diff';
import { TranscriptCall } from './transcript-call';
import {
  type ActivityGroup,
  findLast,
  type GitItem,
  type PlanItem,
  type ToolItem,
  type TranscriptRowItem,
} from './transcript-model';

/*
 * The one renderer of run events (DESIGN.md, The Thread): the run page lists these rows in its
 * transcript, and a thread shows a turn's work with them, folded or live. What the agent said
 * reads as plain Markdown, its thinking in quiet italics, and every stretch of tool calls and log
 * lines as one muted sentence that opens into a row per call.
 */

const PHASE_LABELS: Record<string, string> = {
  preparing: 'Preparing',
  cloning: 'Cloning',
  starting: 'Starting',
  running: 'Running',
  finalizing: 'Finalizing',
  pushing: 'Pushing',
  cancelling: 'Cancelling',
};

export function phaseLabel(phase: string): string {
  return PHASE_LABELS[phase] ?? phase;
}

/** "Usage limit reached (five hour window). Resets Oct 5, 2026, 3:00 PM." */
export function rateLimitText(limit: RateLimitPayload): string {
  const window = limit.window ? ` (${limit.window.replace(/_/g, ' ')} window)` : '';
  const head =
    limit.status === 'rejected'
      ? `Usage limit reached${window}.`
      : `Close to the usage limit${window}${
          typeof limit.utilization === 'number'
            ? `: ${Math.round(limit.utilization * 100)}% used`
            : ''
        }.`;
  return limit.resetsAt ? `${head} Resets ${formatAbsolute(limit.resetsAt)}.` : head;
}

// --- The verbs of tool calls ------------------------------------------------------------------

interface Verb {
  /** The verb of a row in an open group. */
  readonly row: string;
  /** The group sentence's phrase for `n` calls. */
  readonly summary: (n: number) => string;
  /** A title that starts with this word repeats the verb: it is cut off. */
  readonly titleWords: readonly string[];
}

const OTHER: Verb = { row: 'Used', summary: (n) => `used ${plural(n, 'tool')}`, titleWords: [] };

const VERBS: Partial<Record<ToolCallKind, Verb>> = {
  execute: { row: 'Ran', summary: (n) => `ran ${plural(n, 'command')}`, titleWords: [] },
  read: { row: 'Read', summary: (n) => `read ${plural(n, 'file')}`, titleWords: ['read'] },
  search: { row: 'Searched', summary: (n) => `searched ${n}`, titleWords: ['search'] },
  edit: { row: 'Edited', summary: (n) => `edited ${n}`, titleWords: ['edit', 'write'] },
  fetch: { row: 'Fetched', summary: (n) => `fetched ${n}`, titleWords: ['fetch'] },
  delete: { row: 'Deleted', summary: (n) => `deleted ${n}`, titleWords: ['delete'] },
  move: { row: 'Moved', summary: (n) => `moved ${n}`, titleWords: ['move'] },
};

function verbOf(kind: ToolCallKind): Verb {
  return VERBS[kind] ?? OTHER;
}

/** The verb of a tool call's row: "Ran", "Read", "Searched"… */
export function rowVerb(tool: ToolItem): string {
  return verbOf(tool.toolKind).row;
}

/** What the call worked on: its title, without a leading word that repeats the verb. */
export function rowTarget(tool: ToolItem): string {
  const title = tool.title.trim();
  const [first, ...rest] = title.split(/\s+/);
  if (rest.length > 0 && verbOf(tool.toolKind).titleWords.includes(first.toLowerCase())) {
    return rest.join(' ');
  }
  return title;
}

/** "Ran 5 commands, read 2 files, searched 1": the kinds in order of first use. */
export function groupSummary(tools: readonly ToolItem[]): string {
  const counts = new Map<Verb, number>();
  for (const tool of tools) {
    const verb = verbOf(tool.toolKind);
    counts.set(verb, (counts.get(verb) ?? 0) + 1);
  }
  const text = [...counts].map(([verb, n]) => verb.summary(n)).join(', ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

const ICONS = provideIcons({
  lucideCheck,
  lucideChevronDown,
  lucideChevronRight,
  lucideCircle,
  lucideCircleDot,
  lucideCircleX,
  lucideClock,
  lucideGitBranch,
  lucideGitCommitHorizontal,
  lucideListChecks,
  lucideLoaderCircle,
  lucideUpload,
});

/** Log lines of one block shown before "Show earlier". */
const LOG_LINES = 50;
let nextGroupId = 0;

/**
 * A stretch of tool calls and log lines as one sentence ("Ran 5 commands, read 2 files"), closed
 * by default even live. It opens into one row per call or log line, and a row opens into the
 * call's details. While a call runs, the closed group says which; after a failure, which failed.
 */
@Component({
  selector: 'app-transcript-group',
  imports: [NgIcon, TranscriptCall],
  providers: [ICONS],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex min-w-0 flex-col' },
  template: `
    <button
      type="button"
      class="text-muted-foreground hover:text-foreground focus-visible:ring-ring/50 flex w-fit max-w-full min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5 rounded-sm text-start text-[0.8125rem] outline-none focus-visible:ring-3"
      data-testid="transcript-group"
      [attr.aria-expanded]="open()"
      [attr.aria-controls]="listId"
      (click)="open.set(!open())"
    >
      @if (current()) {
        <ng-icon
          name="lucideLoaderCircle"
          class="text-primary shrink-0 animate-spin text-[14px] motion-reduce:animate-none"
          aria-hidden="true"
        />
      } @else {
        <ng-icon
          [name]="open() ? 'lucideChevronDown' : 'lucideChevronRight'"
          class="shrink-0 text-[14px]"
          aria-hidden="true"
        />
      }
      <span>{{ summary() }}</span>
      @if (current()) {
        <span>· now</span>
      }
      @if (tools().length > 0 && logLines() > 0) {
        <span class="text-muted-foreground">· {{ logLinesText() }}</span>
      }
      @if (failed() > 0) {
        <span class="text-destructive">· {{ failed() }} failed</span>
      }
      @if (!open() && headline(); as tool) {
        <code
          class="min-w-0 flex-1 truncate font-mono text-xs"
          [class]="current() ? 'text-foreground' : 'text-destructive'"
          [title]="tool.title"
          >{{ target(tool) }}</code
        >
      }
    </button>
    @if (open()) {
      <ol class="mt-1 flex flex-col gap-0.5" aria-label="Tool calls and log lines" [id]="listId">
        @for (row of item().items; track row.id) {
          @if (row.kind === 'tool') {
            @let opened = calls().has(row.id);
            <li data-kind="tool">
              <button
                type="button"
                class="hover:text-foreground focus-visible:ring-ring/50 flex w-full min-w-0 items-baseline gap-2.5 rounded-sm py-[3px] ps-[22px] text-start text-[0.8125rem] outline-none focus-visible:ring-3"
                [attr.aria-expanded]="opened"
                (click)="toggleCall(row.id)"
              >
                <span
                  class="w-16 shrink-0"
                  [class]="opened ? 'text-foreground' : 'text-muted-foreground'"
                  >{{ verb(row) }}</span
                >
                <code
                  class="min-w-0 flex-1 truncate font-mono text-xs"
                  [class]="
                    row.status === 'failed'
                      ? 'text-destructive'
                      : opened
                        ? 'text-foreground'
                        : 'text-muted-foreground'
                  "
                  [title]="row.title"
                  >{{ target(row) }}</code
                >
                @if (notes().get(row.id); as note) {
                  <span class="shrink-0 font-mono text-xs" [class]="note.tone">{{
                    note.text
                  }}</span>
                }
              </button>
              @if (opened) {
                <app-transcript-call [item]="row" />
              }
            </li>
          } @else {
            @let lines = allLogs() ? row.lines : row.lines.slice(-logLimit);
            @if (lines.length < row.lines.length) {
              <li>
                <button
                  type="button"
                  class="text-muted-foreground hover:text-foreground py-[3px] ps-[22px] text-[0.8125rem]"
                  (click)="allLogs.set(true)"
                >
                  Show {{ row.lines.length - lines.length }} earlier log lines
                </button>
              </li>
            }
            @for (line of lines; track line.seq) {
              <li
                class="flex min-w-0 items-baseline gap-2.5 py-[3px] ps-[22px] text-[0.8125rem]"
                data-kind="log"
                [attr.data-level]="line.level"
              >
                <span
                  class="w-16 shrink-0"
                  [class.text-warning]="line.level === 'warn'"
                  [class.text-destructive]="line.level === 'error'"
                  [class.text-muted-foreground]="line.level === 'info' || line.level === 'debug'"
                  >{{ line.level }}</span
                >
                <code
                  class="text-muted-foreground min-w-0 flex-1 truncate font-mono text-xs"
                  [title]="line.message"
                  >{{ line.message }}</code
                >
              </li>
            }
          }
        }
      </ol>
    }
  `,
})
export class TranscriptGroup {
  readonly item = input.required<ActivityGroup>();
  /** The run is still going: a call that has not finished is running now. */
  readonly live = input(false);

  protected readonly listId = `transcript-group-${nextGroupId++}`;
  protected readonly open = signal(false);
  protected readonly calls = signal<ReadonlySet<string>>(new Set());
  protected readonly allLogs = signal(false);
  protected readonly logLimit = LOG_LINES;
  protected readonly verb = rowVerb;
  protected readonly target = rowTarget;

  protected readonly tools = computed(() =>
    this.item().items.filter((row): row is ToolItem => row.kind === 'tool'),
  );
  protected readonly logLines = computed(() =>
    this.item().items.reduce((n, row) => n + (row.kind === 'logs' ? row.lines.length : 0), 0),
  );
  protected readonly logLinesText = computed(() => plural(this.logLines(), 'log line'));
  /** "Ran 2 commands", or for a stretch of log lines only, "3 log lines". */
  protected readonly summary = computed(() =>
    this.tools().length > 0 ? groupSummary(this.tools()) : this.logLinesText(),
  );
  protected readonly failed = computed(
    () => this.tools().filter((tool) => tool.status === 'failed').length,
  );
  /** The call running now, while the run is live. */
  protected readonly current = computed(() => {
    if (!this.live()) return null;
    return (
      findLast(
        this.tools(),
        (tool) => tool.status === 'in_progress' || tool.status === 'pending',
      ) ?? null
    );
  });
  /** What the closed group names: the running call, else the last one that failed. */
  protected readonly headline = computed(
    () => this.current() ?? findLast(this.tools(), (tool) => tool.status === 'failed') ?? null,
  );
  /** The note at the end of a call's row: failed, running, or the lines a change added and removed. */
  protected readonly notes = computed(() => {
    const notes = new Map<string, { text: string; tone: string }>();
    for (const tool of this.tools()) {
      if (tool.status === 'failed') {
        notes.set(tool.id, { text: 'failed', tone: 'text-destructive' });
      } else if (tool.status === 'in_progress' || tool.status === 'pending') {
        notes.set(
          tool.id,
          this.live()
            ? { text: 'running', tone: 'text-primary' }
            : { text: 'stopped', tone: 'text-muted-foreground' },
        );
      } else if (tool.diffs.length > 0) {
        let added = 0;
        let removed = 0;
        for (const diff of tool.diffs) {
          const { stats } = unifiedDiff(diff.oldText ?? null, diff.newText);
          added += stats.added;
          removed += stats.removed;
        }
        notes.set(tool.id, { text: `+${added} −${removed}`, tone: 'text-muted-foreground' });
      }
    }
    return notes;
  });

  protected toggleCall(id: string): void {
    const next = new Set(this.calls());
    if (!next.delete(id)) next.add(id);
    this.calls.set(next);
  }
}

/** What a git step did, in a few words: "Checked out agent/SHOP-241", "Pushed … (2 commits)". */
function gitText(item: GitItem): string {
  const { action, branch, commits } = item;
  const n = commits !== null ? ` (${plural(commits, 'commit')})` : '';
  switch (action) {
    case 'cloned':
      return `Checked out ${branch}`;
    case 'committed':
      return `Committed on ${branch}${n}`;
    case 'pushed':
      return `Pushed ${branch}${n}`;
    case 'nothing_to_push':
      return `Nothing to push on ${branch}`;
    default:
      return `${action} ${branch}`;
  }
}

const GIT_ICONS: Record<string, string> = {
  pushed: 'lucideUpload',
  committed: 'lucideGitCommitHorizontal',
};

/** One row of a transcript, by kind. */
@Component({
  selector: 'app-transcript-row',
  imports: [NgIcon, Markdown, TranscriptGroup],
  providers: [ICONS],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block min-w-0', '[attr.data-kind]': 'item().kind' },
  template: `
    @let row = item();
    @switch (row.kind) {
      @case ('message') {
        <app-markdown class="max-w-[66ch] text-[0.9375rem] leading-normal" [text]="row.text" />
      }
      @case ('thought') {
        <p
          class="text-muted-foreground max-w-[66ch] text-sm break-words whitespace-pre-line italic"
        >
          <span class="sr-only">Thinking: </span>{{ row.text.trim() }}
        </p>
      }
      @case ('git') {
        <p
          class="text-muted-foreground flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[0.8125rem]"
        >
          <ng-icon [name]="gitIcon(row.action)" class="shrink-0 text-[13px]" aria-hidden="true" />
          <span>{{ gitText(row) }}</span>
          @if (row.dir) {
            <span data-testid="git-dir" [title]="'/workspace/' + row.dir"
              >in <code class="font-mono">{{ row.dir }}</code></span
            >
          }
          @if (row.headSha) {
            <code class="text-muted-foreground font-mono" [title]="row.headSha">{{ sha(row.headSha) }}</code>
          }
        </p>
      }
      @case ('status') {
        <p class="text-muted-foreground flex min-w-0 items-baseline gap-1.5 text-xs">
          <span class="text-muted-foreground font-semibold" [attr.data-phase]="row.phase">{{
            phase(row.phase)
          }}</span>
          @if (row.message; as message) {
            <span class="min-w-0 truncate" [title]="message">{{ message }}</span>
          }
        </p>
      }
      @case ('plan') {
        <div class="text-muted-foreground flex flex-col gap-[3px] text-[0.8125rem]">
          <p class="flex items-center gap-2">
            <ng-icon name="lucideListChecks" class="text-[14px]" aria-hidden="true" />
            Plan
            <span class="text-muted-foreground">{{ planDone(row) }} of {{ row.entries.length }}</span>
          </p>
          <ul class="flex flex-col gap-[3px]" aria-label="Plan">
            @for (step of row.entries; track $index) {
              @let now = step.status === 'in_progress' && live();
              <li
                class="flex items-center gap-2 ps-[22px]"
                [class.text-foreground]="now"
                [class.text-muted-foreground]="step.status !== 'completed' && !now"
              >
                <ng-icon
                  class="shrink-0 text-[13px]"
                  [class.text-success]="step.status === 'completed'"
                  [class.text-primary]="now"
                  [name]="
                    step.status === 'completed'
                      ? 'lucideCheck'
                      : now
                        ? 'lucideCircleDot'
                        : 'lucideCircle'
                  "
                  aria-hidden="true"
                />
                {{ step.content }}
                <span class="sr-only"
                  >({{
                    step.status === 'completed' ? 'done' : now ? 'in progress' : 'not done'
                  }})</span
                >
              </li>
            }
          </ul>
        </div>
      }
      @case ('rate-limit') {
        <p class="text-warning flex items-center gap-2 text-[0.8125rem]" role="note">
          <ng-icon name="lucideClock" class="shrink-0 text-[13px]" aria-hidden="true" />
          {{ rateLimit(row.limit) }}
        </p>
      }
      @case ('error') {
        <p class="text-destructive flex items-start gap-2 text-[0.8125rem]" role="note">
          <ng-icon name="lucideCircleX" class="mt-[3px] shrink-0 text-[13px]" aria-hidden="true" />
          <span class="min-w-0 break-words whitespace-pre-line"
            ><span class="font-semibold">{{ failurePrefix(row.failureClass) }}</span
            >{{ row.message }}</span
          >
        </p>
      }
      @default {
        @if (group(); as g) {
          <app-transcript-group [item]="g" [live]="live()" />
        }
      }
    }
  `,
})
export class TranscriptRow {
  readonly item = input.required<TranscriptRowItem>();
  /** Whether the run is still going; unfinished calls of a finished run show as stopped. */
  readonly live = input(false);

  /** A group, or a lone call or log block shown as a group of one. */
  protected readonly group = computed<ActivityGroup | null>(() => {
    const row = this.item();
    if (row.kind === 'activity') return row;
    if (row.kind === 'tool' || row.kind === 'logs') {
      return { kind: 'activity', id: `activity-${row.id}`, seq: row.seq, at: row.at, items: [row] };
    }
    return null;
  });

  protected readonly sha = shortSha;
  protected readonly phase = phaseLabel;
  protected readonly rateLimit = rateLimitText;
  protected readonly gitText = gitText;

  /** "Git error: " before the message, when the error names its failure class. */
  protected failurePrefix(failureClass: string | null): string {
    const label = failureClassLabel(failureClass);
    return label ? `${label}: ` : '';
  }

  protected gitIcon(action: string): string {
    return GIT_ICONS[action] ?? 'lucideGitBranch';
  }

  protected planDone(plan: PlanItem): number {
    return plan.entries.filter((e) => e.status === 'completed').length;
  }
}

/**
 * A turn's open work in a thread, behind a hairline on the left: the checkout, groups of tool
 * calls, what the agent thought and said, and while live its plan. New entries of a live run
 * fade in.
 */
@Component({
  selector: 'app-transcript-work',
  imports: [TranscriptRow],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'ms-1.5 flex min-w-0 flex-col gap-3 border-s ps-[18px]' },
  template: `
    @for (entry of entries(); track entry.id) {
      <app-transcript-row [class.thread-enter]="live()" [item]="entry" [live]="live()" />
    }
  `,
})
export class TranscriptWork {
  readonly entries = input.required<readonly TranscriptRowItem[]>();
  readonly live = input(false);
}
