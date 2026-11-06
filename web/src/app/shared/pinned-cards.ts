import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  linkedSignal,
  output,
  signal,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideChevronDown, lucideChevronUp, lucideCornerLeftUp } from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import type { ConversationBinding, Decision } from '../api/generated/models';
import { BindingCard, bindingCardText } from './binding-card';
import { DecisionCard, decisionKindLabel, decisionTitle } from './decision-card';
import { plural } from './labels';
import { ResultReviewCard } from './result-review-card';
import { boundText } from './who-acts-who-pays-view';

/** An open card of a conversation that waits on its reader. */
export type PinnedItem =
  | {
      readonly kind: 'decision';
      readonly id: string;
      readonly since: string;
      readonly decision: Decision;
    }
  | {
      readonly kind: 'binding';
      readonly id: string;
      readonly since: string;
      readonly binding: ConversationBinding;
    };

/** Where a pinned card was raised in the thread, for the page to scroll to. */
export interface PinnedJump {
  readonly kind: PinnedItem['kind'];
  readonly id: string;
}

/**
 * The account a tool call runs as, from the conversation's bindings (plan §4.1): the bound
 * binding of the call's system, in the server's words ("Jira: your own account"); null when the
 * system is not bound yet.
 */
export function bindingTextFor(
  decision: Decision,
  bindings: readonly ConversationBinding[],
): string | null {
  const systemId = decision.subject?.systemId;
  if (!systemId) return null;
  const bound = bindings.find((b) => b.systemId === systemId && b.state === 'BOUND');
  return bound ? boundText(bound) : null;
}

/**
 * What waits on the reader, longest waiting first: the open decisions they decide (`mayDecide`,
 * the server's answer) and the waiting bindings they act on. A decision for someone else, and a
 * binding an admin must fix, stay where they were raised and are not pinned.
 */
export function pinnedItems(
  decisions: readonly Decision[],
  bindings: readonly ConversationBinding[],
  reader: { readonly agentName: string; readonly mine: boolean },
): PinnedItem[] {
  const items: PinnedItem[] = [];
  for (const decision of decisions) {
    if (decision.status === 'OPEN' && decision.mayDecide) {
      items.push({ kind: 'decision', id: decision.id, since: decision.createdAt, decision });
    }
  }
  for (const binding of bindings) {
    if (binding.state !== 'WAITING') continue;
    if (!bindingCardText(binding, { agentName: reader.agentName, mine: reader.mine })?.actionable) {
      continue;
    }
    items.push({ kind: 'binding', id: binding.id, since: binding.waitingSince ?? '', binding });
  }
  return items.sort((a, b) => a.since.localeCompare(b.since));
}

/** A pinned card's one line while folded. */
function itemTitle(item: PinnedItem, agentName: string): string {
  if (item.kind === 'decision') {
    return `${decisionKindLabel(item.decision)} · ${decisionTitle(item.decision)}`;
  }
  return bindingCardText(item.binding, { agentName, mine: true })?.title ?? 'An account to connect';
}

/** The compact form's line: a question by its own words ("Question: Should the saved address…"). */
function compactTitle(item: PinnedItem, agentName: string): string {
  if (item.kind === 'decision' && item.decision.kind === 'QUESTION' && item.decision.question) {
    return `Question: ${item.decision.question}`;
  }
  if (item.kind === 'decision') {
    return `${decisionKindLabel(item.decision)}: ${decisionTitle(item.decision)}`;
  }
  return itemTitle(item, agentName);
}

/** What the compact form's button says the reader does with a card. */
function itemAction(item: PinnedItem): string {
  if (item.kind === 'binding') return 'Connect';
  if (item.decision.subject?.type === 'RESULT') return 'Review';
  return item.decision.kind === 'QUESTION' ? 'Answer' : 'Decide';
}

/** What waits, counted: "1 open question · 1 account to connect". */
export function pinnedCounts(items: readonly PinnedItem[]): string {
  let questions = 0;
  let approvals = 0;
  let reviews = 0;
  let accounts = 0;
  for (const item of items) {
    if (item.kind === 'binding') accounts++;
    else if (item.decision.subject?.type === 'RESULT') reviews++;
    else if (item.decision.kind === 'QUESTION') questions++;
    else approvals++;
  }
  return [
    questions ? plural(questions, 'open question') : '',
    approvals ? plural(approvals, 'approval') : '',
    reviews ? plural(reviews, 'review') : '',
    accounts ? plural(accounts, 'account to connect', 'accounts to connect') : '',
  ]
    .filter(Boolean)
    .join(' · ');
}

