import {
  afterEveryRender,
  afterNextRender,
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  DOCUMENT,
  effect,
  type ElementRef,
  inject,
  Injector,
  input,
  resource,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { NgIcon, provideIcons } from '@ng-icons/core';
import { lucideChevronDown, lucidePaperclip, lucideTriangleAlert } from '@ng-icons/lucide';
import { HlmSheetImports } from '@spartan-ng/helm/sheet';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { Api } from '../../../api/generated/api';
import { getAgent, listMyAccounts } from '../../../api/generated/functions';
import type { Conversation, ConversationBinding } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { CallerPrincipal } from '../../../core/caller-principal';
import { CurrentCompany } from '../../../core/current-company';
import { sameParams } from '../../../core/paged-resource';
import { mediaQuery } from '../../../core/media-query';
import { Notify } from '../../../core/notify';
import { isBreakGlass, isMember, isViewer, managesCompany } from '../../../core/permissions';
import { EndFollower, prefersReducedMotion } from '../../../core/scroll-anchor';
import { continueBindingAfterSignIn } from '../../../shared/binding-card';
import { chatPlaceView } from '../../../shared/chat/chat-place';
import {
  ConversationReads,
  ConversationReadsButton,
  OWNER_READS_TEXT,
} from '../../../shared/conversation-reads';
import { ErrorState } from '../../../shared/error-state';
import { PinnedCards, type PinnedJump } from '../../../shared/pinned-cards';
import { type SignInResult, signInReturn } from '../../../shared/sign-in-result';
import { WhoActsWhoPays, WhoActsWhoPaysLine } from '../../../shared/who-acts-who-pays';
import { ViewerNotice } from '../../../shared/viewer-notice';
import { NotFound } from '../../not-found';
import { Composer } from './composer';
import { ConversationCards } from './conversation-cards';
import { ConversationHeader, ConversationMeta } from './conversation-header';
import { ConversationSide } from './conversation-side';
import { ConversationStatus } from './conversation-status';
import { ConversationStore } from './conversation-store';
import { conversationPhase, openQuestions, turnActive } from './conversation-view';
import { MessageList } from './message-list';
import { OwnerNotice } from './owner-notice';
import { TrackerPlaceNotice } from '../triggers/conversation/tracker-place-notice';

/**
 * The conversation page, `conversations/:conversationId` (plan §4.1): one agent's work for one
 * person, readable at a glance: what was said, what the agent does, what it needs from you, with
 * whose accounts and on whose budget. The thread comes first.
 *
 * The head spans the width: the header (`ConversationHeader`: title, state, the agent, key and
 * branch, the subject, the menu), then one quiet line of who acts and who pays
 * (`app-who-acts-who-pays-line`), whose "Details" opens the full panel in place, and, for its
 * person, the standing Owner notice as an eye button that lists who else read it
 * (`app-conversation-reads-button`, W7). Below, the thread sheet and, from `lg`, a 20rem side
 * column (`ConversationSide`: W4c's outputs and files, the workspace, hosts and turns) start on
 * the same line; the open panel sits above the thread, level with the side column.
 *
 * The thread: the messages and turns (`MessageList`, with W4b's decision cards where they were
 * raised and the live turn), what the state means (`ConversationStatus`), the Connect cards
 * (`ConversationCards`), and the composer, which sticks to the bottom of the screen as the page
 * scrolls, with W4b's `app-pinned-cards` above it while the reader's open cards in the thread are
 * out of view. The Owner, who is not its person, gets the banner where the composer would be
 * (`OwnerNotice`); a conversation a chat holds says where to write instead.
 *
 * On a phone the header and the line shrink to a few lines, and "Details" opens a sheet with what
 * they leave out (what the conversation is, who acts and who pays, who read it) and the side
 * column; from `sm` to `lg` the sheet holds the side column.
 *
 * The conversation lives in a `ConversationStore` (freshness: every answer replaces it, the live
 * turn's stream drives it, otherwise it polls while visible). Back from a sign-in a Connect card
 * started (`?account=`), the page continues the binding and says what happened.
 */
@Component({
  selector: 'app-conversation-page',
  imports: [
    NgIcon,
    HlmSheetImports,
    HlmSkeleton,
    Composer,
    ConversationCards,
    ConversationHeader,
    ConversationMeta,
    ConversationSide,
    ConversationReads,
    ConversationReadsButton,
    ConversationStatus,
    ErrorState,
    MessageList,
    NotFound,
    OwnerNotice,
    ViewerNotice,
    PinnedCards,
    TrackerPlaceNotice,
    WhoActsWhoPays,
    WhoActsWhoPaysLine,
  ],
  providers: [
    ConversationStore,
    provideIcons({ lucideChevronDown, lucidePaperclip, lucideTriangleAlert }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block', 'data-testid': 'conversation-page' },
  template: `
    @let companyId = current.id() ?? '';
    @if (store.notFound()) {
      <app-not-found />
    } @else if (conversation(); as c) {
      <div
        class="flex min-w-0 flex-col gap-3.5"
        [attr.data-phase]="phase()"
        [attr.data-conversation-id]="c.id"
      >
        <app-conversation-header
          [conversation]="c"
          [companyId]="companyId"
          [mine]="mine()"
          [canAct]="canAct()"
          [agentPaused]="agentPaused()"
          (details)="detailsOpen.set(true)"
        >
          <app-who-acts-who-pays-line
            [conversation]="c"
            [access]="caller.modelAccess()"
            [principalId]="caller.id()"
          >
            <button
              type="button"
              class="text-foreground hover:bg-accent focus-visible:ring-ring/50 inline-flex min-h-7 shrink-0 items-center gap-1 rounded-lg px-1.5 font-semibold outline-none focus-visible:ring-3 max-sm:hidden"
              data-testid="who-acts-details"
              [attr.aria-controls]="wapOpen() ? 'who-acts-panel' : null"
              [attr.aria-expanded]="wapOpen()"
              (click)="wapOpen.set(!wapOpen())"
            >
              {{ wapOpen() ? 'Hide details' : 'Details'
              }}<span class="sr-only"> of who acts and who pays</span>
              <ng-icon
                name="lucideChevronDown"
                size="14"
                class="transition-transform duration-150 motion-reduce:transition-none"
                aria-hidden="true"
                [class.rotate-180]="wapOpen()"
              />
            </button>
            @if (mine()) {
              <!-- The standing Owner notice, and who read it besides its person (package 6, W7). -->
              <app-conversation-reads-button
                class="ms-auto max-sm:hidden"
                [companyId]="companyId"
                [conversationId]="c.id"
              />
            }
          </app-who-acts-who-pays-line>
        </app-conversation-header>

        <div class="grid min-w-0 gap-5 lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start">
          <div class="flex min-w-0 flex-col gap-3">
            @if (wapOpen()) {
              <app-who-acts-who-pays
                id="who-acts-panel"
                class="max-sm:hidden"
                [conversation]="c"
                [access]="caller.modelAccess()"
                [principalId]="caller.id()"
                [columns]="true"
              />
            }
            <!-- A trigger's conversation: where it lives, and where people talk (W5). -->
            <app-tracker-place-notice [conversation]="c" [openQuestions]="questions()" />

            @if (store.refreshError(); as error) {
              <p
                class="text-warning flex items-start gap-2 text-xs"
                role="status"
                data-testid="refresh-error"
              >
                <ng-icon name="lucideTriangleAlert" class="mt-0.5 shrink-0" aria-hidden="true" />
                Couldn't read the conversation again: {{ refreshMessage(error) }} It shows as it
                was; Tinfirm tries again.
              </p>
            }

            <section
              #thread
              class="bg-card flex min-w-0 flex-col rounded-[20px]"
              aria-label="Conversation"
              data-testid="thread"
            >
              <!-- Where the thread starts: once it is scrolled past, the reader is in the thread. -->
              <div class="h-0" aria-hidden="true" #threadStart></div>
              <div class="flex min-w-0 flex-col gap-5 px-3.5 pt-4 pb-4 sm:px-6 sm:pt-5" #turns>
                <app-message-list
                  [conversation]="c"
                  [companyId]="companyId"
                  [principalId]="caller.id()"
                  [payerMine]="payerMine()"
                  [reader]="reader()"
                  [agentEditable]="agentEditable()"
                  (decided)="store.replaceDecision($event)"
                  (outdated)="store.acted()"
                  (runStatus)="store.refresh()"
                  (runEnded)="store.refresh()"
                  (toCards)="showCards()"
                />
                <app-conversation-status
                  [conversation]="c"
                  [companyId]="companyId"
                  [phase]="phase()"
                  [mine]="mine()"
                  [payerMine]="payerMine()"
                  [canAct]="canAct()"
                  [access]="caller.modelAccess()"
                />
                <app-conversation-cards
                  [conversation]="c"
                  [companyId]="companyId"
                  [mine]="mine()"
                  [signIn]="signIn()"
                  (bindingChanged)="onBinding($event)"
                  (outdated)="store.acted()"
                />
              </div>
              @if (composing() || banner()) {
                <!-- The composer, or the Owner's banner in its place, stays at the bottom of the
                     screen while the thread is on it. -->
                <div
                  class="bg-card before:from-card sticky bottom-0 z-[2] rounded-b-[20px] px-3.5 pt-2 pb-[calc(1rem+env(safe-area-inset-bottom))] before:pointer-events-none before:absolute before:inset-x-0 before:-top-4 before:h-4 before:bg-linear-to-t before:to-transparent sm:px-6"
                  data-testid="composer-dock"
                >
                  @if (composing()) {
                    @if (inThread() && !cardsInView()) {
                      <!-- What waits on the reader, while they read the thread away from its cards. -->
                      <app-pinned-cards
                        class="mb-2"
                        [decisions]="c.decisions"
                        [bindings]="c.bindings"
                        [companyId]="companyId"
                        [conversationId]="c.id"
                        [agentName]="c.agentName"
                        [agentId]="c.agentId"
                        [agentEditable]="agentEditable()"
                        [signInSystems]="signInSystems()"
                        [mine]="pinsMine()"
                        [returnTo]="returnTo()"
                        (decided)="store.replaceDecision($event)"
                        (outdated)="store.acted()"
                        (bindingChanged)="onBinding($event)"
                        [compact]="narrow()"
                        (jump)="jumpTo($event)"
                      />
                    }
                    <app-composer
                      [agentName]="c.agentName"
                      [openQuestions]="questions()"
                      [turnActive]="active()"
                      [external]="composerNote()"
                      [answersOnly]="answersOnly()"
                      [postedWhere]="postedWhere()"
                      (posted)="onPosted()"
                    >
                      @if (canAttach()) {
                        <button
                          composerActions
                          type="button"
                          class="text-muted-foreground hover:bg-accent hover:text-foreground focus-visible:ring-ring/50 inline-flex size-[34px] shrink-0 items-center justify-center rounded-full outline-none focus-visible:ring-3 max-sm:size-11"
                          aria-label="Add a file"
                          title="Add a file"
                          data-testid="composer-attach"
                          (click)="attach()"
                        >
                          <ng-icon name="lucidePaperclip" class="text-base" aria-hidden="true" />
                        </button>
                      }
                    </app-composer>
                  } @else if (viewer()) {
                    <app-viewer-notice place="conversation" />
                  } @else {
                    <app-owner-notice [conversation]="c" />
                  }
                </div>
              }
            </section>
          </div>

          <aside
            class="bg-card hidden min-w-0 rounded-[14px] border px-4 lg:block"
            aria-label="Details"
          >
            <app-conversation-side
              #side
              [conversation]="c"
              [companyId]="companyId"
              [mine]="mine()"
              [mayAcknowledge]="mayAcknowledge()"
              (changed)="store.acted()"
            />
          </aside>
        </div>
      </div>

      <hlm-sheet
        side="right"
        [state]="detailsOpen() ? 'open' : 'closed'"
        (closed)="detailsOpen.set(false)"
      >
        <hlm-sheet-content
          *hlmSheetPortal="let ctx"
          class="w-[min(22rem,92vw)] gap-0 overflow-y-auto p-5"
        >
          <hlm-sheet-header>
            <h2 hlmSheetTitle>Details</h2>
          </hlm-sheet-header>
          <!-- On a phone, what the head leaves out. -->
          <div class="flex flex-col gap-4 border-b pt-2 pb-4 sm:hidden">
            <app-conversation-meta [conversation]="c" [companyId]="companyId" [mine]="mine()" />
            <app-who-acts-who-pays
              [conversation]="c"
              [access]="caller.modelAccess()"
              [principalId]="caller.id()"
              [framed]="false"
            />
            @if (mine()) {
              <app-conversation-reads
                class="bg-muted block rounded-xl px-3 py-2.5"
                [companyId]="companyId"
                [conversationId]="c.id"
                [intro]="ownerReads"
              />
            }
          </div>
          <app-conversation-side
            [conversation]="c"
            [companyId]="companyId"
            [mine]="mine()"
            [mayAcknowledge]="mayAcknowledge()"
            (changed)="store.acted()"
          />
        </hlm-sheet-content>
      </hlm-sheet>
    } @else if (store.error(); as error) {
      <app-error-state
        [error]="error"
        heading="Couldn't open this conversation"
        (retry)="reload()"
      />
    } @else {
      <div class="flex flex-col gap-3" aria-busy="true" data-testid="conversation-loading">
        <span class="sr-only" role="status">Loading the conversation…</span>
        <hlm-skeleton class="h-7 w-1/2" />
        <hlm-skeleton class="h-4 w-2/3" />
        <hlm-skeleton class="h-72 w-full" />
      </div>
    }
  `,
})
export class ConversationPage {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly doc = inject(DOCUMENT);
  private readonly injector = inject(Injector);
  protected readonly store = inject(ConversationStore);
  protected readonly current = inject(CurrentCompany);
  protected readonly caller = inject(CallerPrincipal);

  /** From the route (`/c/:companyId/conversations/:conversationId`). */
  readonly conversationId = input.required<string>();

  private readonly back = signInReturn();
  private readonly threadEl = viewChild<ElementRef<HTMLElement>>('thread');
  private readonly turnsEl = viewChild<ElementRef<HTMLElement>>('turns');
  private readonly threadStartEl = viewChild<ElementRef<HTMLElement>>('threadStart');
  private readonly side = viewChild<ConversationSide>('side');

  /** The details sheet: below `lg` the side column, and on a phone what the head leaves out. */
  protected readonly detailsOpen = signal(false);
  /** Who acts and who pays in full, in place above the thread. */
  protected readonly wapOpen = signal(false);
  protected readonly ownerReads = OWNER_READS_TEXT;

  protected readonly conversation = computed(() => {
    const c = this.store.conversation();
    return c?.id === this.conversationId() ? c : null;
  });

  /**
   * Whether the page knows who reads it: a member's principal comes with their model access
   * (plan §9, gap 2); an instance admin who is not a member has none.
   */
  protected readonly readerKnown = computed(
    () => this.caller.id() !== null || !isMember(this.current.access()),
  );
  /** The reader is the conversation's person: they write, decide and connect accounts here. */
  protected readonly mine = computed(() => {
    const me = this.caller.id();
    return !!me && this.conversation()?.personPrincipalId === me;
  });
  /** The reader pays for it: "your budget". */
  protected readonly payerMine = computed(() => {
    const me = this.caller.id();
    return !!me && this.conversation()?.payer.principalId === me;
  });
  /** Its person and the Owner cancel, retry and end (the API decides again). */
  protected readonly canAct = computed(() => {
    const access = this.current.access();
    return this.mine() || access.role === 'OWNER' || isBreakGlass(access);
  });
  /** Who reads a queue reason: the reader's own budget and hosts for its person, the company's for an admin. */
  protected readonly reader = computed(() => ({
    member: this.mine(),
    admin: managesCompany(this.current.access()),
  }));
  /** OWNER or ADMIN: may store a file that looks like a credential, once acknowledged (ADR-0014). */
  protected readonly mayAcknowledge = computed(() => {
    const role = this.current.access().role;
    return role === 'OWNER' || role === 'ADMIN';
  });
  /** A chat holds it (package 5): the composer says where to write instead. */
  protected readonly chat = computed(() => {
    const c = this.conversation();
    return c ? chatPlaceView(c) : null;
  });
  /**
   * What the composer says instead of taking a message: a chat's or a tracker issue's place, while
   * the reader may post neither a message nor an answer here (`mayPost`, `mayAnswer`).
   */
  protected readonly composerNote = computed(() => {
    const c = this.conversation();
    if (!c || c.mayPost || (c.mayAnswer && this.questions() > 0)) return null;
    return this.chat()?.composer ?? null;
  });
  /** Only answers to the open questions here: people talk on the issue or in the chat. */
  protected readonly answersOnly = computed(() => {
    const c = this.conversation();
    return !!c && !c.mayPost && !!c.mayAnswer && this.questions() > 0;
  });
  /** Where an answer sent here is posted too. */
  protected readonly postedWhere = computed(() => {
    const place = this.conversation()?.place;
    return place === 'TRACKER_ISSUE'
      ? 'on the issue'
      : place === 'CHAT_THREAD'
        ? 'in the chat'
        : null;
  });
  /**
   * Whose cards are pinned: its person's, and those of anyone who may write in it (`mayPost`,
   * `mayAnswer`: a trigger's author or team) or decide one of its open decisions.
   */
  protected readonly pinsMine = computed(() => {
    const c = this.conversation();
    if (!c) return false;
    return (
      this.mine() ||
      !!c.mayPost ||
      !!c.mayAnswer ||
      c.decisions.some((d) => d.status === 'OPEN' && d.mayDecide)
    );
  });
  /**
   * A phone, or a screen too short for a whole card above the composer (720px high, where it would
   * cover half of it): the pinned cards are one line until unfolded.
   */
  protected readonly narrow = mediaQuery('(max-width: 39.99rem), (max-height: 50rem)');
  /** From `lg` the side column stands beside the thread; below it, it is in the sheet. */
  private readonly wide = mediaQuery('(min-width: 64rem)');
  /** Where a sign-in started from a pinned Connect card comes back to: this page. */
  protected readonly returnTo = computed(() => {
    const companyId = this.current.id();
    return companyId ? `/c/${companyId}/conversations/${this.conversationId()}` : null;
  });
  /** Whether one of the reader's open cards in the thread is in view: then nothing is pinned. */
  protected readonly cardsInView = signal(false);
  /**
   * Whether the reader has scrolled into the thread, past its start. Above it, the header says what
   * waits on them, and pinned cards would push the composer below the screen: nothing is pinned.
   */
  protected readonly inThread = signal(false);

  /** A Viewer never writes, answers or decides (ADR-0077): a note in the composer's place. */
  protected readonly viewer = computed(() => isViewer(this.current.access()));

  protected readonly composing = computed(() => {
    const c = this.conversation();
    return !this.viewer() && c?.state === 'OPEN' && (this.mine() || !!c.mayPost || !!c.mayAnswer);
  });
  /** The Owner reading someone else's conversation: the banner where the composer would be. */
  protected readonly banner = computed(() => {
    const c = this.conversation();
    if (c && this.viewer()) return c.state === 'OPEN';
    return !!c && this.readerKnown() && !this.mine() && !c.trigger;
  });
  /** Its person adds files while it is open, from the composer too. */
  protected readonly canAttach = computed(
    () => this.mine() && this.conversation()?.state === 'OPEN',
  );
  protected readonly active = computed(() => {
    const c = this.conversation();
    return !!c && turnActive(c);
  });
  protected readonly questions = computed(() => {
    const c = this.conversation();
    return c ? openQuestions(c) : 0;
  });

  /** The agent: whether it is paused, and whether the reader may approve "for good". */
  private readonly agentKey = computed(
    () => {
      const companyId = this.current.id();
      const c = this.conversation();
      return companyId && c
        ? { companyId, agentId: c.agentId, reason: c.waitingReason ?? '', state: c.state }
        : undefined;
    },
    { equal: sameParams },
  );
  private readonly agent = resource({
    params: () => this.agentKey(),
    loader: ({ params }) =>
      this.api.invoke(getAgent, { companyId: params.companyId, agentId: params.agentId }),
  });
  protected readonly agentPaused = computed(
    () => this.agent.hasValue() && this.agent.value()?.status === 'PAUSED',
  );
  protected readonly agentEditable = computed(
    () => this.agent.hasValue() && !!this.agent.value()?.editableByMe,
  );

  protected readonly phase = computed(() => {
    const c = this.conversation();
    return c ? conversationPhase(c, this.agentPaused()) : 'idle';
  });

  /** The reader's accounts, when one of their bindings waits: whether each system offers a sign-in. */
  private readonly accounts = resource({
    params: () => {
      const companyId = this.current.id();
      const c = this.conversation();
      const waits = !!c && c.bindings.some((b) => b.state === 'WAITING');
      return companyId && this.mine() && waits ? companyId : undefined;
    },
    loader: ({ params: companyId }) => this.api.invoke(listMyAccounts, { companyId }),
  });
  protected readonly signIn = computed<ReadonlyMap<string, boolean>>(() => {
    const items = this.accounts.hasValue() ? (this.accounts.value()?.items ?? []) : [];
    return new Map(items.map((a) => [a.system.id, a.oauthAvailable]));
  });
  protected readonly signInSystems = computed(() =>
    [...this.signIn()].filter(([, available]) => available).map(([systemId]) => systemId),
  );

  /** Keeps the thread's end in view while the reader is there and the turn grows. */
  private readonly follower = new EndFollower(
    this.doc,
    () => this.threadEl()?.nativeElement,
    () => this.turnsEl()?.nativeElement,
  );

  constructor() {
    effect(() => {
      const companyId = this.current.id();
      const conversationId = this.conversationId();
      if (companyId) untracked(() => void this.store.open(companyId, conversationId));
    });

    // Back from a sign-in that a Connect card started: continue its binding, once.
    effect(() => {
      const result = this.back.result();
      const c = this.conversation();
      if (!result || !c) return;
      untracked(() => {
        this.back.dismiss();
        void this.afterSignIn(result, c);
      });
    });

    // A turn that failed on a spent budget, or waits for its reset: the reader's own budget is
    // read again, so that the panel and the failure say what is left and when it resets.
    let budgetSeen = '';
    effect(() => {
      const c = this.conversation();
      if (!c || !this.payerMine()) return;
      const key =
        c.failureClass === 'USAGE_LIMIT' || c.waitingReason === 'BUDGET_SPENT'
          ? `${c.id}|${c.failureClass ?? ''}|${c.waitingReason ?? ''}|${c.turnCount}`
          : '';
      if (key && key !== budgetSeen) untracked(() => this.caller.reload());
      budgetSeen = key;
    });

    // The open cards in the thread ([data-open-card]): while one is in view, nothing is pinned
    // above the composer. Without IntersectionObserver (old browsers, tests) the thread's cards
    // stand alone.
    const view0 = this.doc.defaultView;
    const Observer = view0?.IntersectionObserver;
    if (Observer) {
      const seen = new Set<Element>();
      const observer = new Observer(
        (entries) => {
          for (const entry of entries) {
            if (entry.isIntersecting) seen.add(entry.target);
            else seen.delete(entry.target);
          }
          this.cardsInView.set(seen.size > 0);
        },
        { threshold: 0.25 },
      );
      afterRenderEffect({
        read: () => {
          this.conversation();
          const cards = this.turnsEl()?.nativeElement.querySelectorAll('[data-open-card]') ?? [];
          const now = new Set<Element>(cards);
          for (const element of seen) if (!now.has(element)) seen.delete(element);
          observer.disconnect();
          for (const element of now) observer.observe(element);
          this.cardsInView.set(seen.size > 0);
        },
      });
      // The thread's start: above the screen once the reader has scrolled past it.
      const start = new Observer((entries) => {
        const entry = entries.at(-1);
        if (entry) this.inThread.set(!entry.isIntersecting && entry.boundingClientRect.top < 0);
      });
      let watched: Element | undefined;
      afterRenderEffect({
        read: () => {
          const element = this.threadStartEl()?.nativeElement;
          if (element === watched) return;
          start.disconnect();
          watched = element;
          if (element) start.observe(element);
          else this.inThread.set(false);
        },
      });
      inject(DestroyRef).onDestroy(() => {
        observer.disconnect();
        start.disconnect();
      });
    } else {
      this.cardsInView.set(true);
    }

    // While the reader is at the thread's end, what the turn adds keeps it in view.
    afterRenderEffect({
      write: () => {
        this.conversation();
        untracked(() => this.follower.follow());
      },
    });
    afterEveryRender({ read: () => this.follower.recheck() });
    const view = this.doc.defaultView;
    const onScroll = () => this.follower.measure();
    view?.addEventListener('scroll', onScroll, { passive: true });
    inject(DestroyRef).onDestroy(() => view?.removeEventListener('scroll', onScroll));
  }

  protected reload(): void {
    void this.store.refresh();
  }

  protected refreshMessage(error: unknown): string {
    return problemMessage(error);
  }

  /** The reader wrote at the thread's end: the page follows their message and the turn. */
  protected onPosted(): void {
    this.follower.start();
  }

  /**
   * The composer's attach button: the file picker of the side column's files beside the thread, or
   * below `lg` the details sheet, whose files open the picker once drawn (where the browser still
   * counts the tap; else the sheet's "Add a file" is there).
   */
  protected attach(): void {
    if (this.wide() && this.side()?.pickFile()) return;
    this.detailsOpen.set(true);
    let frames = 0;
    const tryPick = () => {
      const picker = this.doc.querySelector<HTMLInputElement>(
        '[data-slot=sheet-content] [data-testid=file-input]',
      );
      if (picker) picker.click();
      else if (++frames <= 10) requestAnimationFrame(tryPick);
    };
    afterNextRender({ read: tryPick }, { injector: this.injector });
  }

  protected onBinding(binding: ConversationBinding): void {
    this.store.replaceBinding(binding);
    if (binding.state === 'BOUND') {
      this.notify.success(binding.text, 'The conversation goes on with it.');
    }
  }

  /** "Where it was raised" of a pinned card: its card in the thread, in view and focused. */
  protected jumpTo(jump: PinnedJump): void {
    const thread = this.turnsEl()?.nativeElement;
    let card: HTMLElement | null | undefined;
    if (jump.kind === 'decision') {
      card = thread?.querySelector<HTMLElement>(`[data-decision-id="${CSS.escape(jump.id)}"]`);
    } else {
      const binding = this.conversation()?.bindings.find((b) => b.id === jump.id);
      card = binding
        ? thread?.querySelector<HTMLElement>(`[id="binding-${CSS.escape(binding.systemId)}"]`)
        : null;
    }
    if (!card) return;
    card.scrollIntoView?.({
      behavior: prefersReducedMotion(this.doc) ? 'auto' : 'smooth',
      block: 'center',
    });
    const target =
      card.querySelector<HTMLElement>('[tabindex], button, a, input, textarea') ?? card;
    target.focus({ preventScroll: true });
  }

  /** "Connect the account" of a queued turn: the Connect cards, in view and focused. */
  protected showCards(): void {
    const cards = this.doc.getElementById('bindings');
    cards?.scrollIntoView?.({
      behavior: prefersReducedMotion(this.doc) ? 'auto' : 'smooth',
      block: 'center',
    });
    cards?.focus({ preventScroll: true });
  }

  private async afterSignIn(result: SignInResult, c: Conversation): Promise<void> {
    if (!result.connected) {
      this.notify.warning('Nothing was connected', result.message);
      return;
    }
    try {
      const binding = await continueBindingAfterSignIn(
        this.api,
        { companyId: this.current.id() ?? '', conversationId: c.id },
        result,
        c.bindings,
      );
      if (binding) this.onBinding(binding);
      else this.notify.success(result.message);
      void this.store.refresh();
    } catch (error) {
      this.notify.error('Your account is connected, but the conversation could not go on', error);
    }
  }
}
