import type {
  GroupRef,
  ModelAccessView,
  PrincipalStatus,
  ServiceIdentity,
} from '../../../api/generated/models';
import type { Page } from '../../../core/paged-resource';
import { formatDay, formatUsd } from '../../../shared/labels';

/**
 * The words of service accounts, as the web app calls service identities (the web plan §5;
 * ADR-0072): statuses, who may use one, its budget. A credential's words are the system's
 * (`systems/credential-labels.ts`).
 */

export const IDENTITY_STATUS_LABELS: Record<PrincipalStatus, string> = {
  ACTIVE: 'Active',
  SUSPENDED: 'Suspended',
  LEFT: 'Gone',
};

/** Who may use a service account: its groups by name, or the Owner alone. */
export function identityUsers(identity: Pick<ServiceIdentity, 'userGroups'>): string {
  const groups: readonly GroupRef[] = identity.userGroups ?? [];
  if (groups.length === 0) return 'Only the Owner';
  return groups.map((group) => group.name).join(', ');
}

/** A service account's budget as its page lays it out (the web plan §5: "its budget"). */
export interface BudgetView {
  /** False while the company's model gateway is not set up: no budget pays for anything. */
  readonly configured: boolean;
  /** "$50.00". */
  readonly budget: string;
  /** "30d". */
  readonly period: string;
  /** The company's default, not its own. */
  readonly isDefault: boolean;
  /** "$11.80", or null when the gateway has not said. */
  readonly spent: string | null;
  /** The share of the budget spent, 0 to 1; null when either is unknown. */
  readonly ratio: number | null;
  /** "1 Nov", or null. */
  readonly resets: string | null;
}

export function budgetView(view: ModelAccessView): BudgetView {
  const spentUsd = view.spentUsd ?? null;
  return {
    configured: view.gatewayConfigured,
    budget: formatUsd(view.budgetUsd),
    period: view.budgetPeriod,
    isDefault: !view.budgetOverridden,
    spent: spentUsd === null ? null : formatUsd(spentUsd),
    ratio:
      spentUsd === null || !(view.budgetUsd > 0)
        ? null
        : Math.min(1, Math.max(0, spentUsd / view.budgetUsd)),
    resets: formatDay(view.budgetResetsAt),
  };
}

/** Every page of a cursor-paginated list, for the short lists a page needs whole. */
export async function loadAll<T>(
  load: (cursor: string | undefined) => Promise<Page<T>>,
  maxPages = 20,
): Promise<T[]> {
  const items: T[] = [];
  let cursor: string | undefined;
  for (let page = 0; page < maxPages; page++) {
    const result = await load(cursor);
    items.push(...result.items);
    if (!result.nextCursor) break;
    cursor = result.nextCursor;
  }
  return items;
}
