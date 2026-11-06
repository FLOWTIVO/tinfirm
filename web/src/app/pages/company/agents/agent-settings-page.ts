import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  resource,
  signal,
  untracked,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideArrowLeft,
  lucideChevronDown,
  lucideCircleAlert,
  lucideCircleCheck,
  lucideInfo,
  lucideRefreshCw,
  lucideRotateCcw,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmAlertDialogImports } from '@spartan-ng/helm/alert-dialog';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { Api } from '../../../api/generated/api';
import {
  getAgent,
  getAgentReadiness,
  getMyAgentSettings,
  listGatewayModels,
  listMyAccounts,
  listSystems,
  listSystemTools,
  putMyAgentSettings,
  resetMyAgentSettings,
} from '../../../api/generated/functions';
import type { AgentSettings } from '../../../api/generated/models';
import { problemMessage, toProblem } from '../../../api/problem';
import { CallerPrincipal } from '../../../core/caller-principal';
import { loadAll } from '../../../core/company-data';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { AgentSettingsForm } from '../../../shared/agent-settings-form';
import {
  type OwnServerOption,
  settingsChanges,
  type SettingsDraft,
  settingsDraftOf,
  type SettingsErrorAt,
  type SettingsErrors,
  settingsFailure,
  settingsRequest,
} from '../../../shared/agent-settings-model';
import { ErrorState } from '../../../shared/error-state';
import { initials } from '../../../shared/forms';
import { ListSkeleton } from '../../../shared/list-skeleton';
import { MemoryNotesPanel, type MemoryOwner } from '../../../shared/memory-notes';
import { WhoActsWhoPays } from '../../../shared/who-acts-who-pays';
import { budgetLeft, fromReadiness } from '../../../shared/who-acts-who-pays-view';
import { NotFound } from '../../not-found';
import { UnsavedChangesBar } from '../settings/unsaved-changes-bar';
import { WideningCard } from './widening-card';

const GATEWAY_REASONS: Record<string, string> = {
  GATEWAY_NOT_CONFIGURED:
    'The company’s model gateway is not set up yet, so no model can be picked: only a subscription can pay.',
  GATEWAY_UNREACHABLE: 'Tinfirm could not reach the model gateway; try again later.',
};

/**
 * Settings per agent (plan §4.4), `agents/:agentId/settings`: the reader's narrowing of one agent.
 * From `lg`, the sheets of `agent-settings-form` on the left and, sticky on the right, what
 * this means for you (who acts, who pays and where it runs, as saved, read again after each save);
 * on a phone that is a summary line at the top that opens. Changes wait in the unsaved-changes bar
 * and are saved as a whole (`putMyAgentSettings`); "Back to defaults" asks first
 * (`resetMyAgentSettings`). A new version to accept shows the widening card at the top. Under the
 * form, the notes of the reader's memory of the agent (ADR-0063), read again after each save.
 */
