import type {
  ModelAccessUpdateRequest,
  ModelAccessView,
  ModelGateway,
  ModelKeyStatus,
  ModelSettings,
  ModelSettingsRequest,
} from '../../../api/generated/models';
import { parseThresholds } from '../../../shared/governance-labels';
import { formatDay, formatPeriod, formatUsd } from '../../../shared/labels';

/**
 * The words of Model access (plan §5): the company's gateway, the default budget, period and models,
 * and each principal's budget. Pure functions, so that every state of the API is tested as a table.
 */

/** Where the company's gateway stands, from `ModelSettings` alone. */
export type GatewayState = 'NOT_CONFIGURED' | 'UNTESTED' | 'CHANGED' | 'FAILED' | 'READY';

export function gatewayState(settings: ModelSettings): GatewayState {
  if (!settings.configured) return 'NOT_CONFIGURED';
  if (settings.relayEnabled) return 'READY';
  if (settings.lastError) return 'FAILED';
  return settings.testedAt ? 'CHANGED' : 'UNTESTED';
}

/**
 * The gateway's status card. A gateway that waits for its test is `warning`, a failed test is
 * destructive, a ready gateway quiet success; never orange (DESIGN.md, Scarce Orange): the card's
 * steps say what to do.
 */
export interface GatewayStatusView {
  readonly state: GatewayState;
  readonly tone: 'warning' | 'failed' | 'ready';
  /** The chip's word: "Ready", "Not set up", "Not tested", "Test failed". */
  readonly chip: string;
  readonly title: string;
  readonly text: string;
}

export function gatewayStatus(settings: ModelSettings): GatewayStatusView {
  const state = gatewayState(settings);
  switch (state) {
    case 'NOT_CONFIGURED':
      return {
        state,
        tone: 'warning',
        chip: 'Not set up',
        title: 'Model access is not set up',
        text: 'Until a gateway is set up and passes its test, every new conversation is refused: no budget can pay for it.',
      };
    case 'UNTESTED':
      return {
        state,
        tone: 'warning',
        chip: 'Not tested',
        title: 'Test the gateway',
        text: 'It is saved but not tested yet. Until a test passes, nothing is paid by a budget, and new conversations on a budget are refused.',
      };
    case 'CHANGED':
      return {
        state,
        tone: 'warning',
        chip: 'Not tested',
        title: 'Test the gateway again',
        text: 'The gateway or its admin key changed since its last test. Until a test passes, nothing is paid by a budget.',
      };
    case 'FAILED':
      return {
        state,
        tone: 'failed',
        chip: 'Test failed',
        title: 'The last test failed',
        text: failedText(settings.lastError ?? 'LiteLLM did not answer as expected'),
      };
    case 'READY':
      return {
        state,
        tone: 'ready',
        chip: 'Ready',
        title: GATEWAY_NAMES[settings.gateway],
        text: 'The gateway passed its test: turns paid by a budget may start.',
      };
  }
}

/** Why a test failed, and what that means. */
export function failedText(reason: string): string {
  return `${sentence(reason)} Nothing is paid by a budget until a test passes.`;
}

function sentence(text: string): string {
  const trimmed = text.trim();
  return /[.!?]$/.test(trimmed) ? trimmed : `${trimmed}.`;
}

/** The rail's line for the gateway: "Bundled · ready", "Your own · test failed", "Not set up". */
export function gatewayRailDetail(settings: ModelSettings): string {
  const state = gatewayState(settings);
  if (state === 'NOT_CONFIGURED') return 'Not set up';
  const which = settings.gateway === 'EXTERNAL' ? 'Your own' : 'Bundled';
  const how = state === 'READY' ? 'ready' : state === 'FAILED' ? 'test failed' : 'not tested';
  return `${which} · ${how}`;
}

/** One of the three steps a gateway that is not ready shows: choose, set up, test. */
export interface GatewayStep {
  readonly title: string;
  readonly detail: string;
  readonly state: 'done' | 'now' | 'todo' | 'failed';
}

