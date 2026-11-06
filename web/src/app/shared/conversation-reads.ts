import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  resource,
  signal,
  viewChild,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideEye } from '@ng-icons/lucide';
import { Api } from '../api/generated/api';
import { listConversationReads } from '../api/generated/functions';
import type { ConversationReader } from '../api/generated/models';
import { problemMessage } from '../api/problem';
import { joinWords } from './governance-labels';
import { plural } from './labels';
import { Time } from './time';

const WHAT_LABELS: Record<string, string> = {
  conversation: 'the conversation',
  conversations: 'the list of conversations',
  runs: 'its turns',
  'run-events': 'its transcripts',
  'run-stream': 'a live turn',
  attachments: 'its files',
  attachment: 'a file’s content',
  decisions: 'its questions and approvals',
};

/** What the standing notice says to the conversation's person (security review, row 18). */
export const OWNER_READS_TEXT =
  'The company’s Owner can read this conversation in full. Each read is recorded in the audit log and listed here.';

/** What a reader read, in words: "the conversation, its transcripts and a file’s content". */
export function readKindsPhrase(kinds: readonly string[]): string {
  const words = [...new Set(kinds)].map((k) => WHAT_LABELS[k] ?? k.replace(/-/g, ' '));
  return joinWords(words);
}

/**
 * Who has read it, in a few words after "The Owner can read this": "nobody has", "read once",
 * "read 3 times", "2 people read it".
 */
export function readsSummary(readers: readonly ConversationReader[]): string {
  if (readers.length === 0) return 'nobody has';
  if (readers.length > 1) return `${readers.length} people read it`;
  const reads = readers[0].reads;
  return reads === 1 ? 'read once' : `read ${plural(reads, 'time')}`;
}

let nextId = 0;

/**
 * Who read this conversation besides its person, and when (the package 6 contract, §5.6, §11;
 * `GET /conversations/{id}/reads`): each Owner who read it, how often, first and last, and what of
 * it. Every read is recorded; the notice promises this list. The conversation page shows it for
 * the conversation's person, behind its eye button and in its details; reading the list is not a
 * read.
 *
 * Inputs: the company and the conversation; it reads the list itself, and again when either
 * changes, unless the list is given (`readers`). `intro` says what the list is, under its heading.
 */
