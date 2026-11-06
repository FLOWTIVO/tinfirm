import { HttpTestingController, type TestRequest } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { Role, SpendReport } from '../../../api/generated/models';
import {
  aMember,
  anAmounts,
  aServiceIdentity,
  aSpendReport,
  aSpendRow,
} from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { filterCount, filterParams, NO_FILTERS, SpendView } from './spend-view';

const SPEND = '/api/companies/c1/spend';

describe('the spend filters', () => {
  it.each<[string, Partial<typeof NO_FILTERS>, Record<string, string>]>([
    ['none', {}, {}],
    [
      'a payer and a source',
      { payerId: 'p-bob', source: 'RELAY' },
      { payerId: 'p-bob', source: 'RELAY' },
    ],
    ['a model, trimmed', { model: ' claude-sonnet-4-5 ' }, { model: 'claude-sonnet-4-5' }],
    ['a blank model', { model: '  ' }, {}],
    [
      'every one',
      { payerId: 'p', actorId: 'q', agentId: 'a', triggerId: 't', model: 'm', source: 'GATEWAY' },
      { payerId: 'p', actorId: 'q', agentId: 'a', triggerId: 't', model: 'm', source: 'GATEWAY' },
    ],
  ])('sends only those set: %s', (_, change, params) => {
    const filters = { ...NO_FILTERS, ...change };
    expect(filterParams(filters)).toEqual(params);
    expect(filterCount(filters)).toBe(Object.keys(params).length);
  });
});