/** The steps to model access, where the gateway stands; `defaults` names what a setup saves. */
export function gatewaySteps(
  state: GatewayState,
  gateway: ModelGateway,
  defaults: string,
): GatewayStep[] {
  const configured = state !== 'NOT_CONFIGURED';
  return [
    {
      title: 'Choose which LiteLLM',
      detail: `${GATEWAY_LABELS[gateway]}, below`,
      state: 'done',
    },
    {
      title: 'Set it up',
      detail: `Saves the gateway and the defaults of every key: ${defaults}`,
      state: configured ? 'done' : 'now',
    },
    {
      title: 'Test it',
      detail: 'Then every member and service account gets a key, and conversations may start',
      state: !configured ? 'todo' : state === 'FAILED' ? 'failed' : 'now',
    },
  ];
}

export const GATEWAY_LABELS: Record<ModelGateway, string> = {
  BUNDLED: 'Bundled',
  EXTERNAL: 'Your own LiteLLM',
};

/** The gateway as the status card names it. */
export const GATEWAY_NAMES: Record<ModelGateway, string> = {
  BUNDLED: 'Bundled LiteLLM',
  EXTERNAL: 'Your own LiteLLM',
};

export const GATEWAY_DESCRIPTIONS: Record<ModelGateway, string> = {
  BUNDLED: 'The LiteLLM that comes with this Tinfirm deployment. Nothing to enter.',
  EXTERNAL: 'Your company’s own LiteLLM, by its admin URL and a master or admin key.',
};

// --- The budget period ---------------------------------------------------------------------------

/** LiteLLM's units of a budget period. */
export type PeriodUnit = 'h' | 'd' | 'w' | 'mo';

export const PERIOD_UNITS: readonly { value: PeriodUnit; one: string; many: string }[] = [
  { value: 'h', one: 'hour', many: 'hours' },
  { value: 'd', one: 'day', many: 'days' },
  { value: 'w', one: 'week', many: 'weeks' },
  { value: 'mo', one: 'month', many: 'months' },
];

const PERIOD = /^([1-9][0-9]{0,3})(h|d|w|mo)$/;

/** `30d` → 30 days; null for what LiteLLM would not take. */
export function parsePeriod(value: string): { count: number; unit: PeriodUnit } | null {
  const match = PERIOD.exec(value.trim());
  return match ? { count: Number(match[1]), unit: match[2] as PeriodUnit } : null;
}

/** `30d` → "30 days", `1mo` → "month" (the shared words of a budget's period). */
export function periodPhrase(value: string): string {
  return formatPeriod(value);
}

/** "$50.00 per 30 days". */
export function budgetPhrase(budgetUsd: number, period: string): string {
  return `${formatUsd(budgetUsd)} per ${periodPhrase(period)}`;
}

/** "Every model the gateway serves", or the names. */
export function modelsPhrase(models: readonly string[]): string {
  return models.length === 0 ? 'Every model the gateway serves' : models.join(', ');
}

/** "$50", "$12.50": an amount as short as it reads, for a line that sums a setting up. */
export function shortUsd(amount: number): string {
  return Number.isInteger(amount) ? `$${amount.toLocaleString('en-US')}` : formatUsd(amount);
}

// --- The settings form ---------------------------------------------------------------------------

export interface SettingsForm {
  gateway: ModelGateway;
  adminUrl: string;
  /** Write-only: empty keeps the stored key. */
  adminKey: string;
  budget: string;
  periodCount: string;
  periodUnit: PeriodUnit;
  /** Only the models picked; off, every model the gateway serves. */
  modelsLimited: boolean;
  models: string[];
  /** The budget alerts' percentages as typed, "80, 100" (package 6, §5.3). */
  thresholds: string;
  /** The company's monthly alert amount as typed; empty for none. */
  monthlyAlert: string;
}

export function settingsForm(settings: ModelSettings): SettingsForm {
  const period = parsePeriod(settings.budgetPeriod) ?? { count: 30, unit: 'd' as const };
  return {
    gateway: settings.configured || settings.bundledAvailable ? settings.gateway : 'EXTERNAL',
    adminUrl: settings.adminUrl ?? '',
    adminKey: '',
    budget: String(settings.defaultBudgetUsd),
    periodCount: String(period.count),
    periodUnit: period.unit,
    modelsLimited: settings.defaultModels.length > 0,
    models: [...settings.defaultModels],
    thresholds: (settings.budgetAlertThresholds ?? [80, 100]).join(', '),
    monthlyAlert:
      settings.monthlyAlertUsd === null || settings.monthlyAlertUsd === undefined
        ? ''
        : String(settings.monthlyAlertUsd),
  };
}

