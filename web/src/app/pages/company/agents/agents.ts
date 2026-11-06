import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideBot,
  lucideCircleArrowUp,
  lucideMessageSquare,
  lucidePause,
  lucidePencil,
  lucidePlus,
  lucideShieldPlus,
  lucideSlidersHorizontal,
} from '@ng-icons/lucide';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmEmptyImports } from '@spartan-ng/helm/empty';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import { listAgents } from '../../../api/generated/functions';
import type { AgentSummary } from '../../../api/generated/models';
import { CurrentCompany } from '../../../core/current-company';
import { pagedResource } from '../../../core/paged-resource';
import { canEditAgent, canUseAgent, isViewer, mayWork } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import { initials } from '../../../shared/forms';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { PageHeader } from '../../../shared/page-header';
import { ViewerNotice } from '../../../shared/viewer-notice';
import { Segmented, type SegmentedOption } from '../../../shared/segmented';
import { runtimesText } from './agent-labels';

const PAGE_SIZE = 100;

/** Which agents the list shows: those the reader may use, or all they see. */
export type AgentsShown = 'usable' | 'all';

const SHOWN: readonly SegmentedOption[] = [
  { value: 'usable', label: 'I can use' },
  { value: 'all', label: 'All I see' },
];

/**
 * The agents (plan §4.3): those the reader may use, or all they see (their own, those shared with
 * their groups, and for OWNER and ADMIN every agent). Each row says whether it is paused or has a
 * new version for the reader to review, and to its editors how many of its skills have a newer
 * version in the library (`skillUpdates`); it offers Talk to and My settings to whoever may use it
 * and Edit to whoever may edit it. Rights are the server's answers (`usableByMe`, `editableByMe`).
 */
