import type { ModelAccessView } from '../../../api/generated/models';
import { aModelAccess } from '../../../testing/fixtures';
import { budgetView } from './budget-view';

type KeyStatus = NonNullable<ModelAccessView['keyStatus']>;

/** Every key status of the contract: a status the API adds and this table lacks breaks the build. */
const EVERY_STATUS: Record<KeyStatus, true> = {
  OK: true,
  BUDGET_SPENT: true,
  BLOCKED: true,
  ERROR: true,
};

describe('budgetView (plan §5: Accounts › Budget)', () => {
  it('reads the budget, what is left, its period and reset', () => {
    expect(budgetView(aModelAccess())).toMatchObject({
      notice: null,
      numbers: true,
      left: '$38.20',
      budget: '$50.00',
      spent: '$11.80',
      period: 'per 30 days',
      resets: 'Resets 1 Nov',
      whose: 'The company’s default',
      models: null,
    });
    expect(budgetView(aModelAccess()).percent).toBeCloseTo(23.6);
  });

  it('says a budget and models an admin set for the reader', () => {
    const view = budgetView(
      aModelAccess({
        budgetUsd: 80,
        budgetOverridden: true,
        models: ['claude-sonnet-4-5', 'gpt-5'],
        modelsOverridden: true,
      }),
    );
    expect(view).toMatchObject({
      left: '$68.20',
      whose: 'Set for you by an admin',
      models: ['claude-sonnet-4-5', 'gpt-5'],
      modelsWhose: 'set for you by an admin',
    });
  });

  it('has a notice for every key status but OK', () => {
    const notices = (Object.keys(EVERY_STATUS) as KeyStatus[]).map(
      (keyStatus) => budgetView(aModelAccess({ keyStatus })).notice?.key ?? null,
    );
    expect(notices).toEqual([null, 'spent', 'blocked', 'error']);
  });

  it.each`
    case                     | access                                                               | key               | title
    ${'gateway not set up'}  | ${{ gatewayConfigured: false, keyStatus: null }}                     | ${'unconfigured'} | ${'Model access is not set up yet'}
    ${'no key yet'}          | ${{ keyStatus: null }}                                               | ${'pending'}      | ${'Your budget is being set up'}
    ${'spent until a reset'} | ${{ keyStatus: 'BUDGET_SPENT', spentUsd: 50 }}                       | ${'spent'}        | ${'Your budget is spent until 1 Nov'}
    ${'spent, no reset'}     | ${{ keyStatus: 'BUDGET_SPENT', spentUsd: 50, budgetResetsAt: null }} | ${'spent'}        | ${'Your budget is spent'}
    ${'blocked'}             | ${{ keyStatus: 'BLOCKED' }}                                          | ${'blocked'}      | ${'Your budget is blocked'}
    ${'an error'}            | ${{ keyStatus: 'ERROR', lastError: 'LiteLLM answered 500' }}         | ${'error'}        | ${'Your budget cannot be used right now'}
  `('$case', ({ access, key, title }) => {
    const view = budgetView(aModelAccess(access));
    expect(view.notice).toMatchObject({ key, title });
  });

  it('shows no numbers before the gateway is set up, and caps the meter', () => {
    expect(budgetView(aModelAccess({ gatewayConfigured: false })).numbers).toBe(false);
    const spent = budgetView(aModelAccess({ keyStatus: 'BUDGET_SPENT', spentUsd: 61.5 }));
    expect(spent).toMatchObject({ left: '$0.00', spent: '$61.50', percent: 100 });
    expect(budgetView(aModelAccess({ budgetUsd: 0, spentUsd: 0 })).percent).toBe(100);
    expect(budgetView(aModelAccess({ spentUsd: null })).spent).toBe('$0.00');
  });

  it('names the gateway’s error', () => {
    expect(
      budgetView(aModelAccess({ keyStatus: 'ERROR', lastError: 'LiteLLM answered 500' })).notice
        ?.body,
    ).toContain('The model gateway answered: LiteLLM answered 500.');
  });
});
