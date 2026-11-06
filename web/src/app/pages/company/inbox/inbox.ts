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
  linkedSignal,
  resource,
  signal,
  untracked,
} from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideBot,
  lucideCheck,
  lucideCircleAlert,
  lucideEyeOff,
  lucideInbox,
  lucideMessagesSquare,
  lucideX,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { Api } from '../../../api/generated/api';
import {
  continueConversationBinding,
  dismissInboxNotice,
  getAgent,
  getInbox,
  listMyAccounts,
  restoreInboxNotice,
} from '../../../api/generated/functions';
import type { Decision, InboxItem, MyAccount } from '../../../api/generated/models';
import { problemMessage } from '../../../api/problem';
import { CurrentCompany } from '../../../core/current-company';
import { InboxCounts } from '../../../core/inbox-counts';
import { isBreakGlass, isViewer, managesCompany } from '../../../core/permissions';
import { pollWhileVisible } from '../../../core/polling';
import { ErrorState } from '../../../shared/error-state';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { PageHeader } from '../../../shared/page-header';
import { ViewerNotice } from '../../../shared/viewer-notice';
import { signInReturn } from '../../../shared/sign-in-result';
import { InboxTriggerItem } from '../triggers/inbox-trigger-item';
import { InboxNeed } from './inbox-need';
import { InboxNotice, NOTICE_LINK } from './inbox-notice';
import { InboxRow } from './inbox-row';
import {
  INBOX_GROUPS,
  INBOX_KIND_GROUPS,
  type InboxDoneView,
  type InboxGroup,
  type InboxReader,
  inboxBindingCard,
  inboxGroupOf,
  inboxItemKey,
  inboxRowView,
  unlisted,
} from './inbox-words';

/** How often the inbox is read again while the page is visible. */
const POLL_MS = 15_000;

/** An item of a group as the page lists it: open, or collapsed once the reader settled it here. */
interface InboxEntry {
  readonly key: string;
  readonly item: InboxItem;
  readonly done: InboxDoneView | null;
  /** A notice the reader dismissed here: its line with Undo, in its place. */
  readonly dismissed: DismissedNotice | null;
}

/**
 * A notice the reader dismissed on this visit. `dismissing`: sent; `dismissed`: the server hides
 * it for them; `restoring`: Undo is sent, and its row is back already.
 */
interface DismissedNotice {
  readonly item: InboxItem;
  /** What it said, for its line and Undo's name. */
  readonly title: string;
  readonly state: 'dismissing' | 'dismissed' | 'restoring';
  /** Why Undo failed. */
  readonly error: string | null;
}

/** One pill of the row above the groups: all of them, or one group alone. */
interface InboxPill {
  readonly group: InboxGroup | null;
  readonly label: string;
  readonly count: number | null;
  readonly human: boolean;
}

const COUNT =
  'inline-flex h-5 min-w-5 items-center justify-center rounded-full px-1.5 font-mono text-[0.6875rem] font-medium tabular-nums';
const COUNT_NEUTRAL = `${COUNT} bg-secondary text-foreground`;
const COUNT_HUMAN = `${COUNT} bg-human-soft text-human`;

/**
 * The inbox, `/c/:companyId/inbox`, every member's home (plan §5): what needs the reader now,
 * longest waiting first, then the turns that stopped, then notices. A row of pills shows all of
 * them or one group alone, with their counts (kept in `?show=`). What needs the reader is one
 * compact orange row each (`InboxNeed`), answered in the row or opened in place as the whole card,
 * one at a time: questions and approvals, accounts to connect, and for admins a service account's
 * account to sign in again or the audit log's failed check. The stopped turns are neutral rows
 * (`InboxRow`: Retry, Continue, why a turn waits and until when); the notices quiet lines
 * (`InboxNotice`, `InboxTriggerItem`). A row the reader settles here collapses to a muted line of
 * what was done, in its place, while the inbox and the sidebar's badge are read again. A notice
 * that only informs is dismissed for the reader alone: it collapses at once to a line with Undo,
 * and the server hides it until its condition changes. Back from a sign-in a Connect card
 * started, the waiting bindings of that system are continued.
 */