describe('SpendView', () => {
  let fixture: ComponentFixture<SpendView>;
  let http: HttpTestingController;
  let el: HTMLElement;

  async function open(role: Role = 'ADMIN'): Promise<TestRequest> {
    TestBed.configureTestingModule({
      imports: [SpendView],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany(role).provider,
        fakeNotify().provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SpendView);
    el = fixture.nativeElement;
    await settle();
    answerLists();
    return http.expectOne((r) => r.url === SPEND);
  }

  /** The filters' choices: agents and triggers for everyone, people for admins. */
  function answerLists() {
    const lists: Record<string, unknown[]> = {
      '/api/companies/c1/agents': [{ id: 'a-ba', name: 'Business analyst' }],
      '/api/companies/c1/triggers': [{ id: 't-1', name: 'Triage new issues' }],
      '/api/companies/c1/members': [
        aMember({ userId: 'u-bob', principalId: 'p-bob', displayName: 'Bob' }),
        aMember({ userId: 'u-x', principalId: null, displayName: 'Xavier' }),
      ],
      '/api/companies/c1/service-identities': [
        aServiceIdentity({ id: 'p-svc', name: 'svc-reader' }),
      ],
    };
    for (const [url, items] of Object.entries(lists)) {
      for (const req of http.match((r) => r.url === url)) req.flush({ items });
    }
  }

  async function answer(req: TestRequest, report: SpendReport | null, status = 200) {
    if (report) req.flush(report);
    else req.flush(problem(status, 'SPEND_RANGE_INVALID'), { status, statusText: 'Bad Request' });
    await settle();
    await fixture.whenStable();
  }

  const rows = () => Array.from(el.querySelectorAll('[data-testid=spend-row]'), (r) => words(r));
  const q = (id: string) => el.querySelector<HTMLElement>(`[data-testid=${id}]`);

  afterEach(() => http.verify());

  it('asks for this month by person, in the browser’s zone', async () => {
    const req = await open();
    const params = req.request.params;
    expect(params.get('groupBy')).toBe('payer');
    expect(params.get('bucket')).toBe('DAY');
    expect(params.get('from')).toMatch(/^\d{4}-\d{2}-01$/);
    expect(params.get('zone')).toBeTruthy();
    expect(req.request.headers.get('Accept')).toContain('application/json');
    await answer(req, aSpendReport());
    expect(rows()).toEqual(['Alice 3 12.4K $4.20', 'Bob 1 12.4K $0.50']);
    expect(words(q('spend-total'))).toBe('Total 4 12.4K $4.70');
  });

  it.each<[string, SpendReport, string[], string | null]>([
    [
      'the relay’s share and a subscription',
      aSpendReport({
        rows: [
          aSpendRow('Alice', { amounts: anAmounts({ relayCostUsd: 1 }) }),
          aSpendRow('Bob', { amounts: anAmounts({ costUsd: 0, subscriptionTokens: 5000 }) }),
        ],
        totals: anAmounts({ costUsd: 4.2, relayCostUsd: 1, subscriptionTokens: 5000 }),
      }),
      [
        'Alice 3 12.4K $4.20 · $1.00 by Tinfirm’s count',
        'Bob 3 12.4K $0.00 · 5K subscription tokens',
      ],
      '$1.00 of it is Tinfirm’s own count',
    ],
    [
      'another person’s conversations folded for an admin',
      aSpendReport({
        groupBy: ['conversation'],
        rows: [
          {
            keys: [
              {
                dimension: 'conversation',
                id: 'cv-mine',
                key: 'ME-1',
                title: 'Mine',
                folded: false,
              },
            ],
            amounts: anAmounts(),
          },
          {
            keys: [{ dimension: 'conversation', folded: true, personPrincipalId: 'p-bob' }],
            amounts: anAmounts(),
          },
        ],
      }),
      ['ME-1 · Mine 3 12.4K $4.20', 'Their personal conversations 3 12.4K $4.20'],
      null,
    ],
  ])('shows %s', async (_, report, expected, note) => {
    await answer(await open(), report);
    expect(rows()).toEqual(expected);
    if (note) expect(words(q('spend-sources'))).toContain(note);
    else expect(q('spend-sources')).toBeNull();
  });

  it('links a conversation’s key to it, never a folded row', async () => {
    await answer(
      await open('OWNER'),
      aSpendReport({
        groupBy: ['conversation'],
        rows: [
          {
            keys: [{ dimension: 'conversation', id: 'cv-bob', key: 'BOB-3', folded: false }],
            amounts: anAmounts(),
          },
          {
            keys: [{ dimension: 'conversation', folded: true, personPrincipalId: 'p-bob' }],
            amounts: anAmounts(),
          },
        ],
      }),
    );
    const links = el.querySelectorAll('[data-testid=spend-row] a');
    expect(links).toHaveLength(1);
    expect(links[0].getAttribute('href')).toBe('/c/c1/conversations/cv-bob');
  });

  it('says when nothing was spent', async () => {
    await answer(await open('MEMBER'), aSpendReport({ rows: [], totals: anAmounts({ runs: 0 }) }));
    expect(words(q('spend-empty'))).toContain('Nothing spent in this range');
  });

  it('groups by a second thing, and per week over time', async () => {
    await answer(await open(), aSpendReport());
    const period = el.querySelector<HTMLInputElement>('#spend-group-period')!;
    period.click();
    await settle();
    const byTime = http.expectOne((r) => r.url === SPEND);
    expect(byTime.request.params.get('groupBy')).toBe('period');
    await answer(byTime, aSpendReport({ groupBy: ['period'] }));

    const then = el.querySelector<HTMLSelectElement>('#spend-then')!;
    then.value = 'agent';
    then.dispatchEvent(new Event('change'));
    await settle();
    const two = http.expectOne((r) => r.url === SPEND);
    expect(two.request.params.get('groupBy')).toBe('period,agent');
    await answer(two, aSpendReport({ groupBy: ['period', 'agent'] }));

    const bucket = el.querySelector<HTMLSelectElement>('#spend-bucket')!;
    bucket.value = 'WEEK';
    bucket.dispatchEvent(new Event('change'));
    await settle();
    const weekly = http.expectOne((r) => r.url === SPEND);
    expect(weekly.request.params.get('bucket')).toBe('WEEK');
    await answer(weekly, aSpendReport());
  });

  it('filters by who paid, the agent and the source, and clears them', async () => {
    await answer(await open(), aSpendReport());
    // A member without a principal here is no one to filter by.
    const payer = el.querySelector<HTMLSelectElement>('#spend-payer')!;
    expect(Array.from(payer.options, (o) => o.text)).toEqual(['Anyone', 'Bob', 'svc-reader']);

    const pick = async (id: string, value: string) => {
      const select = el.querySelector<HTMLSelectElement>(id)!;
      select.value = value;
      select.dispatchEvent(new Event('change'));
      await settle();
      return http.expectOne((r) => r.url === SPEND);
    };
    await answer(await pick('#spend-payer', 'p-bob'), aSpendReport());
    await answer(await pick('#spend-agent', 'a-ba'), aSpendReport());
    const third = await pick('#spend-source', 'RELAY');
    expect(third.request.params.get('payerId')).toBe('p-bob');
    expect(third.request.params.get('agentId')).toBe('a-ba');
    expect(third.request.params.get('source')).toBe('RELAY');
    await answer(third, aSpendReport({ rows: [], totals: anAmounts({ runs: 0 }) }));
    expect(words(q('spend-filters')?.querySelector('summary'))).toBe('Only some · 3 set');

    q('spend-clear-filters')!.click();
    await settle();
    const cleared = http.expectOne((r) => r.url === SPEND);
    expect(cleared.request.params.has('payerId')).toBe(false);
    expect(cleared.request.params.has('source')).toBe(false);
    await answer(cleared, aSpendReport());
  });

  it('offers a member no people to filter by', async () => {
    await answer(await open('MEMBER'), aSpendReport());
    expect(el.querySelector('#spend-payer')).toBeNull();
    expect(el.querySelector('#spend-agent')).not.toBeNull();
    expect(
      Array.from(el.querySelectorAll<HTMLOptionElement>('#spend-trigger option'), (o) => o.text),
    ).toEqual(['Any trigger, or none', 'Triage new issues']);
  });

  it('refuses a range the server would refuse, before asking', async () => {
    await answer(await open(), aSpendReport());
    const from = el.querySelector<HTMLInputElement>('#spend-from')!;
    from.value = '2024-01-01';
    from.dispatchEvent(new Event('change'));
    await settle();
    await fixture.whenStable();
    expect(words(q('spend-range-error'))).toBe('A range covers at most 366 days.');
    http.expectNone((r) => r.url === SPEND);
    expect((q('spend-csv') as HTMLButtonElement).disabled).toBe(true);
  });

  it('explains SPEND_RANGE_INVALID from the server', async () => {
    await answer(await open(), null, 400);
    expect(words(el)).toContain('cover at most 366 days');
  });

  it('downloads the same rows as CSV', async () => {
    await answer(await open(), aSpendReport());
    const created = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined);
    q('spend-csv')!.click();
    await settle();
    const csv = http.expectOne((r) => r.url === SPEND && r.headers.get('Accept') === 'text/csv');
    expect(csv.request.params.get('groupBy')).toBe('payer');
    csv.flush('payer,runs\nAlice,3\n');
    await settle();
    expect(created).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
    created.mockRestore();
    click.mockRestore();
  });
});
