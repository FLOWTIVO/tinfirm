import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { aModelAccess, aMyAccount } from '../../../testing/fixtures';
import { fakeCurrentCompany, provideTestHttp, settle } from '../../../testing/test-helpers';
import { Accounts } from './accounts';

describe('Accounts', () => {
  let http: HttpTestingController;
  let harness: RouterTestingHarness;

  async function open(url: string) {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(
          [{ path: 'c/:companyId/accounts', component: Accounts }],
          withComponentInputBinding(),
        ),
        ...provideTestHttp(),
        fakeCurrentCompany('MEMBER').provider,
      ],
    });
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
    await harness.navigateByUrl(url);
    await settle();
  }

  const el = () => harness.routeNativeElement as HTMLElement;
  const checked = () => el().querySelector<HTMLInputElement>('app-segmented input:checked')?.value;

  afterEach(() => http.verify());

  it('opens on Systems, and each tab on its own', async () => {
    await open('/c/c1/accounts');
    expect(el().querySelector('h1')?.textContent?.trim()).toBe('Accounts');
    expect(checked()).toBe('systems');
    http.expectOne('/api/companies/c1/me/accounts').flush({ items: [aMyAccount()] });
    http.expectOne('/api/companies/c1/system-settings').flush({ personalMcpServers: true });
    await settle();
    harness.detectChanges();
    expect(el().querySelector('app-accounts-systems')).not.toBeNull();

    // Picking a tab puts it in the URL, without a history entry.
    el().querySelector<HTMLInputElement>('#accounts-tab-budget')!.click();
    await settle();
    expect(TestBed.inject(Router).url).toBe('/c/c1/accounts?tab=budget');
    http.expectOne('/api/companies/c1/me/model-access').flush(aModelAccess());
    await settle();
    harness.detectChanges();
    expect(el().querySelector('app-accounts-budget')).not.toBeNull();
    expect(el().querySelector('app-accounts-systems')).toBeNull();

    el().querySelector<HTMLInputElement>('#accounts-tab-systems')!.click();
    await settle();
    expect(TestBed.inject(Router).url).toBe('/c/c1/accounts');
    http.expectOne('/api/companies/c1/me/accounts').flush({ items: [] });
    http.expectOne('/api/companies/c1/system-settings').flush({ personalMcpServers: true });
  });

  it.each`
    url                              | tab          | component
    ${'/c/c1/accounts?tab=hosts'}    | ${'hosts'}   | ${'app-accounts-hosts'}
    ${'/c/c1/accounts?tab=budget'}   | ${'budget'}  | ${'app-accounts-budget'}
    ${'/c/c1/accounts?tab=nonsense'} | ${'systems'} | ${'app-accounts-systems'}
  `('opens $url on $tab', async ({ url, tab, component }) => {
    await open(url);
    expect(checked()).toBe(tab);
    expect(el().querySelector(component)).not.toBeNull();
    http.match(() => true).forEach((r) => r.flush({ items: [] }));
  });
});
