import { aModelAccess, aServiceIdentity } from '../../../testing/fixtures';
import { budgetView, identityUsers, loadAll } from './identity-labels';

describe('service account labels', () => {
  it.each`
    groups                                                         | users
    ${[]}                                                          | ${'Only the Owner'}
    ${null}                                                        | ${'Only the Owner'}
    ${[{ id: 'g1', name: 'Analysts' }]}                            | ${'Analysts'}
    ${[{ id: 'g1', name: 'Analysts' }, { id: 'g2', name: 'Ops' }]} | ${'Analysts, Ops'}
  `('is used by "$users"', ({ groups, users }) => {
    expect(identityUsers(aServiceIdentity({ userGroups: groups }))).toBe(users);
  });

  it('lays out a budget: spent of what, per what, and when it resets', () => {
    expect(budgetView(aModelAccess())).toEqual({
      configured: true,
      budget: '$50.00',
      period: '30d',
      isDefault: true,
      spent: '$11.80',
      ratio: 11.8 / 50,
      resets: '1 Nov',
    });
    // Its own budget, its spend unknown: no share to draw.
    expect(
      budgetView(
        aModelAccess({
          budgetUsd: 200,
          budgetOverridden: true,
          spentUsd: null,
          budgetResetsAt: null,
        }),
      ),
    ).toMatchObject({
      budget: '$200.00',
      isDefault: false,
      spent: null,
      ratio: null,
      resets: null,
    });
    // Spent beyond a budget of nothing, or beyond the budget: the bar stays within its track.
    expect(budgetView(aModelAccess({ budgetUsd: 0, spentUsd: 3 })).ratio).toBeNull();
    expect(budgetView(aModelAccess({ budgetUsd: 10, spentUsd: 12 })).ratio).toBe(1);
    expect(budgetView(aModelAccess({ gatewayConfigured: false })).configured).toBe(false);
  });

  it('reads every page of a list', async () => {
    const pages: Record<string, { items: number[]; nextCursor?: string | null }> = {
      first: { items: [1, 2], nextCursor: 'b' },
      b: { items: [3], nextCursor: 'c' },
      c: { items: [4], nextCursor: null },
    };
    const load = vi.fn((cursor: string | undefined) => Promise.resolve(pages[cursor ?? 'first']));
    expect(await loadAll(load)).toEqual([1, 2, 3, 4]);
    expect(load).toHaveBeenCalledTimes(3);
  });
});
