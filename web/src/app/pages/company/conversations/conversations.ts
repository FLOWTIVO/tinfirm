import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  resource,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideEye,
  lucideMessageSquare,
  lucideMessagesSquare,
  lucidePlus,
  lucideSearchX,
  lucideTag,
  lucideX,
  lucideZap,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  listAgents,
  listConversations,
  listSubjects,
  listMembers,
  listTriggers,
} from '../../../api/generated/functions';
import type { ConversationSummary, Subject } from '../../../api/generated/models';
import { CallerPrincipal } from '../../../core/caller-principal';
import { CurrentCompany } from '../../../core/current-company';
import { pagedResource } from '../../../core/paged-resource';
import { isViewer, mayWork } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import { initials } from '../../../shared/forms';
import { plural } from '../../../shared/labels';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { PageHeader } from '../../../shared/page-header';
import { ViewerNotice } from '../../../shared/viewer-notice';
import { formatCost, formatTokens } from '../../../shared/run-status';
import { Segmented, type SegmentedOption } from '../../../shared/segmented';
import { Time } from '../../../shared/time';
import { WaitingOnBadge } from '../../../shared/waiting-on';
import {
  type ConversationFilters,
  conversationTitle,
  endedLabel,
  filterCount,
  filtersOf,
  SHOW_FILTERS,
  showOf,
} from './conversation-labels';
import { tokenBreakdown, tokenTotal } from './conversation-view';

const PAGE_SIZE = 50;

const WHOSE: readonly SegmentedOption[] = [
  { value: 'mine', label: 'Mine' },
  { value: 'everyone', label: 'Everyone’s' },
];

const VIEWS: readonly SegmentedOption[] = [
  { value: 'list', label: 'List' },
  { value: 'subjects', label: 'By subject' },
];

/** The query parameters this page keeps its state in. */
const FILTER_PARAMS = [
  'agentId',
  'state',
  'waitingOn',
  'subjectSystemId',
  'subjectKey',
  'triggerId',
  'personId',
] as const;

interface ListParams extends ConversationFilters {
  readonly companyId: string;
}

/**
 * The conversations (plan §5): the reader's own, what waits on them first, then the newest
 * activity; filtered by a row of pills (all, open, whom they wait on, ended) and by agent, subject
 * and trigger, all kept in the URL; or "By subject", the issues they are about with their counts
 * (`listSubjects`). The Owner sees their own by default and may switch to "Everyone's" (§10,
 * question 5), which says under a banner that each read is recorded, names each conversation's
 * person and filters by one. A trigger's audience sees its conversations here
 * too (package 4). With nothing yet, the page offers the agents the reader may talk to.
 */
