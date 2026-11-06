import { NgTemplateOutlet } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  output,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideChevronUp,
  lucideCirclePlay,
  lucideKeyRound,
  lucideLogIn,
  lucideShieldAlert,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  decideDecision,
  startMyAccountSignIn,
  startSystemCredentialSignInAgain,
  updateSystemCredential,
} from '../../../api/generated/functions';
import type {
  ConversationBinding,
  DecideRequest,
  Decision,
  InboxItem,
} from '../../../api/generated/models';
import { hasProblemCode, problemMessage, toProblem } from '../../../api/problem';
import { BrowserNavigation } from '../../../core/browser-navigation';
import { SubmissionKey, submitWithKey } from '../../../core/submission-key';
import { BindingCard } from '../../../shared/binding-card';
import { DecisionCard, decisionActions } from '../../../shared/decision-card';
import { formatAbsolute } from '../../../shared/time';
import { initials } from '../../../shared/forms';
import { signInReturnTo } from '../../../shared/sign-in-result';
import { accountProblemMessage } from '../../../shared/token-hints';
import { CREDENTIAL_MESSAGES } from '../systems/credential-labels';
import {
  CELL_ACTS,
  CELL_ASIDE,
  CELL_KIND,
  CELL_TEXT,
  CELL_TILE,
  KIND_PILL,
  KIND_TONES,
  META,
  META_LINK,
  ROW_BUTTON,
  ROW_GRID,
  ROW_TITLE,
  TILE,
  TILE_AGENT,
  TILE_NEUTRAL,
} from './inbox-styles';
import {
  agentOf,
  boundView,
  decidedView,
  enabledView,
  type InboxDoneView,
  type InboxReader,
  inboxRowView,
  isPlainLine,
  waitedText,
} from './inbox-words';

/** A decision answered from its row in one click. */
interface QuickAction {
  readonly id: string;
  readonly label: string;
  readonly primary: boolean;
  readonly body: DecideRequest;
}

/** A question offers its options in its row while they are this few. */
const MAX_QUICK_OPTIONS = 4;

let nextId = 0;

/**
 * One item of the inbox that needs the reader (plan §5), as a compact orange row: the kind's pill,
 * the agent's tile, what it asks in one line, the conversation and how long it waits, and what
 * settles it in one click: a question's options, an approval's Approve and Deny, Sign in with the
 * system of a Connect card, an admin's Sign in again. "Reply…", "Add a note" or a click on the ask
 * opens it in place as the whole card (`DecisionCard` or `BindingCard`, inline); the page keeps one
 * open at a time. Acted on, it emits what its row collapses to.
 */
