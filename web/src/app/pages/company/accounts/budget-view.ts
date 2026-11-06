import type { ModelAccessView } from '../../../api/generated/models';
import { formatDay, formatPeriod, formatUsd } from '../../../shared/labels';

/**
 * How Accounts › Budget reads (plan §5), from `getMyModelAccess`: the budget, what is left and when
 * it resets, its state (the gateway not configured, `BUDGET_SPENT` until its reset, `BLOCKED`,
 * `ERROR`, a key not created yet), and the models the key may use. Never the key itself.
 */

/** A notice above the numbers: neutral, since what it waits on is not the reader (DESIGN.md). */
export interface BudgetNotice {
  readonly key: 'unconfigured' | 'pending' | 'spent' | 'blocked' | 'error';
  readonly title: string;
  readonly body: string;
}

export interface BudgetView {
  readonly notice: BudgetNotice | null;
  /** Whether the numbers mean anything: not before the gateway is set up. */
  readonly numbers: boolean;
  readonly left: string;
  readonly budget: string;
  readonly spent: string;
  /** Spent as a share of the budget, 0 to 100. */
  readonly percent: number;
  /** "per 30 days". */
  readonly period: string;
  /** "Resets 1 Nov", or null while unknown. */
  readonly resets: string | null;
  /** "The company’s default" or "Set for you by an admin". */
  readonly whose: string;
  /** The models the key may use, or null for every model the gateway serves. */
  readonly models: readonly string[] | null;
  readonly modelsWhose: string;
}

export function budgetView(access: ModelAccessView): BudgetView {
  const spent = access.spentUsd ?? 0;
  const resetsDay = formatDay(access.budgetResetsAt);
  const percent =
    access.budgetUsd > 0 ? Math.min(100, Math.max(0, (spent / access.budgetUsd) * 100)) : 100;
  return {
    notice: notice(access, resetsDay),
    numbers: access.gatewayConfigured,
    left: formatUsd(Math.max(0, access.budgetUsd - spent)),
    budget: formatUsd(access.budgetUsd),
    spent: formatUsd(spent),
    percent,
    period: `per ${formatPeriod(access.budgetPeriod)}`,
    resets: resetsDay ? `Resets ${resetsDay}` : null,
    whose: access.budgetOverridden ? 'Set for you by an admin' : 'The company’s default',
    models: access.models.length ? access.models : null,
    modelsWhose: access.modelsOverridden ? 'set for you by an admin' : 'the company’s default',
  };
}

function notice(access: ModelAccessView, resetsDay: string | null): BudgetNotice | null {
  if (!access.gatewayConfigured) {
    return {
      key: 'unconfigured',
      title: 'Model access is not set up yet',
      body: 'An admin sets up the company’s model gateway under Model access. Until then nothing is paid by a budget, so new conversations are refused unless a subscription of yours pays.',
    };
  }
  switch (access.keyStatus) {
    case 'BUDGET_SPENT':
      return {
        key: 'spent',
        title: resetsDay ? `Your budget is spent until ${resetsDay}` : 'Your budget is spent',
        body: `Turns it pays wait until it resets${resetsDay ? ` on ${resetsDay}` : ''}. A subscription on a runner host of yours can pay for your conversations meanwhile.`,
      };
    case 'BLOCKED':
      return {
        key: 'blocked',
        title: 'Your budget is blocked',
        body: 'Your access to this company is suspended, so nothing is paid by your budget until an admin resumes it.',
      };
    case 'ERROR':
      return {
        key: 'error',
        title: 'Your budget cannot be used right now',
        body: access.lastError
          ? `The model gateway answered: ${access.lastError}. Turns it pays wait; Tinfirm tries again on its own.`
          : 'Turns it pays wait; Tinfirm tries again on its own.',
      };
    case null:
    case undefined:
      return {
        key: 'pending',
        title: 'Your budget is being set up',
        body: 'Tinfirm is creating your key at the model gateway; it is ready in a moment.',
      };
    default:
      return null;
  }
}
