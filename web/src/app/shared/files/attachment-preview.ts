import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  resource,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideDownload } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDialogImports } from '@spartan-ng/helm/dialog';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../api/generated/api';
import { downloadAttachment } from '../../api/generated/functions';
import type { Attachment } from '../../api/generated/models';
import { ErrorState } from '../error-state';
import { type FileResolver, Markdown } from '../markdown';
import { attachmentUrl, fileSize } from './file-labels';
import {
  buildPreview,
  MAX_CSV_COLUMNS,
  MAX_DIFF_LINES,
  MAX_TEXT_BYTES,
  type Preview,
  type PreviewKind,
  previewKind,
  resolveSibling,
} from './preview';

interface PreviewParams {
  readonly companyId: string;
  readonly conversationId: string;
  readonly attachmentId: string;
  readonly kind: PreviewKind;
}

/** What the preview says under a file it shows only part of; null when it shows all of it. */
export function previewNotice(file: Attachment, preview: Preview): string | null {
  if (!('truncated' in preview)) return null;
  if (preview.kind === 'csv') {
    const shown = [
      ...(preview.truncated ? [`the first ${preview.rows.length} rows`] : []),
      ...(preview.truncatedColumns ? [`the first ${MAX_CSV_COLUMNS} columns`] : []),
    ];
    return shown.length ? `Showing ${shown.join(' and ')}. Download the file for all of it.` : null;
  }
  if (!preview.truncated) return null;
  if (preview.kind === 'diff' && preview.lines.length >= MAX_DIFF_LINES) {
    return `Showing the first ${preview.lines.length} lines. Download the file for all of it.`;
  }
  return `Showing the first ${fileSize(MAX_TEXT_BYTES)} of ${fileSize(file.sizeBytes)}. Download the file for all of it.`;
}

/**
 * A file of the conversation shown in a dialog (ADR-0047): a document an agent produced or a file a
 * member attached. Text, code and CSV are read through the API and bound as text, Markdown goes
 * through the app's renderer with the document's own images and links resolved to its sibling
 * files, and an image is loaded by the browser from the download URL, as an `img`. Nothing of a
 * file is ever interpreted as HTML or script; what cannot be shown is downloaded. The parent owns
 * `file`: null closes the dialog.
 */
