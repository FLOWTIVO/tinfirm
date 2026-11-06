import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  resource,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArrowLeft,
  lucideCircleAlert,
  lucideInfo,
  lucideMessageSquare,
  lucidePause,
  lucidePencil,
  lucidePlay,
  lucideSlidersHorizontal,
  lucideTrash2,
  lucideUser,
  lucideUsers,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  deleteAgent,
  getAgent,
  getAgentReadiness,
  listMyAccounts,
  pauseAgent,
  resumeAgent,
} from '../../../api/generated/functions';
import type { Agent } from '../../../api/generated/models';
import { problemMessage, toProblem } from '../../../api/problem';
import { CallerPrincipal } from '../../../core/caller-principal';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { canEditAgent, canUseAgent, isViewer, mayWork } from '../../../core/permissions';
import { ErrorState } from '../../../shared/error-state';
import { initials } from '../../../shared/forms';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { Markdown } from '../../../shared/markdown';
import { Time } from '../../../shared/time';
import { ViewerNotice } from '../../../shared/viewer-notice';
import { WhoActsWhoPays } from '../../../shared/who-acts-who-pays';
import { NotFound } from '../../not-found';
import { AgentDefinition } from './agent-definition';
import { AgentSkills } from './agent-skills';
import { notUsableText, runtimesText, sharingText } from './agent-labels';
import { WideningCard } from './widening-card';

const NOT_EDITABLE = {
  AGENT_NOT_EDITABLE: 'Only the agent’s owner and the company’s admins can do this.',
};

/**
 * One agent (plan §4.3): what it may use (each system's tools with their effect and mode, its
 * repositories with their access, its network hosts), its instructions and skills (each at the
 * version it pins, downloaded by whoever sees it), whom it is shared with, its owner and
 * version; "When you use it", who acts and who pays for the reader, from `getAgentReadiness`; a
 * new version to accept (the widening card); and, for whoever may edit it, Edit, Pause (with
 * stopping the turns that run) or Resume, and Delete.
 */