@Component({
  selector: 'app-agent-settings-page',
  imports: [
    RouterLink,
    NgIcon,
    HlmAlertImports,
    HlmAlertDialogImports,
    HlmButton,
    HlmSpinner,
    AgentSettingsForm,
    ErrorState,
    ListSkeleton,
    MemoryNotesPanel,
    NotFound,
    UnsavedChangesBar,
    WhoActsWhoPays,
    WideningCard,
  ],
  providers: [
    provideIcons({
      lucideArrowLeft,
      lucideChevronDown,
      lucideCircleAlert,
      lucideCircleCheck,
      lucideInfo,
      lucideRefreshCw,
      lucideRotateCcw,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @let companyId = current.id();
    @if (notFound()) {
      <app-not-found />
    } @else {
      <div class="flex flex-col gap-5">
        <!-- Phone: the back link and "Back to defaults" share the first row. -->
        <div class="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-x-4 gap-y-2.5">
          <a
            hlmBtn
            variant="ghost"
            size="sm"
            class="col-start-1 row-start-1 -ms-3 max-w-full justify-self-start max-sm:h-11"
            [routerLink]="['/c', companyId, 'agents', agentId()]"
          >
            <ng-icon name="lucideArrowLeft" aria-hidden="true" />
            <span class="truncate">{{ agent()?.name ?? 'Agent' }}</span>
          </a>
          <div
            class="col-span-2 row-start-2 flex min-w-0 items-start gap-3 sm:col-span-1 sm:col-start-1 sm:gap-4"
          >
            @if (agent(); as a) {
              <span
                class="bg-primary text-primary-foreground flex size-10 shrink-0 items-center justify-center rounded-xl text-[0.9375rem] font-bold sm:size-12 sm:rounded-[14px] sm:text-[1.0625rem]"
                aria-hidden="true"
                >{{ initialsOf(a.name) }}</span
              >
            }
            <div class="min-w-0">
              <h1 class="text-xl font-bold tracking-tight text-balance sm:text-2xl">
                My settings{{ agent() ? ' for ' + agent()!.name : '' }}
              </h1>
              <p class="text-muted-foreground mt-1 text-sm text-pretty">
                Narrows {{ agent()?.name ?? 'the agent' }} for your own conversations. It never lets
                it do more than its owner allowed.
              </p>
            </div>
          </div>
          @if (saved(); as s) {
            <button
              hlmBtn
              variant="ghost"
              type="button"
              class="col-start-2 row-start-1 -me-3 max-sm:h-11 sm:row-start-2 sm:me-0"
              data-testid="reset-settings"
              [disabled]="busy() || !s.stored"
              (click)="resetOpen.set(true)"
            >
              <ng-icon name="lucideRotateCcw" aria-hidden="true" />
              Back to defaults
            </button>
          }
        </div>

        @if (loading()) {
          <app-list-skeleton [rows]="4" />
        } @else if (loadError(); as error) {
          <app-error-state
            [error]="error"
            heading="Couldn't load your settings"
            [messages]="notUsable"
            (retry)="retry()"
          />
        } @else if (agent() && draft() && saved()) {
          @let a = agent()!;
          @let s = saved()!;
          @if (s.widening && companyId) {
            <app-widening-card
              [companyId]="companyId"
              [agent]="a"
              [widening]="s.widening"
              (accepted)="onAccepted($event)"
            />
          }
          @if (acceptedVersion(); as version) {
            <p
              class="bg-card m-0 flex items-start gap-2 rounded-xl border px-4 py-3 text-sm"
              role="status"
              data-testid="accepted-note"
            >
              <ng-icon
                name="lucideCircleCheck"
                class="text-success mt-0.5 shrink-0"
                aria-hidden="true"
              />
              Version {{ version }} accepted. Your conversations use it from their next turn.
            </p>
          }
          @if (!s.stored) {
            <p
              class="bg-card text-muted-foreground m-0 flex items-start gap-2 rounded-xl border px-4 py-3 text-sm"
              data-testid="defaults-note"
            >
              <ng-icon name="lucideInfo" class="mt-0.5 shrink-0" aria-hidden="true" />
              Nothing saved yet: these are the defaults.
            </p>
          }
          @if (formError(); as message) {
            <div hlmAlert variant="destructive" data-testid="settings-error">
              <ng-icon name="lucideCircleAlert" aria-hidden="true" />
              <p hlmAlertDescription>{{ message }}</p>
            </div>
          }

          <div class="grid min-w-0 gap-5 lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start">
            <!-- First on a phone, as a summary that opens; the sticky column from lg. -->
            <aside
              class="min-w-0 lg:sticky lg:top-18 lg:col-start-2 lg:row-start-1"
              aria-label="What this means for you"
              data-testid="settings-who-acts"
            >
              <div class="bg-card rounded-xl border">
                <button
                  type="button"
                  class="focus-visible:ring-ring/50 flex min-h-15 w-full items-center gap-2.5 rounded-xl py-2 ps-3.5 pe-1 text-start outline-none focus-visible:ring-3 lg:hidden"
                  aria-controls="settings-who-body"
                  [attr.aria-expanded]="whoOpen()"
                  data-testid="who-toggle"
                  (click)="whoOpen.set(!whoOpen())"
                >
                  <ng-icon
                    name="lucideCircleCheck"
                    class="text-success shrink-0"
                    aria-hidden="true"
                  />
                  <span class="flex min-w-0 flex-1 flex-col leading-snug">
                    <span class="text-sm font-semibold">What this means for you</span>
                    @if (whoSummary(); as summary) {
                      <span class="text-muted-foreground line-clamp-2 text-[0.8125rem]">{{
                        summary
                      }}</span>
                    }
                    @if (changed()) {
                      <span class="text-muted-foreground flex items-center gap-1 text-xs">
                        <ng-icon name="lucideRefreshCw" size="12" aria-hidden="true" />
                        Read again after you save
                      </span>
                    }
                  </span>
                  <span
                    class="text-muted-foreground flex size-11 shrink-0 items-center justify-center"
                  >
                    <ng-icon
                      name="lucideChevronDown"
                      class="transition-transform motion-reduce:transition-none"
                      [class.rotate-180]="whoOpen()"
                      aria-hidden="true"
                    />
                  </span>
                </button>
                <div
                  id="settings-who-body"
                  class="flex-col gap-3 px-4 pt-3 pb-4 max-lg:border-t lg:flex lg:p-5 max-lg:[&_h2]:sr-only lg:[&_h2]:text-base lg:[&_h2]:font-bold"
                  [class.hidden]="!whoOpen()"
                  [class.flex]="whoOpen()"
                >
                  @if (readinessValue(); as r) {
                    <app-who-acts-who-pays
                      heading="What this means for you"
                      intro="Who acts and who pays as your saved settings stand."
                      [framed]="false"
                      [readiness]="r"
                      [access]="caller.modelAccess()"
                      [accounts]="accounts()"
                    />
                  } @else {
                    <h2 class="text-sm font-semibold">What this means for you</h2>
                    @if (readiness.error()) {
                      <p class="text-destructive text-sm" role="alert">{{ readinessError() }}</p>
                    } @else {
                      <app-list-skeleton [rows]="2" />
                    }
                  }
                  @if (changed()) {
                    <p
                      class="text-muted-foreground m-0 hidden items-center gap-1.5 text-xs lg:flex"
                      role="status"
                      data-testid="read-again"
                    >
                      <ng-icon name="lucideRefreshCw" size="14" aria-hidden="true" />
                      Read again after you save
                    </p>
                  }
                </div>
              </div>
            </aside>

            <form
              id="agent-settings"
              class="min-w-0 lg:col-start-1 lg:row-start-1"
              novalidate
              (submit)="save($event)"
              data-testid="settings-page-form"
            >
              <app-agent-settings-form
                [agent]="a"
                [widening]="s.widening ?? null"
                [draft]="draft()!"
                (draftChange)="draft.set($event)"
                [saved]="savedDraft()"
                [subscriptions]="caller.modelAccess()?.subscriptions ?? []"
                [allowedModels]="caller.modelAccess()?.models ?? []"
                [budgetDetail]="budgetDetail()"
                [budgetUnavailable]="budgetUnavailable()"
                [gatewayModels]="gatewayModels()"
                [modelsUnavailable]="modelsUnavailable()"
                [ownServers]="ownServers()"
                [accountsLink]="companyId ? ['/c', companyId, 'accounts'] : null"
                [errors]="errors()"
                [errorAt]="errorAt()"
                [disabled]="busy()"
              />
            </form>

            @if (companyId) {
              <app-memory-notes
                class="min-w-0 lg:col-start-1 lg:row-start-2"
                [companyId]="companyId"
                [owner]="memoryOwner()"
                [agent]="a"
                [revision]="revision()"
              />
            }
          </div>

          @if (changed()) {
            <app-unsaved-changes-bar
              form="agent-settings"
              saveTestId="save-settings"
              [changes]="changes()"
              [saving]="busy()"
              (discard)="discard()"
            />
          }
        }
      </div>

      <hlm-alert-dialog [state]="resetOpen() ? 'open' : 'closed'" (closed)="resetOpen.set(false)">
        <hlm-alert-dialog-content *hlmAlertDialogPortal="let ctx" data-testid="reset-dialog">
          <hlm-alert-dialog-header>
            <h2 hlmAlertDialogTitle>Back to the defaults?</h2>
            <p hlmAlertDialogDescription>
              Your model, effort, added instructions, default repositories and projects, every
              system and tool you narrowed and every skill you switched off go back to what
              {{ agent()?.name ?? 'the agent' }}’s owner set, from each conversation’s next turn.
              New conversations start with the default runtime and payer. The version you accepted
              stays.
            </p>
          </hlm-alert-dialog-header>
          <hlm-alert-dialog-footer>
            <button hlmAlertDialogCancel [disabled]="busy()">Cancel</button>
            <button
              hlmAlertDialogAction
              variant="destructive"
              data-testid="confirm-reset"
              [disabled]="busy()"
              (click)="reset()"
            >
              @if (busy()) {
                <hlm-spinner aria-hidden="true" />
              }
              Back to defaults
            </button>
          </hlm-alert-dialog-footer>
        </hlm-alert-dialog-content>
      </hlm-alert-dialog>
    }
  `,
})
export class AgentSettingsPage {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  protected readonly current = inject(CurrentCompany);
  protected readonly caller = inject(CallerPrincipal);

  readonly agentId = input.required<string>();

  protected readonly notUsable = {
    AGENT_NOT_USABLE:
      'This agent is not shared with you, so you keep no settings of it. Ask its owner to share it with you or your group.',
  };

  private readonly params = computed(() => {
    const companyId = this.current.id();
    return companyId ? { companyId, agentId: this.agentId() } : undefined;
  });

  protected readonly agentRes = resource({
    params: () => this.params(),
    loader: ({ params }) => this.api.invoke(getAgent, params),
  });
  private readonly settingsRes = resource({
    params: () => this.params(),
    loader: ({ params }) => this.api.invoke(getMyAgentSettings, params),
  });
  /**
   * Who acts and who pays, and the memory, as the saved settings stand; `revision` re-reads them
   * after a save.
   */
  protected readonly revision = signal(0);
  protected readonly memoryOwner = computed<MemoryOwner>(() => ({
    kind: 'person',
    agentId: this.agentId(),
  }));
  protected readonly readiness = resource({
    params: () => {
      const params = this.params();
      return params ? { ...params, revision: this.revision() } : undefined;
    },
    loader: ({ params }) =>
      this.api.invoke(getAgentReadiness, {
        companyId: params.companyId,
        agentId: params.agentId,
      }),
  });
  protected readonly readinessValue = computed(() =>
    this.readiness.hasValue() ? this.readiness.value() : undefined,
  );
  protected readonly readinessError = computed(() => problemMessage(this.readiness.error()));

  private readonly modelsRes = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(listGatewayModels, { companyId }),
  });
  protected readonly gatewayModels = computed(() =>
    this.modelsRes.hasValue() ? (this.modelsRes.value()?.models ?? []) : [],
  );
  private readonly gatewayProblem = computed(() => {
    const error = this.modelsRes.error();
    return error ? toProblem(error) : null;
  });
  /** Why no model can be picked: the gateway is not configured (409) or does not answer. */
  protected readonly modelsUnavailable = computed(() => {
    const problem = this.gatewayProblem();
    if (!problem) return null;
    return (
      GATEWAY_REASONS[problem.code ?? ''] ??
      `The gateway’s models could not be read: ${problemMessage(this.modelsRes.error())}`
    );
  });
  /** Without a gateway, a budget cannot pay at all; only a subscription can. */
  protected readonly budgetUnavailable = computed(() =>
    this.gatewayProblem()?.code === 'GATEWAY_NOT_CONFIGURED'
      ? 'Needs the company’s model gateway, which is not set up yet'
      : null,
  );
  protected readonly budgetDetail = computed(() => budgetLeft(this.caller.modelAccess()));

  /** The reader's own MCP servers, with the tools each serves. */
  private readonly ownServersRes = resource({
    params: () => this.current.id(),
    loader: async ({ params: companyId }): Promise<OwnServerOption[]> => {
      const systems = await loadAll((cursor) =>
        this.api.invoke(listSystems, { companyId, limit: 100, cursor }),
      );
      return Promise.all(
        systems
          .filter((s) => s.personal && s.kind === 'MCP')
          .map(async (system) => ({
            system,
            tools: await this.api
              .invoke(listSystemTools, { companyId, systemId: system.id })
              .then((list) => list.items)
              .catch(() => null),
          })),
      );
    },
  });
  protected readonly ownServers = computed(() =>
    this.ownServersRes.hasValue() ? (this.ownServersRes.value() ?? []) : [],
  );

  private readonly accountsRes = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(listMyAccounts, { companyId }),
  });
  protected readonly accounts = computed(() =>
    this.accountsRes.hasValue() ? (this.accountsRes.value()?.items ?? []) : [],
  );

  protected readonly agent = computed(() =>
    this.agentRes.hasValue() ? this.agentRes.value() : undefined,
  );
  /** The settings as saved: the baseline of the unsaved changes. */
  protected readonly saved = signal<AgentSettings | null>(null);
  protected readonly savedDraft = computed(() => {
    const saved = this.saved();
    return saved ? settingsDraftOf(saved) : null;
  });
  protected readonly draft = signal<SettingsDraft | null>(null);

  protected readonly errors = signal<SettingsErrors>({});
  protected readonly errorAt = signal<SettingsErrorAt | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly resetOpen = signal(false);
  /** The phone's "What this means for you", open. */
  protected readonly whoOpen = signal(false);
  /** The version accepted on this page, said until the page is left. */
  protected readonly acceptedVersion = signal<number | null>(null);

  protected readonly loading = computed(
    () =>
      (this.agentRes.isLoading() && !this.agent()) ||
      (this.settingsRes.isLoading() && !this.saved()),
  );
  protected readonly loadError = computed(() =>
    !this.agent() ? this.agentRes.error() : !this.saved() ? this.settingsRes.error() : undefined,
  );
  protected readonly notFound = computed(() => toProblem(this.agentRes.error()).status === 404);
  /** What the draft changes, by the names of the settings, for the unsaved-changes bar. */
  protected readonly changes = computed(() => {
    const draft = this.draft();
    const saved = this.savedDraft();
    const agent = this.agent();
    return draft && saved && agent ? settingsChanges(saved, draft, agent) : [];
  });
  protected readonly changed = computed(() => this.changes().length > 0);

  /** The phone's summary line: who pays, then who acts on each system. */
  protected readonly whoSummary = computed(() => {
    const readiness = this.readinessValue();
    if (!readiness) return '';
    const view = fromReadiness(readiness, {
      access: this.caller.modelAccess(),
      accounts: this.accounts(),
    });
    return [view.pays.text.split(':')[0], ...view.acts.map((line) => line.text)].join(' · ');
  });

  constructor() {
    effect(() => {
      const settings = this.settingsRes.hasValue() ? this.settingsRes.value() : undefined;
      if (!settings) return;
      untracked(() => this.adopt(settings));
    });
  }

  protected initialsOf(name: string): string {
    return initials(name);
  }

  /** New saved settings: the baseline, and the draft unless it holds unsaved changes. */
  private adopt(settings: AgentSettings, keepDraft = false): void {
    const draft = keepDraft && this.changed() ? this.draft() : settingsDraftOf(settings);
    this.saved.set(settings);
    this.draft.set(draft);
  }

  private clearErrors(): void {
    this.errors.set({});
    this.errorAt.set(null);
    this.formError.set(null);
  }

  protected retry(): void {
    this.agentRes.reload();
    this.settingsRes.reload();
  }

  protected onAccepted(settings: AgentSettings): void {
    this.adopt(settings, true);
    this.acceptedVersion.set(settings.acceptedVersion);
    this.agentRes.reload();
    this.revision.update((n) => n + 1);
  }

  protected discard(): void {
    const saved = this.saved();
    if (!saved) return;
    this.draft.set(settingsDraftOf(saved));
    this.clearErrors();
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    const params = this.params();
    const draft = this.draft();
    if (!params || !draft || this.busy() || !this.changed()) return;
    this.busy.set(true);
    this.clearErrors();
    try {
      const settings = await this.api.invoke(putMyAgentSettings, {
        ...params,
        body: settingsRequest(draft),
      });
      this.adopt(settings);
      this.revision.update((n) => n + 1);
      this.notify.success('Settings saved', 'Your conversations take them at their next turn.');
    } catch (error) {
      const failure = settingsFailure(error);
      this.errors.set(failure.errors);
      this.errorAt.set(failure.at);
      this.formError.set(failure.message);
    } finally {
      this.busy.set(false);
    }
  }

  protected async reset(): Promise<void> {
    const params = this.params();
    if (!params || this.busy()) return;
    this.busy.set(true);
    this.clearErrors();
    try {
      const settings = await this.api.invoke(resetMyAgentSettings, params);
      this.adopt(settings);
      this.revision.update((n) => n + 1);
      this.resetOpen.set(false);
      this.notify.success('Back to the defaults', 'The version you accepted stays.');
    } catch (error) {
      this.resetOpen.set(false);
      this.formError.set(problemMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}