/**
 * Whether the form must carry an admin key, as the server says (400 otherwise): an external gateway
 * entered for the first time, switched to from the bundled one, at a new URL, or without a stored key.
 */
export function adminKeyRequired(form: SettingsForm, settings: ModelSettings): boolean {
  if (form.gateway !== 'EXTERNAL') return false;
  return (
    !settings.configured ||
    settings.gateway !== 'EXTERNAL' ||
    !settings.adminKeySet ||
    (settings.adminUrl ?? '') !== form.adminUrl.trim()
  );
}

/** The form's problems, by field; empty when it may be sent. */
export function settingsErrors(
  form: SettingsForm,
  settings: ModelSettings,
): Partial<Record<SettingsField, string>> {
  const errors: Partial<Record<SettingsField, string>> = {};
  if (form.gateway === 'EXTERNAL') {
    const url = form.adminUrl.trim();
    if (!url) errors.adminUrl = 'Enter the admin URL of your LiteLLM.';
    else if (!/^https?:\/\/[^\s/]+\S*$/.test(url) || url.length > 500) {
      errors.adminUrl = 'Enter an https URL, such as https://litellm.example.com.';
    }
    if (adminKeyRequired(form, settings) && !form.adminKey.trim()) {
      errors.adminKey = 'Enter the master or admin key: it goes with the URL.';
    }
  }
  if (parseUsd(form.budget) === null) {
    errors.budget = 'Enter an amount from 0 to 1,000,000, such as 50 or 12.50.';
  }
  if (!/^[1-9][0-9]{0,3}$/.test(form.periodCount.trim())) {
    errors.period = 'Enter a whole number from 1 to 9999.';
  }
  if (form.modelsLimited && form.models.length === 0) {
    errors.models = 'Pick at least one model, or allow every model.';
  }
  if (parseThresholds(form.thresholds) === null) {
    errors.thresholds = 'Enter 1 to 3 different percentages from 1 to 100, such as 80, 100.';
  }
  if (form.monthlyAlert.trim() && parseAlertUsd(form.monthlyAlert) === null) {
    errors.monthlyAlert = 'Enter an amount in USD, such as 2000, or leave it empty.';
  }
  return errors;
}

/** The fields the settings form checks. */
export type SettingsField =
  'adminUrl' | 'adminKey' | 'budget' | 'period' | 'models' | 'thresholds' | 'monthlyAlert';

/** The company's monthly alert in USD as typed: 0 to 1 000 000 000, at most two decimals, or null. */
export function parseAlertUsd(value: string): number | null {
  const text = value.trim().replace(/^\$/, '').replace(/,/g, '');
  if (!/^\d+(\.\d{1,2})?$/.test(text)) return null;
  const amount = Number(text);
  return amount >= 0 && amount <= 1_000_000_000 ? amount : null;
}

/** A budget in USD as typed: a number from 0 to 1 000 000 with at most two decimals, or null. */
export function parseUsd(value: string): number | null {
  const text = value.trim().replace(/^\$/, '');
  if (!/^\d+(\.\d{1,2})?$/.test(text)) return null;
  const amount = Number(text);
  return amount >= 0 && amount <= 1_000_000 ? amount : null;
}

/**
 * The request a valid form sends. The alert thresholds and the company's monthly alert (package 6,
 * §5.3) go only when they differ from what is `stored`; an emptied monthly alert is removed with
 * `clearMonthlyAlert`. Without `stored`, they go whenever they are set.
 */
export function settingsRequest(form: SettingsForm, stored?: ModelSettings): ModelSettingsRequest {
  const request: ModelSettingsRequest = {
    gateway: form.gateway,
    defaultBudgetUsd: parseUsd(form.budget) ?? 0,
    budgetPeriod: `${Number(form.periodCount)}${form.periodUnit}`,
    defaultModels: form.modelsLimited ? [...form.models] : [],
  };
  const thresholds = parseThresholds(form.thresholds);
  const storedThresholds = stored?.budgetAlertThresholds ?? [80, 100];
  if (thresholds && (!stored || thresholds.join() !== storedThresholds.join())) {
    request.budgetAlertThresholds = thresholds;
  }
  const monthly = form.monthlyAlert.trim() ? parseAlertUsd(form.monthlyAlert) : null;
  const storedMonthly = stored?.monthlyAlertUsd ?? null;
  if (monthly !== null && monthly !== storedMonthly) request.monthlyAlertUsd = monthly;
  else if (!form.monthlyAlert.trim() && storedMonthly !== null) request.clearMonthlyAlert = true;
  if (form.gateway === 'EXTERNAL') {
    request.adminUrl = form.adminUrl.trim();
    if (form.adminKey.trim()) request.adminKey = form.adminKey.trim();
  }
  return request;
}

