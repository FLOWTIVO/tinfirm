import { HttpTestingController } from '@angular/common/http/testing';
import { type ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import type { ErrorReport, ErrorReportDetail, ErrorReporting } from '../../../api/generated/models';
import { fakeNotify, problem, provideTestHttp, settle } from '../../../testing/test-helpers';
import { ErrorReports } from './error-reports';

const SETTINGS = '/api/admin/error-reporting';
const REPORTS = '/api/admin/error-reports';
const INSTANCE = '0192f1e2-3b4c-7d5e-8f90-a1b2c3d4e5f6';

function settings(overrides: Partial<ErrorReporting> = {}): ErrorReporting {
  return {
    mode: 'LOCAL',
    setBy: 'ADMIN',
    destination: 'errors.example.com',
    instanceId: INSTANCE,
    pending: 2,
    ...overrides,
  };
}

function aReport(overrides: Partial<ErrorReport> = {}): ErrorReport {
  return {
    id: 'r1',
    source: 'SERVER',
    title: 'java.lang.IllegalStateException',
    message: 'Issue <str> at <url>',
    logger: 'com.flowtivo.tinfirm.system.Sync',
    route: 'POST /api/companies/{companyId}/systems/{systemId}/test',
    occurrences: 3,
    unreported: 3,
    firstSeenAt: '2026-10-16T08:00:00Z',
    lastSeenAt: '2026-10-16T09:00:00Z',
    firstVersion: '0.6.0',
    lastVersion: '0.6.1',
    ...overrides,
  };
}

describe('ErrorReports', () => {
  let fixture: ComponentFixture<ErrorReports>;
  let http: HttpTestingController;
  let el: HTMLElement;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(current: ErrorReporting = settings(), reports: ErrorReport[] = [aReport()]) {
    const fake = fakeNotify();
    notify = fake.notify;
    TestBed.configureTestingModule({
      imports: [ErrorReports],
      providers: [provideRouter([]), ...provideTestHttp(), fake.provider],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(ErrorReports);
    el = fixture.nativeElement;
    await settle();
    http.expectOne(SETTINGS).flush(current);
    http.expectOne((r) => r.url === REPORTS).flush({ items: reports });
    await settle();
    fixture.detectChanges();
  }

  function automaticSwitch(): HTMLElement {
    return el.querySelector<HTMLElement>('hlm-switch [role=switch]')!;
  }

  afterEach(() => http.verify());

  it('lists the reports and offers the download of what would be sent', async () => {
    await open();

    const rows = el.querySelectorAll('[data-testid=report]');
    expect(rows).toHaveLength(1);
    expect(rows[0].textContent).toContain('java.lang.IllegalStateException');
    expect(rows[0].textContent).toContain('Issue <str> at <url>');
    expect(rows[0].textContent).toContain('Server');
    expect(el.querySelector('[data-testid=download]')!.getAttribute('href')).toBe(
      '/api/admin/error-reports/export',
    );
    expect(el.querySelector('[data-testid=instance-id]')!.textContent).toContain(INSTANCE);
    expect(el.querySelector('[data-testid=pending]')!.textContent).toContain('2');
  });

  it('turns automatic delivery on', async () => {
    await open();

    automaticSwitch().click();
    await settle();
    const request = http.expectOne(SETTINGS);
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toEqual({ automatic: true });
    request.flush(settings({ mode: 'AUTOMATIC' }));
    await settle();
    fixture.detectChanges();

    expect(notify.success).toHaveBeenCalledWith(
      'Errors are sent automatically',
      'The next delivery sends them to errors.example.com.',
    );
    expect(el.textContent).toContain('Sent to errors.example.com.');
  });

  it('puts the switch back when the change is refused', async () => {
    await open();

    automaticSwitch().click();
    await settle();
    http.expectOne(SETTINGS).flush(problem(409, 'ERROR_REPORTING_NO_DESTINATION'), {
      status: 409,
      statusText: 'Conflict',
    });
    await settle();
    fixture.detectChanges();

    expect(notify.error).toHaveBeenCalled();
    expect(automaticSwitch().getAttribute('aria-checked')).toBe('false');
  });

  it("cannot move the operator's choice, and says where it is made", async () => {
    await open(settings({ mode: 'AUTOMATIC', setBy: 'OPERATOR', label: 'acme-staging' }));

    expect(automaticSwitch().getAttribute('aria-checked')).toBe('true');
    expect(
      automaticSwitch().hasAttribute('disabled') ||
        automaticSwitch().dataset['disabled'] !== undefined,
    ).toBe(true);
    expect(el.textContent).toContain('TINFIRM_ERROR_REPORTS');
    expect(el.textContent).toContain('acme-staging');
  });

  it('says when reporting is off', async () => {
    await open(settings({ mode: 'OFF', setBy: 'OPERATOR' }), []);

    expect(el.querySelector('[data-testid=reporting-off]')).not.toBeNull();
    expect(el.querySelector('hlm-switch')).toBeNull();
    expect(el.querySelector('[data-testid=no-reports]')).not.toBeNull();
  });

  it("opens a report's sample with its frames, Tinfirm's own code apart", async () => {
    await open();

    el.querySelector<HTMLButtonElement>('[data-testid=report] button[aria-expanded]')!.click();
    await settle();
    const detail: ErrorReportDetail = {
      ...aReport(),
      logMessage: 'Sync of {} failed',
      runtime: 'Java 25.0.4',
      exceptions: [
        {
          type: 'java.lang.IllegalStateException',
          message: 'Issue <str>',
          frames: [
            {
              module: 'com.flowtivo.tinfirm.system.Sync',
              function: 'run',
              file: 'Sync.java',
              line: 42,
              inApp: true,
            },
            { module: 'org.springframework.Proxy', function: 'invoke', inApp: false },
          ],
        },
      ],
    };
    http.expectOne(`${REPORTS}/r1`).flush(detail);
    await settle();
    fixture.detectChanges();

    const frames = el.querySelectorAll('[data-testid=report-detail] li');
    expect(frames[0].textContent!.trim()).toBe(
      'at com.flowtivo.tinfirm.system.Sync.run (Sync.java:42)',
    );
    expect(frames[1].classList).toContain('text-muted-foreground');
    expect(el.querySelector('[data-testid=report-detail]')!.textContent).toContain('Java 25.0.4');
  });

  it('deletes a report', async () => {
    await open();

    el.querySelector<HTMLButtonElement>('[aria-label^="Delete the report"]')!.click();
    await settle();
    const request = http.expectOne(`${REPORTS}/r1`);
    expect(request.request.method).toBe('DELETE');
    request.flush(null, { status: 204, statusText: 'No Content' });
    await settle();
    fixture.detectChanges();

    expect(el.querySelectorAll('[data-testid=report]')).toHaveLength(0);
    expect(notify.success).toHaveBeenCalledWith(
      'Report deleted',
      'It starts again if the error happens again.',
    );
  });
});