@Component({
  selector: 'app-conversations',
  imports: [
    NgTemplateOutlet,
    RouterLink,
    NgIcon,
    HlmButton,
    HlmEmptyImports,
    HlmNativeSelectImports,
    HlmSpinner,
    ErrorState,
    ListSkeleton,
    PageHeader,
    ViewerNotice,
    Segmented,
    Time,
    WaitingOnBadge,
  ],
  providers: [
    provideIcons({
      lucideEye,
      lucideMessageSquare,
      lucideMessagesSquare,
      lucidePlus,
      lucideSearchX,
      lucideTag,
      lucideX,
      lucideZap,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let companyId = current.id();
    <div class="flex flex-col gap-5">
      <app-page-header title="Conversations" [description]="description()">
        @if (member()) {
          <a
            hlmBtn
            data-testid="new-conversation"
            [routerLink]="['/c', companyId, 'conversations', 'new']"
          >
            <ng-icon name="lucidePlus" aria-hidden="true" />
            New conversation
          </a>
        }
      </app-page-header>

      @if (viewer()) {
        <app-viewer-notice place="conversations" />
      }

      <div class="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center">
        @if (owner()) {
          <app-segmented
            inputId="conversations-whose"
            label="Whose conversations"
            data-testid="whose"
            [options]="whoseOptions"
            [value]="everyone() ? 'everyone' : 'mine'"
            (valueChange)="setWhose($event)"
          />
        }
        <app-segmented
          inputId="conversations-view"
          label="How to list them"
          data-testid="view"
          [options]="viewOptions"
          [value]="bySubject() ? 'subjects' : 'list'"
          (valueChange)="setView($event)"
        />
      </div>

      @if (everyone()) {
        <div
          class="bg-card text-muted-foreground flex items-start gap-2.5 rounded-xl border px-4 py-3 text-sm text-pretty"
          role="note"
          data-testid="everyone-banner"
        >
          <ng-icon name="lucideEye" class="mt-0.5 shrink-0" aria-hidden="true" />
          <p>
            <strong class="text-foreground font-semibold"
              >You are reading everyone’s conversations.</strong
            >
            Only each conversation’s person writes, decides and connects accounts in it. Their
            people are told that the company’s Owner can read them, and every conversation you open
            is recorded in the audit log.
          </p>
        </div>
      }

      @if (bySubject()) {
        @if (subjects.firstLoad()) {
          <app-list-skeleton [rows]="3" />
        } @else if (subjects.failed()) {
          <app-error-state
            [error]="subjects.error()"
            heading="Couldn't load the subjects"
            (retry)="subjects.retry()"
          />
        } @else if (subjects.empty()) {
          <div hlmEmpty class="border border-dashed" data-testid="subjects-empty">
            <div hlmEmptyHeader>
              <div hlmEmptyMedia variant="icon">
                <ng-icon name="lucideTag" aria-hidden="true" />
              </div>
              <div hlmEmptyTitle>No conversation has a subject yet</div>
              <p hlmEmptyDescription>
                A conversation started about an issue or a change request is grouped here by its
                key, such as SHOP-241.
              </p>
            </div>
          </div>
        } @else {
          <ul class="bg-card flex flex-col divide-y rounded-xl border" aria-label="Subjects">
            @for (s of subjects.items(); track subjectKeyOf(s)) {
              <li
                class="flex min-w-0 flex-col gap-2 px-4 py-3.5 sm:flex-row sm:items-center sm:gap-4"
                data-testid="subject"
                [attr.data-key]="s.key"
              >
                <div class="flex min-w-0 flex-1 flex-col gap-1">
                  <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
                    @if (s.url) {
                      <a
                        class="focus-visible:ring-ring/50 rounded-sm font-mono text-sm font-semibold underline-offset-2 outline-none hover:underline focus-visible:ring-3"
                        target="_blank"
                        rel="noopener noreferrer"
                        [href]="s.url"
                        >{{ s.key }}<span class="sr-only"> (opens in a new tab)</span></a
                      >
                    } @else {
                      <span class="font-mono text-sm font-semibold">{{ s.key }}</span>
                    }
                    @if (s.systemName) {
                      <span class="text-muted-foreground text-xs">{{ s.systemName }}</span>
                    }
                  </div>
                  @if (s.title) {
                    <p class="text-sm text-pretty break-words">{{ s.title }}</p>
                  }
                  <p class="text-muted-foreground text-xs" data-testid="subject-counts">
                    {{ subjectCounts(s) }}
                    @if (s.lastActivityAt) {
                      · <app-time [value]="s.lastActivityAt" />
                    }
                  </p>
                </div>
                @if (s.systemId) {
                  <button
                    hlmBtn
                    variant="outline"
                    size="sm"
                    type="button"
                    class="sm:shrink-0"
                    data-testid="subject-conversations"
                    [attr.aria-label]="'Show conversations about ' + s.key"
                    (click)="showSubject(s)"
                  >
                    Show conversations
                  </button>
                }
              </li>
            }
          </ul>
          @if (subjects.hasMore()) {
            <div class="flex justify-center">
              <button
                hlmBtn
                variant="outline"
                size="sm"
                type="button"
                [disabled]="subjects.loadingMore()"
                (click)="subjects.loadMore()"
              >
                @if (subjects.loadingMore()) {
                  <hlm-spinner aria-hidden="true" />
                }
                Load more
              </button>
            </div>
          }
        }
      } @else {
        <div class="bg-card overflow-hidden rounded-xl border" data-testid="conversation-list">
          <form
            class="flex flex-col gap-2.5 border-b p-2.5 lg:flex-row lg:flex-wrap lg:items-center lg:justify-between"
            aria-label="Filters"
            data-testid="conversation-filters"
            (submit)="$event.preventDefault()"
          >
            <app-segmented
              inputId="conversations-show"
              label="Show"
              data-testid="show"
              [scroll]="true"
              [options]="showOptions"
              [value]="show() ?? ''"
              (valueChange)="setShow($event)"
            />
            <div class="flex flex-wrap items-center gap-2">
              @if (filters().subjectKey; as key) {
                <span
                  class="bg-primary/10 text-primary inline-flex h-8 items-center gap-1 rounded-full ps-3 pe-1 text-[0.8125rem] font-semibold"
                  data-testid="subject-filter"
                >
                  About <span class="font-mono font-medium">{{ key }}</span>
                  <button
                    type="button"
                    class="hover:bg-primary/15 focus-visible:ring-ring/50 inline-flex size-6 items-center justify-center rounded-full outline-none focus-visible:ring-3"
                    [attr.aria-label]="'Stop filtering by ' + key"
                    (click)="clearSubject()"
                  >
                    <ng-icon name="lucideX" size="12" aria-hidden="true" />
                  </button>
                </span>
              }
              <div [class]="filterPill">
                <label class="text-muted-foreground shrink-0 font-medium" for="filter-agent"
                  >Agent</label
                >
                <hlm-native-select
                  selectId="filter-agent"
                  [selectClass]="filterSelect"
                  [value]="filters().agentId ?? ''"
                  (valueChange)="setFilter('agentId', $event)"
                >
                  <option value="">Any</option>
                  @for (a of agentOptions(); track a.id) {
                    <option [value]="a.id" [selected]="a.id === filters().agentId">
                      {{ a.name }}
                    </option>
                  }
                </hlm-native-select>
              </div>
              @if (triggerOptions().length > 0 || filters().triggerId) {
                <div [class]="filterPill">
                  <label class="text-muted-foreground shrink-0 font-medium" for="filter-trigger"
                    >Trigger</label
                  >
                  <hlm-native-select
                    selectId="filter-trigger"
                    [selectClass]="filterSelect"
                    [value]="filters().triggerId ?? ''"
                    (valueChange)="setFilter('triggerId', $event)"
                  >
                    <option value="">Any, or none</option>
                    @for (t of triggerOptions(); track t.id) {
                      <option [value]="t.id" [selected]="t.id === filters().triggerId">
                        {{ t.name }}
                      </option>
                    }
                  </hlm-native-select>
                </div>
              }
              @if (everyone()) {
                <div [class]="filterPill">
                  <label class="text-muted-foreground shrink-0 font-medium" for="filter-person"
                    >Person</label
                  >
                  <hlm-native-select
                    selectId="filter-person"
                    [selectClass]="filterSelect"
                    [value]="filters().personId ?? ''"
                    (valueChange)="setFilter('personId', $event)"
                  >
                    <option value="">Everyone</option>
                    @for (m of memberOptions(); track m.principalId) {
                      <option
                        [value]="m.principalId"
                        [selected]="m.principalId === filters().personId"
                      >
                        {{ m.displayName }}
                      </option>
                    }
                  </hlm-native-select>
                </div>
              }
            </div>
          </form>
          @if (listLoading()) {
            <app-list-skeleton [rows]="4" class="block [&>div]:rounded-none [&>div]:border-0" />
          } @else if (conversations.failed()) {
            <app-error-state
              class="block p-4"
              [error]="conversations.error()"
              heading="Couldn't load conversations"
              [messages]="{
                'status:404': 'This agent or trigger does not exist, or you do not see it.',
              }"
              (retry)="conversations.retry()"
            />
          } @else if (conversations.empty()) {
            <div class="p-4">
              @if (filtered()) {
                <div
                  hlmEmpty
                  class="border border-dashed"
                  data-testid="conversations-filtered-empty"
                >
                  <div hlmEmptyHeader>
                    <div hlmEmptyMedia variant="icon">
                      <ng-icon name="lucideSearchX" aria-hidden="true" />
                    </div>
                    <div hlmEmptyTitle>No conversation matches these filters</div>
                    <p hlmEmptyDescription>Change a filter, or clear them to see every one.</p>
                  </div>
                  <div hlmEmptyContent>
                    <button
                      hlmBtn
                      variant="outline"
                      size="sm"
                      type="button"
                      data-testid="clear-filters"
                      (click)="clearFilters()"
                    >
                      <ng-icon name="lucideX" aria-hidden="true" />
                      Clear the filters
                    </button>
                  </div>
                </div>
              } @else if (everyone()) {
                <div hlmEmpty class="border border-dashed" data-testid="conversations-empty">
                  <div hlmEmptyHeader>
                    <div hlmEmptyMedia variant="icon">
                      <ng-icon name="lucideMessagesSquare" aria-hidden="true" />
                    </div>
                    <div hlmEmptyTitle>Nobody has talked to an agent yet</div>
                    <p hlmEmptyDescription>
                      Every conversation in the company will be listed here.
                    </p>
                  </div>
                </div>
              } @else {
                <div hlmEmpty class="border border-dashed" data-testid="conversations-empty">
                  <div hlmEmptyHeader>
                    <div hlmEmptyMedia variant="icon">
                      <ng-icon name="lucideMessagesSquare" aria-hidden="true" />
                    </div>
                    <div hlmEmptyTitle>Talk to an agent</div>
                    <p hlmEmptyDescription>
                      @if (usableAgents().length > 0) {
                        Pick one to start. Before your first message goes, you see which account it
                        uses on each system and who pays.
                      } @else if (agentsLoaded()) {
                        No agent is shared with you yet. Create one of your own, or ask the owner of
                        an agent to share it with you or your group.
                      } @else {
                        Your conversations with agents will be listed here.
                      }
                    </p>
                  </div>
                  @if (member()) {
                    <div hlmEmptyContent>
                      @if (usableAgents().length > 0) {
                        <ul class="flex w-full flex-col gap-2" aria-label="Agents you may talk to">
                          @for (a of usableAgents(); track a.id) {
                            <li>
                              <a
                                class="bg-card hover:bg-muted/60 focus-visible:ring-ring/50 flex min-w-0 items-center gap-3 rounded-lg border px-3 py-2.5 text-start outline-none focus-visible:ring-3"
                                data-testid="talk-to-agent"
                                [routerLink]="['/c', companyId, 'conversations', 'new']"
                                [queryParams]="{ agentId: a.id }"
                              >
                                <span
                                  class="bg-primary text-primary-foreground flex size-8 shrink-0 items-center justify-center rounded-[9px] text-xs font-bold"
                                  aria-hidden="true"
                                  >{{ initialsOf(a.name) }}</span
                                >
                                <span class="flex min-w-0 flex-1 flex-col">
                                  <span class="text-sm font-semibold">Talk to {{ a.name }}</span>
                                  @if (a.description) {
                                    <span class="text-muted-foreground truncate text-xs">{{
                                      a.description
                                    }}</span>
                                  }
                                </span>
                                <ng-icon
                                  name="lucideMessageSquare"
                                  class="text-muted-foreground shrink-0"
                                  aria-hidden="true"
                                />
                              </a>
                            </li>
                          }
                        </ul>
                      } @else if (agentsLoaded()) {
                        <a
                          hlmBtn
                          variant="outline"
                          size="sm"
                          [routerLink]="['/c', companyId, 'agents', 'new']"
                        >
                          <ng-icon name="lucidePlus" aria-hidden="true" />
                          Create an agent
                        </a>
                      }
                    </div>
                  }
                </div>
              }
            </div>
          } @else {
            <div class="flex flex-col divide-y">
              @if (pinnedRows().length > 0) {
                <h2 id="conversations-pinned" [class]="groupHeading" data-testid="pinned-heading">
                  {{ everyone() ? 'Needs a person' : 'Needs you' }}
                  <span [class]="groupCount"
                    >{{ pinnedRows().length }}{{ needsPerson.hasMore() ? '+' : '' }}</span
                  >
                </h2>
                <ul
                  class="flex flex-col divide-y"
                  aria-labelledby="conversations-pinned"
                  data-testid="pinned"
                >
                  @for (c of pinnedRows(); track c.id) {
                    <ng-container
                      [ngTemplateOutlet]="row"
                      [ngTemplateOutletContext]="{ $implicit: c }"
                    />
                  }
                  @if (needsPerson.hasMore()) {
                    <li class="flex justify-center px-4 py-2.5">
                      <button
                        hlmBtn
                        variant="ghost"
                        size="sm"
                        type="button"
                        [disabled]="needsPerson.loadingMore()"
                        (click)="
                          needsPerson.moreFailed() ? needsPerson.retry() : needsPerson.loadMore()
                        "
                      >
                        @if (needsPerson.loadingMore()) {
                          <hlm-spinner aria-hidden="true" />
                        }
                        Load more
                      </button>
                    </li>
                  }
                </ul>
                @if (restRows().length > 0) {
                  <h2 id="conversations-rest" [class]="groupHeading" data-testid="rest-heading">
                    Other conversations
                    <span [class]="groupCount"
                      >{{ restRows().length }}{{ conversations.hasMore() ? '+' : '' }}</span
                    >
                  </h2>
                }
              }
              @if (restRows().length > 0) {
                <ul
                  class="flex flex-col divide-y"
                  data-testid="rest"
                  [attr.aria-labelledby]="pinnedRows().length > 0 ? 'conversations-rest' : null"
                  [attr.aria-label]="pinnedRows().length > 0 ? null : 'Conversations'"
                >
                  @for (c of restRows(); track c.id) {
                    <ng-container
                      [ngTemplateOutlet]="row"
                      [ngTemplateOutletContext]="{ $implicit: c }"
                    />
                  }
                </ul>
              }
            </div>
          }
        </div>

        @if (conversations.moreFailed()) {
          <app-error-state
            [error]="conversations.error()"
            heading="Couldn't load more"
            (retry)="conversations.retry()"
          />
        } @else if (!listLoading() && conversations.hasMore()) {
          <div class="flex justify-center">
            <button
              hlmBtn
              variant="outline"
              size="sm"
              type="button"
              [disabled]="conversations.loadingMore()"
              (click)="conversations.loadMore()"
            >
              @if (conversations.loadingMore()) {
                <hlm-spinner aria-hidden="true" />
              }
              Load more
            </button>
          </div>
        }
      }
    </div>

    <ng-template #row let-c>
      <li
        class="hover:bg-accent/50 relative grid grid-cols-[2rem_minmax(0,1fr)] items-start gap-x-3.5 gap-y-2 px-4 py-3.5 transition-colors sm:grid-cols-[2rem_minmax(0,1fr)_auto] sm:items-center"
        data-testid="conversation"
        [attr.data-conversation-id]="c.id"
        [attr.data-state]="c.state"
      >
        <span
          class="flex size-8 items-center justify-center rounded-[9px] text-xs font-bold"
          [class]="
            c.state === 'ENDED'
              ? 'bg-muted text-muted-foreground'
              : 'bg-primary text-primary-foreground'
          "
          aria-hidden="true"
          >{{ initialsOf(c.agentName) }}</span
        >
        <div class="flex min-w-0 flex-col gap-0.5">
          <a
            class="focus-visible:ring-ring/50 -my-0.5 min-w-0 self-start rounded-sm py-0.5 text-[0.9375rem] leading-snug font-semibold break-words underline-offset-2 outline-none after:absolute after:inset-0 hover:underline focus-visible:ring-3"
            data-testid="conversation-link"
            [class.text-muted-foreground]="c.state === 'ENDED'"
            [routerLink]="['/c', current.id(), 'conversations', c.id]"
            >{{ title(c) }}</a
          >
          <p
            class="text-muted-foreground flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-2 text-xs"
          >
            <a
              class="hover:text-foreground relative -my-1 py-1 underline-offset-2 hover:underline"
              [routerLink]="['/c', current.id(), 'agents', c.agentId]"
              >{{ c.agentName }}</a
            >
            @if (everyone() && personOf(c); as person) {
              <span class="text-faint" aria-hidden="true">·</span>
              @if (c.personPrincipalId && !filters().personId) {
                <button
                  type="button"
                  class="hover:text-foreground focus-visible:ring-ring/50 relative -my-1 rounded-sm py-1 underline-offset-2 outline-none hover:underline focus-visible:ring-3"
                  data-testid="conversation-person"
                  [attr.aria-label]="'Only ' + person + '’s conversations'"
                  (click)="setFilter('personId', c.personPrincipalId)"
                >
                  {{ person }}
                </button>
              } @else {
                <span data-testid="conversation-person">{{ person }}</span>
              }
            }
            @if (c.subject; as s) {
              <span class="text-faint" aria-hidden="true">·</span>
              <span class="sr-only">about</span>
              <span
                class="bg-muted text-foreground inline-flex h-5 items-center rounded-md px-1.5 font-mono"
                data-testid="conversation-subject"
                [title]="s.title ?? ''"
                >{{ s.key }}</span
              >
            }
            @if (c.trigger; as t) {
              <span class="text-faint" aria-hidden="true">·</span>
              <span class="inline-flex items-center gap-1" data-testid="conversation-trigger">
                <ng-icon name="lucideZap" size="12" aria-hidden="true" />
                <a
                  class="hover:text-foreground relative -my-1 py-1 underline-offset-2 hover:underline"
                  [routerLink]="['/c', current.id(), 'triggers', t.triggerId]"
                  >{{ t.name }}</a
                >
              </span>
            }
            <span class="text-faint" aria-hidden="true">·</span>
            <span>{{ turns(c) }}</span>
            @if (cost(c); as spent) {
              <span class="text-faint" aria-hidden="true">·</span>
              <span
                class="font-mono tabular-nums"
                data-testid="conversation-cost"
                title="What its settled turns cost"
                ><span class="sr-only">cost </span>{{ spent }}</span
              >
            }
            @if (tokens(c); as used) {
              <span class="text-faint" aria-hidden="true">·</span>
              <span
                class="font-mono tabular-nums"
                data-testid="conversation-tokens"
                [title]="used.breakdown"
                >{{ used.total }}</span
              >
            }
            <span class="text-faint" aria-hidden="true">·</span>
            <span class="max-w-full truncate font-mono">{{ c.key }}</span>
          </p>
        </div>
        <div
          class="col-start-2 flex flex-wrap items-center gap-x-3 gap-y-1 sm:col-start-auto sm:justify-end"
        >
          @if (c.state === 'ENDED') {
            <span
              class="bg-muted text-muted-foreground inline-flex h-6 items-center rounded-full px-2.5 text-xs font-semibold"
              data-testid="conversation-ended"
              >{{ ended(c) }}</span
            >
          } @else {
            <app-waiting-on-badge
              class="empty:hidden"
              [item]="c"
              [mine]="isMine(c)"
              [personName]="personOf(c)"
            />
          }
          <span class="text-muted-foreground text-xs whitespace-nowrap sm:min-w-23 sm:text-end"
            ><app-time [value]="c.lastTurnAt ?? c.updatedAt"
          /></span>
        </div>
      </li>
    </ng-template>
  `,
})
export class Conversations {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly caller = inject(CallerPrincipal);
  protected readonly current = inject(CurrentCompany);

  // --- The URL's query (`withComponentInputBinding`) ----------------------------------------
  /** `everyone`: the Owner reads every conversation; otherwise their own. */
  readonly who = input<string | undefined>(undefined);
  /** `subjects`: the subjects with their counts; otherwise the conversations. */
  readonly view = input<string | undefined>(undefined);
  readonly agentId = input<string | undefined>(undefined);
  readonly state = input<string | undefined>(undefined);
  readonly waitingOn = input<string | undefined>(undefined);
  readonly subjectSystemId = input<string | undefined>(undefined);
  readonly subjectKey = input<string | undefined>(undefined);
  readonly triggerId = input<string | undefined>(undefined);
  readonly personId = input<string | undefined>(undefined);

  protected readonly whoseOptions = WHOSE;
  protected readonly viewOptions = VIEWS;
  protected readonly showOptions: readonly SegmentedOption[] = SHOW_FILTERS.map(
    ({ value, label }) => ({ value, label }),
  );

  /** A filter of the list's bar: its name, then a borderless select, in one pill. */
  protected readonly filterPill =
    'border-input hover:bg-accent/40 has-[select:focus-visible]:border-ring has-[select:focus-visible]:ring-ring/50 flex h-8 max-w-full min-w-0 items-center rounded-full border ps-3 text-[0.8125rem] transition-colors has-[select:focus-visible]:ring-3';
  protected readonly filterSelect =
    'h-7.5 max-w-56 rounded-full border-0 bg-transparent py-0 ps-1.5 pe-7 text-[0.8125rem] font-semibold hover:bg-transparent focus-visible:ring-0';
  /** The heading of the pinned section and of the rest, with its count. */
  protected readonly groupHeading =
    'bg-muted/50 text-muted-foreground flex items-center gap-2 px-4 py-2 text-xs font-semibold';
  protected readonly groupCount =
    'bg-muted text-foreground inline-flex h-4.5 min-w-5 items-center justify-center rounded-full px-1.5 font-mono text-[0.6875rem] font-medium';

  /** A member who may work: a Viewer starts and edits nothing (ADR-0077). */
  protected readonly member = computed(() => mayWork(this.current.access()));
  protected readonly viewer = computed(() => isViewer(this.current.access()));
  protected readonly owner = computed(() => this.current.access().role === 'OWNER');
  protected readonly everyone = computed(() => this.owner() && this.who() === 'everyone');
  protected readonly bySubject = computed(() => this.view() === 'subjects');

  protected readonly filters = computed<ConversationFilters>(() => {
    const f = filtersOf({
      agentId: this.agentId(),
      state: this.state(),
      waitingOn: this.waitingOn(),
      subjectSystemId: this.subjectSystemId(),
      subjectKey: this.subjectKey(),
      triggerId: this.triggerId(),
      personId: this.personId(),
    });
    // A person is a filter of "Everyone's" only.
    return this.everyone() ? f : { ...f, personId: undefined };
  });
  protected readonly filtered = computed(() => filterCount(this.filters()) > 0);

  /** The pill the URL's state and waiting pick. */
  protected readonly show = computed(() => showOf(this.filters()));

  /** What the list asks the API for. */
  private readonly listParams = computed<ListParams | undefined>(() => {
    const companyId = this.current.id();
    if (!companyId || this.bySubject()) return undefined;
    const filters = this.filters();
    if (!this.owner() || this.everyone()) return { companyId, ...filters };
    // The Owner's "Mine": the API answers everyone's unless it names their principal.
    const me = this.caller.id();
    return me ? { companyId, ...filters, personId: me } : undefined;
  });

  protected readonly conversations = pagedResource<ConversationSummary, ListParams>({
    params: () => this.listParams(),
    load: (params, cursor) =>
      this.api.invoke(listConversations, { ...params, limit: PAGE_SIZE, cursor }),
  });

  /** Whether what waits on a person leads the list: while it shows all, or the open ones. */
  private readonly pinWanted = computed(() => this.show() === 'all' || this.show() === 'open');

  /**
   * The conversations that wait on a person, read on their own (`waitingOn=PERSON`) to lead the
   * list. Should they fail to load, the list stays one, newest first.
   */
  protected readonly needsPerson = pagedResource<ConversationSummary, ListParams>({
    params: () => {
      const params = this.listParams();
      return params && this.pinWanted() ? { ...params, waitingOn: 'PERSON' } : undefined;
    },
    load: (params, cursor) =>
      this.api.invoke(listConversations, { ...params, limit: PAGE_SIZE, cursor }),
  });

  /** The pinned ones: those that wait on the reader in "Mine", on any person in "Everyone's". */
  protected readonly pinnedRows = computed(() =>
    this.pinWanted() && !this.needsPerson.failed()
      ? this.needsPerson.items().filter((c) => this.leads(c))
      : [],
  );

  /**
   * The rest, newest first. While the pinned section has more to load, one that belongs there waits
   * for its "Load more" rather than show here.
   */
  protected readonly restRows = computed(() => {
    const pinned = new Set(this.pinnedRows().map((c) => c.id));
    const more = pinned.size > 0 && this.needsPerson.hasMore();
    return this.conversations.items().filter((c) => !pinned.has(c.id) && !(more && this.leads(c)));
  });

  protected readonly listLoading = computed(
    () => this.conversations.firstLoad() || (this.pinWanted() && this.needsPerson.firstLoad()),
  );

  protected readonly description = computed(() => {
    const whose = this.everyone()
      ? 'Every conversation in the company'
      : 'Your conversations with agents';
    if (this.bySubject() || !this.pinWanted()) return `${whose}, newest activity first.`;
    const who = this.everyone() ? 'a person' : 'you';
    return `${whose}. What needs ${who} comes first, then the newest activity.`;
  });

  protected readonly subjects = pagedResource<Subject, { companyId: string }>({
    params: () => {
      const companyId = this.current.id();
      return companyId && this.bySubject() ? { companyId } : undefined;
    },
    load: ({ companyId }, cursor) =>
      this.api.invoke(listSubjects, { companyId, limit: PAGE_SIZE, cursor }),
  });

  /** The agents the reader sees: the filter's choices, and those they may talk to. */
  private readonly agents = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(listAgents, { companyId, limit: 100 }),
  });
  protected readonly agentsLoaded = computed(() => this.agents.hasValue());
  protected readonly agentOptions = computed(() =>
    this.agents.hasValue() ? (this.agents.value()?.items ?? []) : [],
  );
  protected readonly usableAgents = computed(() =>
    this.agentOptions().filter((a) => a.usableByMe && a.status !== 'PAUSED'),
  );

  /** The triggers the reader sees (package 4): a filter only when there is one. */
  private readonly triggers = resource({
    params: () => (this.bySubject() ? undefined : this.current.id()),
    loader: ({ params: companyId }) => this.api.invoke(listTriggers, { companyId, limit: 100 }),
  });
  protected readonly triggerOptions = computed(() =>
    this.triggers.hasValue() ? (this.triggers.value()?.items ?? []) : [],
  );

  /** The members, for "Everyone's" person filter: those with a principal in the company. */
  private readonly members = resource({
    params: () => (this.everyone() && !this.bySubject() ? this.current.id() : undefined),
    loader: ({ params: companyId }) => this.api.invoke(listMembers, { companyId, limit: 200 }),
  });
  protected readonly memberOptions = computed(() =>
    (this.members.hasValue() ? (this.members.value()?.items ?? []) : [])
      .filter((m): m is typeof m & { principalId: string } => !!m.principalId)
      .sort((a, b) => a.displayName.localeCompare(b.displayName)),
  );

  protected title(c: ConversationSummary): string {
    return conversationTitle(c);
  }

  protected ended(c: ConversationSummary): string {
    return endedLabel(c.endedReason);
  }

  protected turns(c: ConversationSummary): string {
    return plural(c.turnCount, 'turn');
  }

  /** "$1.24": what its settled turns cost; nothing before any spend. */
  protected cost(c: ConversationSummary): string | null {
    return c.costUsd ? formatCost(c.costUsd) : null;
  }

  /** "7,085 tokens" of its settled turns, with in, cache write, cache read and out as its title. */
  protected tokens(c: ConversationSummary): { total: string; breakdown: string } | null {
    const total = c.tokens ? tokenTotal(c.tokens) : 0;
    if (!c.tokens || total === 0) return null;
    return { total: `${formatTokens(total)} tokens`, breakdown: tokenBreakdown(c.tokens) };
  }

  protected initialsOf(name: string): string {
    return initials(name);
  }

  /** The conversation's person, or a trigger's author. */
  protected personOf(c: ConversationSummary): string | null {
    return c.personName ?? c.trigger?.authorName ?? null;
  }

  /** Whether it leads the list: it waits on its person, the reader in "Mine". */
  private leads(c: ConversationSummary): boolean {
    return c.state === 'OPEN' && c.waitingOn === 'PERSON' && (this.everyone() || this.isMine(c));
  }

  /** Whether it waits on the reader: their own, or a trigger's they wrote. */
  protected isMine(c: ConversationSummary): boolean {
    const me = this.caller.id();
    if (!me) return !this.everyone();
    return (c.personPrincipalId ?? c.trigger?.authorPrincipalId) === me;
  }

  protected subjectCounts(s: Subject): string {
    return `${plural(s.conversations, 'conversation')} · ${s.open} open`;
  }

  protected subjectKeyOf(s: Subject): string {
    return `${s.systemId ?? ''}:${s.key}`;
  }

  protected setWhose(value: string): void {
    this.navigate({ who: value === 'everyone' ? 'everyone' : null, personId: null });
  }

  protected setShow(value: string): void {
    const show = SHOW_FILTERS.find((s) => s.value === value);
    this.navigate({ state: show?.state ?? null, waitingOn: show?.waitingOn ?? null });
  }

  protected setView(value: string): void {
    this.navigate({ view: value === 'subjects' ? 'subjects' : null });
  }

  protected setFilter(
    name: (typeof FILTER_PARAMS)[number],
    value: string | null | undefined,
  ): void {
    this.navigate({ [name]: value || null });
  }

  protected showSubject(s: Subject): void {
    this.navigate({ view: null, subjectSystemId: s.systemId ?? null, subjectKey: s.key });
  }

  protected clearSubject(): void {
    this.navigate({ subjectSystemId: null, subjectKey: null });
  }

  protected clearFilters(): void {
    this.navigate(Object.fromEntries(FILTER_PARAMS.map((p) => [p, null])));
  }

  private navigate(queryParams: Record<string, string | null>): void {
    void this.router.navigate([], { queryParams, queryParamsHandling: 'merge', replaceUrl: true });
  }
}
