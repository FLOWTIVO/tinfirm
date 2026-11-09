import { HttpHeaders } from '@angular/common/http';
import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import type { AuditCheck, Role } from '../../../api/generated/models';
import { anAuditCheck } from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  provideTestHttp,
  settle,
  typeInto,
} from '../../../testing/test-helpers';
import { words } from '../../../testing/words';
import { acknowledgeable, AuditChecks, checkPhrase } from './audit-checks';
import { AuditExport, type ExportForm, exportPlan } from './audit-export';

const EVENTS = '/api/companies/c1/audit-events';

describe('the audit export’s plan', () => {
  const base: ExportForm = {
    by: 'seq',
    fromSeq: '',
    toSeq: '',
    fromDay: '',
    fromTime: '',
    toDay: '',
    toTime: '',
  };

  it.each<[string, Partial<ExportForm>, number | null, ReturnType<typeof exportPlan>]>([
    [
      'the whole chain',
      {},
      1216,
      {
        query: { fromSeq: undefined, toSeq: undefined },
        summary: 'Event 1 to the latest, 1216:',
        count: 1216,
      },
    ],
    [
      'the whole chain, its head not known yet',
      {},
      null,
      { query: { fromSeq: undefined, toSeq: undefined }, summary: 'Event 1 to the latest:' },
    ],
    [
      'a range of events',
      { fromSeq: '10', toSeq: ' 20 ' },
      1216,
      { query: { fromSeq: 10, toSeq: 20 }, summary: 'Event 10 to event 20:', count: 11 },
    ],
    [
      'a range past the head',
      { fromSeq: '1200', toSeq: '5000' },
      1216,
      { query: { fromSeq: 1200, toSeq: 5000 }, summary: 'Event 1200 to event 5000:', count: 17 },
    ],
    [
      'an inverted range',
      { fromSeq: '20', toSeq: '10' },
      1216,
      { error: 'The first event must come before the last.' },
    ],
    ['event zero', { fromSeq: '0' }, 1216, { error: 'Enter whole numbers from 1.' }],
    ['a fraction', { toSeq: '1.5' }, 1216, { error: 'Enter whole numbers from 1.' }],
    [
      'more than an export holds',
      { toSeq: '2000000' },
      3_000_000,
      { error: 'That is more than 1,000,000 events: narrow the range.' },
    ],
    [
      'a range of times',
      { by: 'time', fromDay: '1 Oct 2026', toDay: '2 Oct 2026', toTime: '09:30' },
      null,
      {
        query: {
          from: new Date(2026, 9, 1).toISOString(),
          to: new Date(2026, 9, 2, 9, 30).toISOString(),
        },
        summary: 'Every event from 1 Oct 2026, 00:00 until 2 Oct 2026, 09:30,',
      },
    ],
    [
      'from a time until now',
      { by: 'time', fromDay: '2026-10-01', fromTime: '8:15' },
      null,
      {
        query: { from: new Date(2026, 9, 1, 8, 15).toISOString(), to: undefined },
        summary: 'Every event from 2026-10-01, 8:15 until now,',
      },
    ],
    [
      'a day that is none',
      { by: 'time', fromDay: 'yesterday' },
      null,
      { error: 'Enter a valid day and time, such as 8 Oct 2026 and 09:30.' },
    ],
    [
      'times that end before they start',
      { by: 'time', fromDay: '2 Oct 2026', toDay: '1 Oct 2026' },
      null,
      { error: 'The range must end after it starts.' },
    ],
  ])('plans %s', (_, change, head, plan) => {
    expect(exportPlan({ ...base, ...change }, head)).toEqual(plan);
  });
});

describe('the daily checks, in words', () => {
  it.each<[Partial<AuditCheck>, string]>([
    [{}, 'Passed: 40 new events chain from the head before.'],
    [{ eventsChecked: 0 }, 'Passed: no new events since the check before.'],
    [
      { ok: false, failure: 'SHORTER' },
      'Failed: the chain ends before the head the last check recorded. Its newest events were removed.',
    ],
    [
      { ok: false, failure: 'HEAD_CHANGED' },
      'Failed: the event at the head the last check recorded has another hash now. It was changed.',
    ],
    [
      { ok: false, failure: 'BROKEN', firstInvalidSeq: 121 },
      'Failed: event 121 does not chain from the one before. It, or one before it, was changed.',
    ],
    [
      { ok: false, failure: 'ANCHOR_MISMATCH', firstInvalidSeq: 88, anchorCheckpoint: 12 },
      'Failed: event 88 is gone or has another hash than checkpoint 12 outside the database names. The chain was rewritten or cut, and recomputed.',
    ],
  ])('says %o', (change, text) => {
    expect(checkPhrase(anAuditCheck(change))).toBe(text);
  });

  it.each<[string, AuditCheck[], string | null]>([
    ['no check', [], null],
    ['a pass', [anAuditCheck()], null],
    ['a failure', [anAuditCheck({ id: 'f', ok: false, failure: 'SHORTER' })], 'f'],
    [
      'an acknowledged failure',
      [anAuditCheck({ ok: false, failure: 'SHORTER', acknowledgedAt: '2026-10-12T00:00:00Z' })],
      null,
    ],
    ['an older failure', [anAuditCheck(), anAuditCheck({ ok: false, failure: 'BROKEN' })], null],
  ])('acknowledges only the latest failure: %s', (_, checks, id) => {
    expect(acknowledgeable(checks)?.id ?? null).toBe(id);
  });
});

