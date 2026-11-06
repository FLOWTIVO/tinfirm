import { aModelAccess } from '../../../testing/fixtures';
import {
  budgetCounts,
  type BudgetRow,
  budgetsRailDetail,
  groupRows,
  kindLabel,
  shownRow,
} from './budget-rows';

function row(name: string, overrides: Partial<BudgetRow> = {}): BudgetRow {
  return {
    principalId: `p-${name.toLowerCase()}`,
    kind: 'member',
    name,
    email: `${name.toLowerCase()}@acme.test`,
    role: 'MEMBER',
    suspended: false,
    access: aModelAccess({ principalId: `p-${name.toLowerCase()}` }),
    error: null,
    ...overrides,
  };
}

const ROWS = [
  row('Alice', { access: aModelAccess({ spentUsd: 43.1 }) }),
  row('Bob', { role: 'ADMIN' }),
  row('Carol', { access: aModelAccess({ budgetUsd: 25, budgetOverridden: true, spentUsd: 9.8 }) }),
  row('Dave', {
    access: aModelAccess({
      budgetUsd: 100,
      budgetOverridden: true,
      spentUsd: 100,
      keyStatus: 'BUDGET_SPENT',
    }),
  }),
  row('svc-dev', {
    kind: 'identity',
    email: undefined,
    role: undefined,
    access: aModelAccess({ budgetUsd: 200, budgetOverridden: true, spentUsd: 51.3 }),
  }),
  row('Erin', { access: null, error: 'Not found' }),
].map(shownRow);

const names = (groups: ReturnType<typeof groupRows>) =>
  groups.map((g) => [g.label, g.rows.map((r) => r.row.name)]);

describe('Members’ budgets', () => {
  it('lists near or past the limit first, then their own budgets, then the default', () => {
    expect(names(groupRows(ROWS, 'all', ''))).toEqual([
      ['Near or past the limit', ['Dave', 'Alice']],
      ['With their own budget', ['Carol', 'svc-dev']],
      ['On the default', ['Bob']],
      ['Not read', ['Erin']],
    ]);
    expect(groupRows(ROWS, 'all', '').every((g) => g.heading)).toBe(true);
  });

  it.each<[Parameters<typeof groupRows>[1], string, string[]]>([
    ['own', '', ['Dave', 'Carol', 'svc-dev']],
    ['near', '', ['Alice']],
    ['spent', '', ['Dave']],
    ['all', 'car', ['Carol']],
    ['all', 'BOB@acme', ['Bob']],
    ['own', 'alice', []],
  ])('filters %s, searched for %j', (filter, query, shown) => {
    const groups = groupRows(ROWS, filter, query);
    expect(groups.flatMap((g) => g.rows.map((r) => r.row.name))).toEqual(shown);
    expect(groups.every((g) => !g.heading)).toBe(true);
  });

  it('counts each filter, and says them in the rail', () => {
    const counts = budgetCounts(ROWS);
    expect(counts).toEqual({ all: 6, own: 3, near: 1, spent: 1 });
    expect(budgetsRailDetail(counts)).toBe('3 of their own · 1 near its limit · 1 spent');
    expect(budgetsRailDetail({ all: 4, own: 0, near: 2, spent: 0 })).toBe(
      '0 of their own · 2 near their limit',
    );
  });

  it('names what each one is', () => {
    expect(ROWS.map((r) => kindLabel(r.row))).toEqual([
      'Member',
      'Admin',
      'Member',
      'Member',
      'Service account',
      'Member',
    ]);
    expect(kindLabel(row('Company key', { kind: 'company', principalId: 'company' }))).toBe(
      'Tinfirm’s own requests',
    );
  });
});
