import type { ModelAccessView, Role } from '../../../api/generated/models';
import { type BudgetMeter, budgetMeter } from './model-access-view';

/**
 * The rows of Members' budgets (plan §5): every member with a principal and every service
 * identity, and the company's own key (ADR-0065), each with its model access as read, in one list.
 * Pure functions, so that the order, the filters and the rail's counts are tested as tables.
 */

/** The company key's row id: no principal's. */
export const COMPANY_KEY_ROW = 'company';

/** A member's, a service account's or the company key's budget, or why it could not be read. */
export interface BudgetRow {
  /** The principal's id: what `getPrincipalModelAccess` takes; `COMPANY_KEY_ROW` for the company key. */
  readonly principalId: string;
  /** `company`: the company's own key, which pays for Tinfirm's own requests. */
  readonly kind: 'member' | 'identity' | 'company';
  readonly name: string;
  /** A member's email: what the row's test id names it by. */
  readonly email?: string;
  readonly role?: Role;
  readonly suspended: boolean;
  readonly access: ModelAccessView | null;
  readonly error: string | null;
}

/** A row with its spend bar; null while its model access could not be read. */
export interface ShownRow {
  readonly row: BudgetRow;
  readonly meter: BudgetMeter | null;
  readonly own: boolean;
}

export type BudgetFilter = 'all' | 'own' | 'near' | 'spent';

export const BUDGET_FILTERS: readonly { value: BudgetFilter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'own', label: 'Their own budget' },
  { value: 'near', label: 'Near the limit' },
  { value: 'spent', label: 'Spent' },
];

const KIND_LABELS: Record<Role, string> = {
  OWNER: 'Owner',
  ADMIN: 'Admin',
  MEMBER: 'Member',
  VIEWER: 'Viewer',
};

/** "Member", "Admin", "Owner", "Service account", "Tinfirm’s own requests". */
export function kindLabel(row: BudgetRow): string {
  if (row.kind === 'company') return 'Tinfirm’s own requests';
  return row.kind === 'identity' ? 'Service account' : KIND_LABELS[row.role ?? 'MEMBER'];
}

export function shownRow(row: BudgetRow): ShownRow {
  return {
    row,
    meter: row.access ? budgetMeter(row.access) : null,
    own: !!row.access?.budgetOverridden,
  };
}

/** How many rows each filter shows. */
export function budgetCounts(rows: readonly ShownRow[]): Record<BudgetFilter, number> {
  return {
    all: rows.length,
    own: rows.filter((r) => r.own).length,
    near: rows.filter((r) => r.meter?.state === 'near').length,
    spent: rows.filter((r) => r.meter?.state === 'spent').length,
  };
}

/** The rail's line: "3 of their own · 1 near its limit · 1 spent". */
export function budgetsRailDetail(counts: Record<BudgetFilter, number>): string {
  const parts = [`${counts.own} of their own`];
  if (counts.near) {
    parts.push(`${counts.near} ${counts.near === 1 ? 'near its limit' : 'near their limit'}`);
  }
  if (counts.spent) parts.push(`${counts.spent} spent`);
  return parts.join(' · ');
}

const RANK = { spent: 0, near: 1, ok: 2 } as const;

/** Spent first, then near the limit, then their own budget before the default, then by name. */
export function compareRows(a: ShownRow, b: ShownRow): number {
  const rank = (r: ShownRow) => (r.meter ? RANK[r.meter.state] : 3);
  return rank(a) - rank(b) || Number(b.own) - Number(a.own) || a.row.name.localeCompare(b.row.name);
}

/** Whether a row passes the filter and the search (by name, or a member's email). */
export function matchesRow(r: ShownRow, filter: BudgetFilter, query: string): boolean {
  const q = query.trim().toLowerCase();
  const found =
    !q || r.row.name.toLowerCase().includes(q) || (r.row.email?.toLowerCase().includes(q) ?? false);
  if (!found) return false;
  switch (filter) {
    case 'all':
      return true;
    case 'own':
      return r.own;
    case 'near':
      return r.meter?.state === 'near';
    case 'spent':
      return r.meter?.state === 'spent';
  }
}

/** A group of the list; a filtered or searched list is one group without a heading. */
export interface RowGroup {
  readonly id: string;
  readonly label: string;
  readonly heading: boolean;
  readonly rows: readonly ShownRow[];
}

/**
 * The list as shown: everyone in three groups (near or past the limit, their own budget, the
 * default), or, filtered or searched, the matches in one.
 */
export function groupRows(
  rows: readonly ShownRow[],
  filter: BudgetFilter,
  query: string,
): RowGroup[] {
  const shown = rows.filter((r) => matchesRow(r, filter, query)).sort(compareRows);
  if (filter !== 'all' || query.trim()) {
    return shown.length ? [{ id: 'matches', label: 'Budgets', heading: false, rows: shown }] : [];
  }
  const groups: RowGroup[] = [
    {
      id: 'attention',
      label: 'Near or past the limit',
      heading: true,
      rows: shown.filter((r) => r.meter && r.meter.state !== 'ok'),
    },
    {
      id: 'own',
      label: 'With their own budget',
      heading: true,
      rows: shown.filter((r) => r.meter?.state === 'ok' && r.own),
    },
    {
      id: 'default',
      label: 'On the default',
      heading: true,
      rows: shown.filter((r) => r.meter?.state === 'ok' && !r.own),
    },
    {
      id: 'unread',
      label: 'Not read',
      heading: true,
      rows: shown.filter((r) => !r.meter),
    },
  ];
  return groups.filter((g) => g.rows.length > 0);
}
