import {
  afterRenderEffect,
  ChangeDetectionStrategy,
  Component,
  computed,
  type ElementRef,
  inject,
  input,
  linkedSignal,
  resource,
  signal,
  viewChild,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { NgIcon, provideIcons } from '@ng-icons/core';
import {
  lucideCheck,
  lucideCircleAlert,
  lucideCircleCheck,
  lucideCircleDashed,
  lucideCircleDot,
  lucideCircleX,
  lucideKeyRound,
  lucideLock,
  lucidePlugZap,
  lucideReceipt,
  lucideUsers,
  lucideWallet,
} from '@ng-icons/lucide';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmNativeSelectImports } from '@spartan-ng/helm/native-select';
import { HlmSkeleton } from '@spartan-ng/helm/skeleton';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmSwitchImports } from '@spartan-ng/helm/switch';
import { Api } from '../../../api/generated/api';
import {
  getModelSettings,
  getCompanyKeyModelAccess,
  getPrincipalModelAccess,
  getSpend$Json,
  listGatewayModels,
  listMembers,
  listServiceIdentities,
  putModelSettings,
  testModelSettings,
} from '../../../api/generated/functions';
import type {
  Member,
  ModelAccessView,
  ModelGateway,
  ModelSettings,
  ServiceIdentity,
} from '../../../api/generated/models';
import { isStatus, problemMessage, toProblem } from '../../../api/problem';
import { loadAll, loadEach } from '../../../core/company-data';
import { CurrentCompany } from '../../../core/current-company';
import { Notify } from '../../../core/notify';
import { CopyButton } from '../../../shared/copy-button';
import { ErrorState } from '../../../shared/error-state';
import { formatUsd } from '../../../shared/labels';
import { PageHeader } from '../../../shared/page-header';
import { Segmented, type SegmentedOption } from '../../../shared/segmented';
import { Time } from '../../../shared/time';
import { SettingRow } from '../settings/setting-row';
import { UnsavedChangesBar } from '../settings/unsaved-changes-bar';
import { SpendView } from '../spend/spend-view';
import {
  budgetCounts,
  type BudgetRow,
  budgetsRailDetail,
  COMPANY_KEY_ROW,
  type ShownRow,
  shownRow,
} from './budget-rows';
import { MembersBudgets } from './members-budgets';
import {
  adminKeyRequired,
  budgetPhrase,
  CHANGE_NAMES,
  defaultsRailDetail,
  failedText,
  GATEWAY_DESCRIPTIONS,
  GATEWAY_FIELDS,
  GATEWAY_LABELS,
  VENDOR_LABELS,
  GATEWAY_NAMES,
  gatewayRailDetail,
  gatewayStatus,
  gatewaySteps,
  modelChoices,
  parseAlertUsd,
  parseUsd,
  PERIOD_UNITS,
  periodPhrase,
  type PeriodUnit,
  reachPhrase,
  type SettingsField,
  type SettingsForm,
  settingsChanges,
  settingsErrors,
  settingsForm,
  settingsRequest,
  settingsWas,
} from './model-access-view';
import {
  type BudgetDefaults,
  type BudgetPrincipal,
  PrincipalBudgetDialog,
} from './principal-budget-dialog';

const FORBIDDEN = { 'status:403': 'Only owners and admins set up model access.' };

/** How many principals' model access the page reads at a time. */
const READS_AT_A_TIME = 6;

/** A section of the page: the page shows one at a time. */
type SectionId = 'gateway' | 'defaults' | 'budgets' | 'spend';

const SECTIONS: readonly { id: SectionId; label: string; icon: string }[] = [
  { id: 'gateway', label: 'Gateway', icon: 'lucidePlugZap' },
  { id: 'defaults', label: 'Default budget', icon: 'lucideWallet' },
  { id: 'budgets', label: 'Members’ budgets', icon: 'lucideUsers' },
  { id: 'spend', label: 'Spend', icon: 'lucideReceipt' },
];

