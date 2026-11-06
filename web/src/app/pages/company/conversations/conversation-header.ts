import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  type ElementRef,
  inject,
  Injector,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArrowLeft,
  lucideCheck,
  lucideCircleAlert,
  lucideEllipsis,
  lucideExternalLink,
  lucidePanelRight,
  lucidePencil,
  lucideRotateCw,
  lucideSquare,
  lucideSquareCheck,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmDropdownMenuImports } from '@spartan-ng/helm/dropdown-menu';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import type { Conversation } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { Notify } from '../../../core/notify';
import { initials } from '../../../shared/forms';
import { ChatPlace } from '../../../shared/chat/chat-place';
import { ClientBadge } from '../../../shared/client-badge';
import { LiveDot, stoppedAtTimeLimit } from '../../../shared/run-status';
import { ConversationStore } from './conversation-store';
import { type ChipTone, retryable, stateChip, turnActive } from './conversation-view';

const CHIP_TONES: Record<ChipTone, string> = {
  human: 'bg-human-soft text-human',
  agent: 'bg-primary/10 text-primary',
  destructive: 'bg-destructive/10 text-destructive',
  muted: 'bg-muted text-muted-foreground',
};

/**
 * The head's grid. From `sm`: the title, its state and the actions on one line, what the
 * conversation is under it, then the page's line. On a phone: the title and the actions, then the
 * state and the page's line, cut short, side by side.
 */
const TITLE_GRID =
  "grid-cols-[auto_minmax(0,1fr)_auto] [grid-template-areas:'title_title_actions'_'state_line_line'] sm:grid-cols-[minmax(0,max-content)_auto_minmax(0,1fr)_auto] sm:[grid-template-areas:'title_state_._actions'_'meta_meta_meta_meta'_'line_line_line_line']";
/** While the title is renamed: the form takes the first line, the actions stay. */
const EDIT_GRID =
  "grid-cols-[minmax(0,1fr)_auto] [grid-template-areas:'title_actions'_'line_line'] sm:[grid-template-areas:'title_actions'_'meta_meta'_'line_line']";

/** The longest title the API takes (`ConversationUpdateRequest.title`). */
const MAX_TITLE = 200;

/** The words of the actions' failures, per problem code. */
const ACTION_MESSAGES = {
  CONVERSATION_BOUND: 'Only the person whose conversation this is renames it.',
};

/**
 * What a conversation is, in one muted line (plan §4.1): the agent (its tile and a link), whose it
 * is when it is not the reader's, its key and branch in mono, the subject (its key, linking to the
 * issue), where a chat holds it (`app-chat-place`, package 5) and whether an AI client started it
 * (`app-client-badge`, package 6). The header shows it from `sm`; on a phone the details sheet does.
 */
@Component({
  selector: 'app-conversation-meta',
  imports: [RouterLink, NgIcon, ChatPlace, ClientBadge],
  providers: [provideIcons({ lucideExternalLink })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class:
      'text-muted-foreground flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-[0.8125rem]',
    'data-testid': 'conversation-meta',
  },
  template: `
    @let c = conversation();
    <a
      class="text-foreground decoration-input hover:decoration-primary focus-visible:ring-ring/50 inline-flex min-h-6 items-center gap-1.5 rounded-sm font-semibold underline decoration-1 underline-offset-[3px] outline-none focus-visible:ring-3 max-sm:min-h-11"
      data-testid="conversation-agent"
      [routerLink]="['/c', companyId(), 'agents', c.agentId]"
    >
      <span
        class="bg-primary text-primary-foreground inline-flex size-5 shrink-0 items-center justify-center rounded-md text-[0.5625rem] font-bold no-underline"
        aria-hidden="true"
        >{{ agentInitials() }}</span
      >
      {{ c.agentName }}</a
    >
    @if (!mine() && c.personName) {
      <span class="text-muted-foreground max-sm:hidden" aria-hidden="true">·</span>
      <span data-testid="conversation-person"
        >for <span class="text-foreground">{{ c.personName }}</span></span
      >
    }
    <span class="text-muted-foreground max-sm:hidden" aria-hidden="true">·</span>
    <span class="font-mono text-xs break-all" data-testid="conversation-key"
      >{{ c.key }}
      @if (c.branch) {
        · {{ c.branch }}
      }
    </span>
    @if (c.subject; as s) {
      @if (s.url) {
        <a
          class="bg-card text-foreground hover:bg-accent focus-visible:ring-ring/50 inline-flex h-[22px] items-center gap-1 rounded-full border px-2 font-mono text-xs outline-none focus-visible:ring-3 max-sm:h-8 max-sm:px-3"
          target="_blank"
          rel="noopener noreferrer"
          data-testid="conversation-subject"
          [href]="s.url"
          [title]="s.title ?? s.key"
          >{{ s.key }}<ng-icon name="lucideExternalLink" size="12" aria-hidden="true" /><span
            class="sr-only"
          >
            (opens in a new tab)</span
          ></a
        >
      } @else {
        <span
          class="bg-card text-foreground inline-flex h-[22px] items-center rounded-full border px-2 font-mono text-xs"
          data-testid="conversation-subject"
          [title]="s.title ?? s.key"
          >{{ s.key }}</span
        >
      }
    }
    <app-chat-place [conversation]="c" />
    <app-client-badge [origin]="c.origin" />
  `,
})
export class ConversationMeta {
  readonly conversation = input.required<Conversation>();
  readonly companyId = input.required<string>();
  /** The reader is the conversation's person: it does not say whose it is. */
  readonly mine = input(false);