// --- What changed ---------------------------------------------------------------------------------

/** A setting of the form that may differ from what is stored. */
export type SettingsChange =
  | 'gateway'
  | 'adminUrl'
  | 'adminKey'
  | 'budget'
  | 'period'
  | 'models'
  | 'thresholds'
  | 'monthlyAlert';

/** Each change by the name the unsaved-changes bar gives it, in the page's order. */
export const CHANGE_NAMES: Record<SettingsChange, string> = {
  gateway: 'Gateway',
  adminUrl: 'Admin URL',
  adminKey: 'Admin key',
  budget: 'Default budget',
  period: 'Budget period',
  models: 'Default models',
  thresholds: 'Alert thresholds',
  monthlyAlert: 'Monthly alert',
};

/** The changes, and the problems, of the Gateway section; the rest are the Default budget's. */
export const GATEWAY_FIELDS: readonly (SettingsChange | SettingsField)[] = [
  'gateway',
  'adminUrl',
  'adminKey',
];

/** What the form changes from the stored settings, in the page's order. */
export function settingsChanges(form: SettingsForm, settings: ModelSettings): SettingsChange[] {
  const stored = settingsForm(settings);
  const external = form.gateway === 'EXTERNAL';
  const budget = parseUsd(form.budget);
  const period = `${Number(form.periodCount.trim())}${form.periodUnit}`;
  const sameModels =
    form.modelsLimited === stored.modelsLimited &&
    (!form.modelsLimited || [...form.models].sort().join() === [...stored.models].sort().join());
  const thresholds = parseThresholds(form.thresholds);
  const monthlyText = form.monthlyAlert.trim();
  const monthly = monthlyText ? parseAlertUsd(monthlyText) : null;
  const changed: Record<SettingsChange, boolean> = {
    gateway: form.gateway !== stored.gateway,
    adminUrl: external && form.adminUrl.trim() !== stored.adminUrl,
    adminKey: external && !!form.adminKey.trim(),
    budget:
      budget === null ? form.budget.trim() !== stored.budget : budget !== settings.defaultBudgetUsd,
    period: !/^[1-9][0-9]{0,3}$/.test(form.periodCount.trim()) || period !== settings.budgetPeriod,
    models: !sameModels,
    thresholds:
      thresholds === null
        ? form.thresholds.trim() !== stored.thresholds
        : thresholds.join() !== (settings.budgetAlertThresholds ?? [80, 100]).join(),
    monthlyAlert:
      monthlyText && monthly === null
        ? true
        : (monthly ?? null) !== (settings.monthlyAlertUsd ?? null),
  };
  return (Object.keys(CHANGE_NAMES) as SettingsChange[]).filter((key) => changed[key]);
}

/** What a changed setting was, beside its field ("was $50.00"); none before a first setup. */
export function settingsWas(
  changes: readonly SettingsChange[],
  settings: ModelSettings,
): Partial<Record<SettingsChange, string>> {
  if (!settings.configured) return {};
  const was: Partial<Record<SettingsChange, string>> = {};
  for (const change of changes) {
    switch (change) {
      case 'gateway':
        was.gateway = settings.gateway === 'EXTERNAL' ? 'your own LiteLLM' : 'Bundled';
        break;
      case 'budget':
        was.budget = formatUsd(settings.defaultBudgetUsd);
        break;
      case 'period':
        was.period = periodPhrase(settings.budgetPeriod);
        break;
      case 'models':
        was.models = settings.defaultModels.length
          ? settings.defaultModels.join(', ')
          : 'every model';
        break;
      case 'thresholds':
        was.thresholds = (settings.budgetAlertThresholds ?? [80, 100]).join(', ');
        break;
      case 'monthlyAlert':
        was.monthlyAlert =
          settings.monthlyAlertUsd === null || settings.monthlyAlertUsd === undefined
            ? 'no alert'
            : formatUsd(settings.monthlyAlertUsd);
        break;
    }
  }
  return was;
}