describe('AuditChecks', () => {
  let fixture: ComponentFixture<AuditChecks>;
  let http: HttpTestingController;
  let el: HTMLElement;

  async function open(role: Role, checks: AuditCheck[]) {
    TestBed.configureTestingModule({
      imports: [AuditChecks],
      providers: [...provideTestHttp(), fakeCurrentCompany(role).provider, fakeNotify().provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AuditChecks);
    el = fixture.nativeElement;
    await settle();
    http.expectOne((r) => r.url === `${EVENTS}/checks`).flush({ items: checks });
    await settle();
    await fixture.whenStable();
  }

  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const q = (testId: string) => el.querySelector<HTMLElement>(`[data-testid=${testId}]`);
  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('says where the chain stood at the last check, and its head to note elsewhere', async () => {
    await open('ADMIN', [anAuditCheck({ headSeq: 1204 })]);
    expect(words(q('chain-status'))).toMatch(
      /^Chain checked (today|yesterday|on \d+ \w+( \d{4})?) at \d\d:\d\d · 1,204 events · none removed$/,
    );
    expect(q('head-hash')?.textContent).toBe('c'.repeat(64));
    expect(q('acknowledge-check')).toBeNull();
    expect(q('past-checks')).toBeNull();
  });

  it('opens the past checks in place', async () => {
    await open('ADMIN', [anAuditCheck(), anAuditCheck({ id: 'check-0', eventsChecked: 0 })]);
    const toggle = q('toggle-past-checks')!;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click();
    await settle();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(words(q('past-checks'))).toContain('Passed: 40 new events chain from the head before.');
    expect(words(q('past-checks'))).toContain('Passed: no new events since the check before.');
  });

  it('says when no check ran yet', async () => {
    await open('ADMIN', []);
    expect(words(q('no-checks'))).toBe('No check has run yet: the first runs within a day.');
    expect(q('toggle-past-checks')).toBeNull();
  });

  it('says what a failed check found', async () => {
    await open('ADMIN', [anAuditCheck({ ok: false, failure: 'BROKEN', firstInvalidSeq: 1187 })]);
    expect(words(q('chain-status'))).toMatch(
      /^Daily check failed .+ at \d\d:\d\d: event 1187 does not chain from the one before\. It, or one before it, was changed\.$/,
    );
    expect(q('chain-status')?.className).toContain('text-destructive');
  });

  it('lets only the Owner acknowledge a failure, with a note', async () => {
    await open('ADMIN', [anAuditCheck({ id: 'f', ok: false, failure: 'SHORTER' })]);
    expect(words(el)).toContain('Only the Owner acknowledges a failure.');
    TestBed.resetTestingModule();

    await open('OWNER', [anAuditCheck({ id: 'f', ok: false, failure: 'SHORTER' })]);
    q('acknowledge-check')!.click();
    await settle();
    await fixture.whenStable();
    typeInto(overlay().querySelector('textarea'), 'Restored from a backup');
    await settle();
    overlay().querySelector<HTMLElement>('[data-testid=ack-dialog-confirm]')!.click();
    await settle();
    const req = http.expectOne({ method: 'POST', url: `${EVENTS}/checks/f/acknowledge` });
    expect(req.request.body).toEqual({ note: 'Restored from a backup' });
    req.flush(
      anAuditCheck({
        id: 'f',
        ok: false,
        failure: 'SHORTER',
        acknowledgedAt: '2026-10-12T10:00:00Z',
      }),
    );
    await settle();
    http
      .expectOne((r) => r.url === `${EVENTS}/checks`)
      .flush({
        items: [
          anAuditCheck({
            id: 'f',
            ok: false,
            failure: 'SHORTER',
            acknowledgedAt: '2026-10-12T10:00:00Z',
            acknowledgedBy: 'Olga',
            acknowledgedNote: 'Restored from a backup',
          }),
        ],
      });
    await settle();
    await fixture.whenStable();
    expect(words(el)).toContain('by Olga: “Restored from a backup”. The next check starts');
    expect(q('acknowledge-check')).toBeNull();
    expect(q('chain-status')?.className).not.toContain('text-destructive');
  });
});

describe('AuditExport', () => {
  let fixture: ComponentFixture<AuditExport>;
  let http: HttpTestingController;
  let el: HTMLElement;

  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const dialog = () => overlay().querySelector<HTMLElement>('[data-testid=audit-export]');
  const inDialog = (testId: string) =>
    dialog()?.querySelector<HTMLElement>(`[data-testid=${testId}]`) ?? null;

  /** Opens the dialog; the chain's latest event answers 1216. */
  async function openDialog(): Promise<void> {
    el.querySelector<HTMLElement>('[data-testid=open-export]')!.click();
    await settle();
    const head = http.expectOne((r) => r.url === EVENTS);
    expect(head.request.params.get('limit')).toBe('1');
    head.flush({ items: [{ seq: 1216 }] });
    await settle();
  }

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [AuditExport],
      providers: [...provideTestHttp(), fakeCurrentCompany('ADMIN').provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(AuditExport);
    el = fixture.nativeElement;
    await settle();
  });
  afterEach(() => {
    http.verify();
    overlay()?.replaceChildren();
  });

  it('downloads a range of the chain as NDJSON, and says the log holds it', async () => {
    const exported = vi.fn();
    fixture.componentInstance.exported.subscribe(exported);
    const created = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:x');
    const click = vi
      .spyOn(HTMLAnchorElement.prototype, 'click')
      .mockImplementation(() => undefined);
    await openDialog();
    expect(words(inDialog('export-summary'))).toContain(
      'Event 1 to the latest, 1216: 1,216 events, oldest first.',
    );
    typeInto(dialog()!.querySelector('#export-from-seq'), '1150');
    await settle();
    expect(words(inDialog('export-summary'))).toContain(
      'Event 1150 to the latest, 1216: 67 events, oldest first.',
    );
    inDialog('export-audit')!.click();
    await settle();
    const req = http.expectOne((r) => r.url === `${EVENTS}/export`);
    expect(req.request.params.get('fromSeq')).toBe('1150');
    expect(req.request.params.has('toSeq')).toBe(false);
    expect(req.request.headers.get('Accept')).toBe('application/x-ndjson');
    req.flush(new Blob(['{"kind":"export"}\n']));
    await settle();
    expect(created).toHaveBeenCalled();
    expect(click).toHaveBeenCalled();
    expect(words(inDialog('export-done'))).toContain('The download has started audit-c1.ndjson');
    expect(exported).toHaveBeenCalled();
    created.mockRestore();
    click.mockRestore();
  });

  it('checks what is typed before it asks', async () => {
    await openDialog();
    typeInto(dialog()!.querySelector('#export-from-seq'), '30');
    typeInto(dialog()!.querySelector('#export-to-seq'), '20');
    await settle();
    expect(words(dialog()!.querySelector('#export-invalid'))).toBe(
      'The first event must come before the last.',
    );
    expect((inDialog('export-audit') as HTMLButtonElement).disabled).toBe(true);
  });

  it('exports by time, a day picked on the calendar', async () => {
    await openDialog();
    dialog()!.querySelector<HTMLInputElement>('#export-by-time')!.click();
    await settle();
    dialog()!.querySelector<HTMLElement>('[aria-label="Pick the first day"]')!.click();
    await settle();
    const days = dialog()!.querySelectorAll<HTMLButtonElement>('[data-day]');
    days[0].click();
    await settle();
    const first = days[0].getAttribute('data-day')!;
    const [y, m, d] = first.split('-').map(Number);
    expect(dialog()!.querySelector<HTMLInputElement>('#export-from-day')!.value).toMatch(
      new RegExp(`^${d} \\w{3} ${y}$`),
    );
    expect(dialog()!.querySelector<HTMLInputElement>('#export-from-time')!.value).toBe('00:00');
    inDialog('export-audit')!.click();
    await settle();
    const req = http.expectOne((r) => r.url === `${EVENTS}/export`);
    expect(req.request.params.get('from')).toBe(new Date(y, m - 1, d).toISOString());
    expect(req.request.params.has('to')).toBe(false);
    req.flush(new Blob(['']));
    await settle();
  });

  it('reads the problem out of a refused download: AUDIT_EXPORT_TOO_LARGE', async () => {
    await openDialog();
    inDialog('export-audit')!.click();
    await settle();
    http
      .expectOne((r) => r.url === `${EVENTS}/export`)
      .flush(
        new Blob([
          JSON.stringify({ status: 400, code: 'AUDIT_EXPORT_TOO_LARGE', title: 'Too large' }),
        ]),
        {
          status: 400,
          statusText: 'Bad Request',
          headers: new HttpHeaders({ 'content-type': 'application/problem+json' }),
        },
      );
    await settle(6);
    await fixture.whenStable();
    expect(words(inDialog('export-error'))).toContain('An export holds at most 1,000,000 events.');
  });
});