/** The section a `tab` query parameter opens; the gateway without one. */
function sectionOf(tab: string | undefined): SectionId {
  return SECTIONS.find((s) => s.id === tab)?.id ?? 'gateway';
}

/** The gateway's models, or why there are none to pick from yet. */
interface Served {
  readonly models: readonly string[];
  readonly unavailable: string | null;
}

/** A service account with its model access, or the reason it could not be read. */
interface IdentityBudget {
  readonly identity: ServiceIdentity;
  readonly access: ModelAccessView | null;
  readonly error: string | null;
}

/** A member with their model access, or the reason it could not be read. */
interface MemberBudget {
  readonly member: Member;
  readonly access: ModelAccessView | null;
  readonly error: string | null;
}

/** The server's own words for a 400 without field errors (the model settings name the field in `detail`). */
function saveMessage(error: unknown): string {
  const problem = toProblem(error);
  if (problem.code === 'VALIDATION_FAILED' && problem.detail) return problem.detail;
  return problemMessage(error, FORBIDDEN);
}

/**
 * Model access (plan §5), the company admin's, one section at a time: the Gateway (the bundled
 * LiteLLM or the company's own, its status and its test), the Default budget (the budget, period
 * and models of every key, and the budget alerts), Members' budgets (each member's and service
 * identity's, the default or their own) and the Spend. The rail at the left says how each stands,
 * and marks one with unsaved changes; on a phone it is a row of pills. One unsaved-changes bar
 * saves the gateway, the defaults and the alerts together. Until the gateway is set up and tested,
 * every new conversation is refused, and the Gateway says so in three steps. `?tab=` names the
 * section (`?tab=spend`), so that a link opens it. Members' budgets lists the company's own key
 * too, which pays for Tinfirm's own requests (ADR-0065); its model is chosen where it is used
 * (Settings › Memory).
 */