@Component({
  selector: 'app-inbox',
  imports: [
    RouterLink,
    NgIcon,
    HlmButton,
    HlmEmptyImports,
    ErrorState,
    InboxNeed,
    InboxNotice,
    InboxRow,
    InboxTriggerItem,
    ListSkeleton,
    PageHeader,
    ViewerNotice,
  ],
  providers: [
    provideIcons({
      lucideBot,
      lucideCheck,
      lucideCircleAlert,
      lucideEyeOff,
      lucideInbox,
      lucideMessagesSquare,
      lucideX,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex max-w-[66rem] flex-col gap-6' },
  template: `
    @let companyId = current.id() ?? '';
    <div class="flex flex-col gap-4">
      <app-page-header
        title="Inbox"
        description="What needs you comes first: questions, approvals and accounts to connect. Then the turns that stopped, and notices."
      />
      @if (viewer()) {
        <app-viewer-notice place="inbox" />
      }
      @if (!empty() && inbox.hasValue()) {
        <div
          class="-mx-4 flex gap-1 overflow-x-auto px-4 pt-0.5 pb-1 [scrollbar-width:none] md:mx-0 md:px-0"
          role="group"
          aria-label="Show"
          data-testid="inbox-filters"
        >
          @for (pill of pills(); track pill.label) {
            <button
              type="button"
              class="text-muted-foreground hover:bg-sidebar-accent hover:text-foreground focus-visible:ring-ring/50 aria-pressed:border-border aria-pressed:bg-card aria-pressed:text-foreground inline-flex h-9 flex-none items-center gap-2 rounded-full border border-transparent px-3.5 text-sm font-semibold outline-none focus-visible:ring-3 aria-pressed:shadow-[0_1px_3px_rgb(0_0_0/0.08)] max-md:h-11"
              [attr.aria-pressed]="filter() === pill.group"
              [attr.data-filter]="pill.group ?? 'all'"
              (click)="pick(pill.group)"
            >
              {{ pill.label }}
              @if (pill.count !== null) {
                <span [class]="pill.human ? countHuman : countNeutral">{{ pill.count }}</span>
              }
            </button>
          }
        </div>
      }
    </div>

    @if (returned(); as r) {
      <div
        class="bg-card flex items-center gap-2.5 rounded-[14px] border py-2 ps-4 pe-2 text-sm"
        role="status"
        data-testid="inbox-sign-in-result"
        [class.border-destructive]="!r.connected"
      >
        <ng-icon
          class="shrink-0"
          aria-hidden="true"
          [name]="r.connected ? 'lucideCheck' : 'lucideCircleAlert'"
          [class]="r.connected ? 'text-success' : 'text-destructive'"
        />
        <p class="min-w-0 flex-1">{{ r.message }}</p>
        <button
          hlmBtn
          variant="ghost"
          size="icon-sm"
          type="button"
          class="text-muted-foreground max-md:size-11"
          aria-label="Dismiss"
          (click)="signIn.dismiss()"
        >
          <ng-icon name="lucideX" aria-hidden="true" />
        </button>
      </div>
    }

    @if (inbox.isLoading() && !inbox.hasValue()) {
      <app-list-skeleton [rows]="3" />
    } @else if (inbox.error() && !inbox.hasValue()) {
      <app-error-state
        [error]="inbox.error()"
        heading="Couldn't load the inbox"
        (retry)="inbox.reload()"
      />
    } @else if (empty()) {
      <div hlmEmpty class="border border-dashed" data-testid="inbox-empty">
        <div hlmEmptyHeader>
          <div hlmEmptyMedia variant="icon">
            <ng-icon name="lucideInbox" aria-hidden="true" />
          </div>
          <h2 hlmEmptyTitle>Nothing needs you</h2>
          <p hlmEmptyDescription>
            Your agents are working or done. When one asks you something, wants your approval or
            needs an account of yours, it shows up here, and so do the turns that stop.
          </p>
        </div>
        <div hlmEmptyContent>
          <div class="flex flex-wrap justify-center gap-2">
            <a
              hlmBtn
              variant="outline"
              class="max-md:h-11"
              data-testid="inbox-empty-conversations"
              [routerLink]="['/c', companyId, 'conversations']"
            >
              <ng-icon
                name="lucideMessagesSquare"
                class="text-muted-foreground"
                aria-hidden="true"
              />
              Your conversations
            </a>
            <a
              hlmBtn
              variant="outline"
              class="max-md:h-11"
              data-testid="inbox-empty-agents"
              [routerLink]="['/c', companyId, 'agents']"
            >
              <ng-icon name="lucideBot" class="text-muted-foreground" aria-hidden="true" />
              Agents
            </a>
          </div>
        </div>
      </div>
    } @else if (inbox.hasValue()) {
      @for (group of sections(); track group.key) {
        <section
          class="flex scroll-mt-20 flex-col gap-2.5"
          [id]="'inbox-group-' + group.key"
          [attr.aria-labelledby]="'inbox-' + group.key"
          [attr.data-testid]="'inbox-' + group.key"
        >
          <div class="flex flex-wrap items-center gap-x-2.5 gap-y-1">
            <h2 class="text-base font-bold" [id]="'inbox-' + group.key">{{ group.title }}</h2>
            <span
              [class]="group.key === 'needs' && counts()[group.key] > 0 ? countHuman : countNeutral"
              data-testid="inbox-group-count"
              >{{ counts()[group.key] }}</span
            >
            <p class="text-muted-foreground ms-auto hidden text-[0.8125rem] md:block">
              {{ group.hint }}
            </p>
          </div>
          <ul
            class="bg-card divide-y overflow-hidden rounded-[14px] border"
            [attr.aria-labelledby]="'inbox-' + group.key"
          >
            @for (entry of entries()[group.key]; track entry.key) {
              <li [attr.data-entry]="entry.key">
                @if (entry.done; as done) {
                  <div
                    class="text-muted-foreground bg-muted/70 focus-visible:ring-ring/50 flex min-h-11 flex-wrap items-center gap-x-2 gap-y-0.5 px-4 py-2 text-[0.8125rem] outline-none focus-visible:ring-3 focus-visible:ring-inset md:grid md:grid-cols-[8rem_minmax(0,1fr)_auto] md:gap-x-3.5"
                    role="status"
                    tabindex="-1"
                    data-testid="inbox-done"
                    [attr.data-kind]="entry.item.kind"
                  >
                    <span class="inline-flex items-center gap-1.5 font-semibold"
                      ><ng-icon
                        name="lucideCheck"
                        size="14"
                        class="text-success"
                        aria-hidden="true"
                      />{{ done.label }}</span
                    >{{ ' ' }}
                    <span class="min-w-0 break-words">
                      @if (done.detail) {
                        <b class="text-foreground font-semibold">{{ done.detail }}</b> ·
                      }
                      {{ done.after }}
                    </span>
                    @if (entry.item.conversation; as c) {
                      <a
                        class="hover:text-foreground focus-visible:ring-ring/50 rounded-sm font-mono text-xs underline-offset-2 outline-none hover:underline focus-visible:ring-3"
                        [routerLink]="['/c', companyId, 'conversations', c.id]"
                        >{{ c.key }}</a
                      >
                    }
                  </div>
                } @else if (entry.dismissed; as d) {
                  <div
                    class="text-muted-foreground bg-muted/70 flex min-h-11 flex-wrap items-center gap-x-2 gap-y-0.5 px-4 py-1.5 text-[0.8125rem] md:grid md:grid-cols-[8rem_minmax(0,1fr)_auto] md:gap-x-3.5"
                    role="status"
                    data-testid="inbox-dismissed"
                    [attr.data-kind]="entry.item.kind"
                  >
                    <span class="inline-flex items-center gap-1.5 font-semibold"
                      ><ng-icon name="lucideEyeOff" size="14" aria-hidden="true" />Dismissed</span
                    >{{ ' ' }}
                    <span class="min-w-0 break-words"
                      ><b class="text-foreground font-semibold">{{ d.title }}</b> · It comes back if
                      it changes.</span
                    >
                    <button
                      type="button"
                      data-testid="inbox-dismissed-undo"
                      [class]="noticeLink"
                      [attr.aria-label]="'Undo dismissing ' + d.title"
                      [attr.aria-disabled]="d.state === 'dismissing' || null"
                      (click)="restore(entry)"
                    >
                      Undo
                    </button>
                    @if (d.error; as message) {
                      <p
                        class="text-destructive basis-full text-xs md:col-span-2 md:col-start-2"
                        role="alert"
                        data-testid="inbox-dismissed-error"
                      >
                        {{ message }}
                      </p>
                    }
                  </div>
                } @else {
                  @switch (group.key) {
                    @case ('needs') {
                      <app-inbox-need
                        [item]="entry.item"
                        [companyId]="companyId"
                        [reader]="reader()"
                        [open]="openKey() === entry.key"
                        [agentEditable]="agentEditable(entry.item.decision)"
                        [binding]="bindingOf(entry.item)"
                        [signInAvailable]="signInAvailable(entry.item)"
                        (toggled)="openKey.set($event ? entry.key : null)"
                        (done)="settle(entry, $event)"
                        (changed)="refresh()"
                      />
                    }
                    @case ('turns') {
                      <app-inbox-row
                        [item]="entry.item"
                        [companyId]="companyId"
                        [reader]="reader()"
                        (done)="settle(entry, $event)"
                      />
                    }
                    @default {
                      @if (entry.item.kind === 'TRIGGER' && entry.item.trigger) {
                        <app-inbox-trigger-item
                          [item]="entry.item"
                          [companyId]="companyId"
                          [dismissError]="dismissErrors().get(entry.key) ?? null"
                          (changed)="refresh()"
                          (dismiss)="dismiss(entry)"
                        />
                      } @else {
                        <app-inbox-notice
                          [item]="entry.item"
                          [companyId]="companyId"
                          [reader]="reader()"
                          [dismissError]="dismissErrors().get(entry.key) ?? null"
                          (changed)="refresh()"
                          (dismiss)="dismiss(entry)"
                        />
                      }
                    }
                  }
                }
              </li>
            } @empty {
              <li class="text-muted-foreground px-4 py-3.5 text-[0.8125rem]">
                {{ group.empty }}
              </li>
            }
          </ul>
        </section>
      }
      @if (truncated()) {
        <p class="text-muted-foreground text-xs" data-testid="inbox-truncated">
          The inbox shows at most 100 of each kind, longest waiting first; the rest follow.
        </p>
      }
    }
  `,
})
export class Inbox {
  private readonly api = inject(Api);
  private readonly badge = inject(InboxCounts);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  protected readonly current = inject(CurrentCompany);
  protected readonly signIn = signInReturn();

  /** `?show=`: the group the pills show alone (`needs`, `stopped`, `notices`); all without it. */
  readonly show = input<string | undefined>(undefined);

  protected readonly countNeutral = COUNT_NEUTRAL;
  protected readonly countHuman = COUNT_HUMAN;
  protected readonly noticeLink = NOTICE_LINK;

  protected readonly inbox = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(getInbox, { companyId }),
  });

  private readonly items = computed<readonly InboxItem[]>(() =>
    this.inbox.hasValue() ? this.inbox.value().items : [],
  );

  /** The group shown alone, from the URL and then the pills. */
  protected readonly filter = linkedSignal(() => inboxGroupOf(this.show()));
  /** The key of the item open in place; one at a time. */
  protected readonly openKey = signal<string | null>(null);
  /** What the reader settled here, by key: its row stays collapsed in its place. */
  private readonly done = signal<ReadonlyMap<string, { item: InboxItem; view: InboxDoneView }>>(
    new Map(),
  );
  /** The notices the reader dismissed here, by key: each keeps its line, with Undo, in its place. */
  private readonly dismissed = signal<ReadonlyMap<string, DismissedNotice>>(new Map());
  /** Why a notice could not be dismissed, by key: said in its row, which came back. */
  protected readonly dismissErrors = signal<ReadonlyMap<string, string>>(new Map());

  /** The reader's accounts, for the Connect cards: the token a system takes, sign-in, grants. */
  private readonly accounts = resource({
    params: () => {
      const companyId = this.current.id();
      return companyId && this.items().some((i) => i.kind === 'BINDING') ? companyId : undefined;
    },
    loader: ({ params: companyId }) => this.api.invoke(listMyAccounts, { companyId }),
  });

  /**
   * Whether the reader may edit each agent that asks for a repository or a host, for "for good":
   * the server's `editableByMe`, never derived.
   */
  private readonly editable = resource({
    params: () => {
      const companyId = this.current.id();
      const agentIds = [
        ...new Set(
          this.items()
            .map((i) => i.decision)
            .filter(
              (d): d is Decision =>
                !!d?.agentId && (d.subject?.type === 'REPOSITORY' || d.subject?.type === 'HOST'),
            )
            .map((d) => d.agentId!),
        ),
      ].sort();
      return companyId && agentIds.length > 0 ? { companyId, agentIds } : undefined;
    },
    loader: async ({ params }) => {
      const answers = await Promise.all(
        params.agentIds.map((agentId) =>
          this.api
            .invoke(getAgent, { companyId: params.companyId, agentId })
            .then((agent) => [agentId, agent.editableByMe] as const)
            .catch(() => [agentId, false] as const),
        ),
      );
      return new Map(answers);
    },
  });

  /** A Viewer sees their inbox but answers and decides nothing (ADR-0077). */
  protected readonly viewer = computed(() => isViewer(this.current.access()));

  protected readonly reader = computed<InboxReader>(() => {
    const access = this.current.access();
    return {
      admin: managesCompany(access),
      owner: isBreakGlass(access) || access.role === 'OWNER',
    };
  });

  /**
   * Each group's entries in the inbox's order, longest waiting first, one per key. An item the
   * reader settled or dismissed here keeps its place as its line once the inbox no longer lists
   * it; a dismissed notice whose Undo is under way shows as its row again.
   */
  protected readonly entries = computed(() => {
    const done = this.done();
    const dismissed = this.dismissed();
    const groups: Record<InboxGroup, InboxEntry[]> = { needs: [], turns: [], notices: [] };
    const listed = new Set<string>();
    const line = (d: DismissedNotice | undefined) => (d && d.state !== 'restoring' ? d : null);
    for (const item of this.items()) {
      const key = inboxItemKey(item);
      if (listed.has(key)) continue;
      listed.add(key);
      groups[INBOX_KIND_GROUPS[item.kind] ?? 'notices'].push({
        key,
        item,
        done: done.get(key)?.view ?? null,
        dismissed: line(dismissed.get(key)),
      });
    }
    const keep = (entry: InboxEntry) => {
      listed.add(entry.key);
      const list = groups[INBOX_KIND_GROUPS[entry.item.kind] ?? 'notices'];
      const at = list.findIndex((e) => e.item.since > entry.item.since);
      list.splice(at < 0 ? list.length : at, 0, entry);
    };
    for (const [key, { item, view }] of done) {
      if (!listed.has(key)) keep({ key, item, done: view, dismissed: null });
    }
    for (const [key, d] of dismissed) {
      if (!listed.has(key)) keep({ key, item: d.item, done: null, dismissed: line(d) });
    }
    return groups;
  });

  /** What waits in each group: its open entries, and those the inbox holds beyond its list. */
  protected readonly counts = computed<Record<InboxGroup, number>>(() => {
    const entries = this.entries();
    const counts = this.inbox.hasValue() ? this.inbox.value().counts : null;
    const count = (group: InboxGroup) =>
      entries[group].filter((e) => !e.done && !e.dismissed).length +
      (counts ? unlisted(group, this.items(), counts) : 0);
    return { needs: count('needs'), turns: count('turns'), notices: count('notices') };
  });

  protected readonly pills = computed<InboxPill[]>(() => {
    const counts = this.counts();
    return [
      { group: null, label: 'All', count: null, human: false },
      ...INBOX_GROUPS.map((g) => ({
        group: g.key,
        label: g.title,
        count: counts[g.key],
        human: g.key === 'needs' && counts[g.key] > 0,
      })),
    ];
  });

  /** The groups shown: the one a pill picked, even empty; else each that holds anything. */
  protected readonly sections = computed(() => {
    const filter = this.filter();
    const entries = this.entries();
    return INBOX_GROUPS.filter((g) => (filter ? g.key === filter : entries[g.key].length > 0));
  });

  /** Nothing waits, and nothing was settled or dismissed here on this visit. */
  protected readonly empty = computed(
    () =>
      this.inbox.hasValue() &&
      this.items().length === 0 &&
      this.done().size === 0 &&
      this.dismissed().size === 0,
  );

  /** Whether a kind holds more than the inbox lists (at most 100 of each). */
  protected readonly truncated = computed(() => {
    if (!this.inbox.hasValue()) return false;
    const counts = this.inbox.value().counts;
    return INBOX_GROUPS.some((g) => unlisted(g.key, this.items(), counts) > 0);
  });

  /** What a sign-in started here came back with, once said. */
  protected readonly returned = signal<{ connected: boolean; message: string } | null>(null);

  constructor() {
    pollWhileVisible(() => {
      if (this.inbox.hasValue() && !this.inbox.isLoading()) this.inbox.reload();
    }, POLL_MS);

    // Back from a sign-in a Connect card started: continue the waiting bindings of that system.
    effect(() => {
      const result = this.signIn.result();
      const companyId = this.current.id();
      if (!companyId) return;
      untracked(() => {
        if (!result) {
          this.returned.set(null);
          return;
        }
        if (!result.connected || !result.systemId) {
          this.returned.set(result);
          return;
        }
        void this.continueAfterSignIn(companyId, result.systemId, result.message);
      });
    });
  }

  /** Shows one group alone, or all of them, and brings the group into view; kept in the URL. */
  protected pick(group: InboxGroup | null): void {
    this.filter.set(group);
    const param = INBOX_GROUPS.find((g) => g.key === group)?.param ?? null;
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { show: param },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
    if (!group) return;
    afterNextRender(
      () =>
        this.host.nativeElement
          .querySelector(`#inbox-group-${group}`)
          ?.scrollIntoView({ block: 'nearest' }),
      { injector: this.injector },
    );
  }

  protected bindingOf(item: InboxItem) {
    return item.kind === 'BINDING' && item.binding && item.conversation
      ? inboxBindingCard(item.binding, this.accountOf(item.binding.systemId))
      : null;
  }

  protected signInAvailable(item: InboxItem): boolean {
    const binding = item.binding;
    if (!binding) return false;
    return this.accountOf(binding.systemId)?.oauthAvailable ?? binding.oauthAvailable ?? false;
  }

  protected agentEditable(decision: Decision | null | undefined): boolean {
    if (!decision?.agentId || !this.editable.hasValue()) return false;
    return this.editable.value()?.get(decision.agentId) ?? false;
  }

  /**
   * The reader settled an item here: its row collapses to what was done, in its place, and the
   * inbox and the badge are read again. The focus, when it was in the row, goes to that line.
   */
  protected settle(entry: InboxEntry, view: InboxDoneView): void {
    const focused = this.hasFocus(entry.key);
    this.done.update((done) => new Map(done).set(entry.key, { item: entry.item, view }));
    if (this.openKey() === entry.key) this.openKey.set(null);
    this.refresh();
    if (focused) this.focusIn(entry.key, '[data-testid=inbox-done]');
  }

  protected refresh(): void {
    this.inbox.reload();
    this.badge.refresh();
  }

  /**
   * The reader dismissed a notice that only informs them: it collapses at once to a line with
   * Undo, in its place, while the server hides it for them alone (`PUT
   * /inbox/dismissals/{noticeKey}`) until its condition changes. Refused, its row is back, saying
   * why. The focus, when it was in the row, goes to Undo.
   */
  protected async dismiss(entry: InboxEntry): Promise<void> {
    const companyId = this.current.id();
    const noticeKey = entry.item.noticeKey;
    if (!companyId || !noticeKey || this.dismissed().has(entry.key)) return;
    const focused = this.hasFocus(entry.key);
    const title = inboxRowView(entry.item, this.reader()).title;
    this.dismissErrors.update((errors) => without(errors, entry.key));
    this.dismissed.update((dismissed) =>
      new Map(dismissed).set(entry.key, {
        item: entry.item,
        title,
        state: 'dismissing',
        error: null,
      }),
    );
    if (focused) this.focusIn(entry.key, '[data-testid=inbox-dismissed-undo]');
    try {
      await this.api.invoke(dismissInboxNotice, { companyId, noticeKey });
    } catch (error) {
      this.dismissed.update((dismissed) => without(dismissed, entry.key));
      this.dismissErrors.update((errors) =>
        new Map(errors).set(entry.key, `Couldn't dismiss it: ${problemMessage(error)}`),
      );
      if (focused) this.focusIn(entry.key, '[data-testid=inbox-notice-dismiss]');
      return;
    }
    this.changeDismissed(entry.key, { state: 'dismissed' });
    this.inbox.reload();
  }

  /**
   * Undo: the notice's row is back at once, in its place, while the server shows it again
   * (`DELETE /inbox/dismissals/{noticeKey}`) and the inbox is read again. Refused, the line stays,
   * saying why.
   */
  protected async restore(entry: InboxEntry): Promise<void> {
    const companyId = this.current.id();
    const notice = this.dismissed().get(entry.key);
    const noticeKey = notice?.item.noticeKey;
    if (!companyId || !notice || !noticeKey || notice.state !== 'dismissed') return;
    const focused = this.hasFocus(entry.key);
    this.changeDismissed(entry.key, { state: 'restoring', error: null });
    if (focused) this.focusIn(entry.key, '[data-testid=inbox-notice-dismiss]');
    try {
      await this.api.invoke(restoreInboxNotice, { companyId, noticeKey });
    } catch (error) {
      this.changeDismissed(entry.key, {
        state: 'dismissed',
        error: `Couldn't undo: ${problemMessage(error)}`,
      });
      if (focused) this.focusIn(entry.key, '[data-testid=inbox-dismissed-undo]');
      return;
    }
    // The inbox lists it again before its row stops being kept here, so that it never blinks.
    try {
      this.inbox.set(await this.api.invoke(getInbox, { companyId }));
    } catch {
      this.inbox.reload();
    }
    this.dismissed.update((dismissed) => without(dismissed, entry.key));
  }

  private changeDismissed(key: string, change: Partial<DismissedNotice>): void {
    this.dismissed.update((dismissed) => {
      const notice = dismissed.get(key);
      return notice ? new Map(dismissed).set(key, { ...notice, ...change }) : dismissed;
    });
  }

  /** Whether the focus is in the entry's row, or nowhere: then it follows the row's change. */
  private hasFocus(key: string): boolean {
    const active = document.activeElement;
    return !active || active === document.body || !!this.entryElement(key)?.contains(active);
  }

  /** Focuses the element of the entry's row once it is drawn. */
  private focusIn(key: string, selector: string): void {
    afterNextRender(() => this.entryElement(key)?.querySelector<HTMLElement>(selector)?.focus(), {
      injector: this.injector,
    });
  }

  private entryElement(key: string): HTMLElement | undefined {
    return Array.from(this.host.nativeElement.querySelectorAll<HTMLElement>('li[data-entry]')).find(
      (li) => li.dataset['entry'] === key,
    );
  }

  private accountOf(systemId: string): MyAccount | undefined {
    return this.accounts.hasValue()
      ? this.accounts.value()?.items.find((a) => a.system.id === systemId)
      : undefined;
  }

  private async continueAfterSignIn(
    companyId: string,
    systemId: string,
    message: string,
  ): Promise<void> {
    try {
      const inbox = this.inbox.hasValue()
        ? this.inbox.value()
        : await this.api.invoke(getInbox, { companyId });
      const waiting = inbox.items.filter(
        (i) => i.kind === 'BINDING' && i.binding?.systemId === systemId && i.conversation,
      );
      for (const item of waiting) {
        await this.api.invoke(continueConversationBinding, {
          companyId,
          conversationId: item.conversation!.id,
          systemId,
        });
      }
      this.returned.set({
        connected: true,
        message:
          waiting.length > 0
            ? `${message} ${waiting.length === 1 ? 'Its conversation goes on.' : `${waiting.length} conversations go on.`}`
            : message,
      });
    } catch (error) {
      this.returned.set({ connected: false, message: problemMessage(error) });
    }
    this.refresh();
  }
}

/** The map without the key. */
function without<V>(map: ReadonlyMap<string, V>, key: string): ReadonlyMap<string, V> {
  if (!map.has(key)) return map;
  const copy = new Map(map);
  copy.delete(key);
  return copy;
}