/**
 * The rail's line for the default budget, from the form as typed where it may be saved:
 * "$50 per 30 days · alerts at 80%, 100%".
 */
export function defaultsRailDetail(form: SettingsForm, settings: ModelSettings): string {
  const budget = parseUsd(form.budget) ?? settings.defaultBudgetUsd;
  const count = form.periodCount.trim();
  const period = /^[1-9][0-9]{0,3}$/.test(count)
    ? periodPhrase(`${Number(count)}${form.periodUnit}`)
    : periodPhrase(settings.budgetPeriod);
  const thresholds = parseThresholds(form.thresholds) ??
    settings.budgetAlertThresholds ?? [80, 100];
  return `${shortUsd(budget)} per ${period} · alerts at ${thresholds.map((t) => `${t}%`).join(', ')}`;
}

/** A principal whose key a change of the defaults may reach. */
export interface ReachedPrincipal {
  readonly name: string;
  /** A member's own values are "their own"; a service account's "its own". */
  readonly member: boolean;
  readonly access: ModelAccessView;
}

/** "A, B and C"; past `max` names, "A, B, C and 4 more". */
export function namesPhrase(names: readonly string[], max = 4): string {
  if (names.length <= 1) return names.join('');
  if (names.length > max) {
    return `${names.slice(0, max - 1).join(', ')} and ${names.length - max + 1} more`;
  }
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}

/**
 * Which keys saving the changed defaults reaches at once: those without their own budget for a new
 * budget, every key for a new period, those without their own models for new models. Null when it
 * reaches none, or while the defaults cannot be saved.
 */
export function reachPhrase(
  changes: readonly SettingsChange[],
  principals: readonly ReachedPrincipal[],
): string | null {
  const budget = changes.includes('budget');
  const period = changes.includes('period');
  const models = changes.includes('models');
  if (!budget && !period && !models) return null;
  const keyed = principals.filter((p) => !!p.access.keyStatus);
  const reaches = (p: ReachedPrincipal) =>
    (budget && !p.access.budgetOverridden) || period || (models && !p.access.modelsOverridden);
  const reached = keyed.filter(reaches);
  if (reached.length === 0) return null;
  const kept = keyed.filter((p) => !reaches(p));
  const keys = reached.length === 1 ? '1 key' : `${reached.length} keys`;
  let text = `Saving reaches ${keys} at once: ${namesPhrase(reached.map((p) => p.name))}.`;
  if (kept.length) {
    const keeps =
      kept.length === 1 ? (kept[0].member ? 'keeps their own' : 'keeps its own') : 'keep their own';
    text += ` ${namesPhrase(kept.map((p) => p.name))} ${keeps}.`;
  }
  return text;
}

/**
 * The models a picker offers: the gateway's, then those picked or stored that it no longer serves
 * (`served` false), so that nothing stored is dropped unseen.
 */
export function modelChoices(
  served: readonly string[],
  picked: readonly string[],
): { name: string; served: boolean }[] {
  const known = new Set(served);
  return [
    ...served.map((name) => ({ name, served: true })),
    ...picked.filter((name) => !known.has(name)).map((name) => ({ name, served: false })),
  ];
}

// --- A principal's budget ------------------------------------------------------------------------

/** A principal's key, in words; null when nothing needs saying. A spent budget is destructive. */
export function keyStatusPhrase(view: ModelAccessView): {
  tone: 'muted' | 'warning' | 'destructive';
  text: string;
} | null {
  const status: ModelKeyStatus | null | undefined = view.keyStatus;
  switch (status) {
    case 'OK':
      return null;
    case 'BUDGET_SPENT': {
      const day = formatDay(view.budgetResetsAt);
      return {
        tone: 'destructive',
        text: day ? `New turns refused until ${day}` : 'New turns refused',
      };
    }
    case 'BLOCKED':
      return { tone: 'muted', text: 'Blocked while suspended' };
    case 'ERROR':
      return {
        tone: 'destructive',
        text: view.lastError ? `Key error: ${view.lastError}` : 'Key error',
      };
    default:
      return {
        tone: 'muted',
        text: view.gatewayConfigured ? 'No key yet' : 'No key until the gateway passes its test',
      };
  }
}

