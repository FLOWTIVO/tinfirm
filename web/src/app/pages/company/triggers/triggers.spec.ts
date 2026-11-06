import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import type { Role, TriggerSummary } from '../../../api/generated/models';
import { aTriggerFiring, aTriggerSummary } from '../../../testing/fixtures';
import { fakeCurrentCompany, provideTestHttp, settle } from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { Triggers } from './triggers';

const URL = '/api/companies/c1/triggers';

describe('Triggers', () => {
  let fixture: ComponentFixture<Triggers>;
  let http: HttpTestingController;
  let el: HTMLElement;

  async function open(
    items: TriggerSummary[],
    options: { role?: Role | null; status?: string } = {},
  ): Promise<string> {
    TestBed.configureTestingModule({
      imports: [Triggers],
      providers: [
        provideRouter([]),
        ...provideTestHttp(),
        fakeCurrentCompany(options.role === undefined ? 'MEMBER' : options.role).provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(Triggers);
    if (options.status) fixture.componentRef.setInput('status', options.status);
    el = fixture.nativeElement;
    await settle();
    const req = http.expectOne((r) => r.url === URL);
    req.flush({ items });
    await settle();
    return req.request.urlWithParams;
  }

  afterEach(() => http.verify());

  const row = (id: string) => el.querySelector<HTMLElement>(`[data-testid="trigger-${id}"]`)!;

  it('lists each trigger with its sentence, author, team, payer and last firing', async () => {
    await open([aTriggerSummary()]);
    const r = row('t-prepare');
    expect(r.querySelector('[data-testid=trigger-link]')?.getAttribute('href')).toBe(
      '/c/c1/triggers/t-prepare',
    );
    expect(words(r.querySelector('[data-testid=trigger-sentence]'))).toBe(
      'When an issue in SHOP is assigned to svc-reader, Business analyst prepares it.',
    );
    expect(words(r.querySelector('[data-testid=trigger-meta]'))).toBe(
      'Jira · Business analyst · by Alice Novak · team Analysts · paid by svc-reader',
    );
    expect(words(r.querySelector('[data-testid=trigger-last-firing]'))).toContain(
      'Started a conversation on SHOP-241 (assigned)',
    );
    // An active trigger that waits on nothing has no chip.
    expect(r.querySelector('[data-testid=trigger-paused]')).toBeNull();
    expect(r.querySelector('[data-testid=trigger-waiting]')).toBeNull();
  });

  it.each([
    ['AUTHOR_LEFT', 'Paused: Its author left the company'],
    ['SYSTEM_PAUSED', 'Paused: An admin paused its system'],
    ['ACCOUNT_BROKEN', 'Paused: An account it acts with needs signing in again'],
    ['NOT_ENTITLED', 'Paused: Its author may no longer use an account, the agent or the payer'],
    ['USER', 'Paused: Paused by hand'],
  ] as const)('says why a trigger is paused: %s', async (reason, says) => {
    await open([aTriggerSummary({ status: 'PAUSED', pausedReason: reason, lastFiring: null })]);
    const chip = row('t-prepare').querySelector('[data-testid=trigger-paused]')!;
    expect(words(chip)).toBe(says);
    expect(chip.getAttribute('data-reason')).toBe(reason);
    // Neutral: whoever reads the list is not asked to act here.
    expect(chip.className).not.toContain('human');
  });

  it.each([
    ['COMPANY_STOPPED', 'Waits: an admin stopped the company’s runs'],
    ['GATEWAY_NOT_CONFIGURED', 'Waits for an admin to set up model access'],
    ['SCOPE_UNREADABLE', 'Waits: its account cannot read the scope'],
    ['SYSTEM_UNREACHABLE', 'Waits: its system does not answer'],
  ] as const)('says why a trigger waits: %s', async (reason, says) => {
    await open([aTriggerSummary({ waitingReason: reason })]);
    expect(words(row('t-prepare').querySelector('[data-testid=trigger-waiting]'))).toBe(says);
  });

  it('names a schedule, a trigger without a team, and a run now', async () => {
    await open([
      aTriggerSummary({
        source: 'SCHEDULE',
        systemId: null,
        systemName: null,
        teamGroupId: null,
        teamName: null,
        lastFiring: aTriggerFiring({
          subjectKey: null,
          event: 'SCHEDULE',
          outcome: 'SKIPPED_OPEN',
        }),
      }),
    ]);
    expect(words(row('t-prepare').querySelector('[data-testid=trigger-meta]'))).toContain(
      'Schedule · Business analyst · by Alice Novak · no team',
    );
    expect(words(row('t-prepare').querySelector('[data-testid=trigger-last-firing]'))).toContain(
      'Skipped: the last one is still busy',
    );
  });

  it('filters by status in the address', async () => {
    const url = await open([], { status: 'PAUSED' });
    expect(url).toContain('status=PAUSED');
    expect(words(el.querySelector('[data-testid=triggers-empty]'))).toContain('No paused triggers');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    el.querySelector<HTMLInputElement>('#triggers-shown-none')!.click();
    expect(navigate).toHaveBeenCalledWith([], {
      queryParams: { status: null },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  });

  it('offers a member to create one, and an instance admin who is no member nothing', async () => {
    await open([]);
    expect(words(el.querySelector('[data-testid=triggers-empty]'))).toContain('Create a trigger');
    expect(el.querySelector('[data-testid=new-trigger]')).not.toBeNull();
    TestBed.resetTestingModule();
    await open([aTriggerSummary()], { role: null });
    expect(el.querySelector('[data-testid=new-trigger]')).toBeNull();
    expect(el.querySelector('[data-testid=viewer-notice]')).toBeNull();
  });

  it('tells a viewer why they author no trigger (ADR-0077)', async () => {
    await open([], { role: 'VIEWER' });
    expect(el.querySelector('[data-testid=new-trigger]')).toBeNull();
    expect(words(el.querySelector('[data-testid=triggers-empty]'))).not.toContain(
      'Create a trigger',
    );
    expect(words(el.querySelector('[data-testid=viewer-notice]'))).toBe(
      'You are a Viewer in this company. Viewers read the triggers, but don’t create or edit them. An Owner or Admin can make you a Member.',
    );
  });
});
