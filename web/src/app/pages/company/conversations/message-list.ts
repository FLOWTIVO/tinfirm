import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  linkedSignal,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideClock, lucideCornerDownRight, lucideInfo, lucideSend } from '@ng-icons/lucide';
import type {
  Conversation,
  ConversationMessage,
  Decision,
  Run,
} from '../../../api/generated/models';
import { DecisionCard } from '../../../shared/decision-card';
import { ResultReviewCard } from '../../../shared/result-review-card';
import { Markdown } from '../../../shared/markdown';
import { namedLinks } from '../../../shared/named-links';
import { bindingTextFor } from '../../../shared/pinned-cards';
import { isRunActive, stoppedAtTimeLimit } from '../../../shared/run-status';
import type { QueueReasonReader } from '../../../shared/queue-reason';
import { Time } from '../../../shared/time';
import { ExternalMessage } from '../triggers/conversation/external-message';
import { ConversationTurn } from './conversation-turn';
import { timeline } from './conversation-view';
import { LiveTurn } from './live-turn';

/**
 * The thread of a conversation (plan §4.1, `MessageList`; DESIGN.md, The Thread): the person's
 * messages as bubbles (the reader's own on the right, in green), each finished turn as the agent
 * speaking with "Show work", its answer and its status line, Tinfirm's notes as muted, left-aligned notes with an info icon, an
 * issue's comments (package 4) as W5's `app-external-message`, a trigger's result reviews as W5's
 * `app-result-review-card`, the decisions where they were raised (W4b's
 * `app-decision-card`, answerable in place), the turn running now (`LiveTurn`), and last the messages that wait for the next
 * turn. A message a turn took while it worked (steering) sits in that turn, under its work, and says so; one on its way
 * to the running turn says it is being sent. A message left unanswered by a cancel, or by the end, says so.
 */