@Component({
  selector: 'app-conversation-reads',
  imports: [NgIcon, Time],
  providers: [provideIcons({ lucideEye })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section
      class="flex flex-col gap-2 text-sm"
      [attr.aria-labelledby]="titleId"
      data-testid="conversation-reads"
    >
      <h2 class="flex items-center gap-1.5 text-xs font-semibold" [id]="titleId">
        <ng-icon name="lucideEye" aria-hidden="true" />
        Who else read it
      </h2>
      @if (intro(); as text) {
        <p class="text-muted-foreground text-xs text-pretty">{{ text }}</p>
      }
      <div class="flex flex-col gap-2" [class]="intro() ? 'border-t pt-2' : ''">
        @if (loading()) {
          <p class="text-muted-foreground text-xs" role="status">Loading…</p>
        } @else if (failed()) {
          <p class="text-muted-foreground text-xs">{{ error() }}</p>
        } @else if (shown().length === 0) {
          <p class="text-muted-foreground text-xs" data-testid="no-reads">
            Nobody but you has read it.
          </p>
        } @else {
          <ul class="flex flex-col gap-2">
            @for (reader of shown(); track reader.userId) {
              <li class="text-xs" [attr.data-reader]="reader.name ?? reader.userId">
                <p>
                  <span class="font-semibold">{{ reader.name ?? 'Someone' }}</span>
                  read {{ kinds(reader) }}, {{ times(reader) }}.
                </p>
                <p class="text-muted-foreground">
                  Last <app-time [value]="reader.lastReadAt" />
                  @if (reader.reads > 1) {
                    · first <app-time [value]="reader.firstReadAt" />
                  }
                </p>
              </li>
            }
          </ul>
        }
      </div>
    </section>
  `,
})
export class ConversationReads {
  private readonly api = inject(Api);

  readonly companyId = input.required<string>();
  readonly conversationId = input.required<string>();
  /** The list, when the host read it already; else this reads it. */
  readonly readers = input<readonly ConversationReader[] | null>(null);
  /** What the list is, under its heading. */
  readonly intro = input<string | null>(null);

  protected readonly titleId = `conversation-reads-${nextId++}`;

  protected readonly reads = resource({
    params: () =>
      this.readers()
        ? undefined
        : { companyId: this.companyId(), conversationId: this.conversationId() },
    loader: ({ params }) => this.api.invoke(listConversationReads, params),
  });
  protected readonly shown = computed<readonly ConversationReader[]>(
    () => this.readers() ?? (this.reads.hasValue() ? (this.reads.value() ?? []) : []),
  );
  protected readonly loading = computed(
    () => !this.readers() && this.reads.isLoading() && !this.reads.hasValue(),
  );
  protected readonly failed = computed(() => !this.readers() && !!this.reads.error());
  protected readonly error = computed(() =>
    problemMessage(this.reads.error(), { 'status:404': 'Only its person sees who read it.' }),
  );

  protected kinds(reader: ConversationReader): string {
    return readKindsPhrase(reader.kinds) || 'it';
  }

  protected times(reader: ConversationReader): string {
    return reader.reads === 1 ? 'once' : plural(reader.reads, 'time');
  }
}

/**
 * The standing Owner notice of the conversation page, for its person (security review, row 18):
 * an eye button, "The Owner can read this · nobody has", that opens who else read it
 * (`app-conversation-reads`) in a small popover. It reads the list itself; Escape or a click
 * elsewhere closes the popover, and the button gets the focus back.
 */
@Component({
  selector: 'app-conversation-reads-button',
  imports: [NgIcon, ConversationReads],
  providers: [provideIcons({ lucideEye })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'relative inline-flex',
    '(document:click)': 'onDocumentClick($event)',
    '(document:keydown.escape)': 'onEscape()',
  },
  template: `
    <button
      #trigger
      type="button"
      class="text-muted-foreground hover:bg-accent hover:text-foreground focus-visible:ring-ring/50 aria-expanded:bg-accent inline-flex min-h-7 items-center gap-1.5 rounded-lg px-1.5 text-start text-[0.8125rem] font-medium outline-none focus-visible:ring-3"
      data-testid="owner-notice"
      aria-haspopup="dialog"
      [attr.aria-expanded]="open()"
      [attr.aria-controls]="open() ? panelId : null"
      (click)="toggle()"
    >
      <ng-icon name="lucideEye" class="shrink-0" size="14" aria-hidden="true" />
      <span>The Owner can read this{{ summary() ? ' · ' + summary() : '' }}</span>
    </button>
    @if (open()) {
      <div
        class="bg-popover text-popover-foreground absolute end-0 top-full z-30 mt-1.5 w-[21rem] max-w-[calc(100vw-2rem)] rounded-[14px] border px-4 py-3.5 shadow-[0_12px_32px_-8px_rgb(0_0_0/0.18)]"
        role="dialog"
        aria-label="Who else read it"
        data-testid="reads-popover"
        [id]="panelId"
      >
        <app-conversation-reads
          [companyId]="companyId()"
          [conversationId]="conversationId()"
          [readers]="list()"
          [intro]="introText"
        />
      </div>
    }
  `,
})
export class ConversationReadsButton {
  private readonly api = inject(Api);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly companyId = input.required<string>();
  readonly conversationId = input.required<string>();

  private readonly trigger = viewChild<ElementRef<HTMLButtonElement>>('trigger');

  protected readonly panelId = `conversation-reads-popover-${nextId++}`;
  protected readonly introText = OWNER_READS_TEXT;
  protected readonly open = signal(false);

  private readonly reads = resource({
    params: () => ({ companyId: this.companyId(), conversationId: this.conversationId() }),
    loader: ({ params }) => this.api.invoke(listConversationReads, params),
  });
  /** The list once read; while it loads or failed, the popover reads it again itself. */
  protected readonly list = computed<readonly ConversationReader[] | null>(() =>
    this.reads.hasValue() ? (this.reads.value() ?? []) : null,
  );
  protected readonly summary = computed(() => {
    const list = this.list();
    return list ? readsSummary(list) : '';
  });

  protected toggle(): void {
    this.open.update((open) => !open);
  }

  protected onDocumentClick(event: Event): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.open.set(false);
    }
  }

  protected onEscape(): void {
    if (!this.open()) return;
    this.open.set(false);
    this.trigger()?.nativeElement.focus();
  }
}
