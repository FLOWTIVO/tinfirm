import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { ModelAccessView, Role } from '../../../api/generated/models';
import { aModelAccess, aSubscription } from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  problem,
  provideTestHttp,
  settle,
} from '../../../testing/test-helpers';
import { AccountsBudget } from './accounts-budget';

const ACCESS = '/api/companies/c1/me/model-access';

describe('AccountsBudget', () => {
  let fixture: ComponentFixture<AccountsBudget>;
  let http: HttpTestingController;
  let el: HTMLElement;

  async function open(access: ModelAccessView, role: Role = 'MEMBER') {
    TestBed.configureTestingModule({
      imports: [AccountsBudget],
      providers: [provideRouter([]), ...provideTestHttp(), fakeCurrentCompany(role).provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AccountsBudget);
    el = fixture.nativeElement;
    await settle();
    http.expectOne(ACCESS).flush(access);
    await settle();
    await fixture.whenStable();
  }

  const q = (testId: string) => el.querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const text = (node: Element | null) => node?.textContent?.replace(/\s+/g, ' ').trim();

  afterEach(() => http.verify());

  it('shows what is left of the budget, its spend, period, reset and models', async () => {
    await open(aModelAccess());
    expect(q('budget')?.getAttribute('data-status')).toBe('OK');
    expect(q('budget-notice')).toBeNull();
    expect(text(q('budget-left'))).toBe('$38.20');
    expect(text(q('budget-of'))).toBe('left of $50.00 per 30 days');
    expect(text(q('budget-spent'))).toBe('$11.80');
    expect(text(q('budget-resets'))).toBe('Resets 1 Nov');
    expect(text(q('budget-models'))).toBe('Every model the gateway serves');
    expect(text(q('budget-whose'))).toBe('The company’s default');
    const meter = el.querySelector('[role=meter]')!;
    expect(meter.getAttribute('aria-valuenow')).toBe('11.8');
    expect(meter.getAttribute('aria-valuemax')).toBe('50');
    expect(meter.getAttribute('aria-valuetext')).toBe('$11.80 of $50.00 spent');
    // No orange: nothing waits on the reader here.
    expect(el.querySelector('.text-human')).toBeNull();
  });

  it.each`
    case                   | access                                              | notice       | words
    ${'spent until 1 Nov'} | ${{ keyStatus: 'BUDGET_SPENT', spentUsd: 50 }}      | ${'spent'}   | ${'Your budget is spent until 1 Nov'}
    ${'blocked'}           | ${{ keyStatus: 'BLOCKED' }}                         | ${'blocked'} | ${'Your budget is blocked'}
    ${'an error'}          | ${{ keyStatus: 'ERROR', lastError: 'Key refused' }} | ${'error'}   | ${'The model gateway answered: Key refused.'}
    ${'no key yet'}        | ${{ keyStatus: null }}                              | ${'pending'} | ${'Your budget is being set up'}
  `('says when the budget is $case', async ({ access, notice, words }) => {
    await open(aModelAccess(access));
    expect(q('budget-notice')?.getAttribute('data-notice')).toBe(notice);
    expect(text(q('budget-notice'))).toContain(words);
    expect(q('budget-left')).not.toBeNull();
  });

  it('says that model access is not set up, and links an admin to it', async () => {
    await open(
      aModelAccess({ gatewayConfigured: false, keyStatus: null, spentUsd: null }),
      'ADMIN',
    );
    expect(text(q('budget-notice'))).toContain('Model access is not set up yet');
    expect(q('budget-notice')?.querySelector('a')?.getAttribute('href')).toBe('/c/c1/model-access');
    expect(q('budget-left')).toBeNull();
  });

  it('gives a member no link to the company’s setup', async () => {
    await open(aModelAccess({ gatewayConfigured: false, keyStatus: null }));
    expect(q('budget-notice')?.querySelector('a')).toBeNull();
  });

  it('lists the subscriptions on the reader’s runner hosts, by status', async () => {
    await open(
      aModelAccess({
        subscriptions: [
          aSubscription(),
          aSubscription({
            kind: 'CODEX_CHATGPT_AUTH',
            label: 'chatgpt',
            runtimes: ['CODEX'],
            status: 'COOLDOWN',
          }),
          aSubscription({
            label: 'old-key',
            kind: 'ANTHROPIC_API_KEY',
            status: 'INVALID',
            reported: false,
          }),
        ],
      }),
    );
    const rows = Array.from(el.querySelectorAll('[data-testid=subscription]'));
    expect(rows.map((r) => r.getAttribute('data-status'))).toEqual([
      'AVAILABLE',
      'COOLDOWN',
      'INVALID',
    ]);
    expect(text(rows[0])).toBe(
      'claude-max Claude subscription on alice-macbook · Claude Code Available',
    );
    expect(text(rows[1])).toContain('Cooling down');
    expect(text(rows[2])).toContain('not reported when the host last connected');
    expect(q('subscriptions-empty')).toBeNull();
  });

  it('points to the runner hosts when there is no subscription', async () => {
    await open(aModelAccess());
    const empty = q('subscriptions-empty')!;
    expect(empty.querySelector('a')?.getAttribute('href')).toBe('/?tab=hosts');
  });

  it('says a budget an admin set for the reader, and its models', async () => {
    await open(
      aModelAccess({
        budgetUsd: 80,
        budgetOverridden: true,
        models: ['claude-sonnet-4-5'],
        modelsOverridden: true,
      }),
    );
    expect(text(q('budget-whose'))).toBe('Set for you by an admin');
    expect(text(q('budget-models'))).toBe('claude-sonnet-4-5 · set for you by an admin');
  });

  it('says why the budget could not be read', async () => {
    TestBed.configureTestingModule({
      imports: [AccountsBudget],
      providers: [provideRouter([]), ...provideTestHttp(), fakeCurrentCompany('MEMBER').provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AccountsBudget);
    el = fixture.nativeElement;
    await settle();
    http.expectOne(ACCESS).flush(problem(503), { status: 503, statusText: 'Unavailable' });
    await settle();
    await fixture.whenStable();
    expect(text(el)).toContain("Couldn't load your budget");
  });
});