@Component({
  selector: 'app-message-list',
  imports: [
    NgIcon,
    NgTemplateOutlet,
    ConversationTurn,
    DecisionCard,
    ExternalMessage,
    ResultReviewCard,
    LiveTurn,
    Markdown,
    Time,
  ],
  providers: [provideIcons({ lucideClock, lucideCornerDownRight, lucideInfo, lucideSend })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex min-w-0 flex-col gap-6' },
  template: `
    @let c = conversation();
    <ol class="flex min-w-0 flex-col gap-6" aria-label="Messages" data-testid="message-list">
      @for (entry of view().entries; track entry.id) {
        <li class="min-w-0">
          @switch (entry.kind) {
            @case ('turn') {
              <app-conversation-turn
                [run]="entry.run"
                [messages]="entry.messages"
                [decisions]="entry.decisions"
                [conversation]="c"
                [companyId]="companyId()"
                [agentEditable]="agentEditable()"
                (decided)="decided.emit($event)"
                (outdated)="outdated.emit()"
              >
                <ol
                  turnSteered
                  class="flex min-w-0 flex-col gap-4"
                  aria-label="Messages it took while it worked"
                  [class.hidden]="entry.steered.length === 0"
                >
                  @for (m of entry.steered; track m.id) {
                    <li class="min-w-0">
                      <ng-container *ngTemplateOutlet="messageTpl; context: { $implicit: m }" />
                    </li>
                  }
                </ol>
              </app-conversation-turn>
            }
            @case ('decision') {
              @if (entry.decision.subject?.type === 'RESULT') {
                <app-result-review-card
                  [attr.data-decision-id]="entry.decision.id"
                  [attr.data-open-card]="
                    entry.decision.status === 'OPEN' && entry.decision.mayDecide ? '' : null
                  "
                  [decision]="entry.decision"
                  [companyId]="companyId()"
                  (decided)="decided.emit($event)"
                  (outdated)="outdated.emit()"
                />
              } @else {
                <app-decision-card
                  [attr.data-decision-id]="entry.decision.id"
                  [attr.data-open-card]="
                    entry.decision.status === 'OPEN' && entry.decision.mayDecide ? '' : null
                  "
                  [decision]="entry.decision"
                  [companyId]="companyId()"
                  [agentEditable]="agentEditable()"
                  [bindingText]="bindingText(entry.decision)"
                  (decided)="decided.emit($event)"
                  (outdated)="outdated.emit()"
                />
              }
            }
            @default {
              <ng-container *ngTemplateOutlet="messageTpl; context: { $implicit: entry.message }" />
            }
          }
        </li>
      }
    </ol>

    @if (view().live; as live) {
      <app-live-turn
        [companyId]="companyId()"
        [runId]="live.runId"
        [run]="live.run"
        [conversation]="c"
        [payerMine]="payerMine()"
        [reader]="reader()"
        (statusChanged)="runStatus.emit($event)"
        (runEnded)="runEnded.emit($event)"
        (toCards)="toCards.emit()"
      />
      @if (live.steered.length > 0) {
        <ol class="flex min-w-0 flex-col gap-4" aria-label="Messages the turn took while it works">
          @for (m of live.steered; track m.id) {
            <li class="min-w-0">
              <ng-container *ngTemplateOutlet="messageTpl; context: { $implicit: m }" />
            </li>
          }
        </ol>
      }
    }

    @if (view().waiting.length > 0) {
      <ol class="flex min-w-0 flex-col gap-4" aria-label="Waiting for the next turn">
        @for (m of view().waiting; track m.id) {
          <li class="min-w-0">
            <ng-container *ngTemplateOutlet="messageTpl; context: { $implicit: m }" />
          </li>
        }
      </ol>
    }

    <p class="sr-only" role="status" data-testid="thread-announcement">{{ announcement() }}</p>

    <ng-template #messageTpl let-m>
      @switch (m.kind) {
        @case ('SYSTEM') {
          <!-- Tinfirm's own note, in every place alike: muted, left-aligned, its links by their words. -->
          <div
            class="text-muted-foreground flex min-w-0 items-start gap-2"
            data-author="SYSTEM"
            data-testid="message"
          >
            <ng-icon name="lucideInfo" class="mt-[3px] shrink-0 text-[14px]" aria-hidden="true" />
            <div class="flex max-w-[66ch] min-w-0 flex-col gap-0.5">
              <span class="sr-only">Tinfirm:</span>
              @if (folded(m)) {
                <!-- The review's card above says the same while it is open. -->
                <p class="text-[0.8125rem] leading-normal" data-testid="system-folded">
                  Tinfirm asked for a review before writing; see the card above.
                  <button
                    type="button"
                    class="text-foreground focus-visible:ring-ring/50 rounded-sm font-semibold underline underline-offset-2 outline-none focus-visible:ring-3"
                    data-testid="system-show"
                    [attr.aria-expanded]="false"
                    (click)="unfold(m.id)"
                  >
                    Show
                  </button>
                </p>
              } @else {
                <app-markdown class="text-[0.8125rem] leading-normal" [text]="systemText(m.body)" />
              }
              <app-time class="text-xs" mode="clock" [value]="m.createdAt" />
            </div>
          </div>
        }
        @case ('AGENT') {
          <div class="flex min-w-0 flex-col gap-1" data-author="AGENT" data-testid="message">
            <p class="text-muted-foreground text-xs">
              <span class="text-foreground font-semibold">{{ c.agentName }}</span>
              · <app-time mode="clock" [value]="m.createdAt" />
            </p>
            <app-markdown class="max-w-[66ch] text-[0.9375rem] leading-normal" [text]="m.body" />
          </div>
        }
        @case ('EXTERNAL') {
          <div class="flex min-w-0 flex-col gap-1" data-author="EXTERNAL" data-testid="message">
            <app-external-message [message]="m" [subject]="c.subject ?? null" [place]="c.place" />
            @if (m.waiting || m.unanswered) {
              <div class="ps-10.5">
                <ng-container *ngTemplateOutlet="noteTpl; context: { $implicit: m }" />
              </div>
            }
          </div>
        }
        @default {
          @if (isMine(m)) {
            <div
              class="flex flex-col items-end gap-1"
              data-author="PERSON"
              data-mine="true"
              data-testid="message"
              [attr.data-waiting]="m.waiting || null"
              [attr.data-steering]="m.steering || null"
            >
              <div
                class="bg-bubble-me text-bubble-me-foreground markdown-on-fill max-w-[85%] min-w-0 rounded-[16px_8px_16px_16px] px-3.5 py-[9px]"
              >
                <span class="sr-only">You, <app-time [value]="m.createdAt" />:</span>
                <app-markdown class="text-[0.9375rem] leading-normal" [text]="m.body" />
              </div>
              <ng-container *ngTemplateOutlet="noteTpl; context: { $implicit: m }" />
            </div>
          } @else {
            <div
              class="flex flex-col items-start gap-1"
              data-testid="message"
              [attr.data-author]="m.kind"
              [attr.data-waiting]="m.waiting || null"
              [attr.data-steering]="m.steering || null"
            >
              <p class="text-muted-foreground text-xs">
                <span class="text-foreground font-semibold">{{
                  m.authorName ?? c.personName ?? 'Someone'
                }}</span>
                · <app-time mode="clock" [value]="m.createdAt" />
              </p>
              <div
                class="bg-card max-w-[85%] min-w-0 rounded-[8px_16px_16px_16px] border px-3.5 py-[9px] shadow-[0_1px_2px_rgb(0_0_0/0.06)]"
              >
                <app-markdown class="text-[0.9375rem] leading-normal" [text]="m.body" />
              </div>
              <ng-container *ngTemplateOutlet="noteTpl; context: { $implicit: m }" />
            </div>
          }
        }
      }
    </ng-template>

    <ng-template #noteTpl let-m>
      @if (m.waiting && c.state === 'OPEN' && m.steering === 'SENDING') {
        <p
          class="text-muted-foreground flex items-center gap-1 text-xs"
          data-testid="message-sending"
        >
          <ng-icon name="lucideSend" class="text-[12px]" aria-hidden="true" />
          Sending to {{ c.agentName }}…
        </p>
      } @else if (m.waiting && c.state === 'OPEN') {
        <p
          class="text-muted-foreground flex items-center gap-1 text-xs"
          data-testid="message-waiting"
        >
          <ng-icon name="lucideClock" class="text-[12px]" aria-hidden="true" />
          Waits for the next turn
        </p>
      } @else if (m.steering === 'DELIVERED') {
        <p
          class="text-muted-foreground flex items-center gap-1 text-xs"
          data-testid="message-steered"
        >
          <ng-icon name="lucideCornerDownRight" class="text-[12px]" aria-hidden="true" />
          {{ c.agentName }} took it while working
        </p>
      } @else if (m.unanswered) {
        <p class="text-muted-foreground text-xs" data-testid="message-unanswered">
          Not answered:
          {{ c.state === 'ENDED' ? 'the conversation ended' : 'its turn was cancelled' }}
        </p>
      } @else if (m.answersOpenQuestions) {
        <p class="text-muted-foreground flex items-center gap-1 text-xs">
          <ng-icon name="lucideCornerDownRight" class="text-[12px]" aria-hidden="true" />
          Answers the open questions
        </p>
      }
    </ng-template>
  `,
})
export class MessageList {
  readonly conversation = input.required<Conversation>();
  readonly companyId = input.required<string>();
  /** The reader's principal: their own messages sit on the right. */
  readonly principalId = input<string | null>(null);
  /** The reader pays for the conversation (the live turn's budget line). */
  readonly payerMine = input(false);
  readonly reader = input<QueueReasonReader>({ member: true, admin: false });
  readonly agentEditable = input(false);
  /** A decision as the server answered it once decided here. */
  readonly decided = output<Decision>();
  /** Someone decided a card first, or it was cancelled: read the conversation again. */
  readonly outdated = output<void>();
  readonly runStatus = output<Run>();
  readonly runEnded = output<Run>();
  readonly toCards = output<void>();

  readonly live = viewChild(LiveTurn);

  protected readonly view = computed(() => timeline(this.conversation()));

  /**
   * Said once when a turn finishes while the reader is on the page (plan §8: a live region for new
   * answers), not for the turns that were there when it opened.
   */
  protected readonly announcement = linkedSignal<
    { readonly id: string; readonly turn: Run | null },
    string
  >({
    source: () => {
      const c = this.conversation();
      const turns = c.turns.filter((r) => r.id !== c.activeRunId && !isRunActive(r.status));
      return { id: c.id, turn: turns.at(-1) ?? null };
    },
    computation: (now, previous) => {
      const before = previous?.source;
      if (!before || before.id !== now.id || !now.turn || before.turn?.id === now.turn.id) {
        return previous?.value ?? '';
      }
      const agent = this.conversation().agentName;
      switch (now.turn.status) {
        case 'SUCCEEDED':
          return `${agent} answered.`;
        case 'FAILED':
          return stoppedAtTimeLimit(now.turn.failureClass)
            ? `${agent}’s turn stopped at its time limit.`
            : `${agent}’s turn failed.`;
        case 'LOST':
          return `${agent}’s turn failed.`;
        default:
          return `${agent}’s turn was cancelled.`;
      }
    },
  });

  /** Tinfirm's notes the reader unfolded. */
  private readonly unfolded = signal<ReadonlySet<string>>(new Set());

  /**
   * Whether Tinfirm's note is folded to one line: it raised a result's review (`decisionId`) that
   * is still open, whose card shows the same writes. Once decided, the card is gone and the note
   * shows in full.
   */
  protected folded(m: ConversationMessage): boolean {
    if (!m.decisionId || this.unfolded().has(m.id)) return false;
    const decision = this.conversation().decisions.find((d) => d.id === m.decisionId);
    return decision?.subject?.type === 'RESULT' && decision.status === 'OPEN';
  }

  protected unfold(id: string): void {
    this.unfolded.update((ids) => new Set([...ids, id]));
  }

  protected systemText(body: string): string {
    return namedLinks(body);
  }

  protected bindingText(decision: Decision): string | null {
    return bindingTextFor(decision, this.conversation().bindings);
  }

  protected isMine(m: ConversationMessage): boolean {
    const me = this.principalId();
    return m.kind === 'PERSON' && !!me && m.authorPrincipalId === me;
  }
}
