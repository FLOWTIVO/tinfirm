import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideCheck, lucideClipboardCheck, lucideUndo2 } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmTextarea } from '@spartan-ng/helm/textarea';
import { Api } from '../api/generated/api';
import { decideDecision } from '../api/generated/functions';
import type { Decision, DecisionWrite } from '../api/generated/models';
import { hasProblemCode, problemMessage } from '../api/problem';
import { Markdown } from './markdown';
import { namedLinks } from './named-links';
import { Time } from './time';

/** The longest note the card sends. */
const MAX_NOTE = 20000;

/**
 * The review of a trigger's result (a `RESULT` approval; the package 4 contract, §5.8, T14, V13),
 * for the conversation page and the inbox (W4a, W4b): the turn's summary and exactly what would be
 * written to the issue (the comment, then each action in words), then Approve, or Return with a
 * note, which sends the agent back to work (`REVIEW_RETURNED`). Nothing reaches the issue before
 * it is approved. Orange while open for whoever decides (its team, or the author without one);
 * "Waiting for {team}" for anyone else; the outcome once decided.
 *
 * Inputs: `decision` (subject `RESULT`), `companyId`. Outputs: `decided` (the decision as
 * answered), `outdated` (someone decided it first; the parent reads again).
 */
@Component({
  selector: 'app-result-review-card',
  imports: [NgIcon, HlmButton, HlmSpinner, HlmTextarea, Markdown, Time],
  providers: [provideIcons({ lucideCheck, lucideClipboardCheck, lucideUndo2 })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let d = decision();
    <section
      class="bg-card flex flex-col gap-3 rounded-xl border px-3.5 py-3 text-sm"
      [class.border-human]="actionable()"
      [attr.aria-labelledby]="'review-title-' + d.id"
      data-testid="result-review"
      [attr.data-status]="d.status"
    >
      <div class="flex items-start gap-2.5">
        <ng-icon
          name="lucideClipboardCheck"
          class="mt-0.5 shrink-0"
          [class.text-human]="actionable()"
          aria-hidden="true"
        />
        <div class="flex min-w-0 flex-col gap-0.5">
          <h3
            class="font-semibold text-pretty"
            [class.text-human]="actionable()"
            [id]="'review-title-' + d.id"
          >
            {{ title() }}
          </h3>
          <p class="text-muted-foreground text-xs">
            Raised <app-time [value]="d.createdAt" />
            @if (d.approverGroupName) {
              · for {{ d.approverGroupName }}
            } @else if (d.approverName) {
              · for {{ d.approverName }}
            }
          </p>
        </div>
      </div>

      @if (d.subject?.summary; as summary) {
        <div class="flex flex-col gap-1">
          <p class="text-muted-foreground text-xs font-semibold">Its result</p>
          <app-markdown [text]="summary" />
        </div>
      }

      @if (writes().length) {
        <div class="flex flex-col gap-1.5" data-testid="review-writes">
          <p class="text-muted-foreground text-xs font-semibold">What will be written</p>
          <ol
            class="bg-muted flex flex-col divide-y divide-black/5 rounded-lg dark:divide-white/10"
          >
            @for (w of writes(); track $index) {
              <li class="flex min-w-0 flex-col gap-0.5 px-3 py-2" [attr.data-write]="w.kind">
                <span
                  class="text-muted-foreground text-xs font-semibold"
                  data-testid="write-label"
                  >{{ w.label }}</span
                >
                <app-markdown class="min-w-0" [text]="w.text" />
              </li>
            }
          </ol>
        </div>
      } @else if (d.subject?.writes) {
        <p class="text-muted-foreground text-xs">Nothing is written to the issue for it.</p>
      }

      @if (d.status === 'OPEN') {
        @if (d.mayDecide) {
          <div class="flex flex-col gap-1.5">
            <label class="text-muted-foreground text-xs" [for]="'review-note-' + d.id"
              >Note (needed to return it)</label
            >
            <textarea
              hlmTextarea
              rows="2"
              class="min-h-14 [field-sizing:content]"
              [id]="'review-note-' + d.id"
              [maxLength]="maxNote"
              placeholder="What to change before it is written"
              [value]="note()"
              (input)="note.set($any($event.target).value)"
            ></textarea>
          </div>
          <div class="flex flex-wrap gap-2">
            <button
              hlmBtn
              size="sm"
              type="button"
              data-testid="approve-result"
              [disabled]="busy()"
              (click)="decide('APPROVED')"
            >
              @if (busy() && sending() === 'APPROVED') {
                <hlm-spinner aria-hidden="true" />
              } @else {
                <ng-icon name="lucideCheck" aria-hidden="true" />
              }
              Approve and write it
            </button>
            <button
              hlmBtn
              variant="outline"
              size="sm"
              type="button"
              data-testid="return-result"
              [disabled]="busy() || !note().trim()"
              (click)="decide('DENIED')"
            >
              @if (busy() && sending() === 'DENIED') {
                <hlm-spinner aria-hidden="true" />
              } @else {
                <ng-icon name="lucideUndo2" aria-hidden="true" />
              }
              Return with the note
            </button>
          </div>
        } @else {
          <p class="text-muted-foreground" data-testid="review-waiting">
            Waiting for {{ d.approverGroupName ?? d.approverName ?? 'its team' }} to review it.
          </p>
        }
      } @else {
        <p class="text-sm" data-testid="review-outcome">
          @if (d.status === 'CANCELLED' || !d.outcome) {
            <span class="text-muted-foreground"
              >Cancelled{{ d.cancelCause ? ': ' + d.cancelCause : '' }}</span
            >
          } @else if (d.outcome === 'APPROVED') {
            <span class="text-success font-semibold">Approved</span>
            @if (d.decidedByName) {
              by {{ d.decidedByName }}
            }
            : written to the issue.
          } @else {
            <span class="font-semibold">Returned</span>
            @if (d.decidedByName) {
              by {{ d.decidedByName }}
            }
            @if (d.note) {
              : “{{ d.note }}”
            }
          }
        </p>
      }
      @if (error(); as message) {
        <p class="text-destructive text-xs" role="alert">{{ message }}</p>
      }
    </section>
  `,
})
export class ResultReviewCard {
  private readonly api = inject(Api);

  readonly decision = input.required<Decision>();
  readonly companyId = input.required<string>();
  readonly decided = output<Decision>();
  readonly outdated = output<void>();

  protected readonly maxNote = MAX_NOTE;
  protected readonly note = signal('');
  protected readonly busy = signal(false);
  protected readonly sending = signal<'APPROVED' | 'DENIED' | null>(null);
  protected readonly error = signal<string | null>(null);

  protected readonly actionable = computed(
    () => this.decision().status === 'OPEN' && this.decision().mayDecide,
  );
  protected readonly title = computed(() => {
    const d = this.decision();
    const agent = d.agentName ?? 'The agent';
    return d.status === 'OPEN'
      ? `${agent} finished: review it before it is written`
      : `${agent}’s result was reviewed`;
  });
  /** Exactly what would be written, each write its own item with what it is. */
  protected readonly writes = computed(() => {
    const subject = this.decision().subject;
    return subject?.writeItems?.length
      ? subject.writeItems.map(reviewItem)
      : (subject?.writes ?? []).map(reviewWrite);
  });

  protected async decide(outcome: 'APPROVED' | 'DENIED'): Promise<void> {
    const note = this.note().trim();
    if (this.busy() || (outcome === 'DENIED' && !note)) return;
    this.busy.set(true);
    this.sending.set(outcome);
    this.error.set(null);
    try {
      const updated = await this.api.invoke(decideDecision, {
        companyId: this.companyId(),
        decisionId: this.decision().id,
        body: note ? { outcome, note } : { outcome },
      });
      this.note.set('');
      this.decided.emit(updated);
    } catch (error) {
      this.error.set(problemMessage(error));
      if (hasProblemCode(error, 'DECISION_DECIDED')) this.outdated.emit();
    } finally {
      this.busy.set(false);
      this.sending.set(null);
    }
  }
}

/** One write of a result's review, as the card shows it. */
export interface ReviewWrite {
  readonly kind: 'comment' | 'action';
  /** What it is: "A comment on SHOP-241", "A link on SHOP-241". */
  readonly label: string;
  /** What is written, as Markdown, its links by their words. */
  readonly text: string;
}

/**
 * A write of a result's review by its kind (`DecisionSubject.writeItems`): what it is, on what
 * (`target`), and what it writes (`value`); a review raised before writes had a kind falls back to
 * their words (`reviewWrite`).
 */
export function reviewItem(item: DecisionWrite): ReviewWrite {
  const target = item.target ?? 'the issue';
  const value = item.value ?? '';
  switch (item.kind) {
    case 'COMMENT': {
      const body = /^A comment on [^\n]*\n+([\s\S]*)$/.exec(item.text)?.[1] ?? item.text;
      return { kind: 'comment', label: `A comment on ${target}`, text: namedLinks(body.trim()) };
    }
    case 'TRANSITION':
      return action(`A transition of ${target}`, `Moves ${target} to “${value}”`);
    case 'LABEL_ADD':
      return action(`A label on ${target}`, `Adds the label “${value}”`);
    case 'LABEL_REMOVE':
      return action(`A label on ${target}`, `Removes the label “${value}”`);
    case 'CLOSE':
      return action(`Closing ${target}`, `Closes ${target}`);
    case 'ATTACHMENT':
      return action(`A document on ${target}`, `Attaches “${value}”`);
    case 'REMOTE_LINK':
      return action(`A link on ${target}`, namedLinks(item.text));
    case 'CHAT_POST':
      return { kind: 'comment', label: 'A post in the chat thread', text: namedLinks(item.text) };
    case 'CHAT_FILE':
      return action('A file in the chat thread', `Uploads “${value}”`);
    default:
      return reviewWrite(item.text);
  }
}

function action(label: string, text: string): ReviewWrite {
  return { kind: 'action', label, text };
}

/** The server's words for each action (the trigger's outbox plans), and what each one is. */
const ACTIONS: readonly [RegExp, (m: RegExpMatchArray) => string][] = [
  [/^Link (\S+) to /, (m) => `A link on ${m[1]}`],
  [/^Move (\S+) through /, (m) => `A transition of ${m[1]}`],
  [/^Attach .+ to (\S+)$/, (m) => `A document on ${m[1]}`],
  [/^(?:Add|Remove) the label .+ (?:to|from) (\S+)$/, (m) => `A label on ${m[1]}`],
  [/^Close (\S+)$/, (m) => `Closing ${m[1]}`],
];

/**
 * A write by its words only, for a review raised before writes had a kind: a comment ("A comment on SHOP-241:" then its Markdown) under its
 * own first line, an action under what it is, by the server's words for it.
 */
export function reviewWrite(write: string): ReviewWrite {
  const comment = /^(A comment on [^\n]+?):?\n+([\s\S]*)$/.exec(write);
  if (comment) return { kind: 'comment', label: comment[1], text: namedLinks(comment[2].trim()) };
  const line = write.trim();
  for (const [pattern, label] of ACTIONS) {
    const match = pattern.exec(line);
    if (match) return { kind: 'action', label: label(match), text: namedLinks(line) };
  }
  return { kind: 'action', label: 'An action', text: namedLinks(line) };
}