@Component({
  selector: 'app-inbox-need',
  imports: [NgTemplateOutlet, RouterLink, NgIcon, HlmButton, HlmSpinner, BindingCard, DecisionCard],
  providers: [
    provideIcons({
      lucideChevronUp,
      lucideCirclePlay,
      lucideKeyRound,
      lucideLogIn,
      lucideShieldAlert,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let i = item();
    @let v = view();
    @let d = decision();
    <ng-template #metaLine let-full="full">
      <p [class]="meta">
        @if (conversation(); as c) {
          <a
            data-testid="inbox-row-conversation"
            [class]="metaLink"
            [routerLink]="['/c', companyId(), 'conversations', c.id]"
            >{{ c.key }}</a
          >
          @if (c.title) {
            <span class="min-w-0 break-words">{{ c.title }}</span>
          }
        } @else if (v.body) {
          <span class="min-w-0 break-words" data-testid="inbox-row-body">{{ v.body }}</span>
        }
        <span [class]="full ? 'contents' : 'contents max-md:hidden'">
          <span class="text-faint" aria-hidden="true">·</span>
          <span
            >{{ named() ? agent() + ' · ' : ''
            }}<time [attr.datetime]="i.since" [title]="absolute()">{{ waited() }}</time></span
          >
        </span>
      </p>
    </ng-template>

    @if (open() && opener()) {
      <article
        class="border-human m-1.5 rounded-xl border px-2.5 pt-2.5 pb-4 md:ps-[0.5625rem]"
        data-testid="inbox-need"
        data-open="true"
        [class]="cardGrid"
        [attr.data-kind]="i.kind"
        [attr.data-decision-id]="d?.id ?? null"
        [attr.data-reason]="i.binding?.reason ?? null"
        [attr.aria-labelledby]="ids.title"
      >
        <span [class]="cellKind" class="md:mt-1">
          <span [class]="pill()" [title]="v.kind"
            ><span class="truncate">{{ v.kind }}</span></span
          >
        </span>
        <span class="col-start-3 row-start-1 md:col-start-4">
          <button
            hlmBtn
            variant="ghost"
            size="icon-sm"
            type="button"
            class="text-muted-foreground -my-1 max-md:size-11"
            aria-expanded="true"
            data-testid="inbox-need-fold"
            [attr.aria-label]="foldLabel()"
            (click)="fold()"
          >
            <ng-icon name="lucideChevronUp" aria-hidden="true" />
          </button>
        </span>
        <span [class]="tileClass() + ' ' + cellTile" class="md:mt-1" aria-hidden="true">
          @if (agentTile()) {
            {{ initials() }}
          } @else {
            <ng-icon [name]="tileIcon()" size="16" />
          }
        </span>
        <div [class]="cellText">
          <h3
            class="text-sm leading-snug font-semibold text-pretty break-words outline-none"
            tabindex="-1"
            [id]="ids.title"
          >
            {{ openTitle() }}
          </h3>
          <ng-container [ngTemplateOutlet]="metaLine" [ngTemplateOutletContext]="{ full: true }" />
        </div>
        <div class="col-span-full min-w-0 pt-1 md:col-span-2 md:col-start-3">
          @if (d) {
            <app-decision-card
              appearance="inline"
              [decision]="d"
              [companyId]="companyId()"
              [agentEditable]="agentEditable()"
              [showQuestion]="!questionInTitle()"
              (decided)="onDecided($event)"
              (outdated)="changed.emit()"
              (dismissed)="fold()"
            />
          } @else if (binding(); as b) {
            <app-binding-card
              appearance="inline"
              [binding]="b"
              [companyId]="companyId()"
              [conversationId]="i.conversation?.id ?? ''"
              [agentName]="agent()"
              [agentId]="i.conversation?.agentId ?? null"
              [signInAvailable]="signInAvailable()"
              (changed)="onBound($event)"
              (outdated)="changed.emit()"
            />
          }
        </div>
      </article>
    } @else {
      <div
        class="px-4 py-3 md:min-h-17"
        data-testid="inbox-need"
        [class]="grid"
        [attr.data-kind]="i.kind"
        [attr.data-decision-id]="d?.id ?? null"
        [attr.data-reason]="i.binding?.reason ?? null"
      >
        <span [class]="cellKind">
          <span [class]="pill()" [title]="v.kind"
            ><span class="truncate">{{ v.kind }}</span></span
          >
        </span>
        <span [class]="cellAside">{{ waited() }}</span>
        <span [class]="tileClass() + ' ' + cellTile" aria-hidden="true">
          @if (agentTile()) {
            {{ initials() }}
          } @else {
            <ng-icon [name]="tileIcon()" size="16" />
          }
        </span>
        <div [class]="cellText">
          @if (opener()) {
            <button
              #ask
              type="button"
              aria-expanded="false"
              data-testid="inbox-need-ask"
              [class]="rowTitle"
              class="focus-visible:ring-ring/50 cursor-pointer rounded-sm underline-offset-2 outline-none hover:underline focus-visible:ring-3"
              (click)="toggled.emit(true)"
            >
              {{ v.title }}
            </button>
          } @else {
            <p [class]="rowTitle" data-testid="inbox-row-title">{{ v.title }}</p>
          }
          <ng-container [ngTemplateOutlet]="metaLine" [ngTemplateOutletContext]="{ full: false }" />
          @if (error(); as message) {
            <p class="text-destructive mt-1 text-xs" role="alert" data-testid="inbox-row-error">
              {{ message }}
            </p>
          }
        </div>
        <div
          [class]="cellActs"
          role="group"
          [attr.aria-label]="actsLabel()"
          data-testid="inbox-need-actions"
        >
          @for (option of quickOptions(); track option) {
            <button
              type="button"
              class="border-input bg-card hover:bg-accent focus-visible:ring-ring/50 inline-flex h-8 max-w-full min-w-0 items-center gap-1.5 rounded-full border px-3.5 text-[0.8125rem] font-semibold outline-none focus-visible:ring-3 disabled:opacity-50 max-md:h-11 md:max-w-56"
              data-testid="inbox-quick-option"
              [title]="option"
              [attr.aria-label]="'Answer ' + option"
              [disabled]="!!busy()"
              (click)="decide('option:' + option, { option })"
            >
              @if (busy() === 'option:' + option) {
                <hlm-spinner class="size-3.5" aria-hidden="true" />
              }
              <span class="truncate">{{ option }}</span>
            </button>
          }
          @for (action of quickActions(); track action.id) {
            <button
              hlmBtn
              size="sm"
              type="button"
              [class]="rowButton"
              [variant]="action.primary ? 'default' : 'outline'"
              [attr.data-testid]="'inbox-quick-' + action.id"
              [disabled]="!!busy()"
              (click)="decide(action.id, action.body)"
            >
              @if (busy() === action.id) {
                <hlm-spinner class="size-3.5" aria-hidden="true" />
              }
              {{ action.label }}
            </button>
          }
          @if (bindingSignIn()) {
            <button
              hlmBtn
              size="sm"
              type="button"
              data-testid="inbox-binding-sign-in"
              [class]="rowButton"
              [disabled]="!!busy()"
              (click)="signIntoSystem()"
            >
              @if (busy() === 'sign-in') {
                <hlm-spinner class="size-3.5" aria-hidden="true" />
              } @else {
                <ng-icon name="lucideLogIn" aria-hidden="true" />
              }
              Sign in with {{ i.binding?.systemName ?? 'the system' }}
            </button>
          }
          @if (v.action === 'sign-in') {
            <button
              hlmBtn
              size="sm"
              type="button"
              data-testid="inbox-sign-in"
              [class]="rowButton"
              [disabled]="!!busy()"
              (click)="signInAgain()"
            >
              @if (busy() === 'sign-in') {
                <hlm-spinner class="size-3.5" aria-hidden="true" />
              } @else {
                <ng-icon name="lucideLogIn" aria-hidden="true" />
              }
              Sign in again
            </button>
          }
          @if (v.action === 'enable') {
            <button
              hlmBtn
              size="sm"
              type="button"
              data-testid="inbox-enable"
              [class]="rowButton"
              [disabled]="!!busy()"
              (click)="enable()"
            >
              @if (busy() === 'enable') {
                <hlm-spinner class="size-3.5" aria-hidden="true" />
              } @else {
                <ng-icon name="lucideCirclePlay" aria-hidden="true" />
              }
              Enable it
            </button>
          }
          @if (opener(); as label) {
            <button
              hlmBtn
              size="sm"
              type="button"
              aria-expanded="false"
              data-testid="inbox-need-open"
              [class]="rowButton"
              [variant]="openerAlone() ? 'outline' : 'ghost'"
              [disabled]="!!busy()"
              (click)="toggled.emit(true)"
            >
              {{ label }}
            </button>
          } @else if (!conversation() && v.link; as link) {
            <a
              hlmBtn
              size="sm"
              data-testid="inbox-row-link"
              [class]="rowButton"
              [variant]="v.action ? 'ghost' : 'outline'"
              [routerLink]="['/c', companyId()].concat(link.commands)"
              [queryParams]="link.queryParams"
              [fragment]="link.fragment"
              >{{ link.label }}</a
            >
          }
        </div>
      </div>
    }
  `,
})
export class InboxNeed {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly navigation = inject(BrowserNavigation);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  readonly item = input.required<InboxItem>();
  readonly companyId = input.required<string>();
  readonly reader = input<InboxReader>({ admin: false, owner: false });
  /** Whether it is the item open in place; the page keeps one open at a time. */
  readonly open = input(false);
  /** Whether the reader may edit the agent (`Agent.editableByMe`): "for good" needs it. */
  readonly agentEditable = input(false);
  /** A binding's Connect card, as the page made it with the reader's accounts. */
  readonly binding = input<ConversationBinding | null>(null);
  readonly signInAvailable = input(false);

  /** Asks the page to open it in place (true) or to fold it (false). */
  readonly toggled = output<boolean>();
  /** Settled here, with what its row collapses to. */
  readonly done = output<InboxDoneView>();
  /** Settled elsewhere or changed: the page reads the inbox again. */
  readonly changed = output<void>();

  protected readonly grid = ROW_GRID;
  protected readonly cardGrid = ROW_GRID.replace('items-center', 'items-start');
  protected readonly cellKind = CELL_KIND;
  protected readonly cellAside = CELL_ASIDE;
  protected readonly cellTile = CELL_TILE;
  protected readonly cellText = CELL_TEXT;
  protected readonly cellActs = CELL_ACTS;
  protected readonly rowTitle = ROW_TITLE;
  protected readonly rowButton = ROW_BUTTON;
  protected readonly meta = META;
  protected readonly metaLink = META_LINK;
  protected readonly ids = { title: `inbox-need-${nextId++}` };

  /** The id of the action being sent: a quick decision, or a sign-in. */
  protected readonly busy = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  private readonly submission = new SubmissionKey();
  /** The fold came from this card (its button, Cancel, Escape): the focus goes back to its row. */
  private returnFocus = false;

  protected readonly view = computed(() => inboxRowView(this.item(), this.reader()));
  protected readonly decision = computed<Decision | null>(() =>
    this.item().kind === 'DECISION' ? (this.item().decision ?? null) : null,
  );
  protected readonly agent = computed(() => agentOf(this.item()) ?? 'The agent');
  protected readonly agentTile = computed(() => !!agentOf(this.item()));
  protected readonly initials = computed(() => initials(this.agent()));
  protected readonly tileIcon = computed(() =>
    this.item().kind === 'AUDIT_CHECK' ? 'lucideShieldAlert' : 'lucideKeyRound',
  );
  protected readonly tileClass = computed(
    () => `${TILE} ${this.agentTile() ? TILE_AGENT : TILE_NEUTRAL}`,
  );
  protected readonly pill = computed(() => `${KIND_PILL} ${KIND_TONES[this.view().tone]}`);
  protected readonly waited = computed(() => waitedText(this.item().since));
  protected readonly absolute = computed(() => formatAbsolute(this.item().since));
  /** A question's ask does not name its agent: the meta line does. */
  protected readonly named = computed(() => this.decision()?.kind === 'QUESTION');

  protected readonly conversation = computed(() => {
    const c = this.item().conversation;
    const d = this.decision();
    if (c) return { id: c.id, key: c.key, title: c.title };
    return d?.conversationKey
      ? { id: d.conversationId, key: d.conversationKey, title: d.conversationTitle ?? '' }
      : null;
  });

  /** Whether a question's title carries it whole, so its card does not repeat it. */
  protected readonly questionInTitle = computed(() => {
    const d = this.decision();
    return d?.kind === 'QUESTION' && isPlainLine(d.question);
  });
  protected readonly openTitle = computed(() => {
    const d = this.decision();
    if (d?.kind === 'QUESTION' && !this.questionInTitle()) return `${this.agent()} asks`;
    return this.view().title;
  });

  /** A question's options, answered in one click; none when they are many or long. */
  protected readonly quickOptions = computed<readonly string[]>(() => {
    const d = this.decision();
    if (!d || d.kind !== 'QUESTION' || !d.mayDecide || d.status !== 'OPEN') return [];
    return d.options.length <= MAX_QUICK_OPTIONS ? d.options : [];
  });

  /**
   * An approval's Approve and Deny (Allow for this conversation for a repository or a host), as
   * the card sends them. A result to review, "for good" and a note need the whole card.
   */
  protected readonly quickActions = computed<readonly QuickAction[]>(() => {
    const d = this.decision();
    if (!d || d.kind !== 'APPROVAL' || !d.mayDecide || d.status !== 'OPEN') return [];
    if (d.subject?.type === 'RESULT') return [];
    return decisionActions(d, false)
      .filter((a) => !a.needsText && a.outcome !== 'ANSWERED')
      .map((a) => {
        const body: DecideRequest = { outcome: a.outcome === 'APPROVED' ? 'APPROVED' : 'DENIED' };
        if (a.scope) body.scope = a.scope;
        if (a.outcome === 'APPROVED' && d.subject?.type === 'REPOSITORY') {
          body.access = d.subject.access ?? d.access ?? 'READ';
        }
        return { id: a.id, label: a.label, primary: a.primary, body };
      });
  });

  /** Sign in with the system of a Connect card, in its row. */
  protected readonly bindingSignIn = computed(() => {
    const b = this.item().binding;
    return (
      this.item().kind === 'BINDING' &&
      this.signInAvailable() &&
      (b?.reason === 'NONE' || b?.reason === 'RECONNECT_YOURS')
    );
  });

  /** The button that opens the whole card, by what it holds; null for a row without a card. */
  protected readonly opener = computed<string | null>(() => {
    const item = this.item();
    const d = this.decision();
    if (d) {
      if (!d.mayDecide || d.status !== 'OPEN') return 'Open';
      if (d.kind === 'QUESTION') return 'Reply…';
      switch (d.subject?.type) {
        case 'RESULT':
          return 'Review…';
        case 'REPOSITORY':
        case 'HOST':
          return 'More…';
        default:
          return 'Add a note';
      }
    }
    if (item.kind === 'BINDING' && this.binding()) {
      const reason = item.binding?.reason;
      return reason === 'NONE' || reason === 'RECONNECT_YOURS' ? 'Paste a token' : 'Choose…';
    }
    return null;
  });
  /** The opener is the row's only action: drawn as one. */
  protected readonly openerAlone = computed(
    () =>
      this.quickOptions().length === 0 && this.quickActions().length === 0 && !this.bindingSignIn(),
  );
  protected readonly actsLabel = computed(() =>
    this.decision()?.kind === 'QUESTION'
      ? `Answer ${this.agent()}`
      : `Act on: ${this.view().title}`,
  );
  protected readonly foldLabel = computed(() => {
    const d = this.decision();
    return d ? `Fold the ${this.view().kind.toLowerCase()}` : 'Fold the account to connect';
  });

  constructor() {
    // Opened, the focus goes into the card, to its first field (the answer, the note, the token)
    // or else its title; folded from inside, back to the row's ask.
    let wasOpen = false;
    effect(() => {
      const open = this.open() && !!this.opener();
      if (open === wasOpen) return;
      wasOpen = open;
      const back = this.returnFocus;
      this.returnFocus = false;
      if (!open && !back) return;
      afterNextRender(
        () => {
          const el = this.host.nativeElement;
          const target = open
            ? (el.querySelector<HTMLElement>(
                'textarea, input:not([type=radio]):not([type=checkbox])',
              ) ?? el.querySelector<HTMLElement>('h3'))
            : el.querySelector<HTMLElement>('[data-testid=inbox-need-ask]');
          target?.focus();
        },
        { injector: this.injector },
      );
    });
  }

  protected fold(): void {
    this.returnFocus = true;
    this.toggled.emit(false);
  }

  /** Answers or decides from the row, with one `Idempotency-Key` per submission. */
  protected async decide(id: string, body: DecideRequest): Promise<void> {
    const d = this.decision();
    if (!d || this.busy()) return;
    this.busy.set(id);
    this.error.set(null);
    try {
      const updated = await submitWithKey(this.submission, { decisionId: d.id, body }, (key) =>
        this.api.invoke(decideDecision, {
          companyId: this.companyId(),
          decisionId: d.id,
          'Idempotency-Key': key,
          body,
        }),
      );
      this.done.emit(decidedView(updated));
    } catch (error) {
      this.error.set(problemMessage(error));
      // Someone decided it first, or it was cancelled: the page reads the inbox again.
      if (hasProblemCode(error, 'DECISION_DECIDED')) this.changed.emit();
    } finally {
      this.busy.set(null);
    }
  }

  protected onDecided(decision: Decision): void {
    this.done.emit(decidedView(decision));
  }

  protected onBound(binding: ConversationBinding): void {
    if (binding.state === 'BOUND') this.done.emit(boundView(this.item(), binding));
    else this.changed.emit();
  }

  /** Signs the reader in at a Connect card's system, coming back to the inbox (plan §4.1). */
  protected async signIntoSystem(): Promise<void> {
    const b = this.item().binding;
    if (!b || this.busy()) return;
    await this.startSignIn(
      () =>
        this.api.invoke(startMyAccountSignIn, {
          companyId: this.companyId(),
          systemId: b.systemId,
          body: { returnTo: signInReturnTo(this.router) },
        }),
      (error) => accountProblemMessage(error, b.systemName ?? 'the system'),
    );
  }

  /** Signs a system's credential in again at the system (ADR-0072), coming back to the inbox. */
  protected async signInAgain(): Promise<void> {
    const account = this.item().account;
    if (!account?.credential || this.busy()) return;
    const credentialId = account.credential.id;
    await this.startSignIn(
      () =>
        this.api.invoke(startSystemCredentialSignInAgain, {
          companyId: this.companyId(),
          systemId: account.systemId,
          credentialId,
          body: { returnTo: signInReturnTo(this.router) },
        }),
      (error) =>
        problemMessage(error, {
          OAUTH_NOT_AVAILABLE: `${account.systemName ?? 'The system'} has no sign-in app: store a new token for the credential on its page.`,
          BOT_ACCOUNT_IN_USE: 'This is the chat system’s bot: replace its token under Bot.',
        }),
    );
  }

  /** Enables a disabled credential again (ADR-0072): what waited on it goes on. */
  protected async enable(): Promise<void> {
    const account = this.item().account;
    if (!account?.credential || this.busy()) return;
    this.busy.set('enable');
    this.error.set(null);
    try {
      await this.api.invoke(updateSystemCredential, {
        companyId: this.companyId(),
        systemId: account.systemId,
        credentialId: account.credential.id,
        body: { enabled: true },
      });
      this.done.emit(enabledView(this.item()));
    } catch (error) {
      this.error.set(problemMessage(error, CREDENTIAL_MESSAGES));
      // Gone or changed meanwhile: the page reads the inbox again.
      if (toProblem(error).status === 404) this.changed.emit();
    } finally {
      this.busy.set(null);
    }
  }

  private async startSignIn(
    start: () => Promise<{ authorizationUrl: string }>,
    message: (error: unknown) => string,
  ): Promise<void> {
    this.busy.set('sign-in');
    this.error.set(null);
    try {
      this.navigation.assign((await start()).authorizationUrl);
    } catch (error) {
      this.error.set(message(error));
      this.busy.set(null);
    }
  }
}