/**
 * The open cards of a conversation, pinned above its composer (plan §4.1): what the reader must
 * act on before the agent goes on, as one "Needs you" strip: a header that counts what waits
 * ("Needs you · 1 open question · 1 account to connect", with Fold), then the cards themselves,
 * never inside a box of their own. The oldest card is open and answerable in place; the others
 * are folded into one line each until opened. "Where it was raised" asks the page to scroll to
 * the card in the thread (`jump`). Folded, one line says what waits ("Needs you · Question: Should
 * the saved address…") with the oldest card's action ("Answer"), which scrolls to that card in the
 * thread; the line itself unfolds the cards. On a narrow screen (`compact`), where the cards
 * would cover most of it, the strip starts folded. A trigger's result review is W5's
 * `app-result-review-card`. The conversation page hosts it and shows it while the reader
 * is scrolled away from those cards; it renders nothing while nothing waits on the reader.
 *
 * Inputs: the conversation's `decisions` and `bindings` as `getConversation` answers them, the
 * company, the conversation, the agent's name and id, `agentEditable` (`Agent.editableByMe`, for
 * "for good"), `signInSystems` (the systems whose app lets the reader sign in:
 * `MyAccount.oauthAvailable`), `mine` (false for the Owner reading someone else's: nothing is
 * pinned then), `returnTo` and `compact`. Outputs: a decision as decided (`decided`), a decision someone
 * decided first (`outdated`), a binding as the server answered it (`bindingChanged`), and `jump`.
 */