  protected readonly agentInitials = computed(() => initials(this.conversation().agentName));
}

/**
 * The head of the conversation page (plan §4.1, `ConversationHeader`): a way back to the list, the
 * title on one line, renamed in place by its person (the pencil shows on hover or focus; on a
 * phone "Rename" is in the menu); the state chip from `waitingOn` (orange only when the reader must
 * act); below `lg` "Details", which opens the side column as a sheet; and the menu with Cancel
 * turn, Retry and End (with a confirm dialog), for its person and the Owner, as the API allows.
 * Under it, from `sm`, what the conversation is (`ConversationMeta`), then what the page puts
 * inside (its line of who acts and who pays), which on a phone sits beside the state.
 */
@Component({
  selector: 'app-conversation-header',
  imports: [
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmDropdownMenuImports,
    HlmInput,
    HlmSpinner,
    ConversationMeta,
    LiveDot,
  ],
  providers: [
    provideIcons({
      lucideArrowLeft,
      lucideCheck,
      lucideCircleAlert,
      lucideEllipsis,
      lucidePanelRight,
      lucidePencil,
      lucideRotateCw,
      lucideSquare,
      lucideSquareCheck,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block min-w-0', 'data-testid': 'conversation-header' },
  template: `
    @let c = conversation();
    <div
      class="grid min-w-0 items-center gap-x-2 gap-y-1.5"
      [class]="editing() ? editGrid : titleGrid"
    >
      <div class="group/title flex min-w-0 items-center gap-1 [grid-area:title]">
        <a
          hlmBtn
          variant="ghost"
          size="icon-sm"
          class="text-muted-foreground -ms-2 max-sm:size-11"
          aria-label="Back to conversations"
          title="Conversations"
          data-testid="conversation-back"
          [routerLink]="['/c', companyId(), 'conversations']"
        >
          <ng-icon name="lucideArrowLeft" aria-hidden="true" />
        </a>
        @if (editing()) {
          <form
            class="flex min-w-0 flex-1 flex-wrap items-center gap-2"
            (submit)="saveTitle($event)"
          >
            <label class="sr-only" for="conversation-title-input">Title</label>
            <input
              #titleInput
              id="conversation-title-input"
              hlmInput
              class="min-w-0 flex-1 basis-48 text-base font-semibold"
              data-testid="rename-input"
              [attr.maxlength]="maxTitle"
              [value]="draft()"
              [disabled]="busy() === 'rename'"
              (input)="draft.set(titleInput.value)"
              (keydown.escape)="editing.set(false)"
            />
            <div class="flex gap-2">
              <button
                hlmBtn
                size="sm"
                type="submit"
                class="max-sm:h-11"
                data-testid="rename-save"
                [disabled]="busy() === 'rename' || !draft().trim()"
              >
                @if (busy() === 'rename') {
                  <hlm-spinner aria-hidden="true" />
                } @else {
                  <ng-icon name="lucideCheck" aria-hidden="true" />
                }
                Save
              </button>
              <button
                hlmBtn
                variant="ghost"
                size="sm"
                type="button"
                class="max-sm:h-11"
                (click)="editing.set(false)"
              >
                Cancel
              </button>
            </div>
          </form>
        } @else {
          <h1
            class="min-w-0 truncate text-base leading-tight font-bold tracking-tight sm:text-xl"
            data-testid="conversation-title"
            [title]="c.title"
          >
            {{ c.title }}
          </h1>
          @if (canRename()) {
            <button
              hlmBtn
              variant="ghost"
              size="icon-sm"
              type="button"
              class="text-muted-foreground size-7 group-hover/title:opacity-100 focus-visible:opacity-100 max-sm:hidden [@media(hover:hover)]:opacity-0"
              aria-label="Rename the conversation"
              title="Rename"
              data-testid="rename"
              (click)="startRename()"
            >
              <ng-icon name="lucidePencil" size="14" aria-hidden="true" />
            </button>
          }
        }
      </div>

      @if (!editing()) {
        <div class="flex min-w-0 items-center [grid-area:state]">
          <span
            class="inline-flex h-6 max-w-full items-center gap-1.5 truncate rounded-full px-2.5 text-xs font-semibold whitespace-nowrap"
            data-testid="conversation-state"
            [attr.data-tone]="chip().tone"
            [class]="chipTones[chip().tone]"
          >
            @if (chip().live) {
              <app-live-dot />
            }
            {{ chip().label }}
          </span>
        </div>
      }

      <div class="flex items-center justify-end gap-1 [grid-area:actions]">
        <button
          hlmBtn
          variant="outline"
          size="sm"
          type="button"
          class="lg:hidden max-sm:h-11 max-sm:px-3.5"
          aria-haspopup="dialog"
          data-testid="conversation-details"
          (click)="details.emit()"
        >
          <ng-icon name="lucidePanelRight" aria-hidden="true" />
          Details
        </button>
        @if (canAct() && (active() || canRetry() || c.state === 'OPEN')) {
          <button
            hlmBtn
            variant="ghost"
            size="icon-sm"
            type="button"
            class="text-muted-foreground max-sm:size-11"
            aria-label="Conversation actions"
            data-testid="conversation-menu"
            [hlmDropdownMenuTrigger]="menu"
            align="end"
          >
            @if (busy()) {
              <hlm-spinner aria-hidden="true" />
            } @else {
              <ng-icon name="lucideEllipsis" aria-hidden="true" />
            }
          </button>
        }
      </div>

      <app-conversation-meta
        class="[grid-area:meta] max-sm:hidden"
        [conversation]="c"
        [companyId]="companyId()"
        [mine]="mine()"
      />
      <!-- The page's line under it: who acts and who pays. -->
      <div class="min-w-0 [grid-area:line]">
        <ng-content />
      </div>
    </div>

    <ng-template #menu>
      <hlm-dropdown-menu class="w-52">
        @if (canRename()) {
          <!-- On a phone the pencil gives way: renaming is here. -->
          <button
            hlmDropdownMenuItem
            type="button"
            class="sm:hidden"
            data-testid="rename-menu"
            (triggered)="renameFromMenu()"
          >
            <ng-icon name="lucidePencil" aria-hidden="true" />
            Rename
          </button>
        }
        @if (active()) {
          <button
            hlmDropdownMenuItem
            type="button"
            data-testid="cancel-turn"
            (triggered)="cancel()"
          >
            <ng-icon name="lucideSquare" aria-hidden="true" />
            Cancel turn
          </button>
        }
        @if (canRetry()) {
          <button hlmDropdownMenuItem type="button" data-testid="retry-turn" (triggered)="retry()">
            <ng-icon name="lucideRotateCw" aria-hidden="true" />
            {{ goesOn() ? 'Continue' : 'Retry' }}
          </button>
        }
        @if (c.state === 'OPEN') {
          @if (active() || canRetry()) {
            <hlm-dropdown-menu-separator />
          }
          <button
            hlmDropdownMenuItem
            type="button"
            data-testid="end-conversation"
            (triggered)="askEnd()"
          >
            <ng-icon name="lucideSquareCheck" aria-hidden="true" />
            End conversation
          </button>
        }
      </hlm-dropdown-menu>
    </ng-template>

    <hlm-alert-dialog [state]="endOpen() ? 'open' : 'closed'" (closed)="endOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>End this conversation?</h2>
          <p hlmAlertDialogDescription>
            It takes nothing more: no message, no turn. A turn that runs finishes and its result is
            kept; open questions and approvals are cancelled. Its outputs and files stay. You can’t
            undo this.
          </p>
        </hlm-alert-dialog-header>
        @if (endError(); as message) {
          <div hlmAlert variant="destructive" data-testid="end-error">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="busy() === 'end'">Keep it open</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            data-testid="confirm-end"
            [disabled]="busy() === 'end'"
            (click)="end()"
          >
            @if (busy() === 'end') {
              <hlm-spinner aria-hidden="true" />
            }
            End conversation
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class ConversationHeader {
  private readonly store = inject(ConversationStore);
  private readonly notify = inject(Notify);
  private readonly injector = inject(Injector);

  readonly conversation = input.required<Conversation>();
  readonly companyId = input.required<string>();
  /** The reader is the conversation's person: they rename it. */
  readonly mine = input(false);
  /** The reader may cancel, retry and end: its person and the Owner (the API decides again). */
  readonly canAct = input(false);
  readonly agentPaused = input(false);
  /** "Details": the side column as a sheet, below `lg`. */
  readonly details = output<void>();

  private readonly titleInput = viewChild<ElementRef<HTMLInputElement>>('titleInput');

  protected readonly chipTones = CHIP_TONES;
  protected readonly titleGrid = TITLE_GRID;
  protected readonly editGrid = EDIT_GRID;
  protected readonly maxTitle = MAX_TITLE;
  protected readonly busy = signal<'rename' | 'cancel' | 'retry' | 'end' | null>(null);
  protected readonly editing = signal(false);
  protected readonly draft = signal('');
  protected readonly endOpen = signal(false);
  protected readonly endError = signal<string | null>(null);

  protected readonly chip = computed(() =>
    stateChip(this.conversation(), this.mine(), this.agentPaused()),
  );
  protected readonly canRename = computed(
    () => this.mine() && this.conversation().state === 'OPEN',
  );
  protected readonly active = computed(() => turnActive(this.conversation()));
  protected readonly canRetry = computed(() => retryable(this.conversation()));
  /** The last turn stopped at its time limit: Retry goes on from where it stopped. */
  protected readonly goesOn = computed(
    () =>
      this.conversation().turnStatus === 'FAILED' &&
      stoppedAtTimeLimit(this.conversation().failureClass),
  );

  protected startRename(): void {
    this.draft.set(this.conversation().title);
    this.editing.set(true);
    afterNextRender(
      {
        write: () => {
          const input = this.titleInput()?.nativeElement;
          input?.focus();
          input?.select();
        },
      },
      { injector: this.injector },
    );
  }

  /** From the menu: once it closed and gave the focus back to its button. */
  protected renameFromMenu(): void {
    setTimeout(() => this.startRename());
  }

  protected async saveTitle(event: Event): Promise<void> {
    event.preventDefault();
    const title = this.draft().trim();
    if (!title || this.busy()) return;
    if (title === this.conversation().title) {
      this.editing.set(false);
      return;
    }
    this.busy.set('rename');
    try {
      await this.store.rename(title);
      this.editing.set(false);
    } catch (error) {
      this.notify.error("Couldn't rename the conversation", error, ACTION_MESSAGES);
    } finally {
      this.busy.set(null);
    }
  }

  protected async cancel(): Promise<void> {
    await this.act(
      'cancel',
      () => this.store.cancel(),
      'Turn cancelled',
      "Couldn't cancel the turn",
    );
  }

  protected async retry(): Promise<void> {
    await this.act(
      'retry',
      () => this.store.retry(),
      `${this.conversation().agentName} runs the turn again`,
      "Couldn't retry the turn",
    );
  }

  protected askEnd(): void {
    this.endError.set(null);
    this.endOpen.set(true);
  }

  protected async end(): Promise<void> {
    if (this.busy()) return;
    this.busy.set('end');
    this.endError.set(null);
    try {
      await this.store.end();
      this.endOpen.set(false);
      this.notify.success('Conversation ended', 'It is read only now; its outputs and files stay.');
    } catch (error) {
      this.endError.set(problemMessage(error, ACTION_MESSAGES));
    } finally {
      this.busy.set(null);
    }
  }

  private async act(
    kind: 'cancel' | 'retry',
    action: () => Promise<Conversation>,
    done: string,
    failed: string,
  ): Promise<void> {
    if (this.busy()) return;
    this.busy.set(kind);
    try {
      await action();
      this.notify.success(done);
    } catch (error) {
      this.notify.error(failed, error, ACTION_MESSAGES);
    } finally {
      this.busy.set(null);
    }
  }
}