@Component({
  selector: 'app-attachment-preview',
  imports: [NgIcon, HlmButton, HlmDialogImports, HlmSpinner, ErrorState, Markdown],
  providers: [provideIcons({ lucideDownload })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <hlm-dialog [state]="file() ? 'open' : 'closed'" (closed)="closed.emit()">
      <hlm-dialog-content
        *hlmDialogPortal="let ctx"
        class="flex max-h-[calc(100dvh-2rem)] flex-col sm:max-w-4xl"
        closeLabel="Close the preview"
        data-testid="attachment-preview"
      >
        @if (file(); as f) {
          <hlm-dialog-header class="pe-8">
            <h2 hlmDialogTitle class="font-mono text-sm break-all" data-testid="preview-name">
              {{ f.fileName }}
            </h2>
            <p hlmDialogDescription>
              {{ size(f.sizeBytes) }} · {{ f.contentType }} · {{ f.uploadedBy }}
            </p>
          </hlm-dialog-header>

          <div
            class="bg-card min-h-24 flex-1 overflow-auto rounded-lg border"
            data-testid="preview-body"
          >
            @if (preview.isLoading()) {
              <div class="flex items-center justify-center p-8" aria-busy="true">
                <hlm-spinner aria-hidden="true" />
                <span class="sr-only" role="status">Loading the file…</span>
              </div>
            } @else if (preview.error()) {
              <app-error-state
                class="block p-4"
                [error]="preview.error()"
                heading="Couldn't load the file"
                [messages]="messages"
                (retry)="preview.reload()"
              />
            } @else if (preview.hasValue()) {
              @let v = preview.value();
              @if (v.kind === 'markdown') {
                <app-markdown class="p-4" [text]="v.text" [files]="files()" />
              } @else if (v.kind === 'text') {
                <pre
                  class="p-4 font-mono text-xs leading-normal break-words whitespace-pre-wrap"
                  data-testid="preview-text"
                  >{{ v.text }}</pre>
              } @else if (v.kind === 'code' || v.kind === 'json') {
                <pre
                  class="p-4 font-mono text-xs leading-normal whitespace-pre"
                  data-testid="preview-code"
                  >{{ v.text }}</pre>
              } @else if (v.kind === 'csv') {
                <table class="w-max min-w-full border-collapse text-xs" data-testid="preview-csv">
                  <caption class="sr-only">
                    {{
                      f.fileName
                    }}
                  </caption>
                  <thead class="bg-card sticky top-0">
                    <tr>
                      @for (cell of v.header; track $index) {
                        <th class="border-b px-2 py-1 text-start font-medium">{{ cell }}</th>
                      }
                    </tr>
                  </thead>
                  <tbody>
                    @for (row of v.rows; track $index) {
                      <tr class="even:bg-muted/40">
                        @for (cell of row; track $index) {
                          <td class="px-2 py-1 align-top tabular-nums">{{ cell }}</td>
                        }
                      </tr>
                    }
                  </tbody>
                </table>
              } @else if (v.kind === 'diff') {
                <table
                  class="w-full border-collapse font-mono text-xs leading-[1.65]"
                  data-testid="preview-diff"
                >
                  <caption class="sr-only">
                    {{
                      f.fileName
                    }}
                  </caption>
                  <tbody>
                    @for (line of v.lines; track $index) {
                      <tr
                        [class.bg-diff-add]="line.type === 'add'"
                        [class.bg-diff-del]="line.type === 'del'"
                        [class.text-muted-foreground]="line.type === 'meta'"
                        [class.text-faint]="line.type === 'hunk'"
                        [attr.data-type]="line.type"
                      >
                        <td
                          class="w-3.5 ps-3.5 align-top select-none"
                          [class.text-success]="line.type === 'add'"
                          [class.text-destructive]="line.type === 'del'"
                        >
                          <span aria-hidden="true">{{ sign(line.type) }}</span
                          ><span class="sr-only">{{ spoken(line.type) }}</span>
                        </td>
                        <td class="pe-3.5">
                          <span class="whitespace-pre">{{ line.text }}</span>
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              } @else if (v.kind === 'image') {
                @if (imageFailed()) {
                  <p class="text-muted-foreground p-4 text-sm" data-testid="preview-image-failed">
                    The image couldn't be shown. Download it to open it.
                  </p>
                }
                <img
                  class="mx-auto block max-h-[70dvh] max-w-full object-contain"
                  [class.hidden]="imageFailed()"
                  [src]="downloadUrl()"
                  [alt]="f.fileName"
                  (error)="imageFailed.set(true)"
                  data-testid="preview-image"
                />
              } @else {
                <p class="text-muted-foreground p-4 text-sm" data-testid="preview-none">
                  No preview for this file. Download it to open it.
                </p>
              }
            }
          </div>

          @if (notice(); as n) {
            <p class="text-muted-foreground text-xs" data-testid="preview-notice">{{ n }}</p>
          }

          <hlm-dialog-footer>
            <a
              hlmBtn
              variant="outline"
              size="sm"
              [href]="downloadUrl()"
              [attr.download]="f.fileName"
              data-testid="preview-download"
            >
              <ng-icon name="lucideDownload" aria-hidden="true" />
              Download
            </a>
            <button hlmBtn size="sm" type="button" hlmDialogClose>Close</button>
          </hlm-dialog-footer>
        }
      </hlm-dialog-content>
    </hlm-dialog>
  `,
})
export class AttachmentPreview {
  private readonly api = inject(Api);

  readonly companyId = input.required<string>();
  readonly conversationId = input.required<string>();
  /** The file to show; null closes the dialog. */
  readonly file = input<Attachment | null>(null);
  /** Every file of the conversation, so a document's relative images and links find its siblings. */
  readonly siblings = input<readonly Attachment[]>([]);
  readonly closed = output<void>();

  protected readonly size = fileSize;
  /** A download answers 410 once retention purged the file; its blob body carries no code. */
  protected readonly messages = {
    'status:410':
      'The company’s retention removed this file’s content. Its name, size and hash stay; the file itself cannot be downloaded any more.',
  };

  protected readonly preview = resource<Preview, PreviewParams | undefined>({
    params: () => {
      const f = this.file();
      return f
        ? {
            companyId: this.companyId(),
            conversationId: this.conversationId(),
            attachmentId: f.id,
            kind: previewKind(f.fileName, f.contentType),
          }
        : undefined;
    },
    loader: async ({ params: { companyId, conversationId, attachmentId, kind } }) => {
      if (kind === 'image' || kind === 'none') return { kind };
      const blob = await this.api.invoke(downloadAttachment, {
        companyId,
        conversationId,
        attachmentId,
      });
      return buildPreview(kind, blob);
    },
  });

  /** Set when the browser could not decode the image; reset for the next file. */
  protected readonly imageFailed = linkedSignal<Attachment | null, boolean>({
    source: this.file,
    computation: () => false,
  });

  protected readonly downloadUrl = computed(() => {
    const f = this.file();
    return f ? attachmentUrl(this.companyId(), this.conversationId(), f.id) : '';
  });

  /** A document's reference to one of the conversation's files as that file's download URL. */
  protected readonly files = computed<FileResolver | null>(() => {
    const f = this.file();
    if (!f) return null;
    const companyId = this.companyId();
    const conversationId = this.conversationId();
    const ids = new Map(this.siblings().map((s) => [s.fileName, s.id]));
    return (href) => {
      const name = resolveSibling(f.fileName, href);
      const id = name === null ? undefined : ids.get(name);
      return id ? attachmentUrl(companyId, conversationId, id) : null;
    };
  });

  protected readonly notice = computed<string | null>(() => {
    const f = this.file();
    const v = this.preview.hasValue() ? this.preview.value() : undefined;
    return f && v ? previewNotice(f, v) : null;
  });

  protected sign(type: string): string {
    return type === 'add' ? '+' : type === 'del' ? '−' : '';
  }

  protected spoken(type: string): string {
    return type === 'add' ? 'added: ' : type === 'del' ? 'removed: ' : '';
  }
}