@Component({
  selector: 'app-agents',
  imports: [
    RouterLink,
    NgIcon,
    HlmButton,
    HlmEmptyImports,
    HlmSpinner,
    ErrorState,
    ListSkeleton,
    PageHeader,
    ViewerNotice,
    Segmented,
  ],
  providers: [
    provideIcons({
      lucideBot,
      lucideCircleArrowUp,
      lucideMessageSquare,
      lucidePause,
      lucidePencil,
      lucidePlus,
      lucideShieldPlus,
      lucideSlidersHorizontal,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let companyId = current.id();
    <div class="flex flex-col gap-5">
      <app-page-header
        title="Agents"
        description="The AI agents you talk to. An agent holds no credentials: it acts as whoever uses it."
      >
        @if (member()) {
          <a hlmBtn data-testid="new-agent" [routerLink]="['/c', companyId, 'agents', 'new']">
            <ng-icon name="lucidePlus" aria-hidden="true" />
            New agent
          </a>
        }
      </app-page-header>

      @if (viewer()) {
        <app-viewer-notice place="agents" />
      }

      <app-segmented
        class="sm:self-start"
        inputId="agents-shown"
        label="Which agents"
        [options]="shownOptions"
        [value]="shown()"
        (valueChange)="setShown($event)"
      />

      @if (agents.firstLoad()) {
        <app-list-skeleton [rows]="3" />
      } @else if (agents.failed()) {
        <app-error-state
          [error]="agents.error()"
          heading="Couldn't load agents"
          (retry)="agents.retry()"
        />
      } @else if (agents.empty()) {
        <div hlmEmpty class="border border-dashed" data-testid="agents-empty">
          <div hlmEmptyHeader>
            <div hlmEmptyMedia variant="icon"><ng-icon name="lucideBot" aria-hidden="true" /></div>
            <div hlmEmptyTitle>
              {{ shown() === 'usable' ? 'No agent is shared with you yet' : 'No agents yet' }}
            </div>
            <p hlmEmptyDescription>
              @if (shown() === 'usable') {
                Create one of your own, or ask the owner of an agent to share it with you or your
                group.
              } @else {
                An agent is a role with instructions, the systems and tools it may use, and
                the people it is shared with.
              }
            </p>
          </div>
          @if (member()) {
            <div hlmEmptyContent>
              <a
                hlmBtn
                variant="outline"
                size="sm"
                [routerLink]="['/c', companyId, 'agents', 'new']"
              >
                <ng-icon name="lucidePlus" aria-hidden="true" />
                Create an agent
              </a>
            </div>
          }
        </div>
      } @else {
        <ul class="bg-card flex flex-col divide-y rounded-xl border" aria-label="Agents">
          @for (agent of agents.items(); track agent.id) {
            <li
              class="flex min-w-0 flex-col gap-3 px-4 py-3.5 sm:flex-row sm:items-center sm:gap-4"
              [attr.data-testid]="'agent-' + agent.id"
            >
              <div class="flex min-w-0 flex-1 items-start gap-3">
                <span
                  class="bg-primary text-primary-foreground flex size-9 shrink-0 items-center justify-center rounded-[10px] text-xs font-bold"
                  aria-hidden="true"
                  >{{ initialsOf(agent.name) }}</span
                >
                <div class="flex min-w-0 flex-col gap-1">
                  <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <a
                      class="focus-visible:ring-ring/50 rounded-sm font-semibold underline-offset-2 outline-none hover:underline focus-visible:ring-3"
                      data-testid="agent-link"
                      [routerLink]="['/c', companyId, 'agents', agent.id]"
                      >{{ agent.name }}</a
                    >
                    <span class="text-muted-foreground font-mono text-xs"
                      >&#64;{{ agent.handle }}</span
                    >
                    @if (agent.status === 'PAUSED') {
                      <span
                        class="bg-muted text-muted-foreground inline-flex h-6 items-center gap-1 rounded-full border px-2.5 text-xs font-semibold"
                        data-testid="agent-paused"
                      >
                        <ng-icon name="lucidePause" size="12" aria-hidden="true" />
                        Paused
                      </span>
                    }
                    @if (agent.wideningPending) {
                      <span
                        class="bg-card inline-flex h-6 items-center gap-1 rounded-full border px-2.5 text-xs font-semibold"
                        data-testid="agent-widening"
                        title="Its current version may do more than the one you accepted"
                      >
                        <ng-icon name="lucideShieldPlus" size="12" aria-hidden="true" />
                        New version to review
                      </span>
                    }
                    @if (canEdit(agent) && agent.skillUpdates > 0) {
                      <span
                        class="bg-card inline-flex h-6 items-center gap-1 rounded-full border px-2.5 text-xs font-semibold"
                        data-testid="agent-skill-updates"
                        title="The library has newer versions of its skills; edit it to move their pins"
                      >
                        <ng-icon name="lucideCircleArrowUp" size="12" aria-hidden="true" />
                        {{ agent.skillUpdates }}
                        {{ agent.skillUpdates === 1 ? 'skill update' : 'skill updates' }}
                      </span>
                    }
                  </div>
                  @if (agent.description) {
                    <p class="text-muted-foreground text-sm text-pretty">{{ agent.description }}</p>
                  }
                  <p class="text-muted-foreground text-xs">
                    {{ runtimes(agent) }} · version
                    <span class="font-mono">{{ agent.version }}</span>
                    @if (agent.ownerName) {
                      · owned by {{ agent.ownerName }}
                    }
                    @if (!agent.usableByMe) {
                      · <span data-testid="agent-not-usable">not shared with you</span>
                    }
                  </p>
                </div>
              </div>
              <div class="flex flex-wrap items-center gap-2 sm:shrink-0 sm:justify-end">
                @if (canUse(agent) && member()) {
                  <a
                    hlmBtn
                    variant="outline"
                    size="sm"
                    data-testid="talk-to"
                    [routerLink]="['/c', companyId, 'conversations', 'new']"
                    [queryParams]="{ agentId: agent.id }"
                    [attr.aria-label]="'Talk to ' + agent.name"
                  >
                    <ng-icon name="lucideMessageSquare" aria-hidden="true" />
                    Talk to
                  </a>
                  <a
                    hlmBtn
                    variant="ghost"
                    size="sm"
                    data-testid="my-settings"
                    [routerLink]="['/c', companyId, 'agents', agent.id, 'settings']"
                    [attr.aria-label]="'My settings of ' + agent.name"
                  >
                    <ng-icon name="lucideSlidersHorizontal" aria-hidden="true" />
                    My settings
                  </a>
                }
                @if (canEdit(agent)) {
                  <a
                    hlmBtn
                    variant="ghost"
                    size="sm"
                    data-testid="edit-agent"
                    [routerLink]="['/c', companyId, 'agents', agent.id, 'edit']"
                    [attr.aria-label]="'Edit ' + agent.name"
                  >
                    <ng-icon name="lucidePencil" aria-hidden="true" />
                    Edit
                  </a>
                }
              </div>
            </li>
          }
        </ul>

        @if (agents.moreFailed()) {
          <app-error-state
            [error]="agents.error()"
            heading="Couldn't load more"
            (retry)="agents.retry()"
          />
        } @else if (agents.hasMore()) {
          <div class="flex justify-center">
            <button
              hlmBtn
              variant="outline"
              size="sm"
              type="button"
              [disabled]="agents.loadingMore()"
              (click)="agents.loadMore()"
            >
              @if (agents.loadingMore()) {
                <hlm-spinner aria-hidden="true" />
              }
              Load more
            </button>
          </div>
        }
      }
    </div>
  `,
})
export class Agents {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  protected readonly current = inject(CurrentCompany);

  /** `?show=all` shows every agent the reader sees; otherwise those they may use. */
  readonly show = input<string | undefined>(undefined);

  protected readonly shownOptions = SHOWN;
  protected readonly shown = computed<AgentsShown>(() =>
    this.show() === 'all' ? 'all' : 'usable',
  );
  /** A member who may work: a Viewer starts and edits nothing (ADR-0077). */
  protected readonly member = computed(() => mayWork(this.current.access()));
  protected readonly viewer = computed(() => isViewer(this.current.access()));

  protected readonly agents = pagedResource<AgentSummary, { companyId: string; usable: boolean }>({
    params: () => {
      const companyId = this.current.id();
      return companyId ? { companyId, usable: this.shown() === 'usable' } : undefined;
    },
    load: ({ companyId, usable }, cursor) =>
      this.api.invoke(listAgents, { companyId, usable, limit: PAGE_SIZE, cursor }),
  });

  protected setShown(value: string): void {
    void this.router.navigate([], {
      queryParams: { show: value === 'all' ? 'all' : null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  protected initialsOf(name: string): string {
    return initials(name);
  }

  protected runtimes(agent: AgentSummary): string {
    return runtimesText(agent.runtimes);
  }

  protected canUse(agent: AgentSummary): boolean {
    return canUseAgent(agent);
  }

  protected canEdit(agent: AgentSummary): boolean {
    return canEditAgent(agent);
  }
}
