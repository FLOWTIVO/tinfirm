import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  type ElementRef,
  inject,
  Injector,
  input,
  linkedSignal,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideArrowUp, lucideCircleAlert, lucideMessageCircle } from '@ng-icons/lucide';
import { HlmKbdImports } from '@spartan-ng/helm/kbd';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import type { Conversation } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { isRetryable } from '../../../core/submission-key';
import { ConversationStore } from './conversation-store';

/** The longest message the API takes (`ConversationMessageRequest.body`). */
export const MAX_MESSAGE = 100_000;

/** The modifier of Enter that sends a message: ⌘ on Apple's systems, else Ctrl. */
export const MOD_KEY = /Mac|iPhone|iPad/.test(globalThis.navigator?.platform ?? '') ? '⌘' : 'Ctrl';

let nextId = 0;

/**
 * The message box at the foot of an open conversation (plan §4.1, `Composer`; DESIGN.md,
 * Composer): Markdown, sent with the button or Ctrl+Enter (⌘+Enter) as the next turn
 * (`postConversationMessage`). While a turn runs it says that the agent takes the message after its
 * current step (steering), or in the next turn when it cannot;
 * while questions are open it offers "This answers the open questions" (`answersOpenQuestions`,
 * off by default and again whenever their number changes). The message is an idempotent
 * submission: sent again after a network error, it carries the same key and is posted once. It
 * keeps the text until the server took it. Its actions row takes more buttons
 * (`[composerActions]`). For a conversation a chat holds, it says where to write instead
 * (`external`); the cards above it stay answerable. On a trigger's tracker issue it takes only
 * answers to the open questions (`answersOnly`).
 */
