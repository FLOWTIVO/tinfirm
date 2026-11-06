import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideDownload,
  lucideExternalLink,
  lucideEye,
  lucideFileText,
  lucideGitPullRequest,
  lucideLink,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import type { Attachment, Output } from '../../../api/generated/models';
import { AttachmentPreview } from '../../../shared/files/attachment-preview';
import { attachmentUrl, fileSize } from '../../../shared/files/file-labels';
import { formatDay } from '../../../shared/time';

type ChangeRequestState = NonNullable<Output['changeRequestState']>;

/** A change request's state as its code host last said it, and its tone (DESIGN.md: green for the agent's work). */
export const CHANGE_REQUEST_STATES: Record<ChangeRequestState, { label: string; tone: string }> = {
  OPEN: { label: 'Open', tone: 'bg-primary/10 text-primary' },
  DRAFT: { label: 'Draft', tone: 'bg-muted text-muted-foreground' },
  MERGED: { label: 'Merged', tone: 'bg-success/10 text-success' },
  CLOSED: { label: 'Closed', tone: 'bg-muted text-muted-foreground' },
  UNKNOWN: { label: 'State unknown', tone: 'bg-muted text-muted-foreground' },
};

/** "Removed on 12 Oct 2026 under the company's retention" */
export function purgedText(at: string): string {
  return `Removed on ${formatDay(at)} under the company’s retention`;
}

/** One output as the column shows it: its document's file when it has one. */
interface OutputRow {
  readonly output: Output;
  readonly file: Attachment | null;
}

/**
 * What a conversation produced (plan §4.1, the side column), as flat rows under "Outputs" and
 * their count: the change requests the server opened after a turn pushed, with their state at the
 * code host; the documents the agent saved, each previewed in place (ADR-0047) and downloaded
 * with the row's icon buttons; and the links it saved. Each names the turn that made it. An output
 * the company's retention removed keeps its place and says so. It reads only what it is given: the
 * conversation's `outputs` and `attachments`.
 */
