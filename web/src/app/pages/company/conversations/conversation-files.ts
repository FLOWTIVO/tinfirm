import {
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  inject,
  input,
  linkedSignal,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCircleAlert,
  lucideDownload,
  lucideEye,
  lucidePaperclip,
  lucideRotateCw,
  lucideTrash2,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { deleteAttachment, uploadAttachment } from '../../../api/generated/functions';
import type { Attachment, CredentialMatch } from '../../../api/generated/models';
import { credentialMatches, hasProblemCode, problemMessage, toProblem } from '../../../api/problem';
import { Notify } from '../../../core/notify';
import { AttachmentPreview } from '../../../shared/files/attachment-preview';
import { attachmentUrl, fileSize } from '../../../shared/files/file-labels';
import { Time } from '../../../shared/time';
import { purgedText } from './conversation-outputs';

/** The most the server stores of one file (`413 ATTACHMENT_TOO_LARGE`). */
export const MAX_FILE_BYTES = 25 * 1024 * 1024;

/**
 * An upload in progress or refused. `key` is its `Idempotency-Key` (package R): one per file the
 * person picked, sent again when the same upload is retried after the network or the server
 * failed, so that a file the server did store is not stored twice.
 */
interface Upload {
  readonly file: File;
  readonly key: string;
  readonly busy: boolean;
  /** The refusal in words, while it stands. */
  readonly error: string | null;
  /** Whether trying the same request again may work (a transport or server failure). */
  readonly retryable: boolean;
  /** What the credential scan found (`422 CREDENTIAL_FOUND`), never a value. */
  readonly matches: readonly CredentialMatch[];
}

/** Whether a failure is worth retrying with the same key: the request may not have arrived. */
function retryable(error: unknown): boolean {
  const status = toProblem(error).status;
  return status <= 0 || status >= 500 || status === 429;
}

/** Messages of this column for the problems the files' calls answer. */
const MESSAGES: Record<string, string> = {
  CONVERSATION_ENDED: 'This conversation has ended: its files stay, and it takes no new ones.',
  'status:410': 'The company’s retention removed this file’s content.',
};

/**
 * A conversation's files (plan §4.1, the side column): the files its person added and the
 * documents its agent saved, which its turns see read-only under `/attachments`. Each is previewed
 * in place without being interpreted (ADR-0047) and downloaded as a file. Its person adds files
 * while it is open, with an `Idempotency-Key` per upload (package R), and deletes their own; an
 * output's document stays while its output does (`ATTACHMENT_IS_OUTPUT`), which the row says
 * before anyone tries. The credential scan's refusal names what it found, never the value; an
 * OWNER or ADMIN may store a file whose only matches are known formats after acknowledging it
 * (ADR-0014). A file the company's retention purged keeps its name, size and date, and says so.
 *
 * It holds its own copy of the list, updated by what the server answers, and tells the page
 * (`added`, `deleted`) so that the conversation can be read again. Each file is a flat row with
 * its icon buttons; `pick()` opens the picker for the page (the composer's attach button).
 */
@Component({
  selector: 'app-conversation-files',
  imports: [
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmSpinner,
    AttachmentPreview,
    Time,
  ],
  providers: [
    provideIcons({
      lucideCircleAlert,
      lucideDownload,
      lucideEye,
      lucidePaperclip,
      lucideRotateCw,
      lucideTrash2,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex min-w-0 flex-col gap-2', 'data-testid': 'conversation-files' },
  template: `
    <div class="flex min-h-7 items-center gap-1.5">
      <h2 class="text-[0.8125rem] font-semibold" [id]="headingId">Files</h2>
      @if (files().length > 0) {
        <span class="text-muted-foreground font-mono text-xs tabular-nums">{{
          files().length
        }}</span>
      }
      <span class="flex-1"></span>
      @if (canAdd()) {
        <input
          #picker
          type="file"
          class="sr-only"
          tabindex="-1"
          aria-hidden="true"
          data-testid="file-input"
          (change)="picked($event)"
        />
        <button
          hlmBtn
          variant="outline"
          size="xs"
          type="button"
          class="h-7 max-sm:h-11 max-sm:px-3.5"
          data-testid="add-file"
          [disabled]="uploading()"
          (click)="picker.click()"
        >
          <ng-icon name="lucidePaperclip" aria-hidden="true" />
          Add a file
        </button>
      }
    </div>

    @if (upload(); as u) {
      <div
        class="bg-muted flex flex-col gap-2 rounded-[10px] px-3 py-2.5"
        data-testid="upload"
        [attr.aria-busy]="u.busy"
      >
        <div class="flex min-w-0 items-center gap-2 text-sm">
          @if (u.busy) {
            <hlm-spinner class="size-3.5 shrink-0" aria-hidden="true" />
          }
          <span class="min-w-0 flex-1 truncate font-mono text-xs">{{ u.file.name }}</span>
          <span class="text-muted-foreground shrink-0 text-xs">{{ size(u.file.size) }}</span>
        </div>
        @if (u.busy) {
          <span class="sr-only" role="status">Adding {{ u.file.name }}…</span>
        }
        @if (u.error; as message) {
          <div hlmAlert variant="destructive" data-testid="upload-error">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription role="alert">{{ message }}</p>
          </div>
          @if (u.matches.length > 0) {
            <ul class="text-muted-foreground list-disc ps-5 text-xs" data-testid="upload-matches">
              @for (m of u.matches; track m.kind + m.name) {
                <li>
                  {{ m.kind === 'VAULT_SECRET' ? 'The value of the secret' : 'A' }}
                  <span class="text-foreground font-mono">{{ m.name }}</span>
                  {{ m.kind === 'VAULT_SECRET' ? 'from the company’s vault' : 'credential' }}
                </li>
              }
            </ul>
          }
          @if (acknowledgeable()) {
            <label class="flex items-start gap-2 text-xs" data-testid="upload-acknowledge">
              <input
                type="checkbox"
                class="accent-human size-4"
                [checked]="acknowledged()"
                (change)="acknowledged.set($any($event.target).checked)"
              />
              <span
                >I checked the file: what looks like a credential is not one, or may be shared with
                this conversation's turns.</span
              >
            </label>
          }
          <div class="flex flex-wrap gap-1.5">
            @if (u.retryable) {
              <button
                hlmBtn
                variant="outline"
                size="xs"
                type="button"
                data-testid="upload-retry"
                (click)="send(false)"
              >
                <ng-icon name="lucideRotateCw" aria-hidden="true" />
                Try again
              </button>
            }
            @if (acknowledgeable()) {
              <button
                hlmBtn
                size="xs"
                type="button"
                data-testid="upload-anyway"
                [disabled]="!acknowledged()"
                (click)="send(true)"
              >
                Add it anyway
              </button>
            }
            <button
              hlmBtn
              variant="ghost"
              size="xs"
              type="button"
              data-testid="upload-dismiss"
              (click)="upload.set(null)"
            >
              Dismiss
            </button>
          </div>
        }
      </div>
    }

    @if (files().length === 0) {
      <p class="text-muted-foreground text-[0.8125rem] text-pretty" data-testid="files-empty">
        @if (canAdd()) {
          No files yet. What you add here, every turn reads under
          <code class="font-mono text-xs">/attachments</code>.
        } @else {
          No files.
        }
      </p>
    } @else {
      <ul class="flex flex-col gap-2.5" [attr.aria-labelledby]="headingId">
        @for (f of files(); track f.id) {
          <li
            class="flex min-w-0 items-start gap-1"
            data-testid="file"
            [attr.data-file-id]="f.id"
            [attr.data-purged]="f.contentPurgedAt ? true : null"
          >
            <div class="flex min-w-0 flex-1 flex-col gap-0.5">
              <span class="font-mono text-xs font-medium break-all" data-testid="file-name">{{
                f.fileName
              }}</span>
              <span class="text-muted-foreground text-xs">
                {{ size(f.sizeBytes) }} ·
                {{ f.agentId ? 'saved by ' + f.uploadedBy : 'added by ' + f.uploadedBy }} ·
                <app-time [value]="f.createdAt" />
              </span>
              @if (f.note) {
                <p class="text-muted-foreground text-xs break-words">{{ f.note }}</p>
              }
              @if (f.contentPurgedAt) {
                <p class="text-muted-foreground text-xs italic" data-testid="file-purged">
                  {{ purged(f.contentPurgedAt) }}. Its name, size and hash stay.
                </p>
              }
              @if (f.outputId && canDelete()) {
                <p class="text-muted-foreground text-xs" data-testid="file-is-output">
                  An output’s document: it stays while its output does.
                </p>
              }
            </div>
            @if (!f.contentPurgedAt) {
              <button
                hlmBtn
                variant="ghost"
                size="icon-sm"
                type="button"
                class="text-muted-foreground size-7 max-sm:size-11"
                data-testid="file-preview"
                title="Preview"
                [attr.aria-label]="'Preview ' + f.fileName"
                (click)="previewing.set(f)"
              >
                <ng-icon name="lucideEye" size="14" aria-hidden="true" />
              </button>
              <a
                hlmBtn
                variant="ghost"
                size="icon-sm"
                class="text-muted-foreground size-7 max-sm:size-11"
                data-testid="file-download"
                title="Download"
                [href]="url(f)"
                [attr.download]="f.fileName"
                [attr.aria-label]="'Download ' + f.fileName"
              >
                <ng-icon name="lucideDownload" size="14" aria-hidden="true" />
              </a>
            }
            @if (canDelete() && !f.outputId) {
              <button
                hlmBtn
                variant="ghost"
                size="icon-sm"
                type="button"
                class="text-destructive hover:bg-destructive/10 hover:text-destructive size-7 max-sm:size-11"
                data-testid="file-delete"
                title="Delete"
                [attr.aria-label]="'Delete ' + f.fileName"
                (click)="askDelete(f)"
              >
                <ng-icon name="lucideTrash2" size="14" aria-hidden="true" />
              </button>
            }
          </li>
        }
      </ul>
      @if (canAdd()) {
        <p class="text-muted-foreground text-xs" data-testid="files-where">
          Every turn reads these under <code class="font-mono">/attachments</code>.
        </p>
      }
    }

    @if (note(); as n) {
      <p class="text-muted-foreground text-xs text-pretty" data-testid="files-note">{{ n }}</p>
    }

    <!-- Out of the column's flow: they show as dialogs. -->
    <app-attachment-preview
      class="absolute"
      [companyId]="companyId()"
      [conversationId]="conversationId()"
      [file]="previewing()"
      [siblings]="files()"
      (closed)="previewing.set(null)"
    />

    <hlm-alert-dialog
      class="absolute"
      [state]="deleting() ? 'open' : 'closed'"
      (closed)="deleting.set(null); deleteError.set(null)"
    >
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx" data-testid="delete-file-dialog">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle class="break-all">Delete {{ deleting()?.fileName }}?</h2>
          <p hlmAlertDialogDescription>
            The next turns no longer see it under /attachments. You can't undo this.
          </p>
        </hlm-alert-dialog-header>
        @if (deleteError(); as message) {
          <div hlmAlert variant="destructive" data-testid="delete-file-error">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="deleteBusy()">Cancel</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            data-testid="confirm-delete-file"
            [disabled]="deleteBusy() || !!deleteError()"
            (click)="confirmDelete()"
          >
            @if (deleteBusy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Delete file
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class ConversationFiles {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);

  readonly companyId = input.required<string>();
  readonly conversationId = input.required<string>();
  /** The conversation's files (`Conversation.attachments`, or `listAttachments`). */
  readonly attachments = input<readonly Attachment[]>([]);
  /** Whether the reader is the conversation's person: only they add and delete files. */
  readonly mine = input(true);
  /** The conversation's person, for the Owner's note. */
  readonly personName = input<string | null>(null);
  /** Whether the conversation has ended: its files stay, it takes no new ones. */
  readonly ended = input(false);
  /** Whether the reader is OWNER or ADMIN, who may acknowledge a credential format (ADR-0014). */
  readonly mayAcknowledge = input(false);

  /** A file the server stored, as it answered. */
  readonly added = output<Attachment>();
  /** The id of a file the server deleted. */
  readonly deleted = output<string>();

  private static nextId = 0;
  protected readonly headingId = `conversation-files-${ConversationFiles.nextId++}`;
  private readonly picker = viewChild<ElementRef<HTMLInputElement>>('picker');

  protected readonly size = fileSize;
  protected readonly purged = purgedText;

  /** The list as the server last answered it: the input, then this column's own changes. */
  protected readonly files = linkedSignal<readonly Attachment[], readonly Attachment[]>({
    source: this.attachments,
    computation: (attachments) => attachments,
  });

  protected readonly canAdd = computed(() => this.mine() && !this.ended());
  protected readonly canDelete = this.canAdd;
  protected readonly note = computed(() => {
    if (!this.mine()) {
      return `Only ${this.personName() ?? 'the conversation’s person'} adds and removes files here.`;
    }
    return this.ended() ? 'The conversation has ended: its files stay.' : null;
  });

  protected readonly upload = signal<Upload | null>(null);
  protected readonly uploading = computed(() => this.upload()?.busy ?? false);
  protected readonly acknowledged = signal(false);
  /** An OWNER or ADMIN may store a file whose only matches are known formats. */
  protected readonly acknowledgeable = computed(() => {
    const u = this.upload();
    return (
      this.mayAcknowledge() &&
      !!u &&
      !u.busy &&
      u.matches.length > 0 &&
      u.matches.every((m) => m.kind === 'FORMAT')
    );
  });

  protected readonly previewing = signal<Attachment | null>(null);
  protected readonly deleting = signal<Attachment | null>(null);
  protected readonly deleteBusy = signal(false);
  protected readonly deleteError = signal<string | null>(null);

  /** Opens the file picker, as "Add a file" does; false when the reader may not add one now. */
  pick(): boolean {
    const picker = this.picker()?.nativeElement;
    if (!picker || this.uploading()) return false;
    picker.click();
    return true;
  }

  protected url(file: Attachment): string {
    return attachmentUrl(this.companyId(), this.conversationId(), file.id);
  }

  protected picked(event: Event): void {
    const target = event.target as HTMLInputElement;
    const file = target.files?.[0];
    target.value = '';
    if (!file) return;
    this.acknowledged.set(false);
    if (file.size > MAX_FILE_BYTES) {
      this.upload.set({
        file,
        key: '',
        busy: false,
        retryable: false,
        matches: [],
        error: `${file.name} is ${fileSize(file.size)}; a file may have at most ${fileSize(MAX_FILE_BYTES)}.`,
      });
      return;
    }
    this.upload.set({
      file,
      key: crypto.randomUUID(),
      busy: false,
      error: null,
      retryable: false,
      matches: [],
    });
    void this.send(false);
  }

  /**
   * Sends the current upload. A retry after a transport or server failure sends the same key; the
   * acknowledged upload is another request, so it gets a key of its own.
   */
  protected async send(acknowledge: boolean): Promise<void> {
    const current = this.upload();
    if (!current || current.busy) return;
    const key = acknowledge ? crypto.randomUUID() : current.key;
    this.upload.set({ ...current, key, busy: true, error: null, retryable: false });
    try {
      const stored = await this.api.invoke(uploadAttachment, {
        companyId: this.companyId(),
        conversationId: this.conversationId(),
        'Idempotency-Key': key,
        body: {
          file: current.file,
          ...(acknowledge ? { acknowledgeCredentialMatch: true } : {}),
        },
      });
      this.files.update((files) => [...files.filter((f) => f.id !== stored.id), stored]);
      this.upload.set(null);
      this.notify.success(`Added ${stored.fileName}`);
      this.added.emit(stored);
    } catch (error) {
      const matches = hasProblemCode(error, 'CREDENTIAL_FOUND') ? credentialMatches(error) : [];
      this.upload.set({
        ...current,
        key,
        busy: false,
        error: problemMessage(error, MESSAGES),
        retryable: retryable(error),
        matches,
      });
    } finally {
      this.picker()?.nativeElement.blur();
    }
  }

  protected askDelete(file: Attachment): void {
    this.deleteError.set(null);
    this.deleting.set(file);
  }

  protected async confirmDelete(): Promise<void> {
    const file = this.deleting();
    if (!file || this.deleteBusy()) return;
    this.deleteBusy.set(true);
    try {
      await this.api.invoke(deleteAttachment, {
        companyId: this.companyId(),
        conversationId: this.conversationId(),
        attachmentId: file.id,
      });
      this.files.update((files) => files.filter((f) => f.id !== file.id));
      this.deleting.set(null);
      this.notify.success(`Deleted ${file.fileName}`);
      this.deleted.emit(file.id);
    } catch (error) {
      this.deleteError.set(problemMessage(error, MESSAGES));
    } finally {
      this.deleteBusy.set(false);
    }
  }
}