/** How much of a budget is spent: below 80 % it is fine, from 80 % near its limit, then spent. */
export type BudgetState = 'ok' | 'near' | 'spent';

/** The share of a budget at which it is near its limit. */
export const NEAR_LIMIT = 0.8;

/** A principal's budget as its spend bar shows it. */
export interface BudgetMeter {
  readonly state: BudgetState;
  /** 0 to 100, the bar's width. */
  readonly percent: number;
  /** "$43.10 of $50.00", or "Nothing spent yet". */
  readonly amount: string;
  /** "86%", or "Spent". */
  readonly label: string;
  /** Under the bar: the reset, the limit, or what the key's status says. */
  readonly note: string;
  readonly noteTone: 'faint' | 'muted' | 'warning' | 'destructive';
  /** Whether the note says the key's status, rather than when the budget resets. */
  readonly keyNote: boolean;
}

/**
 * A budget's spend bar (`spentUsd` against `budgetUsd`): neutral below 80 %, warning from 80 %,
 * destructive once spent, when LiteLLM refuses new turns until the reset. `budgetUsd` overrides
 * the budget, for a dialog that shows the one being typed.
 */
export function budgetMeter(view: ModelAccessView, budgetUsd = view.budgetUsd): BudgetMeter {
  const spent = view.spentUsd ?? null;
  const ratio = spent === null ? 0 : budgetUsd > 0 ? spent / budgetUsd : 1;
  const state: BudgetState =
    view.keyStatus === 'BUDGET_SPENT' || (spent !== null && spent >= budgetUsd)
      ? 'spent'
      : ratio >= NEAR_LIMIT
        ? 'near'
        : 'ok';
  const percent = state === 'spent' ? 100 : Math.min(100, Math.round(ratio * 100));
  const day = formatDay(view.budgetResetsAt);
  const key = keyStatusPhrase(view);
  let note: string;
  let noteTone: BudgetMeter['noteTone'];
  if (key && view.keyStatus !== 'BUDGET_SPENT') {
    note = key.text;
    noteTone = key.tone;
  } else if (state === 'spent') {
    note = day ? `New turns refused until ${day}` : 'New turns refused';
    noteTone = 'destructive';
  } else if (state === 'near') {
    note = day ? `Near the limit · resets ${day}` : 'Near the limit';
    noteTone = 'warning';
  } else {
    note = day ? `Resets ${day}` : '';
    noteTone = 'faint';
  }
  return {
    state,
    percent,
    amount: spent === null ? 'Nothing spent yet' : `${formatUsd(spent)} of ${formatUsd(budgetUsd)}`,
    label: state === 'spent' ? 'Spent' : `${percent}%`,
    note,
    noteTone,
    keyNote: !!key,
  };
}

/** The budget dialog's choices: the company's default, or the principal's own. */
export interface BudgetForm {
  budgetOwn: boolean;
  budget: string;
  modelsOwn: boolean;
  models: string[];
}

export function budgetForm(view: ModelAccessView): BudgetForm {
  return {
    budgetOwn: view.budgetOverridden,
    budget: String(view.budgetUsd),
    modelsOwn: view.modelsOverridden,
    models: view.modelsOverridden ? [...view.models] : [],
  };
}

/**
 * What a PATCH must send to reach the form from the view; null when nothing changes. Back to the
 * default is `clear…`; an own value is sent when it is new or differs.
 */
export function budgetUpdate(
  view: ModelAccessView,
  form: BudgetForm,
): ModelAccessUpdateRequest | null {
  const update: ModelAccessUpdateRequest = {};
  if (form.budgetOwn) {
    const amount = parseUsd(form.budget);
    if (amount !== null && (!view.budgetOverridden || amount !== view.budgetUsd)) {
      update.budgetUsd = amount;
    }
  } else if (view.budgetOverridden) {
    update.clearBudget = true;
  }
  if (form.modelsOwn) {
    const same =
      view.modelsOverridden &&
      form.models.length === view.models.length &&
      form.models.every((m) => view.models.includes(m));
    if (!same) update.models = [...form.models];
  } else if (view.modelsOverridden) {
    update.clearModels = true;
  }
  return Object.keys(update).length ? update : null;
}