@Component({
  selector: 'app-conversation-outputs',
  imports: [RouterLink, NgIcon, HlmButton, AttachmentPreview],
  providers: [
    provideIcons({
      lucideDownload,
      lucideExternalLink,
      lucideEye,
      lucideFileText,
      lucideGitPullRequest,
      lucideLink,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex min-w-0 flex-col gap-2', 'data-testid': 'conversation-outputs' },
  template: `
    <div class="flex items-center gap-1.5">
      <h2 class="text-[0.8125rem] font-semibold" [id]="headingId">Outputs</h2>
      @if (rows().length > 0) {
        <span class="text-muted-foreground font-mono text-xs tabular-nums">{{
          rows().length
        }}</span>
      }
    </div>
    @if (rows().length === 0) {
      <p class="text-muted-foreground text-[0.8125rem] text-pretty" data-testid="outputs-empty">
        Nothing yet. The change requests, documents and links the agent makes appear here.
      </p>
    } @else {
      <ul class="flex flex-col gap-2.5" [attr.aria-labelledby]="headingId">
        @for (row of rows(); track row.output.id) {
          @let o = row.output;
          <li
            class="flex min-w-0 items-start gap-1.5"
            data-testid="output"
            [attr.data-kind]="o.kind"
            [attr.data-output-id]="o.id"
          >
            <ng-icon
              class="text-muted-foreground mt-[3px] shrink-0"
              size="14"
              [name]="icons[o.kind]"
              aria-hidden="true"
            />
            <div class="flex min-w-0 flex-1 flex-col gap-0.5 text-[0.8125rem]">
              <span class="sr-only">{{ kinds[o.kind] }}:</span>
              @if (o.purgedAt) {
                <span class="text-muted-foreground italic" data-testid="output-purged">
                  {{ purged(o.purgedAt) }}
                </span>
              } @else if (o.url && o.kind !== 'DOCUMENT') {
                <a
                  class="text-foreground hover:decoration-primary focus-visible:ring-ring/50 w-fit max-w-full rounded-sm font-medium break-words underline decoration-1 underline-offset-2 outline-none focus-visible:ring-3"
                  target="_blank"
                  rel="noopener noreferrer"
                  data-testid="output-link"
                  [href]="o.url"
                  >{{ o.title || o.url }}<span class="sr-only"> (opens in a new tab)</span>
                  <ng-icon
                    class="text-muted-foreground ms-0.5 inline align-[-2px]"
                    name="lucideExternalLink"
                    size="12"
                    aria-hidden="true"
                /></a>
              } @else {
                <span class="font-medium break-words" data-testid="output-title">{{
                  o.title || row.file?.fileName || 'Untitled'
                }}</span>
              }
              @if (o.kind === 'CHANGE_REQUEST') {
                <div class="flex flex-wrap items-center gap-x-1.5 gap-y-1 text-xs">
                  @if (o.changeRequestState; as state) {
                    <span
                      class="inline-flex h-5 items-center rounded-full px-2 text-[0.6875rem] font-semibold"
                      data-testid="change-request-state"
                      [class]="states[state].tone"
                      [attr.data-state]="state"
                      >{{ states[state].label }}</span
                    >
                  }
                  <span class="text-muted-foreground font-mono break-all">
                    {{ o.repositoryName ?? 'A removed repository'
                    }}{{ o.externalNumber ? ' #' + o.externalNumber : '' }}
                  </span>
                </div>
              }
              @if (o.kind === 'DOCUMENT' && row.file; as f) {
                @if (f.contentPurgedAt) {
                  <span class="text-muted-foreground text-xs" data-testid="document-purged">
                    {{ f.fileName }} · {{ purged(f.contentPurgedAt) }}
                  </span>
                } @else {
                  <span class="text-muted-foreground font-mono text-xs break-all"
                    >{{ f.fileName }} · {{ size(f.sizeBytes) }}</span
                  >
                }
              }
              @if (o.runId) {
                <a
                  class="text-muted-foreground hover:text-foreground focus-visible:ring-ring/50 w-fit rounded-sm text-xs underline underline-offset-2 outline-none focus-visible:ring-3"
                  data-testid="output-run"
                  [routerLink]="['/c', companyId(), 'runs', o.runId]"
                  >From turn {{ turnNumbers()[o.runId] ?? '' }} · View run</a
                >
              }
            </div>
            @if (o.kind === 'DOCUMENT' && !o.purgedAt && row.file; as f) {
              @if (!f.contentPurgedAt) {
                <button
                  hlmBtn
                  variant="ghost"
                  size="icon-sm"
                  type="button"
                  class="text-muted-foreground size-7 max-sm:size-11"
                  data-testid="document-preview"
                  title="Preview"
                  [attr.aria-label]="'Preview ' + (o.title || f.fileName)"
                  (click)="previewing.set(f)"
                >
                  <ng-icon name="lucideEye" size="14" aria-hidden="true" />
                </button>
                <a
                  hlmBtn
                  variant="ghost"
                  size="icon-sm"
                  class="text-muted-foreground size-7 max-sm:size-11"
                  data-testid="document-download"
                  title="Download"
                  [href]="url(f)"
                  [attr.download]="f.fileName"
                  [attr.aria-label]="'Download ' + (o.title || f.fileName)"
                >
                  <ng-icon name="lucideDownload" size="14" aria-hidden="true" />
                </a>
              }
            }
          </li>
        }
      </ul>
    }
    <!-- Out of the column's flow: it shows as a dialog. -->
    <app-attachment-preview
      class="absolute"
      [companyId]="companyId()"
      [conversationId]="conversationId()"
      [file]="previewing()"
      [siblings]="attachments()"
      (closed)="previewing.set(null)"
    />
  `,
})
export class ConversationOutputs {
  readonly companyId = input.required<string>();
  readonly conversationId = input.required<string>();
  readonly outputs = input<readonly Output[]>([]);
  /** The conversation's files: a document's file is one of them (`Output.attachmentId`). */
  readonly attachments = input<readonly Attachment[]>([]);
  /** The conversation's turns by run id, to say "From turn 2"; optional. */
  readonly turns = input<readonly { id: string; turnNumber: number }[]>([]);

  private static nextId = 0;
  protected readonly headingId = `conversation-outputs-${ConversationOutputs.nextId++}`;

  protected readonly icons: Record<Output['kind'], string> = {
    CHANGE_REQUEST: 'lucideGitPullRequest',
    DOCUMENT: 'lucideFileText',
    LINK: 'lucideLink',
  };
  protected readonly kinds: Record<Output['kind'], string> = {
    CHANGE_REQUEST: 'Change request',
    DOCUMENT: 'Document',
    LINK: 'Link',
  };
  protected readonly states = CHANGE_REQUEST_STATES;
  protected readonly size = fileSize;
  protected readonly purged = purgedText;

  /** The document shown in the preview dialog. */
  protected readonly previewing = signal<Attachment | null>(null);

  /** Change requests first, then documents, then links; the newest first within each. */
  protected readonly rows = computed<OutputRow[]>(() => {
    const files = new Map(this.attachments().map((a) => [a.id, a]));
    const order: Output['kind'][] = ['CHANGE_REQUEST', 'DOCUMENT', 'LINK'];
    return [...this.outputs()]
      .sort(
        (a, b) =>
          order.indexOf(a.kind) - order.indexOf(b.kind) || b.createdAt.localeCompare(a.createdAt),
      )
      .map((output) => ({
        output,
        file: output.attachmentId ? (files.get(output.attachmentId) ?? null) : null,
      }));
  });

  protected readonly turnNumbers = computed<Record<string, number>>(() =>
    Object.fromEntries(this.turns().map((t) => [t.id, t.turnNumber])),
  );

  protected url(file: Attachment): string {
    return attachmentUrl(this.companyId(), this.conversationId(), file.id);
  }
}