@Component({
  selector: 'app-model-access',
  imports: [
    NgIcon,
    NgTemplateOutlet,
    RouterLink,
    HlmAlertImports,
    HlmButton,
    HlmInput,
    HlmNativeSelectImports,
    HlmSkeleton,
    HlmSpinner,
    HlmSwitchImports,
    CopyButton,
    ErrorState,
    MembersBudgets,
    PageHeader,
    PrincipalBudgetDialog,
    Segmented,
    SettingRow,
    SpendView,
    Time,
    UnsavedChangesBar,
  ],
  providers: [
    provideIcons({
      lucideCheck,
      lucideCircleAlert,
      lucideCircleCheck,
      lucideCircleDashed,
      lucideCircleDot,
      lucideCircleX,
      lucideKeyRound,
      lucideLock,
      lucidePlugZap,
      lucideReceipt,
      lucideUsers,
      lucideWallet,
    }),
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './model-access.html',
})
export class ModelAccess {
  private readonly api = inject(Api);
  private readonly notify = inject(Notify);
  private readonly current = inject(CurrentCompany);
  private readonly router = inject(Router);

  /** From the `tab` query parameter: the section open. */
  readonly tab = input<string | undefined>();
  protected readonly section = computed(() => sectionOf(this.tab()));
  /** The spend is read once its section is first opened, and kept while the page is. */
  protected readonly spendOpened = linkedSignal<SectionId, boolean>({
    source: this.section,
    computation: (section, previous) => section === 'spend' || (previous?.value ?? false),
  });

  protected readonly companyId = this.current.id;
  protected readonly periodUnits = PERIOD_UNITS;
  protected readonly gatewayDescriptions = GATEWAY_DESCRIPTIONS;

  protected readonly settings = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(getModelSettings, { companyId }),
  });
  protected readonly loaded = computed<ModelSettings | null>(() =>
    this.settings.hasValue() ? (this.settings.value() ?? null) : null,
  );
  protected readonly status = computed(() => {
    const settings = this.loaded();
    return settings ? gatewayStatus(settings) : null;
  });

  /**
   * The models are asked again when a test or a save changes whether the gateway passed its test;
   * a computed of its own, so that a save that changes nothing else asks nothing.
   */
  private readonly servedKey = computed(() => {
    const companyId = this.current.id();
    const settings = this.loaded();
    return companyId && settings
      ? `${companyId}|${settings.relayEnabled}|${settings.testedAt ?? ''}`
      : undefined;
  });
  protected readonly served = resource({
    params: () => this.servedKey(),
    loader: async ({ params }): Promise<Served> => {
      const companyId = params.split('|')[0];
      try {
        const list = await this.api.invoke(listGatewayModels, { companyId });
        return { models: list.models, unavailable: null };
      } catch (error) {
        if (isStatus(error, 409)) {
          return {
            models: [],
            unavailable: 'The gateway lists its models once it passes its test.',
          };
        }
        return { models: [], unavailable: problemMessage(error) };
      }
    },
  });
  protected readonly servedModels = computed(() =>
    this.served.hasValue() ? (this.served.value()?.models ?? []) : [],
  );
  protected readonly servedNote = computed(() => {
    // A gateway whose team makes the keys lists no models of its own (ADR-0094).
    if (this.loaded()?.gateway === 'PROVIDED')
      return 'Each key serves the models its gateway team allows.';
    return this.served.hasValue() ? (this.served.value()?.unavailable ?? null) : null;
  });

  /** What the company spent this calendar month (UTC), as the monthly alert counts it. */
  protected readonly monthSpend = resource({
    params: () => this.current.id(),
    loader: async ({ params: companyId }) =>
      (await this.api.invoke(getSpend$Json, { companyId })).totals.costUsd,
  });
  private readonly spentThisMonth = computed(() =>
    this.monthSpend.hasValue() ? (this.monthSpend.value() ?? null) : null,
  );

  // --- The form: the gateway, the defaults and the alerts -----------------------------------------

  /**
   * Follows the stored settings. A read that stores the same (a test reads them again) keeps what
   * was typed; a save and a discard set the form themselves.
   */
  protected readonly form = linkedSignal<ModelSettings | null, SettingsForm | null>({
    source: this.loaded,
    computation: (settings, previous) => {
      if (!settings) return null;
      const fresh = settingsForm(settings);
      const before = previous?.source ? JSON.stringify(settingsForm(previous.source)) : null;
      return previous?.value && before === JSON.stringify(fresh) ? previous.value : fresh;
    },
  });
  protected readonly submitted = signal(false);
  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly gatewayOptions = computed<SegmentedOption[]>(() => {
    const bundled = this.loaded()?.bundledAvailable ?? true;
    return [
      {
        value: 'BUNDLED',
        label: GATEWAY_LABELS.BUNDLED,
        disabled: !bundled,
        title: bundled ? undefined : 'This deployment bundles no model gateway.',
      },
      { value: 'EXTERNAL', label: GATEWAY_LABELS.EXTERNAL },
      { value: 'PROVIDED', label: GATEWAY_LABELS.PROVIDED },
    ];
  });

  protected readonly vendorOptions: SegmentedOption[] = [
    { value: 'LITELLM', label: VENDOR_LABELS.LITELLM },
    { value: 'GENERIC', label: VENDOR_LABELS.GENERIC },
  ];

  /** What the form changes, in the page's order. */
  protected readonly changes = computed(() => {
    const settings = this.loaded();
    const form = this.form();
    return settings && form ? settingsChanges(form, settings) : [];
  });
  protected readonly changeNames = computed(() => this.changes().map((c) => CHANGE_NAMES[c]));
  protected readonly gatewayChanged = computed(() =>
    this.changes().some((c) => GATEWAY_FIELDS.includes(c)),
  );
  protected readonly defaultsChanged = computed(() =>
    this.changes().some((c) => !GATEWAY_FIELDS.includes(c)),
  );
  /** "was …" beside each changed setting. */
  protected readonly was = computed(() => {
    const settings = this.loaded();
    return settings ? settingsWas(this.changes(), settings) : {};
  });

  protected readonly keyRequired = computed(() => {
    const settings = this.loaded();
    const form = this.form();
    return !!settings && !!form && adminKeyRequired(form, settings);
  });
  private readonly allErrors = computed<Partial<Record<SettingsField, string>>>(() => {
    const settings = this.loaded();
    const form = this.form();
    return settings && form ? settingsErrors(form, settings) : {};
  });
  /**
   * The problems shown: a default or an alert as soon as it is typed, the gateway's URL and key
   * once a save was tried (switching to your own LiteLLM leaves them empty at first).
   */
  protected readonly errors = computed(() => {
    const errors = { ...this.allErrors() };
    if (!this.submitted()) {
      delete errors.adminUrl;
      delete errors.adminKey;
      delete errors.modelUrl;
    }
    return errors;
  });
  protected readonly blocked = computed(() =>
    Object.keys(this.errors()).length ? 'Fix the marked fields first.' : null,
  );
  protected readonly choices = computed(() =>
    modelChoices(this.servedModels(), this.form()?.models ?? []),
  );

  /** The default budget's period as typed, else as stored: "30 days". */
  protected readonly periodWords = computed(() => {
    const form = this.form();
    const settings = this.loaded();
    if (form && /^[1-9][0-9]{0,3}$/.test(form.periodCount.trim())) {
      return periodPhrase(`${Number(form.periodCount.trim())}${form.periodUnit}`);
    }
    return periodPhrase(settings?.budgetPeriod ?? '30d');
  });

  /** "This month so far", beside a monthly alert that is set. */
  protected readonly monthSoFar = computed(() => {
    const spent = this.spentThisMonth();
    const monthly = this.form()?.monthlyAlert.trim() ?? '';
    return spent !== null && monthly && parseAlertUsd(monthly) !== null ? formatUsd(spent) : null;
  });

  protected patch(change: Partial<SettingsForm>): void {
    this.form.update((form) => (form ? { ...form, ...change } : form));
  }

  protected setGateway(value: string): void {
    if (value === 'BUNDLED' || value === 'EXTERNAL' || value === 'PROVIDED') {
      this.patch({ gateway: value as ModelGateway });
    }
  }

  protected setVendor(value: string): void {
    if (value === 'LITELLM' || value === 'GENERIC') this.patch({ vendor: value });
  }

  protected setUnit(value: string | null | undefined): void {
    const unit = PERIOD_UNITS.find((u) => u.value === value)?.value;
    if (unit) this.patch({ periodUnit: unit as PeriodUnit });
  }

  protected toggleModel(name: string, checked: boolean): void {
    this.form.update((form) =>
      form
        ? {
            ...form,
            models: checked
              ? [...form.models.filter((m) => m !== name), name]
              : form.models.filter((m) => m !== name),
          }
        : form,
    );
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    const companyId = this.current.id();
    const settings = this.loaded();
    const form = this.form();
    if (!companyId || !settings || !form || this.saving()) return;
    this.submitted.set(true);
    const problem = Object.keys(settingsErrors(form, settings))[0] as SettingsField | undefined;
    if (problem) {
      this.openProblem(GATEWAY_FIELDS.includes(problem) ? 'gateway' : 'defaults');
      return;
    }
    if (settings.configured && this.changes().length === 0) return;
    this.saving.set(true);
    this.saveError.set(null);
    try {
      const saved = await this.api.invoke(putModelSettings, {
        companyId,
        body: settingsRequest(form, settings),
      });
      this.settings.set(saved);
      this.form.set(settingsForm(saved));
      this.submitted.set(false);
      this.testResult.set(null);
      this.notify.success(
        'Model access saved',
        saved.relayEnabled
          ? 'Every key without its own values follows the new defaults.'
          : 'Test the gateway: nothing is paid by a budget until a test passes.',
      );
      this.members.reload();
      this.identities.reload();
      this.companyKey.reload();
    } catch (error) {
      this.saveError.set(saveMessage(error));
    } finally {
      this.saving.set(false);
    }
  }

  protected discard(): void {
    const settings = this.loaded();
    if (settings) this.form.set(settingsForm(settings));
    this.submitted.set(false);
    this.saveError.set(null);
  }

  /** Opens the section with a problem a save found, and brings its first marked field to focus. */
  private openProblem(section: SectionId): void {
    if (this.section() !== section) this.openSection(section);
    setTimeout(() =>
      document
        .querySelector<HTMLElement>(`[data-panel="${section}"] [aria-invalid="true"]`)
        ?.focus(),
    );
  }

  private openSection(section: SectionId): void {
    void this.router.navigate([], {
      queryParams: { tab: section === 'gateway' ? null : section },
      queryParamsHandling: 'merge',
    });
  }

  // --- The gateway's status and its test ----------------------------------------------------------

  protected readonly testing = signal(false);
  /** The last test of this visit, which the status card says in its own words. */
  protected readonly testResult = signal<{ ok: boolean; text: string } | null>(null);

  /** The status card's sentence: the last test's result where it is the news, else the state's. */
  protected readonly statusText = computed(() => {
    const status = this.status();
    const result = this.testResult();
    if (!status) return '';
    if (!result) return status.text;
    if (result.ok) return status.state === 'READY' ? result.text : status.text;
    return status.state === 'FAILED' ? failedText(result.text) : result.text;
  });

  /** The steps to model access while the gateway is not ready; what a setup saves, as typed. */
  protected readonly steps = computed(() => {
    const status = this.status();
    const settings = this.loaded();
    const form = this.form();
    if (!status || !settings || !form || status.state === 'READY') return [];
    const budget = parseUsd(form.budget) ?? settings.defaultBudgetUsd;
    const models =
      form.modelsLimited && form.models.length ? form.models.join(', ') : 'every model';
    return gatewaySteps(
      status.state,
      form.gateway,
      `${formatUsd(budget)} per ${this.periodWords()}, ${models}`,
    );
  });

  protected readonly testNote = computed(() => {
    if (this.gatewayChanged()) return 'Save your changes, then test them.';
    if (!this.loaded()?.configured) return 'Set up the gateway first, then test it.';
    return this.loaded()?.gateway === 'PROVIDED'
      ? 'The test reaches the gateway’s model URL; each key is checked when it is entered.'
      : 'The test reaches the gateway’s admin API and lists its models.';
  });

  protected async test(): Promise<void> {
    const companyId = this.current.id();
    if (!companyId || this.testing()) return;
    this.testing.set(true);
    this.testResult.set(null);
    try {
      const result = await this.api.invoke(testModelSettings, { companyId });
      this.testResult.set({
        ok: result.ok,
        text: result.ok
          ? this.loaded()?.gateway === 'PROVIDED'
            ? 'The gateway answered. Turns may start for whoever has a key entered.'
            : `The gateway answered and serves ${result.models.length} ${result.models.length === 1 ? 'model' : 'models'}. Turns paid by a budget may start.`
          : 'The gateway answered, but not as expected.',
      });
    } catch (error) {
      this.testResult.set({ ok: false, text: problemMessage(error, FORBIDDEN) });
    } finally {
      this.testing.set(false);
      this.settings.reload();
      this.members.reload();
      this.identities.reload();
      this.companyKey.reload();
    }
  }

  // --- Budgets ---------------------------------------------------------------------------------

  protected readonly defaults = computed<BudgetDefaults>(() => {
    const settings = this.loaded();
    return {
      budgetUsd: settings?.defaultBudgetUsd ?? 0,
      period: settings?.budgetPeriod ?? '30d',
      models: settings?.defaultModels ?? [],
    };
  });
  protected readonly defaultBudget = computed(() =>
    budgetPhrase(this.defaults().budgetUsd, this.defaults().period),
  );

  /** The members that have a principal in the company, each with their model access. */
  protected readonly members = resource({
    params: () => this.current.id(),
    loader: async ({ params: companyId }): Promise<MemberBudget[]> => {
      const members = await loadAll((cursor) =>
        this.api.invoke(listMembers, { companyId, limit: 100, cursor }),
      );
      const withPrincipal = members.filter((member) => !!member.principalId);
      return loadEach(withPrincipal, READS_AT_A_TIME, async (member) => {
        try {
          const access = await this.api.invoke(getPrincipalModelAccess, {
            companyId,
            principalId: member.principalId!,
          });
          return { member, access, error: null };
        } catch (error) {
          return { member, access: null, error: problemMessage(error) };
        }
      });
    },
  });

  protected readonly identities = resource({
    params: () => this.current.id(),
    loader: async ({ params: companyId }): Promise<IdentityBudget[]> => {
      const identities = await loadAll((cursor) =>
        this.api.invoke(listServiceIdentities, { companyId, limit: 100, cursor }),
      );
      return loadEach(identities, READS_AT_A_TIME, async (identity) => {
        try {
          const access = await this.api.invoke(getPrincipalModelAccess, {
            companyId,
            principalId: identity.id,
          });
          return { identity, access, error: null };
        } catch (error) {
          return { identity, access: null, error: problemMessage(error) };
        }
      });
    },
  });

  /** The company's own key (ADR-0065): no principal's; it pays for Tinfirm's own requests. */
  protected readonly companyKey = resource({
    params: () => this.current.id(),
    loader: ({ params: companyId }) => this.api.invoke(getCompanyKeyModelAccess, { companyId }),
  });

  /** Every member's and service account's budget, and the company key's, in one list. */
  protected readonly budgetRows = computed<ShownRow[]>(() => {
    const members = this.members.hasValue() ? (this.members.value() ?? []) : [];
    const identities = this.identities.hasValue() ? (this.identities.value() ?? []) : [];
    const companyKey = this.companyKey.hasValue() ? (this.companyKey.value() ?? null) : null;
    const companyError = this.companyKey.error();
    return [
      ...members.map((m) =>
        shownRow({
          principalId: m.member.principalId!,
          kind: 'member',
          name: m.member.displayName,
          email: m.member.email,
          role: m.member.role,
          suspended: m.member.suspended,
          access: m.access,
          error: m.error,
        }),
      ),
      ...identities.map((i) =>
        shownRow({
          principalId: i.identity.id,
          kind: 'identity',
          name: i.identity.name,
          suspended: i.identity.status === 'SUSPENDED',
          access: i.access,
          error: i.error,
        }),
      ),
      ...(companyKey || companyError
        ? [
            shownRow({
              principalId: COMPANY_KEY_ROW,
              kind: 'company',
              name: 'Company key',
              suspended: false,
              access: companyKey,
              error: companyError ? problemMessage(companyError) : null,
            }),
          ]
        : []),
    ];
  });
  protected readonly budgetsLoading = computed(
    () =>
      (this.members.isLoading() && !this.members.hasValue()) ||
      (this.identities.isLoading() && !this.identities.hasValue()) ||
      (this.companyKey.isLoading() && !this.companyKey.hasValue()),
  );
  protected readonly noIdentities = computed(
    () => this.identities.hasValue() && (this.identities.value() ?? []).length === 0,
  );

  /** Which keys saving the changed defaults reaches, from the principals as read. */
  protected readonly reach = computed(() => {
    const errors = this.allErrors();
    if (errors.budget || errors.period || errors.models) return null;
    return reachPhrase(
      this.changes(),
      this.budgetRows().flatMap((r) =>
        r.row.access
          ? [{ name: r.row.name, member: r.row.kind === 'member', access: r.row.access }]
          : [],
      ),
    );
  });

  protected readonly budgetOpen = signal(false);
  protected readonly budgetPrincipal = signal<BudgetPrincipal | null>(null);
  protected readonly budgetView = signal<ModelAccessView | null>(null);

  protected editBudget(row: BudgetRow): void {
    if (!row.access) return;
    this.budgetPrincipal.set(
      row.kind === 'company'
        ? { id: row.principalId, name: 'the company key', company: true }
        : { id: row.principalId, name: row.name, member: row.kind === 'member' },
    );
    this.budgetView.set(row.access);
    this.budgetOpen.set(true);
  }

  protected onBudgetSaved(access: ModelAccessView): void {
    // A key saved or removed keeps the dialog open, on the view the server answered.
    if (
      this.budgetOpen() &&
      (this.budgetView()?.principalId ?? null) === (access.principalId ?? null)
    ) {
      this.budgetView.set(access);
    }
    if (!access.principalId) {
      this.companyKey.set(access);
      return;
    }
    this.members.update((rows) =>
      rows?.map((row) =>
        row.member.principalId === access.principalId ? { ...row, access } : row,
      ),
    );
    this.identities.update((rows) =>
      rows?.map((row) => (row.identity.id === access.principalId ? { ...row, access } : row)),
    );
  }

  // --- The rail ----------------------------------------------------------------------------------

  protected readonly railItems = computed(() => {
    const settings = this.loaded();
    const form = this.form();
    const status = this.status();
    const spent = this.spentThisMonth();
    const loadedBudgets = this.members.hasValue() && this.identities.hasValue();
    const details: Record<SectionId, string> = {
      gateway: settings ? gatewayRailDetail(settings) : '',
      defaults: settings && form ? defaultsRailDetail(form, settings) : '',
      budgets: !status
        ? ''
        : status.state !== 'READY'
          ? 'No keys until a test passes'
          : loadedBudgets
            ? budgetsRailDetail(budgetCounts(this.budgetRows()))
            : '',
      spend:
        spent === null
          ? ''
          : spent > 0
            ? `${formatUsd(spent)} this month`
            : 'Nothing spent this month',
    };
    const dot =
      status?.tone === 'ready'
        ? 'bg-success'
        : status?.tone === 'failed'
          ? 'bg-destructive'
          : 'bg-warning';
    return SECTIONS.map((s) => ({
      ...s,
      active: s.id === this.section(),
      detail: details[s.id],
      dot: s.id === 'gateway' && status ? dot : null,
      unsaved:
        (s.id === 'gateway' && this.gatewayChanged()) ||
        (s.id === 'defaults' && this.defaultsChanged()),
    }));
  });

  /** The setup bar's line: what "Set up model access" saves. */
  protected readonly setupSummary = computed(() => {
    const form = this.form();
    const settings = this.loaded();
    if (!form || !settings) return '';
    const budget = parseUsd(form.budget) ?? settings.defaultBudgetUsd;
    return `${GATEWAY_NAMES[form.gateway]} · defaults ${formatUsd(budget)} per ${this.periodWords()}`;
  });

  private readonly railNav = viewChild.required<ElementRef<HTMLElement>>('railNav');

  constructor() {
    // On a phone the rail is a row of pills: the open section's is kept in view.
    afterRenderEffect(() => {
      this.railItems();
      const nav = this.railNav().nativeElement;
      const open = nav.querySelector<HTMLElement>('[aria-current=page]');
      if (!open || nav.scrollWidth <= nav.clientWidth) return;
      const row = nav.getBoundingClientRect();
      const pill = open.getBoundingClientRect();
      if (pill.left < row.left || pill.right > row.right) {
        nav.scrollLeft += pill.left - row.left - 16;
      }
    });
  }
}