@Component({
  selector: 'app-pinned-cards',
  imports: [NgIcon, HlmButton, BindingCard, DecisionCard, ResultReviewCard],
  providers: [provideIcons({ lucideChevronDown, lucideChevronUp, lucideCornerLeftUp })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let all = items();
    @if (all.length > 0 && folded()) {
      <section
        class="bg-human-soft/40 border-human/40 flex min-w-0 items-center gap-2 rounded-[14px] border py-1.5 ps-3 pe-1.5"
        aria-labelledby="pinned-cards-title"
        data-testid="pinned-cards"
        data-compact="true"
      >
        <h2 id="pinned-cards-title" class="sr-only">
          {{ all.length === 1 ? 'Needs you' : all.length + ' need you' }}
        </h2>
        <button
          type="button"
          class="focus-visible:ring-ring/50 flex min-h-9 min-w-0 flex-1 items-center gap-1.5 rounded-sm text-start text-[0.8125rem] outline-none focus-visible:ring-3 max-sm:min-h-11"
          data-testid="pinned-summary"
          aria-expanded="false"
          [attr.aria-label]="
            (all.length === 1 ? 'Needs you' : all.length + ' need you') + ' · ' + line(all[0])
          "
          (click)="folded.set(false)"
        >
          <span class="text-human shrink-0 font-semibold">{{
            all.length === 1 ? 'Needs you' : all.length + ' need you'
          }}</span>
          <span class="min-w-0 truncate">· {{ line(all[0]) }}</span>
          <ng-icon
            name="lucideChevronUp"
            class="text-muted-foreground shrink-0"
            aria-hidden="true"
          />
        </button>
        <button
          hlmBtn
          size="sm"
          type="button"
          class="shrink-0 max-sm:h-11 max-sm:px-4"
          data-testid="pinned-act"
          (click)="jump.emit({ kind: all[0].kind, id: all[0].id })"
        >
          {{ action(all[0]) }}
        </button>
      </section>
    } @else if (all.length > 0) {
      <!-- A strip, not a box: the cards are the only sheets in it. -->
      <section
        class="flex max-h-[60dvh] flex-col gap-2 overflow-y-auto"
        aria-labelledby="pinned-cards-title"
        data-testid="pinned-cards"
      >
        <div class="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-1 ps-1">
          <h2 id="pinned-cards-title" class="text-human text-[0.8125rem] font-semibold">
            Needs you
          </h2>
          <span class="text-muted-foreground min-w-0 text-xs" data-testid="pinned-counts"
            >· {{ counts() }}</span
          >
          <span class="flex-1"></span>
          @if (all.length > 1) {
            <button
              hlmBtn
              variant="ghost"
              size="sm"
              type="button"
              class="h-7 px-2 text-xs max-sm:h-11"
              data-testid="pinned-toggle"
              [attr.aria-expanded]="expanded()"
              (click)="expanded.set(!expanded())"
            >
              <ng-icon
                [name]="expanded() ? 'lucideChevronUp' : 'lucideChevronDown'"
                aria-hidden="true"
              />
              {{ expanded() ? 'Fold the others' : 'Show all' }}
            </button>
          }
          <button
            hlmBtn
            variant="ghost"
            size="sm"
            type="button"
            class="h-7 px-2 text-xs max-sm:h-11"
            data-testid="pinned-fold"
            aria-expanded="true"
            (click)="folded.set(true)"
          >
            <ng-icon name="lucideChevronDown" aria-hidden="true" />
            Fold
          </button>
        </div>
        @for (item of all; track item.kind + item.id; let first = $first) {
          @let shown = first || expanded() || opened() === item.id;
          <div class="flex flex-col gap-1" [attr.data-pinned]="item.kind" [attr.data-id]="item.id">
            @if (shown) {
              @switch (item.kind) {
                @case ('decision') {
                  @if (item.decision.subject?.type === 'RESULT') {
                    <app-result-review-card
                      [decision]="item.decision"
                      [companyId]="companyId()"
                      (decided)="decided.emit($event)"
                      (outdated)="outdated.emit()"
                    />
                  } @else {
                    <app-decision-card
                      [decision]="item.decision"
                      [companyId]="companyId()"
                      [agentEditable]="agentEditable()"
                      [bindingText]="bindingText(item.decision)"
                      (decided)="decided.emit($event)"
                      (outdated)="outdated.emit()"
                    />
                  }
                }
                @case ('binding') {
                  <app-binding-card
                    [binding]="item.binding"
                    [companyId]="companyId()"
                    [conversationId]="conversationId()"
                    [agentName]="agentName()"
                    [agentId]="agentId()"
                    [signInAvailable]="signInSystems().includes(item.binding.systemId)"
                    [returnTo]="returnTo()"
                    (changed)="bindingChanged.emit($event)"
                  />
                }
              }
              <button
                type="button"
                class="text-muted-foreground hover:text-foreground focus-visible:ring-ring/50 inline-flex items-center gap-1 self-end rounded-sm px-1 text-xs underline-offset-2 outline-none hover:underline focus-visible:ring-3 max-sm:min-h-11"
                data-testid="pinned-jump"
                (click)="jump.emit({ kind: item.kind, id: item.id })"
              >
                <ng-icon name="lucideCornerLeftUp" size="12" aria-hidden="true" />
                Where it was raised
              </button>
            } @else {
              <button
                type="button"
                class="bg-card hover:bg-accent focus-visible:ring-ring/50 flex min-h-10 w-full min-w-0 items-center gap-2 rounded-[10px] border px-3 py-2 text-start text-[0.8125rem] outline-none focus-visible:ring-3 max-sm:min-h-11"
                data-testid="pinned-folded"
                [attr.aria-label]="'Open: ' + title(item)"
                (click)="opened.set(item.id)"
              >
                <span class="bg-human size-2 shrink-0 rounded-full" aria-hidden="true"></span>
                <span class="min-w-0 flex-1 truncate">{{ title(item) }}</span>
                <ng-icon name="lucideChevronDown" class="shrink-0" aria-hidden="true" />
              </button>
            }
          </div>
        }
      </section>
    }
  `,
})
export class PinnedCards {
  readonly decisions = input.required<readonly Decision[]>();
  readonly bindings = input<readonly ConversationBinding[]>([]);
  readonly companyId = input.required<string>();
  readonly conversationId = input.required<string>();
  readonly agentName = input.required<string>();
  readonly agentId = input<string | null>(null);
  readonly agentEditable = input(false);
  readonly signInSystems = input<readonly string[]>([]);
  readonly mine = input(true);
  readonly returnTo = input<string | null>(null);
  /** A narrow screen: one line pinned until the reader unfolds it. Elsewhere Fold makes it one line. */
  readonly compact = input(false);

  readonly decided = output<Decision>();
  readonly outdated = output<void>();
  readonly bindingChanged = output<ConversationBinding>();
  readonly jump = output<PinnedJump>();

  /** Every card open, or only the oldest. */
  protected readonly expanded = signal(false);
  /** A folded card the reader opened. */
  protected readonly opened = signal<string | null>(null);

  protected readonly items = computed(() =>
    this.mine()
      ? pinnedItems(this.decisions(), this.bindings(), {
          agentName: this.agentName(),
          mine: true,
        })
      : [],
  );

  /**
   * Whether the strip is one line: from the start on a narrow screen, else when the reader folds
   * it; as the screen says again when another card is the oldest.
   */
  protected readonly folded = linkedSignal({
    source: () => `${this.compact()}|${this.items()[0]?.id ?? ''}`,
    computation: () => this.compact(),
  });
  /** "1 open question · 1 account to connect". */
  protected readonly counts = computed(() => pinnedCounts(this.items()));

  protected bindingText(decision: Decision): string | null {
    return bindingTextFor(decision, this.bindings());
  }

  protected title(item: PinnedItem): string {
    return itemTitle(item, this.agentName());
  }

  protected line(item: PinnedItem): string {
    return compactTitle(item, this.agentName());
  }

  protected action(item: PinnedItem): string {
    return itemAction(item);
  }
}