@Component({
  selector: 'app-agent-page',
  imports: [
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmSpinner,
    AgentDefinition,
    AgentSkills,
    ErrorState,
    ListSkeleton,
    Markdown,
    NotFound,
    Time,
    ViewerNotice,
    WhoActsWhoPays,
    WideningCard,
  ],
  providers: [
    provideIcons({
      lucideArrowLeft,
      lucideCircleAlert,
      lucideInfo,
      lucideMessageSquare,
      lucidePause,
      lucidePencil,
      lucidePlay,
      lucideSlidersHorizontal,
      lucideTrash2,
      lucideUser,
      lucideUsers,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let companyId = current.id();
    @if (notFound()) {
      <app-not-found />
    } @else {
      <div class="flex flex-col gap-5">
        <a
          hlmBtn
          variant="ghost"
          size="sm"
          class="-ms-3 self-start"
          [routerLink]="['/c', companyId, 'agents']"
        >
          <ng-icon name="lucideArrowLeft" aria-hidden="true" />
          Agents
        </a>

        @if (agentRes.isLoading() && !agent()) {
          <app-list-skeleton [rows]="3" />
        } @else if (agentRes.error() && !agent()) {
          <app-error-state
            [error]="agentRes.error()"
            heading="Couldn't load this agent"
            (retry)="agentRes.reload()"
          />
        } @else if (agent(); as a) {
          <header
            class="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-start sm:justify-between"
            data-testid="agent-header"
          >
            <div class="flex min-w-0 items-start gap-4 sm:flex-[1_1_24rem]">
              <div
                class="bg-primary text-primary-foreground flex size-12 shrink-0 items-center justify-center rounded-[14px] text-lg font-bold"
                aria-hidden="true"
              >
                {{ initialsOf(a.name) }}
              </div>
              <div class="flex min-w-0 flex-col gap-1">
                <div class="flex flex-wrap items-center gap-x-2.5 gap-y-1">
                  <h1 class="text-xl font-bold tracking-tight text-balance sm:text-2xl">
                    {{ a.name }}
                  </h1>
                  <span class="text-muted-foreground font-mono text-[0.8125rem]"
                    >&#64;{{ a.handle }}</span
                  >
                  @if (a.status === 'PAUSED') {
                    <span
                      class="bg-muted text-muted-foreground inline-flex h-6 items-center gap-1 rounded-full border px-2.5 text-xs font-semibold"
                      data-testid="agent-status"
                    >
                      <ng-icon name="lucidePause" size="12" aria-hidden="true" />
                      Paused
                    </span>
                  }
                </div>
                @if (a.description) {
                  <p class="text-muted-foreground text-sm text-pretty">{{ a.description }}</p>
                }
                <p class="text-muted-foreground text-xs" data-testid="agent-meta">
                  Version <span class="font-mono">{{ a.version }}</span>
                  @if (a.acceptedVersion && a.acceptedVersion !== a.version) {
                    (you use <span class="font-mono">{{ a.acceptedVersion }}</span
                    >)
                  }
                  · {{ runtimes() }}
                  @if (a.ownerName) {
                    · owned by {{ a.ownerName }}
                  } @else {
                    · owned by the company’s admins
                  }
                </p>
              </div>
            </div>
            <div class="flex flex-wrap items-center gap-2 sm:shrink-0">
              @if (usable() && member()) {
                <a
                  hlmBtn
                  size="sm"
                  data-testid="talk-to"
                  [routerLink]="['/c', companyId, 'conversations', 'new']"
                  [queryParams]="{ agentId: a.id }"
                >
                  <ng-icon name="lucideMessageSquare" aria-hidden="true" />
                  Talk to
                </a>
                <a
                  hlmBtn
                  variant="outline"
                  size="sm"
                  data-testid="my-settings"
                  [routerLink]="['/c', companyId, 'agents', a.id, 'settings']"
                >
                  <ng-icon name="lucideSlidersHorizontal" aria-hidden="true" />
                  My settings
                </a>
              }
              @if (editable()) {
                <a
                  hlmBtn
                  variant="outline"
                  size="sm"
                  data-testid="edit-agent"
                  [routerLink]="['/c', companyId, 'agents', a.id, 'edit']"
                >
                  <ng-icon name="lucidePencil" aria-hidden="true" />
                  Edit
                </a>
                @if (a.status === 'ACTIVE') {
                  <button
                    hlmBtn
                    variant="ghost"
                    size="sm"
                    type="button"
                    data-testid="pause-agent"
                    (click)="askPause()"
                  >
                    <ng-icon name="lucidePause" aria-hidden="true" />
                    Pause
                  </button>
                }
                <button
                  hlmBtn
                  variant="destructive"
                  size="sm"
                  type="button"
                  data-testid="delete-agent"
                  (click)="askDelete()"
                >
                  <ng-icon name="lucideTrash2" aria-hidden="true" />
                  Delete
                </button>
              }
            </div>
          </header>

          @if (a.status === 'PAUSED') {
            <div
              class="bg-card flex flex-col gap-3 rounded-xl border px-4 py-3 sm:flex-row sm:items-center sm:justify-between"
              role="status"
              data-testid="paused-banner"
            >
              <p class="flex items-start gap-2 text-sm">
                <ng-icon
                  name="lucidePause"
                  class="text-muted-foreground mt-0.5 shrink-0"
                  aria-hidden="true"
                />
                <span class="flex flex-col">
                  <span class="font-semibold"
                    >{{ a.name }} is paused; its turns wait.
                    @if (a.pausedAt) {
                      <span class="text-muted-foreground font-normal"
                        >· paused <app-time [value]="a.pausedAt"
                      /></span>
                    }
                  </span>
                  <span class="text-muted-foreground"
                    >Its conversations start no turn and run no approved call until it is
                    resumed.</span
                  >
                </span>
              </p>
              @if (editable()) {
                <button
                  hlmBtn
                  variant="outline"
                  size="sm"
                  type="button"
                  class="self-start sm:self-auto"
                  data-testid="resume-agent"
                  [disabled]="busy()"
                  (click)="resume()"
                >
                  @if (busy()) {
                    <hlm-spinner aria-hidden="true" />
                  } @else {
                    <ng-icon name="lucidePlay" aria-hidden="true" />
                  }
                  Resume
                </button>
              }
            </div>
          }

          @if (a.widening && companyId) {
            <app-widening-card
              [companyId]="companyId"
              [agent]="a"
              [widening]="a.widening"
              (accepted)="onAccepted()"
            />
          }

          @if (!usable()) {
            <div hlmAlert data-testid="not-usable">
              <ng-icon name="lucideInfo" aria-hidden="true" />
              <h2 hlmAlertTitle>You cannot use this agent</h2>
              <p hlmAlertDescription>{{ notUsable() }}</p>
            </div>
          }

          @if (viewer()) {
            <app-viewer-notice place="agents" />
          }

          <div class="grid gap-5 lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start">
            <div class="flex min-w-0 flex-col gap-5">
              @if (usable() && member()) {
                <div data-testid="when-you-use-it">
                  @if (readiness.value(); as r) {
                    <app-who-acts-who-pays
                      heading="When you use it"
                      intro="An agent holds no credentials: it acts as whoever uses it."
                      [readiness]="r"
                      [access]="caller.modelAccess()"
                      [accounts]="accounts()"
                    />
                  } @else {
                    <section
                      class="bg-card flex flex-col gap-3 rounded-xl border p-4"
                      aria-labelledby="when-you-use-it"
                    >
                      <div class="flex flex-col gap-0.5">
                        <h2 class="text-sm font-semibold" id="when-you-use-it">When you use it</h2>
                        <p class="text-muted-foreground text-xs">
                          An agent holds no credentials: it acts as whoever uses it.
                        </p>
                      </div>
                      @if (readiness.error()) {
                        <p class="text-destructive text-sm" role="alert">{{ readinessError() }}</p>
                      } @else {
                        <app-list-skeleton [rows]="2" />
                      }
                    </section>
                  }
                </div>
              }

              <section
                class="bg-card flex flex-col gap-4 rounded-xl border p-4 sm:p-5"
                aria-labelledby="what-it-may-use"
              >
                <div class="flex flex-col gap-0.5">
                  <h2 class="text-base font-bold" id="what-it-may-use">What it may use</h2>
                  <p class="text-muted-foreground text-xs">
                    “Allowed” calls the tool without asking; “Needs your approval” waits for you to
                    approve each call.
                    @if (a.widening) {
                      This is version {{ a.version }}; your conversations use version
                      {{ a.widening.acceptedVersion }} until you accept it.
                    }
                  </p>
                </div>
                <app-agent-definition [agent]="a" />
              </section>

              <section
                class="bg-card flex flex-col gap-3 rounded-xl border p-4 sm:p-5"
                aria-labelledby="agent-instructions"
              >
                <h2 class="text-base font-bold" id="agent-instructions">Instructions</h2>
                @if (a.instructions.trim()) {
                  <app-markdown class="text-sm" [text]="a.instructions" />
                } @else {
                  <p class="text-muted-foreground text-sm">None yet.</p>
                }
              </section>

              @if (companyId) {
                <app-agent-skills [companyId]="companyId" [agent]="a" />
              }
            </div>

            <aside class="flex min-w-0 flex-col gap-5" aria-label="About the agent">
              <section
                class="bg-card flex flex-col gap-2 rounded-xl border p-4"
                aria-labelledby="agent-sharing"
                data-testid="agent-sharing"
              >
                <h2 class="text-sm font-semibold" id="agent-sharing">Shared with</h2>
                @if (a.shares.length > 0 || a.memberShares.length > 0) {
                  <ul class="flex flex-wrap gap-1.5">
                    @for (g of a.shares; track g.id) {
                      <li
                        class="bg-secondary inline-flex h-6 max-w-full items-center gap-1 rounded-full px-2.5 text-xs font-semibold"
                        [attr.data-group]="g.id"
                      >
                        <ng-icon
                          name="lucideUsers"
                          class="text-muted-foreground shrink-0"
                          aria-hidden="true"
                        />
                        <span class="sr-only">Group</span>
                        <span class="truncate" [title]="g.name">{{ g.name }}</span>
                      </li>
                    }
                    @for (m of a.memberShares; track m.principalId) {
                      <li
                        class="bg-secondary inline-flex h-6 max-w-full items-center gap-1 rounded-full px-2.5 text-xs font-semibold"
                        [attr.data-person]="m.principalId"
                      >
                        <ng-icon
                          name="lucideUser"
                          class="text-muted-foreground shrink-0"
                          aria-hidden="true"
                        />
                        <span class="sr-only">Person</span>
                        <span class="truncate" [title]="m.name">{{ m.name }}</span>
                      </li>
                    }
                  </ul>
                }
                <p class="text-muted-foreground text-xs">
                  {{ sharing() }} Its owner and the company’s admins may edit it.
                </p>
              </section>
            </aside>
          </div>
        }
      </div>
    }

    <hlm-alert-dialog [state]="pauseOpen() ? 'open' : 'closed'" (closed)="pauseOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Pause {{ agent()?.name }}?</h2>
          <p hlmAlertDialogDescription>
            Its conversations start no turn and run no approved call until you resume it. People see
            that it is paused.
          </p>
        </hlm-alert-dialog-header>
        <label class="flex items-start gap-2 text-sm">
          <input
            type="checkbox"
            class="accent-primary mt-0.5 size-4 shrink-0"
            data-testid="stop-running"
            [checked]="stopRunning()"
            (change)="stopRunning.set($any($event.target).checked)"
          />
          <span>
            Also stop the turns running now
            <span class="text-muted-foreground block text-xs"
              >For an agent that misbehaves: its running turns are cancelled.</span
            >
          </span>
        </label>
        @if (actionError(); as message) {
          <div hlmAlert variant="destructive" data-testid="pause-error">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="busy()">Cancel</button>
          <button
            hlmAlertDialogAction
            data-testid="confirm-pause"
            [disabled]="busy()"
            (click)="pause()"
          >
            @if (busy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Pause agent
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>

    <hlm-alert-dialog [state]="deleteOpen() ? 'open' : 'closed'" (closed)="deleteOpen.set(false)">
      <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx">
        <hlm-alert-dialog-header>
          <h2 hlmAlertDialogTitle>Delete {{ agent()?.name }}?</h2>
          <p hlmAlertDialogDescription>
            Its conversations end and everyone’s settings of it go; their history stays. Its name
            and handle are free again. You can’t undo this.
          </p>
        </hlm-alert-dialog-header>
        @if (actionError(); as message) {
          <div hlmAlert variant="destructive" data-testid="delete-error">
            <ng-icon name="lucideCircleAlert" aria-hidden="true" />
            <p hlmAlertDescription>{{ message }}</p>
          </div>
        }
        <hlm-alert-dialog-footer>
          <button hlmAlertDialogCancel [disabled]="busy()">Cancel</button>
          <button
            hlmAlertDialogAction
            variant="destructive"
            data-testid="confirm-delete"
            [disabled]="busy()"
            (click)="remove()"
          >
            @if (busy()) {
              <hlm-spinner aria-hidden="true" />
            }
            Delete agent
          </button>
        </hlm-alert-dialog-footer>
      </hlm-alert-dialog-content>
    </hlm-alert-dialog>
  `,
})
export class AgentPage {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly notify = inject(Notify);
  protected readonly current = inject(CurrentCompany);
  protected readonly caller = inject(CallerPrincipal);

  readonly agentId = input.required<string>();

  /** A member who may work: a Viewer starts and edits nothing (ADR-0077). */
  protected readonly member = computed(() => mayWork(this.current.access()));
  protected readonly viewer = computed(() => isViewer(this.current.access()));

  protected readonly agentRes = resource({
    params: () => {
      const companyId = this.current.id();
      return companyId ? { companyId, agentId: this.agentId() } : undefined;
    },
    loader: ({ params }) => this.api.invoke(getAgent, params),
  });

  /** The agent as last read or as the last action answered it. */
  protected readonly agent = computed<Agent | undefined>(() =>
    this.agentRes.hasValue() ? this.agentRes.value() : undefined,
  );
  protected readonly notFound = computed(
    () => !this.agent() && toProblem(this.agentRes.error()).status === 404,
  );
  protected readonly usable = computed(() => {
    const a = this.agent();
    return !!a && canUseAgent(a);
  });
  protected readonly editable = computed(() => {
    const a = this.agent();
    return !!a && canEditAgent(a);
  });
  protected readonly runtimes = computed(() => runtimesText(this.agent()?.runtimes ?? []));
  protected readonly sharing = computed(() => {
    const a = this.agent();
    return a ? sharingText(a) : '';
  });
  protected readonly notUsable = computed(() => {
    const a = this.agent();
    return a ? notUsableText(a) : '';
  });

  /** Who acts and who pays for the reader, when they may use it (nothing is stored). */
  protected readonly readiness = resource({
    params: () => {
      const companyId = this.current.id();
      const a = this.agent();
      return companyId && a && canUseAgent(a) && this.member()
        ? { companyId, agentId: a.id, version: a.acceptedVersion, status: a.status }
        : undefined;
    },
    loader: ({ params }) =>
      this.api.invoke(getAgentReadiness, { companyId: params.companyId, agentId: params.agentId }),
  });
  protected readonly readinessError = computed(() =>
    problemMessage(this.readiness.error(), {
      AGENT_NOT_USABLE: 'This agent is not shared with you.',
    }),
  );

  /** The reader's accounts, to name the group a grant comes from. */
  private readonly accountsRes = resource({
    params: () => {
      const companyId = this.current.id();
      return companyId && this.member() && this.usable() ? companyId : undefined;
    },
    loader: ({ params: companyId }) => this.api.invoke(listMyAccounts, { companyId }),
  });
  protected readonly accounts = computed(() =>
    this.accountsRes.hasValue() ? (this.accountsRes.value()?.items ?? []) : [],
  );

  protected initialsOf(name: string): string {
    return initials(name);
  }

  /** After accepting, the agent and the readiness are read again: the version changed. */
  protected onAccepted(): void {
    this.agentRes.reload();
    this.notify.success('Version accepted', 'Your conversations use it from their next turn.');
  }

  // --- Pause, resume, delete ------------------------------------------------------------------

  protected readonly busy = signal(false);
  protected readonly actionError = signal<string | null>(null);
  protected readonly pauseOpen = signal(false);
  protected readonly stopRunning = signal(false);
  protected readonly deleteOpen = signal(false);

  protected askPause(): void {
    this.stopRunning.set(false);
    this.actionError.set(null);
    this.pauseOpen.set(true);
  }

  protected async pause(): Promise<void> {
    const companyId = this.current.id();
    const a = this.agent();
    if (!companyId || !a || this.busy()) return;
    this.busy.set(true);
    this.actionError.set(null);
    try {
      const paused = await this.api.invoke(pauseAgent, {
        companyId,
        agentId: a.id,
        body: { stopRunning: this.stopRunning() },
      });
      this.agentRes.set(paused);
      this.pauseOpen.set(false);
      this.notify.success(
        `${a.name} paused`,
        this.stopRunning()
          ? 'Its running turns were stopped; its conversations wait until you resume it.'
          : 'Turns already running finish; new ones wait until you resume it.',
      );
    } catch (error) {
      this.actionError.set(problemMessage(error, NOT_EDITABLE));
    } finally {
      this.busy.set(false);
    }
  }

  protected async resume(): Promise<void> {
    const companyId = this.current.id();
    const a = this.agent();
    if (!companyId || !a || this.busy()) return;
    this.busy.set(true);
    try {
      const resumed = await this.api.invoke(resumeAgent, { companyId, agentId: a.id });
      this.agentRes.set(resumed);
      this.notify.success(`${a.name} resumed`, 'Its waiting turns start again.');
    } catch (error) {
      this.notify.error(`Couldn't resume ${a.name}`, error, NOT_EDITABLE);
    } finally {
      this.busy.set(false);
    }
  }

  protected askDelete(): void {
    this.actionError.set(null);
    this.deleteOpen.set(true);
  }

  protected async remove(): Promise<void> {
    const companyId = this.current.id();
    const a = this.agent();
    if (!companyId || !a || this.busy()) return;
    this.busy.set(true);
    this.actionError.set(null);
    try {
      await this.api.invoke(deleteAgent, { companyId, agentId: a.id });
      this.deleteOpen.set(false);
      this.notify.success(`${a.name} deleted`);
      void this.router.navigate(['/c', companyId, 'agents']);
    } catch (error) {
      this.actionError.set(problemMessage(error, NOT_EDITABLE));
    } finally {
      this.busy.set(false);
    }
  }
}
