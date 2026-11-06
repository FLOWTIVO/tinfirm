import { HttpTestingController } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import type { BorrowedCredential, ServiceIdentity } from '../../../api/generated/models';
import {
  aBorrowedCredential,
  aModelAccess,
  aServiceIdentity,
  aSystemRef,
  AT,
  IDS,
} from '../../../testing/fixtures';
import {
  fakeCurrentCompany,
  fakeNotify,
  problem,
  provideTestHttp,
  settle,
} from '../../../testing/test-helpers';
import { formatDay } from '../../../shared/time';
import { ServiceIdentityPage } from './service-identity-page';

@Component({ selector: 'app-list-stub', template: '<h1>List</h1>' })
class ListStub {}

const API = '/api/companies/c1';
const IDENTITY = `${API}/service-identities/${IDS.svcReader}`;

describe('ServiceIdentityPage', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;
  let notify: ReturnType<typeof fakeNotify>['notify'];

  async function open(
    identity: ServiceIdentity | 404,
    url = `/c/c1/service-identities/${IDS.svcReader}`,
    borrowed: BorrowedCredential[] = [aBorrowedCredential()],
  ) {
    const notifier = fakeNotify();
    notify = notifier.notify;
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [
            { path: 'c/:companyId/service-identities', component: ListStub },
            {
              path: 'c/:companyId/service-identities/:principalId',
              component: ServiceIdentityPage,
            },
          ],
          withComponentInputBinding(),
        ),
        ...provideTestHttp(),
        fakeCurrentCompany('ADMIN').provider,
        notifier.provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    await settle();
    const req = http.expectOne(IDENTITY);
    if (identity === 404) {
      req.flush(problem(404), { status: 404, statusText: 'Not Found' });
    } else {
      req.flush(identity);
    }
    http
      .expectOne(`${API}/principals/${IDS.svcReader}/model-access`)
      .flush(aModelAccess({ principalId: IDS.svcReader }));
    await settle();
    if (identity !== 404) {
      http.expectOne(`${IDENTITY}/credentials`).flush({ items: borrowed });
      await settle();
    }
    harness.detectChanges();
  }

  const el = () => harness.routeNativeElement as HTMLElement;
  const by = (testId: string) => el().querySelector<HTMLElement>(`[data-testid=${testId}]`);
  const overlay = () => document.querySelector('.cdk-overlay-container') as HTMLElement;
  const text = (node: Element | null | undefined) => node?.textContent?.replace(/\s+/g, ' ').trim();
  const button = (label: string) =>
    Array.from(overlay().querySelectorAll('button')).find((b) => text(b) === label)!;

  afterEach(() => {
    // The triggers that use it (W5's addition), which these tests leave to its own spec.
    for (const req of http.match((r) => /\/(triggers|polls)$/.test(r.url)))
      req.flush({ items: [] });
    http.verify();
    overlay()?.replaceChildren();
  });

  it('shows the service account, what it borrows, who may use it and its budget', async () => {
    await open(aServiceIdentity());
    expect(text(el().querySelector('h1'))).toBe('svc-reader');
    expect(text(by('identity-status'))).toBe('Active');
    const github = el().querySelector<HTMLElement>(
      '[data-testid=borrowed-credential][data-system=github]',
    )!;
    expect(text(github.querySelector('[data-testid=borrowed-name]'))).toBe('svc-reader');
    expect(text(github)).toContain('GitHub');
    expect(text(github.querySelector('[data-testid=borrowed-state]'))).toBe('Working');
    expect(text(github.querySelector('[data-testid=borrowed-access]'))).toBe(
      'Read and write · as svc-reader',
    );
    // Since when it is lent, and how many of its triggers act with it.
    expect(text(github.querySelector('[data-testid=borrowed-since]'))).toBe(
      `Lent since ${formatDay(AT.earlier)} · 2 triggers act with it`,
    );
    // Read-only here: lending is changed on the system's page, at the credential.
    const link = github.querySelector('[data-testid=borrowed-link]')!;
    expect(text(link)).toBe('Change on GitHub');
    expect(link.getAttribute('href')).toBe(
      `/c/c1/systems/${IDS.github}#credential-${IDS.readerGithubAccount}`,
    );
    expect(github.querySelector('input, select')).toBeNull();
    expect(text(by('identity-users-list'))).toBe('Analysts');
    expect(text(by('identity-budget-line'))).toContain('$50.00 per 30d');
    expect(by('identity-suspended')).toBeNull();
  });

  it('names its triggers anyone may drive with a credential it borrows (D2)', async () => {
    await open(aServiceIdentity(), undefined, [
      aBorrowedCredential({
        openTriggers: [
          {
            id: 't-mentioned',
            name: 'Mentioned',
            source: 'CHANNEL',
            serviceIdentityId: IDS.svcReader,
            serviceIdentityName: 'svc-reader',
          },
        ],
      }),
    ]);
    const open$ = by('credential-open-triggers')!;
    expect(text(open$)).toBe(
      'A trigger anyone may drive acts with it Mentioned: anyone who may write to its channels drives it',
    );
    expect(open$.querySelector('a')?.getAttribute('href')).toBe('/c/c1/triggers/t-mentioned');
  });

  it('says it borrows nothing yet, and where credentials are lent', async () => {
    await open(aServiceIdentity(), undefined, []);
    expect(text(by('borrowed-empty'))).toContain('It borrows no credential yet.');
    expect(by('borrowed-empty')?.querySelector('a')?.getAttribute('href')).toBe('/c/c1/systems');
    expect(text(by('summary-borrowed'))).toBe('0');
  });

  it('sums it up at a glance: what it borrows, the triggers, its budget', async () => {
    await open(aServiceIdentity());
    for (const req of http.match((r) => r.url === `${API}/triggers`)) req.flush({ items: [] });
    await settle();
    harness.detectChanges();
    const summary = by('identity-summary');
    expect(text(by('summary-borrowed'))).toBe('1');
    expect(text(summary)).toContain('credential, on GitHub');
    expect(text(by('summary-triggers'))).toBe('0');
    expect(text(summary)).toContain('None uses it');
    expect(text(by('summary-budget'))).toBe('$11.80 of $50.00');
    // The budget's share as a meter, in the summary and in its section.
    expect(
      Array.from(el().querySelectorAll('[role=meter]')).map((m) => m.getAttribute('aria-valuenow')),
    ).toEqual(['24', '24']);
    expect(text(by('identity-budget-line'))).toBe(
      '$11.80 spent of $50.00 per 30d (the company’s default)',
    );
  });

  it('notes a chat bot it borrows, marked among what it borrows', async () => {
    const slack = aSystemRef({ id: IDS.slack, name: 'Acme Slack', handle: 'slack', kind: 'SLACK' });
    await open(aServiceIdentity(), undefined, [
      aBorrowedCredential(),
      aBorrowedCredential({
        system: slack,
        credential: {
          id: IDS.slackBotAccount,
          name: 'Bot',
          kind: 'TOKEN',
          status: 'OK',
          enabled: true,
        },
        bot: true,
      }),
    ]);
    const row = el().querySelector<HTMLElement>(
      '[data-testid=borrowed-credential][data-system=slack]',
    )!;
    expect(text(row.querySelector('[data-testid=borrowed-bot]'))).toBe('Chat bot');
    // A bot acts for no trigger: only since when it is lent.
    expect(text(row.querySelector('[data-testid=borrowed-since]'))).toBe(
      `Lent since ${formatDay(AT.earlier)}`,
    );
    expect(row.querySelector('[data-testid=borrowed-link]')?.getAttribute('href')).toBe(
      `/c/c1/systems/${IDS.slack}?tab=bot`,
    );
    const bot = by('identity-bot');
    expect(text(bot)).toContain('Borrows Acme Slack’s bot');
    expect(text(bot)).toContain('svc-reader can’t be deleted while it borrows it.');
    expect(bot?.querySelector('a')?.getAttribute('href')).toBe(
      `/c/c1/systems/${IDS.slack}?tab=bot`,
    );
    // The summary counts the credentials it acts with, not the bot.
    expect(text(by('summary-borrowed'))).toBe('1');
  });

  it('suspends it with a reason, after saying what stops, and resumes it', async () => {
    await open(aServiceIdentity());
    by('suspend-identity')!.click();
    await settle();
    expect(text(overlay())).toContain('Everything that acts through it or on its budget stops');
    const reason = overlay().querySelector<HTMLInputElement>('#suspend-reason')!;
    reason.value = 'A leaked token';
    overlay().querySelector<HTMLButtonElement>('[data-testid=confirm-suspend]')!.click();
    await settle();
    const suspend = http.expectOne(`${IDENTITY}/suspend`);
    expect(suspend.request.body).toEqual({ reason: 'A leaked token' });
    suspend.flush(
      aServiceIdentity({
        status: 'SUSPENDED',
        suspendedAt: AT.start,
        suspensionReason: 'A leaked token',
      }),
    );
    await settle();
    harness.detectChanges();
    expect(text(by('identity-status'))).toBe('Suspended');
    expect(by('identity-suspended')).not.toBeNull();
    // When, and why: the server keeps the reason for OWNER and ADMIN.
    expect(by('identity-suspended-at')?.querySelector('time')?.getAttribute('datetime')).toBe(
      AT.start,
    );
    expect(text(by('identity-suspension-reason'))).toBe('Reason: A leaked token');
    // It is not orange: nobody else waits on it.
    expect(el().querySelector('.text-human')).toBeNull();

    by('resume-identity')!.click();
    await settle();
    http.expectOne(`${IDENTITY}/resume`).flush(aServiceIdentity());
    await settle();
    harness.detectChanges();
    expect(text(by('identity-status'))).toBe('Active');
    expect(notify.success).toHaveBeenCalledWith('svc-reader is active again');
  });

  it('says a suspension without a reason by its time alone', async () => {
    await open(
      aServiceIdentity({ status: 'SUSPENDED', suspendedAt: AT.start, suspensionReason: null }),
    );
    expect(text(by('identity-suspended-at'))).toMatch(/^Suspended \S/);
    expect(by('identity-suspension-reason')).toBeNull();
  });

  it('replaces the groups that may use it', async () => {
    await open(aServiceIdentity());
    by('edit-users')!.click();
    await settle();
    http
      .expectOne((r) => r.url === `${API}/groups`)
      .flush({
        items: [
          { id: IDS.analysts, name: 'Analysts', memberCount: 2 },
          { id: 'g-ops', name: 'Ops', memberCount: 1 },
        ],
      });
    await settle();
    const boxes = overlay().querySelectorAll<HTMLInputElement>('[data-testid=users-picker] input');
    expect(Array.from(boxes).map((b) => b.checked)).toEqual([true, false]);
    boxes[0].click();
    boxes[1].click();
    await settle();
    overlay().querySelector<HTMLButtonElement>('[data-testid=users-save]')!.click();
    await settle();
    const req = http.expectOne(`${IDENTITY}/users`);
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ groupIds: ['g-ops'] });
    req.flush(aServiceIdentity({ userGroups: [{ id: 'g-ops', name: 'Ops' }] }));
    await settle();
    harness.detectChanges();
    expect(text(by('identity-users-list'))).toBe('Ops');
  });

  it('deletes it after a confirmation, and goes back to the list', async () => {
    await open(aServiceIdentity());
    by('delete-identity')!.click();
    await settle();
    expect(text(overlay())).toContain(
      'The credentials it borrows are withdrawn from it and stay with their systems',
    );
    button('Delete svc-reader').click();
    await settle();
    http.expectOne((r) => r.url === IDENTITY && r.method === 'DELETE').flush(null);
    await settle();
    expect(TestBed.inject(Router).url).toBe('/c/c1/service-identities');
  });

  it('is the not-found page and nothing more for an identity the server does not show', async () => {
    await open(404);
    expect(text(el())).toContain('Page not found');
    expect(by('suspend-identity')).toBeNull();
  });
});