@Component({
  selector: 'app-composer',
  imports: [NgIcon, HlmKbdImports, HlmSpinner],
  providers: [provideIcons({ lucideArrowUp, lucideCircleAlert, lucideMessageCircle })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block', 'data-testid': 'composer' },
  template: `
    @if (external(); as note) {
      <!-- A chat holds the conversation: its messages come from there (409 PLACE_IS_EXTERNAL). -->
      <p
        class="bg-card text-muted-foreground flex items-start gap-2 rounded-[18px] border px-4 py-3 text-sm"
        role="note"
        data-testid="composer-external"
      >
        <ng-icon name="lucideMessageCircle" class="mt-0.5 shrink-0" aria-hidden="true" />
        {{ note }}
      </p>
    } @else {
      <form class="flex flex-col gap-2" novalidate (submit)="send($event)">
        @if (error(); as message) {
          <p
            class="text-destructive flex items-start gap-2 text-sm"
            role="alert"
            data-testid="composer-error"
          >
            <ng-icon name="lucideCircleAlert" class="mt-0.5 shrink-0" aria-hidden="true" />
            {{ message }}
          </p>
        }
        <div
          class="bg-card focus-within:ring-ring/40 flex flex-col rounded-[18px] border py-2.5 ps-4 pe-2.5 transition-shadow focus-within:ring-3"
        >
          <div class="flex items-end gap-2">
            <label class="sr-only" [for]="ids.body">Message to {{ agentName() }}</label>
            <textarea
              #box
              rows="1"
              class="placeholder:text-faint max-h-60 min-h-[34px] flex-1 resize-none bg-transparent py-1 text-base leading-normal outline-none [field-sizing:content] md:text-[0.9375rem]"
              data-testid="composer-input"
              [id]="ids.body"
              [attr.aria-describedby]="ids.hint"
              [attr.maxlength]="max"
              [placeholder]="placeholder()"
              [value]="text()"
              [disabled]="busy()"
              (input)="text.set(box.value)"
              (keydown)="onKeydown($event)"
            ></textarea>
            <ng-content select="[composerActions]" />
            <button
              type="submit"
              class="bg-primary text-primary-foreground focus-visible:ring-ring/50 inline-flex size-[34px] shrink-0 items-center justify-center rounded-full outline-none hover:opacity-90 focus-visible:ring-3 disabled:opacity-50 max-sm:size-11"
              data-testid="composer-send"
              [disabled]="busy() || !text().trim()"
            >
              @if (busy()) {
                <hlm-spinner class="size-4" aria-hidden="true" />
              } @else {
                <ng-icon name="lucideArrowUp" class="rotate-90 text-base" aria-hidden="true" />
              }
              <span class="sr-only">Send</span>
            </button>
          </div>
          @if (openQuestions() > 0 && !answersOnly()) {
            <label class="text-muted-foreground flex items-center gap-2 pt-1.5 text-xs">
              <input
                type="checkbox"
                class="accent-primary size-4"
                data-testid="answers-open-questions"
                [checked]="answers()"
                [disabled]="busy()"
                (change)="answers.set($any($event.target).checked)"
              />
              {{ answersLabel() }}
            </label>
          }
          <div class="flex flex-wrap items-center gap-x-2 gap-y-0.5 pt-1">
            <p
              class="text-muted-foreground min-w-48 flex-1 text-xs"
              data-testid="composer-hint"
              [id]="ids.hint"
            >
              {{ hint() }}
            </p>
            <span class="text-muted-foreground hidden text-xs sm:inline"
              >Markdown · <kbd hlmKbd>{{ modKey }}</kbd> <kbd hlmKbd>Enter</kbd> to send</span
            >
          </div>
        </div>
      </form>
    }
  `,
})
export class Composer {
  private readonly store = inject(ConversationStore);
  private readonly injector = inject(Injector);

  readonly agentName = input.required<string>();
  /** The open questions a message can answer. */
  readonly openQuestions = input(0);
  /** A turn is queued or running: the message waits for the next one. */
  readonly turnActive = input(false);
  /**
   * A chat holds the conversation (`chatPlaceView(…).composer`): what is said instead of the
   * message field, since its messages come from the chat. Null for a conversation of the app.
   */
  readonly external = input<string | null>(null);
  /**
   * The reader may answer the open questions here but not post a plain message
   * (`Conversation.mayAnswer` without `mayPost`: people talk on a tracker's issue or in a chat's
   * thread); what is sent answers them and is posted there too.
   */
  readonly answersOnly = input(false);
  /** Where an answer sent here is posted too ("on the issue", "in the chat"). */
  readonly postedWhere = input<string | null>(null);
  /** The message was posted; the conversation as the server answered it. */
  readonly posted = output<Conversation>();

  private readonly box = viewChild<ElementRef<HTMLTextAreaElement>>('box');

  protected readonly ids = { body: `composer-${nextId}`, hint: `composer-${nextId++}-hint` };
  protected readonly max = MAX_MESSAGE;
  protected readonly modKey = MOD_KEY;

  protected readonly text = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Off by default, and again whenever the number of open questions changes. */
  protected readonly answers = linkedSignal({
    source: this.openQuestions,
    computation: () => false,
  });

  protected readonly placeholder = computed(() => `Message ${this.agentName()}…`);
  protected readonly answersLabel = computed(() =>
    this.openQuestions() === 1
      ? 'This answers the open question'
      : `This answers the ${this.openQuestions()} open questions`,
  );
  protected readonly hint = computed(() => {
    const agent = this.agentName();
    if (this.answersOnly()) {
      const where = this.postedWhere();
      return where
        ? `${agent} gets this as the answer to its open questions; it is posted ${where} too.`
        : `${agent} gets this as the answer to its open questions and goes on.`;
    }
    if (this.openQuestions() > 0 && this.answers()) {
      return `${agent} gets this as the answer to its open questions and goes on.`;
    }
    if (this.turnActive()) {
      return `${agent} is at work: it takes your message after its current step, or in the next turn.`;
    }
    if (this.openQuestions() > 0) {
      return `${agent} gets this as a message; its open questions stay open.`;
    }
    return `${agent} gets this as the next turn.`;
  });

  /** Puts the cursor in the box, e.g. after "Reply" elsewhere on the page. */
  focus(): void {
    this.box()?.nativeElement.focus();
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) {
      event.preventDefault();
      void this.send(event);
    }
  }

  protected async send(event: Event): Promise<void> {
    event.preventDefault();
    const text = this.text().trim();
    if (!text || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    let posted = false;
    try {
      const answers = this.answersOnly() || (this.openQuestions() > 0 && this.answers());
      const conversation = await this.store.post(text, answers);
      this.text.set('');
      this.answers.set(false);
      posted = true;
      this.posted.emit(conversation);
    } catch (error) {
      this.error.set(
        isRetryable(error)
          ? `${problemMessage(error)} Your message is still here: send it again, and it is posted once.`
          : problemMessage(error),
      );
    } finally {
      this.busy.set(false);
    }
    // The box was off while it sent: the cursor goes back to it, for the next message.
    if (posted) afterNextRender({ write: () => this.focus() }, { injector: this.injector });
  }
}
